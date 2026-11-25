package com.example.targetdetector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

import java.util.Random;

public class AutoClickService extends AccessibilityService {

    private static final float TAP_BOX_HALF_PX = 18f;
    private static final double AIM_SWIPE_BASE_MS = 85.0;
    private static final double AIM_SWIPE_JITTER_MS = 14.0;
    private static final double AIM_ANGLE_JITTER_DEG = 0.8;
    private static final long MIN_COOLDOWN_MS = 60L;
    private static final long SAFETY_UNLOCK_EXTRA_MS = 180L;

    private static volatile AutoClickService instance;
    private static volatile AssistiveGestureDispatcher dispatcher;
    private static final GesturePhysicsProfile profile = new GesturePhysicsProfile();
    private static final Random random = new Random();

    private static volatile boolean gestureBusy = false;
    private static volatile long busyUnlockAtMs = 0L;
    private static volatile long nextAllowedGestureMs = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        dispatcher = new AssistiveGestureDispatcher(this, profile);
        gestureBusy = false;
        busyUnlockAtMs = 0L;
        nextAllowedGestureMs = 0L;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        if (dispatcher != null) {
            dispatcher.cancelPending();
        }
        gestureBusy = false;
    }

    @Override
    public void onDestroy() {
        if (dispatcher != null) {
            dispatcher.cancelPending();
        }
        if (instance == this) {
            instance = null;
            dispatcher = null;
            gestureBusy = false;
        }
        super.onDestroy();
    }

    public static boolean isRunning() {
        return instance != null && dispatcher != null;
    }

    public static GesturePhysicsProfile getProfile() {
        return profile;
    }

    public static boolean canStartGesture() {
        if (!isRunning()) return false;
        long now = SystemClock.uptimeMillis();
        if (gestureBusy && now < busyUnlockAtMs) {
            return false;
        }
        return now >= nextAllowedGestureMs;
    }

    // Ergonomischer Tipp über den Dispatcher
    public static void click(float x, float y) {
        if (!canStartGesture()) return;
        AssistiveGestureDispatcher d = dispatcher;
        if (d == null) return;

        markBusy(160L);
        RectF bounds = new RectF(
                x - TAP_BOX_HALF_PX,
                y - TAP_BOX_HALF_PX,
                x + TAP_BOX_HALF_PX,
                y + TAP_BOX_HALF_PX
        );
        d.performTapWithResult(bounds, completed -> markFinished(MIN_COOLDOWN_MS));
    }

    // Knopf dynamisch lange gedrückt halten
    public static void hold(float x, float y, long durationMs) {
        if (!canStartGesture()) return;
        AutoClickService service = instance;
        if (service == null) return;

        long safeDuration = Math.max(15L, durationMs);
        markBusy(safeDuration);

        double rollAngle = random.nextDouble() * Math.PI * 2.0;
        float rollDist = (float) (random.nextDouble() * profile.tapMaxRollPx);

        Path path = new Path();
        path.moveTo(Math.max(0f, x), Math.max(0f, y));
        path.lineTo(
                Math.max(0f, x + (float) (Math.cos(rollAngle) * rollDist)),
                Math.max(0f, y + (float) (Math.sin(rollAngle) * rollDist))
        );

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, safeDuration);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean ok = service.dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                markFinished(MIN_COOLDOWN_MS);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                markFinished(MIN_COOLDOWN_MS);
            }
        }, null);

        if (!ok) {
            markFinished(MIN_COOLDOWN_MS);
        }
    }

    // Ergonomischer Wisch von Punkt A nach Punkt B über den Dispatcher
    public static void swipe(float startX, float startY, float endX, float endY, long durationMs) {
        if (!canStartGesture()) return;
        AssistiveGestureDispatcher d = dispatcher;
        if (d == null) return;

        long safeDuration = Math.max(25L, durationMs);
        markBusy(safeDuration + Math.round(profile.settleWindowMs));
        d.performDragWithResult(
                new PointF(startX, startY),
                new PointF(endX, endY),
                safeDuration,
                completed -> markFinished(MIN_COOLDOWN_MS)
        );
    }

    // Gezielter Joystick-Wisch für Brawl Stars:
    // centerX/Y = Mitte des roten Angriffs-Joysticks, dirX/Y = Zielrichtung (Ich -> Gegner), radiusPx = Wischlänge
    public static void aimSwipe(float centerX, float centerY, float dirX, float dirY, float radiusPx) {
        if (!canStartGesture()) return;
        AssistiveGestureDispatcher d = dispatcher;
        if (d == null) return;

        double len = Math.hypot(dirX, dirY);
        if (len < 1e-3 || radiusPx < 10f) return;

        double ux = dirX / len;
        double uy = dirY / len;

        // Minimale Startpunkt-Streuung um die Joystick-Mitte
        double startSigma = Math.max(2.0, radiusPx * 0.04);
        float startX = (float) (centerX + random.nextGaussian() * startSigma);
        float startY = (float) (centerY + random.nextGaussian() * startSigma);

        // Minimale Winkelstreuung, damit keine zwei Schüsse identisch sind
        double angleJitterRad = random.nextGaussian() * Math.toRadians(AIM_ANGLE_JITTER_DEG);
        double cosA = Math.cos(angleJitterRad);
        double sinA = Math.sin(angleJitterRad);
        double rx = ux * cosA - uy * sinA;
        double ry = ux * sinA + uy * sinA;

        float endX = startX + (float) (rx * radiusPx);
        float endY = startY + (float) (ry * radiusPx);

        double durationMs = clamp(
                AIM_SWIPE_BASE_MS + random.nextGaussian() * AIM_SWIPE_JITTER_MS,
                55.0,
                125.0
        );

        markBusy(Math.round(durationMs + profile.settleWindowMs));
        d.performDragWithResult(
                new PointF(startX, startY),
                new PointF(endX, endY),
                durationMs,
                completed -> markFinished(sampleCooldownMs())
        );
    }

    private static void markBusy(long estimatedDurationMs) {
        gestureBusy = true;
        busyUnlockAtMs = SystemClock.uptimeMillis() + Math.max(50L, estimatedDurationMs) + SAFETY_UNLOCK_EXTRA_MS;
    }

    private static void markFinished(long cooldownMs) {
        gestureBusy = false;
        nextAllowedGestureMs = SystemClock.uptimeMillis() + Math.max(MIN_COOLDOWN_MS, cooldownMs);
    }

    private static long sampleCooldownMs() {
        double delay = profile.actionDelayMinMs
                + random.nextDouble() * Math.max(0.0, profile.actionDelayMaxMs - profile.actionDelayMinMs);
        return Math.max(MIN_COOLDOWN_MS, Math.round(delay));
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
