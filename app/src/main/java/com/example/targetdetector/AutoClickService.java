package com.example.targetdetector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

import java.util.Random;

public class AutoClickService extends AccessibilityService {

    private static AutoClickService instance;
    private static final Random random = new Random();
    private static long lastClickTime = 0;
    private static final long CLICK_COOLDOWN_MS = 500; // max. 2 Tipps pro Sekunde

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Wird hier nicht benötigt
    }

    @Override
    public void onInterrupt() {
        // Wird hier nicht benötigt
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void click(float x, float y) {
        if (instance == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastClickTime < CLICK_COOLDOWN_MS) {
            return;
        }
        lastClickTime = now;

        // Kleine Zufallsabweichung (±5 Pixel) und natürliche Tipp-Dauer (45–85 ms)
        float jitterX = Math.max(0, x + (random.nextFloat() * 10f - 5f));
        float jitterY = Math.max(0, y + (random.nextFloat() * 10f - 5f));
        long duration = 45 + random.nextInt(40);

        Path clickPath = new Path();
        clickPath.moveTo(jitterX, jitterY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(clickPath, 0, duration);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(stroke);

        instance.dispatchGesture(builder.build(), null, null);
    }
}
