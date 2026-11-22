package com.example.targetdetector;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int CAPTURE_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        layout.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("Target Detector");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);

        TextView info = new TextView(this);
        info.setText(
                "Grün = du\n" +
                "Blau = Teammitglied\n" +
                "Rot = Gegner\n\n" +
                "Zuerst Overlay-Berechtigung erlauben."
        );
        info.setTextSize(18);
        info.setTextColor(Color.DKGRAY);
        info.setPadding(0, 30, 0, 30);

        Button overlayButton = new Button(this);
        overlayButton.setText("Overlay-Berechtigung öffnen");

        Button startButton = new Button(this);
        startButton.setText("Bildschirmanalyse starten");

        layout.addView(title);
        layout.addView(info);
        layout.addView(overlayButton);
        layout.addView(startButton);

        setContentView(layout);

        overlayButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        });

        startButton.setOnClickListener(v -> requestScreenCapture());
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        Intent captureIntent = manager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, CAPTURE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CAPTURE_REQUEST && resultCode == RESULT_OK && data != null) {
            Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("resultData", data);

            startForegroundService(serviceIntent);
        }
    }
}
