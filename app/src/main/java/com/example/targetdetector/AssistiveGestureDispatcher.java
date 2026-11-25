package com.example.targetdetector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.example.targetdetector.GesturePhysicsProfile.TouchSample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wandelt synthetisierte Touch-Spuren in Accessibility-Gesten um und dispatcht sie.
 *
 * <p><b>Threading:</b> Alle öffentlichen Methoden dürfen aus beliebigen Threads aufgerufen
 * werden. Synthese und Dispatch laufen immer auf dem Main-Thread, sodass der
 * {@link ErgonomicMotionSynthesizer} nie parallel benutzt wird. Callbacks kommen auf dem
 * Main-Thread an und werden pro Geste genau einmal aufgerufen.</p>
 *
 * <p>Ein neuer Gesten-Dispatch bricht eine noch laufende Geste ab; deren Callback meldet
 * dann {@code completed == false}. Drag und Kombi-Geste verwerfen zusätzlich noch wartende
 * (verzögerte) Taps, damit diese nicht mitten in die Wischgeste feuern.</p>
 */
public final class AssistiveGestureDispatcher {

    private static final String TAG = "GestureDispatcher";

    /** Mindesthaltedauer des Pads bei kombinierten Gesten. */
    private static final double MIN_PAD_HOLD_MS = 180.0;

    /** Ergebnis einer Geste: {@code true} = vollständig ausgeführt, {@code false} = abgebrochen/fehlgeschlagen. */
    public interface GestureCallback {
        void onResult(boolean completed);
    }

    public interface GestureOutputAdapter {
        void dispatchMultiTouch(@NonNull List<List<TouchSample>> pointerTracks,
                                @NonNull GestureCallback callback);
    }

    private final ErgonomicMotionSynthesizer synthesizer;
    private final GestureOutputAdapter adapter;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Noch nicht gestartete (verzögerte) Gesten. Nur auf dem Main-Thread benutzen. */
    private final List<PendingGesture> pending = new ArrayList<>();

    public AssistiveGestureDispatcher(@NonNull AccessibilityService service,
                                      @NonNull GesturePhysicsProfile profile) {
        this(new ErgonomicMotionSynthesizer(profile), new AccessibilityGestureAdapter(service));
    }

    /** Für Tests oder alternative Ausgabekanäle. */
    AssistiveGestureDispatcher(@NonNull ErgonomicMotionSynthesizer synthesizer,
                               @NonNull GestureOutputAdapter adapter) {
        this.synthesizer = synthesizer;
        this.adapter = adapter;
    }

    /**
     * Achtung: Der Synthesizer ist nicht thread-sicher. Nur auf dem Main-Thread benutzen,
     * solange gleichzeitig Gesten über diesen Dispatcher laufen.
     */
    @NonNull
    public ErgonomicMotionSynthesizer getSynthesizer() {
        return synthesizer;
    }

    // ---------------------------------------------------------------------------------------
    // Öffentliche API (Runnable, abwärtskompatibel)
    // ---------------------------------------------------------------------------------------

    public void performTap(@Nullable RectF controlBounds, @Nullable Runnable onComplete) {
        performTapWithResult(controlBounds, fromRunnable(onComplete));
    }

    public void performDrag(@Nullable PointF start, @Nullable PointF target, double durationMs,
                            @Nullable Runnable onComplete) {
        performDragWithResult(start, target, durationMs, fromRunnable(onComplete));
    }

    public void performConcurrentPadAndDrag(@Nullable PointF padCenter, float padRadius,
                                            double startAngleRad, double endAngleRad,
                                            @Nullable PointF dragStart, @Nullable PointF dragTarget,
                                            double dragDurationMs, @Nullable Runnable onComplete) {
        performConcurrentPadAndDragWithResult(padCenter, padRadius, startAngleRad, endAngleRad,
                dragStart, dragTarget, dragDurationMs, fromRunnable(onComplete));
    }

    // ---------------------------------------------------------------------------------------
    // Öffentliche API (mit Ergebnis)
    // ---------------------------------------------------------------------------------------

    public void performTapWithResult(@Nullable RectF controlBounds, @Nullable GestureCallback callback) {
        final GestureCallback cb = once(callback);
        if (controlBounds == null || controlBounds.isEmpty()) {
            postResult(cb, false);
            return;
        }
        final RectF bounds = new RectF(controlBounds); // Kopie: Ausführung erfolgt später
        runOnMain(() -> {
            long delay = Math.max(0L, synthesizer.sampleInterActionDelayMs());
            PendingGesture p = new PendingGesture(cb, () -> {
                List<TouchSample> track = synthesizer.synthesizeTap(bounds, 0L);
                dispatch(Collections.singletonList(track), cb);
            });
            pending.add(p);
            mainHandler.postDelayed(p, delay);
        });
    }

