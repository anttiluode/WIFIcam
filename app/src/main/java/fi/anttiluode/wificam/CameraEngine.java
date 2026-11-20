package fi.anttiluode.wificam;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Camera2 engine. No vendor-specific hidden APIs, root, or external SDK. */
public final class CameraEngine {
    private final CameraManager manager;
    private final HandlerThread cameraThread = new HandlerThread("camera2");
    private final HandlerThread imageThread = new HandlerThread("yuv-to-jpeg");
    private final Handler cameraHandler;
    private final Handler imageHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader previewReader;
    private ImageReader photoReader;
    private CaptureRequest.Builder previewRequest;
    private CameraCharacteristics activeCharacteristics;
    private volatile String selected = "";
    private volatile String status = "initializing";
    private volatile String error = "";
    private volatile int videoWidth = 0, videoHeight = 0, photoWidth = 0, photoHeight = 0;
    private volatile int maxVideoWidth = 1280;
    private int generation = 0;
    private volatile boolean manualExposure = false, manualFocus = false, aeLock = false, awbLock = false, torch = false;
    private volatile long shutterUs = 10000;
    private volatile int iso = 400, jpegQuality = 85;
    private volatile float focus = 0f, zoom = 1f;
    private volatile int awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO;
    private final Object frameMonitor = new Object();
    private volatile byte[] frame = null;
    private final AtomicLong frameNumber = new AtomicLong();
    private final AtomicLong photoNumber = new AtomicLong();
    private volatile long lastFrameMs = 0;
    private volatile CompletableFuture<byte[]> pendingPhoto;
    private volatile boolean stopped = false;

    public CameraEngine(Context context) {
        manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        cameraThread.start(); imageThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        imageHandler = new Handler(imageThread.getLooper());
    }

