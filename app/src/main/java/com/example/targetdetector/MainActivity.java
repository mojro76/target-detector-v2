package com.example.targetdetector;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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
                "Türkis-blau = du\n" +
                "Pink-lila = Gegner\n\n" +
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
        addRecordingSettings(layout);

        // Querformat: nicht alles passt auf den Bildschirm -> scrollbar machen
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.addView(layout);
        setContentView(scroll);

        overlayButton.setOnClickListener(v -> {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        });

        startButton.setOnClickListener(v -> requestScreenCapture());
    }

    // Einstellungen für die Aufnahme (werden sofort gespeichert und bei der nächsten Aufnahme benutzt)
    private void addRecordingSettings(LinearLayout layout) {
        final SharedPreferences prefs = getSharedPreferences(FrameRecorder.PREFS, MODE_PRIVATE);

        TextView head = new TextView(this);
        head.setText("Aufnahme (zum Auswerten)");
        head.setTextSize(20);
        head.setTextColor(Color.BLACK);
        head.setPadding(0, 50, 0, 10);
        layout.addView(head);

        TextView help = new TextView(this);
        help.setText(
                "Im Spiel erscheint links ein kleiner Knopf \"REC\". Antippen startet die Aufnahme, "
                        + "nochmal antippen stoppt sie. Den Knopf kannst du mit dem Finger verschieben.\n"
                        + "Die Aufnahme stoppt nach der eingestellten Zeit von selbst und wird als ZIP in "
                        + "Download/TargetDetector gespeichert.\n"
                        + "Änderungen am Knopf gelten nach dem nächsten Start der Bildschirmanalyse."
        );
        help.setTextSize(15);
        help.setTextColor(Color.DKGRAY);
        layout.addView(help);

        CheckBox showButton = new CheckBox(this);
        showButton.setText("Aufnahme-Knopf im Spiel anzeigen");
        showButton.setTextColor(Color.BLACK);
        showButton.setChecked(prefs.getBoolean(FrameRecorder.KEY_BUTTON, true));
        showButton.setOnCheckedChangeListener((b, checked) ->
                prefs.edit().putBoolean(FrameRecorder.KEY_BUTTON, checked).apply());
        layout.addView(showButton);

        layout.addView(label("Dauer:"));
        layout.addView(choiceRow(prefs, FrameRecorder.KEY_SECONDS, FrameRecorder.DEFAULT_SECONDS,
                new int[]{10, 20, 30}, new String[]{"10 s", "20 s", "30 s"}));

        layout.addView(label("Welche Bilder speichern:"));
        layout.addView(choiceRow(prefs, FrameRecorder.KEY_EVERY, FrameRecorder.DEFAULT_EVERY,
                new int[]{1, 2, 3}, new String[]{"jedes", "jedes 2.", "jedes 3."}));

        TextView size = new TextView(this);
        size.setText("Richtwert: 20 s mit jedem 2. Bild ≈ 30–60 MB.");
        size.setTextSize(14);
        size.setTextColor(Color.GRAY);
        size.setPadding(0, 10, 0, 0);
        layout.addView(size);
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(Color.BLACK);
        t.setPadding(0, 20, 0, 0);
        return t;
    }

    // Eine Zeile mit Auswahlknöpfen; die Wahl wird direkt gespeichert
    private RadioGroup choiceRow(SharedPreferences prefs, String key, int def, int[] values, String[] labels) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        int current = prefs.getInt(key, def);
        for (int i = 0; i < values.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextColor(Color.BLACK);
            rb.setId(1000 + i);
            rb.setPadding(0, 0, 30, 0);
            group.addView(rb);
            if (values[i] == current) {
                rb.setChecked(true);
            }
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            int i = checkedId - 1000;
            if (i >= 0 && i < values.length) {
                prefs.edit().putInt(key, values[i]).apply();
            }
        });
        return group;
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
