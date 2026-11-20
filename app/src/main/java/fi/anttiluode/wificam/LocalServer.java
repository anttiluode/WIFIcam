package fi.anttiluode.wificam;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Tiny LAN-only usage HTTP server. Random token required for all endpoints. */
public final class LocalServer {
    private final Context context;
    private final CameraEngine camera;
    private final String token;
    private final int port;
    private final ExecutorService pool = Executors.newFixedThreadPool(10);
    private final Semaphore streamSlots = new Semaphore(3);
    private volatile boolean closed;
    private ServerSocket server;

    public LocalServer(Context context, CameraEngine camera, String token, int port) {
        this.context = context.getApplicationContext(); this.camera = camera; this.token = token; this.port = port;
    }

    public void start() throws IOException {
        server = new ServerSocket(port);
        Thread t = new Thread(() -> {
            while (!closed) {
                try {
                    Socket client = server.accept();
                    client.setSoTimeout(12000);
                    pool.execute(() -> serve(client));
                } catch (Exception e) { if (!closed) android.util.Log.w("WIFIcam", "Accept: " + e); }
            }
        }, "wificam-http");
        t.setDaemon(true); t.start();
    }

    private static void respond(OutputStream out, int code, String mime, byte[] data) throws IOException {
        String status = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 401 ? "Unauthorized" :
                code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : code == 429 ? "Too Many Requests" : "Unavailable";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8)); out.write(data); out.flush();
    }

    private static void txt(OutputStream out, int code, String message) throws IOException {
        respond(out, code, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> query(Uri uri) {
        Map<String, String> q = new HashMap<>();
        for (String key : uri.getQueryParameterNames()) q.put(key, uri.getQueryParameter(key));
        return q;
    }

    private void serve(Socket socket) {
        try (Socket client = socket) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String request = lines.readLine();
            if (request == null || request.length() > 4096) return;
            String[] parts = request.split(" ");
            if (parts.length < 2) { txt(out, 400, "Malformed request"); return; }
            String method = parts[0];
            if (!method.equals("GET") && !method.equals("POST")) { txt(out, 405, "GET/POST only"); return; }
            Uri url = Uri.parse(parts[1]);
            Map<String, String> params = query(url);
            // Consume headers but deliberately ignore body (all settings are query parameters).
            String header;
            int count = 0;
            while ((header = lines.readLine()) != null && !header.isEmpty() && ++count < 100) { }
            if (!token.equals(params.get("token"))) { txt(out, 401, "Invalid local access token"); return; }
            String path = url.getPath();
            if (path == null) path = "/";
            switch (path) {
                case "/":
                    if (!method.equals("GET")) { txt(out,405,"GET required"); return; }
                    try (InputStream asset = context.getAssets().open("index.html")) {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        byte[] temp = new byte[8192]; int n;
                        while ((n = asset.read(temp)) != -1) b.write(temp, 0, n);
                        respond(out,200,"text/html; charset=utf-8",b.toByteArray());
                    }
                    break;
                case "/api/cameras":
                    respond(out, 200, "application/json", camera.cameras().toString().getBytes(StandardCharsets.UTF_8)); break;
                case "/api/state":
                    respond(out, 200, "application/json", camera.state().toString().getBytes(StandardCharsets.UTF_8)); break;
                case "/api/set":
                    if (!method.equals("POST")) { txt(out,405,"POST required"); return; }
                    camera.set(params);
                    respond(out,200,"application/json", "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8)); break;
                case "/api/camera":
                    if (!method.equals("POST")) { txt(out,405,"POST required"); return; }
                    String cameraId = params.get("id");
                    if (cameraId == null || cameraId.isEmpty()) { txt(out,400,"Missing id"); return; }
                    int w = 1280;
                    try { w = Integer.parseInt(params.getOrDefault("width", "1280")); }
                    catch (NumberFormatException ignored) { }
                    camera.choose(cameraId,w);
                    respond(out,200,"application/json","{\"accepted\":true}".getBytes(StandardCharsets.UTF_8)); break;
                case "/snapshot":
                    if (!method.equals("GET")) { txt(out,405,"GET required"); return; }
                    byte[] f = camera.lastFrame();
                    if (f == null) { txt(out,503,"No video frame yet"); return; }
                    respond(out,200,"image/jpeg",f); break;
                case "/photo":
                    if (!method.equals("GET")) { txt(out,405,"GET required"); return; }
                    try {
                        java.util.concurrent.CompletableFuture<byte[]> capture = camera.photo();
                        byte[] jpg;
                        try { jpg = capture.get(9, TimeUnit.SECONDS); }
                        catch (java.util.concurrent.TimeoutException timeout) { capture.cancel(true); throw timeout; }
                        respond(out, 200, "image/jpeg", jpg);
                    } catch (Exception e) { txt(out,503,"Full-resolution photo failed: " + e.getMessage()); }
                    break;
                case "/stream":
                    if (!method.equals("GET")) { txt(out,405,"GET required"); return; }
                    if (!streamSlots.tryAcquire()) { txt(out,429,"Maximum 3 stream clients"); return; }
                    try { stream(out); } finally { streamSlots.release(); }
                    break;
                default: txt(out,404,"No such endpoint");
            }
        } catch (Exception ignored) {
            // Client disconnected, network changed, or phone camera closed.
        }
    }

    private void stream(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        long last = -1;
        while (!closed) {
            byte[] image;
            try { image = camera.awaitNextFrame(last, 3000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            if (image == null) continue;
            last = camera.frameId();
            out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + image.length +
                    "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(image);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
    }

    public void close() {
        closed = true;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }
}