    public void startDefault() throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        if (ids.length == 0) { status = "no cameras"; return; }
        String id = ids[0];
        for (String candidate : ids) {
            Integer facing = manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = candidate; break; }
        }
        choose(id, maxVideoWidth);
    }

    public void choose(String selection, int requestedWidth) {
        cameraHandler.post(() -> {
            if (stopped) return;
            boolean known = false;
            try {
                JSONArray entries = cameras();
                for (int i = 0; i < entries.length(); i++)
                    if (selection.equals(entries.getJSONObject(i).getString("id"))) known = true;
            } catch (Exception ignored) { }
            if (!known) { error = "Unknown or inaccessible camera: " + selection; return; }
            maxVideoWidth = (requestedWidth == 640 || requestedWidth == 1280 || requestedWidth == 1920) ? requestedWidth : 1280;
            selected = selection;
            status = "opening"; error = "";
            int thisGen = ++generation;
            tearDown();
            // Let Camera2 release the previous capture session before opening a new one.
            cameraHandler.postDelayed(() -> openSelection(selection, thisGen), 250);
        });
    }

    private void tearDown() {
        CompletableFuture<byte[]> p = pendingPhoto;
        pendingPhoto = null;
        if (p != null) p.completeExceptionally(new IllegalStateException("Camera changed"));
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (previewReader != null) { previewReader.close(); previewReader = null; }
        if (photoReader != null) { photoReader.close(); photoReader = null; }
        previewRequest = null;
        frame = null;
    }

    private void openSelection(String selection, int thisGen) {
        if (stopped || thisGen != generation) return;
        String logicalId = selection.split("@", 2)[0];
        String physicalId = selection.contains("@") ? selection.substring(selection.indexOf('@') + 1) : null;
        try {
            activeCharacteristics = manager.getCameraCharacteristics(logicalId);
            manager.openCamera(logicalId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (thisGen != generation || stopped) { device.close(); return; }
                    camera = device;
                    setupSession(device, physicalId, thisGen);
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    if (thisGen == generation) { camera = null; status = "disconnected"; }
                }
                @Override public void onError(CameraDevice device, int code) {
                    device.close();
                    if (thisGen == generation) { camera = null; status = "error"; error = "Camera open error " + code; }
                }
            }, cameraHandler);
        } catch (SecurityException | CameraAccessException e) {
            status = "error"; error = "Open: " + e;
        }
    }

    private static Size selectVideoSize(Size[] sizes, int limit) {
        Size chosen = null;
        // Prefer 16:9, then largest supported size within the requested maximum width.
        for (Size s : sizes) {
            if (s.getWidth() > limit || s.getWidth() < 320) continue;
            if (chosen == null) { chosen = s; continue; }
            boolean a = Math.abs((double)s.getWidth() / s.getHeight() - 16.0 / 9.0) < 0.06;
            boolean b = Math.abs((double)chosen.getWidth() / chosen.getHeight() - 16.0 / 9.0) < 0.06;
            if ((a && !b) || (a == b && s.getWidth() * (long)s.getHeight() > chosen.getWidth() * (long)chosen.getHeight())) chosen = s;
        }
        if (chosen != null) return chosen;
        return Arrays.stream(sizes).min((a,b) -> Long.compare((long)a.getWidth()*a.getHeight(), (long)b.getWidth()*b.getHeight())).orElse(new Size(640,480));
    }

    private static Size selectPhotoSize(Size[] sizes) {
        // Stay below 16 MP, if possible, for responsive full-resolution stills.
        Size best = null;
        for (Size size : sizes) {
            long area = (long)size.getWidth() * size.getHeight();
            if (area > 16_000_000) continue;
            if (best == null || area > (long)best.getWidth() * best.getHeight()) best = size;
        }
        if (best != null) return best;
        return Arrays.stream(sizes).min((a,b) -> Long.compare((long)a.getWidth()*a.getHeight(), (long)b.getWidth()*b.getHeight())).orElse(new Size(1920,1080));
    }

    private void setupSession(CameraDevice device, String physicalId, int gen) {
        try {
            StreamConfigurationMap map = activeCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) throw new IllegalStateException("No stream configuration map");
            Size video = selectVideoSize(map.getOutputSizes(ImageFormat.YUV_420_888), maxVideoWidth);
            Size jpeg = selectPhotoSize(map.getOutputSizes(ImageFormat.JPEG));
            videoWidth = video.getWidth(); videoHeight = video.getHeight();
            photoWidth = jpeg.getWidth(); photoHeight = jpeg.getHeight();
            previewReader = ImageReader.newInstance(videoWidth, videoHeight, ImageFormat.YUV_420_888, 3);
            photoReader = ImageReader.newInstance(photoWidth, photoHeight, ImageFormat.JPEG, 2);
            final ImageReader activeVideo = previewReader;
            previewReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    if (reader != previewReader || gen != generation) return;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastFrameMs < 95) return;
                    lastFrameMs = now;
                    byte[] nv21 = toNV21(image);
                    ByteArrayOutputStream out = new ByteArrayOutputStream(nv21.length / 3);
                    YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
                    yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), jpegQuality, out);
                    synchronized (frameMonitor) {
                        frame = out.toByteArray(); frameNumber.incrementAndGet(); frameMonitor.notifyAll();
                    }
                } catch (Exception e) {
                    error = "Image conversion: " + e.getMessage();
                } finally { if (image != null) image.close(); }
            }, imageHandler);
            photoReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) return;
                    if (reader != photoReader || gen != generation) return;
                    ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    byte[] jpegBytes = new byte[buf.remaining()]; buf.get(jpegBytes);
                    CompletableFuture<byte[]> p = pendingPhoto;
                    if (p != null && p.complete(jpegBytes)) { pendingPhoto = null; photoNumber.incrementAndGet(); }
                } catch (Exception e) {
                    CompletableFuture<byte[]> p = pendingPhoto;
                    if (p != null) p.completeExceptionally(e);
                    pendingPhoto = null;
                } finally { if (image != null) image.close(); }
            }, imageHandler);
            OutputConfiguration previewOutput = new OutputConfiguration(previewReader.getSurface());
            OutputConfiguration photoOutput = new OutputConfiguration(photoReader.getSurface());
            if (physicalId != null) {
                // Physical-camera streams are hardware-dependent; don't silently substitute the logical sensor.
                previewOutput.setPhysicalCameraId(physicalId);
                photoOutput.setPhysicalCameraId(physicalId);
            }
            List<OutputConfiguration> outputs = new ArrayList<>();
            outputs.add(previewOutput); outputs.add(photoOutput);
            SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs,
                    task -> cameraHandler.post(task), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (gen != generation || stopped) { configured.close(); return; }
                    session = configured;
                    status = "streaming";
                    updateRepeating();
                }
                @Override public void onConfigureFailed(CameraCaptureSession configured) {
                    if (gen == generation) {
                        status = "error";
                        error = "Session unsupported for selected camera/physical lens (try logical camera or 640px stream)";
                    }
                }
            });
            device.createCaptureSession(config);
        } catch (Exception e) {
            status = "error"; error = "Camera session: " + e;
        }
    }

    private static byte[] toNV21(Image image) {
        int width = image.getWidth(), height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        byte[] output = new byte[width * height * 3 / 2];
        ByteBuffer y = planes[0].getBuffer();
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int ys = planes[0].getRowStride(), yp = planes[0].getPixelStride();
        int us = planes[1].getRowStride(), up = planes[1].getPixelStride();
        int vs = planes[2].getRowStride(), vp = planes[2].getPixelStride();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) output[row * width + col] = y.get(row * ys + col * yp);
        }
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                int i = width * height + row * width + col * 2;
                output[i] = v.get(row * vs + col * vp);
                output[i + 1] = u.get(row * us + col * up);
            }
        }
        return output;
    }

    private void addSettings(CaptureRequest.Builder b, boolean still) {
        if (manualExposure && manualSensorAvailable()) {
            Range<Long> times = activeCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Integer> sensitivities = activeCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            long ns = shutterUs * 1000L;
            if (times != null) ns = Math.max(times.getLower(), Math.min(times.getUpper(), ns));
            int sensitivity = iso;
            if (sensitivities != null) sensitivity = Math.max(sensitivities.getLower(), Math.min(sensitivities.getUpper(), sensitivity));
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeLock);
        }
        Float minFocus = activeCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (manualFocus && minFocus != null && minFocus > 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.max(0f, Math.min(minFocus, focus)));
        } else {
            int[] modes = activeCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            int mode = CaptureRequest.CONTROL_AF_MODE_OFF;
            if (modes != null) {
                for (int n : modes) if (n == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) mode = n;
            }
            b.set(CaptureRequest.CONTROL_AF_MODE, mode);
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
        if (awbMode == CaptureRequest.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AWB_LOCK, awbLock);
        Boolean flash = activeCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        if (torch && Boolean.TRUE.equals(flash)) b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
        // Android 11+ zoomRatio can select between logical wide/ultrawide/tele sensors
        // when the OEM exposes that capability. Crop-region-only zoom cannot go <1x.
        Range<Float> zr = Build.VERSION.SDK_INT >= 30 ?
                activeCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
        if (zr != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO,
                    Math.max(zr.getLower(), Math.min(zr.getUpper(), zoom)));
        } else {
            Float maxZoom = activeCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            Rect active = activeCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active != null) {
                float z = Math.min(Math.max(1f, zoom), maxZoom == null ? 1f : maxZoom);
                int w = Math.max(2, (int)(active.width() / z) / 2 * 2);
                int h = Math.max(2, (int)(active.height() / z) / 2 * 2);
                int x = active.left + (active.width() - w) / 2;
                int y = active.top + (active.height() - h) / 2;
                b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(x,y,x+w,y+h));
            }
        }
        if (still) {
            b.set(CaptureRequest.JPEG_QUALITY, (byte)95);
            b.set(CaptureRequest.JPEG_ORIENTATION, 0); // Sensor-native orientation for calibration.
        }
    }

    private boolean manualSensorAvailable() {
        if (activeCharacteristics == null) return false;
        int[] capabilities = activeCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities == null) return false;
        for (int cap : capabilities) if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) return true;
        return false;
    }

    private void updateRepeating() {
        if (session == null || camera == null || previewReader == null) return;
        try {
            previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequest.addTarget(previewReader.getSurface());
            addSettings(previewRequest, false);
            session.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
        } catch (Exception e) { error = "Repeating request: " + e.getMessage(); }
    }

    public void set(Map<String, String> options) {
        cameraHandler.post(() -> {
            try {
                if (options.containsKey("manualExposure")) manualExposure = bool(options.get("manualExposure"));
                if (options.containsKey("manualFocus")) manualFocus = bool(options.get("manualFocus"));
                if (options.containsKey("aeLock")) aeLock = bool(options.get("aeLock"));
                if (options.containsKey("awbLock")) awbLock = bool(options.get("awbLock"));
                if (options.containsKey("torch")) torch = bool(options.get("torch"));
                if (options.containsKey("iso")) iso = Integer.parseInt(options.get("iso"));
                if (options.containsKey("shutterUs")) shutterUs = Long.parseLong(options.get("shutterUs"));
                if (options.containsKey("focus")) focus = Float.parseFloat(options.get("focus"));
                if (options.containsKey("zoom")) zoom = Float.parseFloat(options.get("zoom"));
                if (options.containsKey("quality")) jpegQuality = Math.min(100, Math.max(30, Integer.parseInt(options.get("quality"))));
                if (options.containsKey("awbMode")) {
                    int v = Integer.parseInt(options.get("awbMode"));
                    int[] modes = activeCharacteristics == null ? null : activeCharacteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
                    if (modes != null) for (int available : modes) if (available == v) awbMode = v;
                }
                updateRepeating();
            } catch (NumberFormatException e) { error = "Invalid setting: " + e.getMessage(); }
        });
    }

    private static boolean bool(String s) { return "true".equalsIgnoreCase(s) || "1".equals(s); }

    public CompletableFuture<byte[]> photo() {
        CompletableFuture<byte[]> promise = new CompletableFuture<>();
        cameraHandler.post(() -> {
            if (session == null || camera == null || photoReader == null) {
                promise.completeExceptionally(new IllegalStateException("Camera not ready")); return;
            }
            if (pendingPhoto != null) { promise.completeExceptionally(new IllegalStateException("Previous photo in progress")); return; }
            pendingPhoto = promise;
            try {
                CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                request.addTarget(photoReader.getSurface());
                addSettings(request, true);
                session.capture(request.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r,
                            android.hardware.camera2.CaptureFailure failure) {
                        promise.completeExceptionally(new IllegalStateException("Still capture failed: " + failure.getReason()));
                        pendingPhoto = null;
                    }
                }, cameraHandler);
            } catch (Exception e) { pendingPhoto = null; promise.completeExceptionally(e); }
        });
        promise.whenComplete((bytes, failure) -> { if (pendingPhoto == promise) pendingPhoto = null; });
        return promise;
    }

    public byte[] lastFrame() { return frame; }
    public long frameId() { return frameNumber.get(); }
    public byte[] awaitNextFrame(long previous, long millis) throws InterruptedException {
        synchronized (frameMonitor) {
            if (frameNumber.get() <= previous) frameMonitor.wait(millis);
            return frameNumber.get() > previous ? frame : null;
        }
    }

    public JSONArray cameras() throws CameraAccessException, JSONException {
        JSONArray result = new JSONArray();
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            result.put(cameraDescriptor(id, id, null, c));
            for (String physical : c.getPhysicalCameraIds()) {
                CameraCharacteristics phys;
                try { phys = manager.getCameraCharacteristics(physical); }
                catch (Exception e) { phys = c; }
                result.put(cameraDescriptor(id + "@" + physical, id, physical, phys));
            }
        }
        return result;
    }

    private JSONObject cameraDescriptor(String key, String logical, String physical, CameraCharacteristics c) throws JSONException {
        JSONObject o = new JSONObject();
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        String direction = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? "Front" :
                facing != null && facing == CameraCharacteristics.LENS_FACING_BACK ? "Rear" : "External";
        float[] lengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        StringBuilder lens = new StringBuilder();
        if (lengths != null) for (float mm : lengths) {
            if (lens.length() > 0) lens.append(", ");
            lens.append(String.format(Locale.US, "%.2f", mm));
        }
        o.put("id", key); o.put("logical", logical); o.put("physical", physical == null ? JSONObject.NULL : physical);
        o.put("label", direction + " " + (physical == null ? "logical " : "physical ") + key +
                (lens.length() > 0 ? " • " + lens + " mm" : ""));
        o.put("manualSensor", hasCapability(c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR));
        Range<Integer> isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Range<Long> expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        o.put("isoMin", isoRange != null ? isoRange.getLower() : 100);
        o.put("isoMax", isoRange != null ? isoRange.getUpper() : 1600);
        o.put("shutterMinUs", expRange != null ? Math.max(1, expRange.getLower() / 1000) : 100);
        o.put("shutterMaxUs", expRange != null ? expRange.getUpper() / 1000 : 100000);
        Float focusMax = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        o.put("focusMax", focusMax == null ? 0 : focusMax);
        Range<Float> zr = Build.VERSION.SDK_INT >= 30 ?
                c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
        Float cropZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        o.put("zoomMin", zr == null ? 1f : zr.getLower());
        o.put("zoomMax", zr == null ? (cropZoom == null ? 1f : cropZoom) : zr.getUpper());
        o.put("zoomRatioApi", zr != null);
        Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        o.put("flash", Boolean.TRUE.equals(flash));
        JSONArray wb = new JSONArray();
        int[] modes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        if (modes != null) for (int v : modes) wb.put(v);
        o.put("awbModes", wb);
        return o;
    }

    private static boolean hasCapability(CameraCharacteristics c, int capability) {
        int[] options = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (options != null) for (int o : options) if (o == capability) return true;
        return false;
    }

    public JSONObject state() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("selected", selected); o.put("status", status); o.put("error", error);
        o.put("videoWidth", videoWidth); o.put("videoHeight", videoHeight);
        o.put("photoWidth", photoWidth); o.put("photoHeight", photoHeight);
        o.put("requestedWidth", maxVideoWidth);
        o.put("frames", frameNumber.get()); o.put("photos", photoNumber.get());
        o.put("manualExposure", manualExposure); o.put("manualFocus", manualFocus);
        o.put("iso", iso); o.put("shutterUs", shutterUs); o.put("focus", focus);
        o.put("zoom", zoom); o.put("quality", jpegQuality); o.put("awbMode", awbMode);
        o.put("aeLock", aeLock); o.put("awbLock", awbLock); o.put("torch", torch);
        return o;
    }

    public void close() {
        stopped = true;
        cameraHandler.post(() -> { ++generation; tearDown(); status = "stopped"; cameraThread.quitSafely(); imageThread.quitSafely(); });
    }
}