    public void performDragWithResult(@Nullable PointF start, @Nullable PointF target,
                                      double durationMs, @Nullable GestureCallback callback) {
        final GestureCallback cb = once(callback);
        if (start == null || target == null || !(durationMs > 0)) {
            postResult(cb, false);
            return;
        }
        final PointF s = new PointF(start.x, start.y);
        final PointF t = new PointF(target.x, target.y);
        runOnMain(() -> {
            cancelPendingOnMain(); // verzögerten Tap nicht in den Drag hineinfeuern lassen
            List<TouchSample> track = synthesizer.synthesizeDragGesture(s, t, durationMs, 0L);
            dispatch(Collections.singletonList(track), cb);
        });
    }

    public void performConcurrentPadAndDragWithResult(@Nullable PointF padCenter, float padRadius,
                                                      double startAngleRad, double endAngleRad,
                                                      @Nullable PointF dragStart,
                                                      @Nullable PointF dragTarget,
                                                      double dragDurationMs,
                                                      @Nullable GestureCallback callback) {
        final GestureCallback cb = once(callback);
        if (padCenter == null || dragStart == null || dragTarget == null
                || !(padRadius > 0) || !(dragDurationMs > 0)) {
            postResult(cb, false);
            return;
        }
        final PointF center = new PointF(padCenter.x, padCenter.y);
        final PointF ds = new PointF(dragStart.x, dragStart.y);
        final PointF dt = new PointF(dragTarget.x, dragTarget.y);
        runOnMain(() -> {
            cancelPendingOnMain(); // verzögerten Tap nicht in die Kombi-Geste hineinfeuern lassen
            long secondaryOffsetMs = Math.max(0L, synthesizer.sampleInterActionDelayMs());
            double totalHoldMs = Math.max(dragDurationMs + secondaryOffsetMs, MIN_PAD_HOLD_MS);

            List<List<TouchSample>> tracks = new ArrayList<>(2);
            tracks.add(synthesizer.synthesizeRadialPadGesture(
                    center, padRadius, startAngleRad, endAngleRad, totalHoldMs, 0L));
            tracks.add(synthesizer.synthesizeDragGesture(ds, dt, dragDurationMs, secondaryOffsetMs));
            dispatch(tracks, cb);
        });
    }

    /**
     * Verwirft noch nicht gestartete (verzögerte) Gesten, z. B. in onInterrupt()/onDestroy().
     * Deren Callbacks werden mit {@code false} aufgerufen, damit niemand ewig wartet.
     */
    public void cancelPending() {
        runOnMain(this::cancelPendingOnMain);
    }

    @MainThread
    private void cancelPendingOnMain() {
        if (pending.isEmpty()) return;
        List<PendingGesture> toCancel = new ArrayList<>(pending);
        pending.clear();
        for (PendingGesture p : toCancel) {
            mainHandler.removeCallbacks(p);
            safeResult(p.callback, false);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Intern
    // ---------------------------------------------------------------------------------------

    private final class PendingGesture implements Runnable {
        final GestureCallback callback;
        final Runnable work;

        PendingGesture(GestureCallback callback, Runnable work) {
            this.callback = callback;
            this.work = work;
        }

        @Override
        public void run() {
            pending.remove(this);
            work.run();
        }
    }

    @MainThread
    private void dispatch(List<List<TouchSample>> tracks, GestureCallback cb) {
        try {
            adapter.dispatchMultiTouch(tracks, cb);
        } catch (RuntimeException e) {
            Log.e(TAG, "Gesture dispatch failed", e);
            safeResult(cb, false);
        }
    }

    private void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    /** Fehlerergebnis immer asynchron und auf dem Main-Thread melden (einheitliches Verhalten). */
    private void postResult(GestureCallback cb, boolean completed) {
        mainHandler.post(() -> safeResult(cb, completed));
    }

    private static void safeResult(GestureCallback cb, boolean completed) {
        try {
            cb.onResult(completed);
        } catch (RuntimeException e) {
            Log.e(TAG, "Gesture callback threw", e);
        }
    }

    /** Stellt sicher, dass ein Callback höchstens einmal ausgelöst wird. */
    private static GestureCallback once(@Nullable GestureCallback cb) {
        if (cb == null) return completed -> { };
        final AtomicBoolean fired = new AtomicBoolean(false);
        return completed -> {
            if (fired.compareAndSet(false, true)) cb.onResult(completed);
        };
    }

    @Nullable
    private static GestureCallback fromRunnable(@Nullable Runnable r) {
        return r == null ? null : completed -> r.run();
    }

    // ---------------------------------------------------------------------------------------
    // Accessibility-Adapter
    // ---------------------------------------------------------------------------------------

    private static final class AccessibilityGestureAdapter implements GestureOutputAdapter {

        private static final long MIN_STROKE_DURATION_MS = 10L;

        private final AccessibilityService service;

        AccessibilityGestureAdapter(@NonNull AccessibilityService service) {
            this.service = service;
        }

        @Override
        public void dispatchMultiTouch(@NonNull List<List<TouchSample>> pointerTracks,
                                       @NonNull GestureCallback callback) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || pointerTracks.isEmpty()) {
                safeResult(callback, false);
                return;
            }

            final GestureDescription gesture;
            try {
                gesture = buildGesture(pointerTracks);
            } catch (IllegalArgumentException | IllegalStateException e) {
                // StrokeDescription/Builder werfen bei ungültigen Pfaden, Zeiten oder zu vielen Strokes.
                Log.w(TAG, "Invalid gesture description", e);
                safeResult(callback, false);
                return;
            }

            if (gesture == null) {
                safeResult(callback, false);
                return;
            }

            boolean dispatched = service.dispatchGesture(gesture,
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            safeResult(callback, true);
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            safeResult(callback, false);
                        }
                    }, null);

