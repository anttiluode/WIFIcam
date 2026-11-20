package fi.anttiluode.wificam;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.Collections;

public class MainActivity extends Activity {
    private CameraEngine engine;
    private LocalServer server;
    private TextView status;
    private String link;
    private static final int CAMERA_REQUEST = 41;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(38, 50, 38, 30);
        layout.setBackgroundColor(Color.rgb(16, 21, 32));
        TextView title = new TextView(this);
        title.setText("WIFIcam  |  Camera2 lab");
        title.setTextSize(27);
        title.setTextColor(Color.WHITE);
        layout.addView(title);
        status = new TextView(this);
        status.setTextColor(Color.rgb(188, 213, 231));
        status.setTextSize(16);
        status.setPadding(0, 28, 0, 30);
        layout.addView(status);
        Button copy = new Button(this);
        copy.setText("Copy control URL");
        layout.addView(copy);
        copy.setOnClickListener(v -> {
            if (link != null) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("WIFIcam URL", link));
                status.append("\nURL copied. Open on your Windows PC.");
            }
        });
        TextView hint = new TextView(this);
        hint.setText("Keep the phone awake with this app in front. Put phone and PC on the same Wi-Fi. No cloud, account, or internet connection required.\n\nThe control URL contains a local access token. Don't share it outside your LAN.");
        hint.setTextSize(15);
        hint.setTextColor(Color.LTGRAY);
        hint.setPadding(0, 30, 0, 0);
        hint.setGravity(Gravity.START);
        layout.addView(hint);
        setContentView(layout);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else { status.setText("Allow Camera permission to start."); requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST); }
    }

    private void startCamera() {
        if (engine != null) return;
        try {
            engine = new CameraEngine(this);
            byte[] bytes = new byte[20];
            new SecureRandom().nextBytes(bytes);
            StringBuilder token = new StringBuilder();
            for (byte b : bytes) token.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            server = new LocalServer(this, engine, token.toString(), 8765);
            server.start();
            String ip = findLocalIp();
            link = "http://" + ip + ":8765/?token=" + token;
            status.setText("Camera server running\n\n" + link + "\n\nOpen this address in your Windows browser.\nCamera starts when permission is granted.");
            engine.startDefault();
        } catch (Exception e) {
            status.setText("Cannot start WIFIcam: " + e.getMessage());
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] names, int[] results) {
        super.onRequestPermissionsResult(request, names, results);
        if (request == CAMERA_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText("Camera permission denied. Enable it in Android Settings, then reopen WIFIcam.");
    }

    private String findLocalIp() {
        try {
            java.util.List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            // Prefer the real Wi-Fi interface rather than cellular/VPN/private tunnels.
            for (NetworkInterface net : interfaces) {
                if (!net.getName().startsWith("wlan") || !net.isUp()) continue;
                for (java.net.InetAddress addr : Collections.list(net.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) return addr.getHostAddress();
                }
            }
            for (NetworkInterface net : interfaces) {
                if (!net.isUp() || net.isLoopback()) continue;
                for (java.net.InetAddress addr : Collections.list(net.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) return addr.getHostAddress();
                }
            }
        } catch (SocketException ignored) { }
        return "PHONE_IP_ADDRESS";
    }

    @Override protected void onDestroy() {
        if (server != null) server.close();
        if (engine != null) engine.close();
        super.onDestroy();
    }
}
