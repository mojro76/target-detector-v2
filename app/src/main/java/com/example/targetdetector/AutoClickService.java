package com.example.targetdetector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

public class AutoClickService extends AccessibilityService {

    private static AutoClickService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
    }

    public static boolean isRunning() {
        return instance != null;
    }

    // Kurzer Tipp (50 ms)
    public static void click(float x, float y) {
        hold(x, y, 50L);
    }

    // Knopf dynamisch lange gedrückt halten (z. B. je nach Abstand)
    public static void hold(float x, float y, long durationMs) {
        if (instance == null) return;
        Path path = new Path();
        path.moveTo(x, y);
        long safeDuration = Math.max(1L, durationMs);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, safeDuration);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        instance.dispatchGesture(gesture, null, null);
    }

    // Wischen oder Ziehen von Punkt A nach Punkt B
    public static void swipe(float startX, float startY, float endX, float endY, long durationMs) {
        if (instance == null) return;
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        long safeDuration = Math.max(10L, durationMs);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, safeDuration);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        instance.dispatchGesture(gesture, null, null);
    }
}