            // Bei false ruft das System keinen Callback auf -> selbst melden.
            if (!dispatched) {
                safeResult(callback, false);
            }
        }

        @RequiresApi(Build.VERSION_CODES.N)
        @Nullable
        private GestureDescription buildGesture(List<List<TouchSample>> pointerTracks) {
            final RectF screen = screenBounds();
            final int maxStrokes = GestureDescription.getMaxStrokeCount();
            final long maxDuration = GestureDescription.getMaxGestureDuration();

            GestureDescription.Builder builder = new GestureDescription.Builder();
            int strokeCount = 0;

            for (List<TouchSample> samples : pointerTracks) {
                if (strokeCount >= maxStrokes) {
                    Log.w(TAG, "Stroke limit reached (" + maxStrokes + "), remaining tracks ignored");
                    break;
                }

                List<TouchSample> valid = filterValid(samples);
                if (valid.isEmpty()) continue;

                TouchSample first = valid.get(0);
                TouchSample last = valid.get(valid.size() - 1);

                long startTime = Math.max(0L, first.timeMs);
                if (startTime >= maxDuration) continue;

                long duration = Math.max(MIN_STROKE_DURATION_MS, last.timeMs - first.timeMs);
                duration = Math.min(duration, maxDuration - startTime);
                if (duration <= 0) continue;

                Path path = new Path();
                path.moveTo(clamp(first.x, screen.left, screen.right),
                        clamp(first.y, screen.top, screen.bottom));
                for (int i = 1; i < valid.size(); i++) {
                    TouchSample s = valid.get(i);
                    path.lineTo(clamp(s.x, screen.left, screen.right),
                            clamp(s.y, screen.top, screen.bottom));
                }

                builder.addStroke(new GestureDescription.StrokeDescription(path, startTime, duration));
                strokeCount++;
            }

            return strokeCount > 0 ? builder.build() : null;
        }

        /** Entfernt null-Samples und Samples mit NaN/Infinity-Koordinaten. */
        private static List<TouchSample> filterValid(@Nullable List<TouchSample> samples) {
            if (samples == null || samples.isEmpty()) return Collections.emptyList();
            List<TouchSample> out = new ArrayList<>(samples.size());
            for (TouchSample s : samples) {
                if (s != null && isFinite(s.x) && isFinite(s.y)) out.add(s);
            }
            return out;
        }

        /** Echte Displaygröße inkl. Systemleisten, damit gültige Randberührungen nicht verfälscht werden. */
        @SuppressWarnings("deprecation")
        private RectF screenBounds() {
            WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Rect b = wm.getMaximumWindowMetrics().getBounds();
                    return new RectF(0f, 0f, Math.max(0, b.width() - 1), Math.max(0, b.height() - 1));
                }
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                return new RectF(0f, 0f, Math.max(0, dm.widthPixels - 1), Math.max(0, dm.heightPixels - 1));
            }
            DisplayMetrics dm = service.getResources().getDisplayMetrics();
            return new RectF(0f, 0f, Math.max(0, dm.widthPixels - 1), Math.max(0, dm.heightPixels - 1));
        }

        private static float clamp(float v, float min, float max) {
            return v < min ? min : (v > max ? max : v);
        }

        private static boolean isFinite(float v) {
            return !Float.isNaN(v) && !Float.isInfinite(v);
        }
    }
}
