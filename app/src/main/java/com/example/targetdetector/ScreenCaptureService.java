package com.example.targetdetector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class ScreenCaptureService extends Service {

    private static final String CHANNEL_ID = "target_detector_channel";
    // Kurze Pause zwischen zwei Bildern. Kleiner = Boxen öfter aktuell, aber Handy wärmer.
    // Wird das Handy zu heiß, Wert erhöhen (z. B. 30 oder 40).
    private static final long MIN_FRAME_INTERVAL_MS = 15;
    private static final float CAPTURE_LONG_SIDE = 640f; // lange Bildseite passend zur KI

    // ===== Schuss-Konfiguration (gemessen auf 2340x1080: Angriff = 1952, 640 | Zielkreis ~155 px) =====
    private static final float ATTACK_JOYSTICK_X_RATIO = 1952f / 2340f; // 0.8342
    private static final float ATTACK_JOYSTICK_Y_RATIO = 640f / 1080f;  // 0.5926
    private static final float ATTACK_SWIPE_RADIUS_RATIO = 148f / 1080f; // ~0.137 * Bildschirmhöhe
    private static final float MAX_SHOOT_RANGE_RATIO = 0.45f;           // max. 45 % der Bildschirmbreite
    private static final long SHOOT_COOLDOWN_MIN_MS = 450L;             // minimale Pause zwischen Schüssen
    private static final long SHOOT_COOLDOWN_MAX_MS = 600L;             // maximale Pause zwischen Schüssen
    private static final long AIM_MAX_AGE_MS = 250L;                    // ältere Ziellinien nicht mehr nutzen

    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;

    private WindowManager windowManager;
    private OverlayView overlayView;
    private YoloDetector yoloDetector;

    // ===== Aufnahme (zum Auswerten am PC) =====
    private FrameRecorder recorder;
    private TextView recButton;                    // kleiner, verschiebbarer Knopf im Spiel
    private WindowManager.LayoutParams recParams;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Aktualisiert jede Viertelsekunde den Countdown auf dem Knopf
    private final Runnable recTicker = new Runnable() {
        @Override
        public void run() {
            updateRecButton();
            if (recorder != null && recorder.isRecording()) {
                mainHandler.postDelayed(this, 250);
            }
        }
    };

    // ===== Schuss-Schalter (gleicher Stil wie REC) =====
    private TextView shootButton;
    private WindowManager.LayoutParams shootParams;
    private volatile boolean shootEnabled = false;
    private long nextShotAllowedMs = 0L;

    private int screenWidth;
    private int screenHeight;
    private int captureWidth;
    private int captureHeight;
    private int screenDensity;
    private int captureDensity;
    private boolean isResizing = false;
    private long lastProcessTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        yoloDetector = new YoloDetector(this);
        recorder = new FrameRecorder(this);
        recorder.setListener(rec -> {
            mainHandler.removeCallbacks(recTicker);
            mainHandler.post(recTicker);
        });
        updateScreenMetrics();
        setupOverlay();
        SharedPreferences prefs = getSharedPreferences(FrameRecorder.PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(FrameRecorder.KEY_BUTTON, true)) {
            setupRecordButton();
        }
        setupShootButton();
    }

    private void updateScreenMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
        float factor = CAPTURE_LONG_SIDE / Math.max(screenWidth, screenHeight);
        captureWidth = Math.max(1, Math.round(screenWidth * factor));
        captureHeight = Math.max(1, Math.round(screenHeight * factor));
        captureDensity = Math.max(1, Math.round(screenDensity * factor));
    }

    private void setupOverlay() {
        overlayView = new OverlayView(this);
        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        windowManager.addView(overlayView, params);
    }

    // Kleiner Knopf am linken Rand: antippen = Aufnahme starten/stoppen, ziehen = verschieben.
    // Dunkel mit winzigem roten Punkt, damit die Ring-Suche ihn nicht für einen Ring hält.
    private void setupRecordButton() {
        recButton = new TextView(this);
        recButton.setTextColor(Color.WHITE);
        recButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int pad = dp(8);
        recButton.setPadding(dp(10), pad, dp(10), pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(170, 30, 30, 30));
        bg.setCornerRadius(dp(16));
        recButton.setBackground(bg);
        updateRecButton();

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        recParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        recParams.gravity = Gravity.TOP | Gravity.LEFT;
        recParams.x = dp(4);
        recParams.y = Math.round(Math.min(screenWidth, screenHeight) * 0.42f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            recParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        final int touchSlop = dp(8);
        recButton.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startX;
            private int startY;
            private boolean dragging;

            @Override
            public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = recParams.x;
                        startY = recParams.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                            dragging = true;
                        }
                        if (dragging && recButton != null) {
                            recParams.x = startX + Math.round(dx);
                            recParams.y = startY + Math.round(dy);
                            windowManager.updateViewLayout(recButton, recParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging && recorder != null) {
                            recorder.toggle();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        windowManager.addView(recButton, recParams);
    }

    // Text des Knopfs: "● REC" in Ruhe, "■ 12 s" während der Aufnahme
    private void updateRecButton() {
        if (recButton == null) {
            return;
        }
        boolean rec = recorder != null && recorder.isRecording();
        String text = rec ? "\u25A0 " + recorder.secondsLeft() + " s" : "\u25CF REC";
        SpannableString span = new SpannableString(text);
        span.setSpan(new ForegroundColorSpan(rec ? Color.WHITE : Color.rgb(255, 70, 70)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        recButton.setText(span);
        GradientDrawable bg = (GradientDrawable) recButton.getBackground();
        if (bg != null) {
            bg.setColor(rec ? Color.argb(200, 150, 20, 20) : Color.argb(170, 30, 30, 30));
        }
    }

    // Zweiter Schwebe-Knopf direkt unter REC: antippen = SCHUSS an/aus, ziehen = verschieben.
    // Dunkel bzw. gedämpftes Bernsteingelb, damit die Ring-Erkennung ihn ignoriert.
    private void setupShootButton() {
        shootButton = new TextView(this);
        shootButton.setTextColor(Color.WHITE);
        shootButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        int pad = dp(8);
        shootButton.setPadding(dp(10), pad, dp(10), pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(170, 30, 30, 30));
        bg.setCornerRadius(dp(16));
        shootButton.setBackground(bg);
        updateShootButton();

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        shootParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        shootParams.gravity = Gravity.TOP | Gravity.LEFT;
        shootParams.x = dp(4);
        shootParams.y = recParams != null
                ? recParams.y + dp(46)
                : Math.round(Math.min(screenWidth, screenHeight) * 0.42f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            shootParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        final int touchSlop = dp(8);
        shootButton.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float downX;
            private float downY;
            private int startX;
            private int startY;
            private boolean dragging;

            @Override
            public boolean onTouch(android.view.View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = shootParams.x;
                        startY = shootParams.y;
                        dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                            dragging = true;
                        }
                        if (dragging && shootButton != null) {
                            shootParams.x = startX + Math.round(dx);
                            shootParams.y = startY + Math.round(dy);
                            windowManager.updateViewLayout(shootButton, shootParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragging) {
                            toggleShoot();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        windowManager.addView(shootButton, shootParams);
    }

    private void toggleShoot() {
        if (!shootEnabled) {
            if (!AutoClickService.isRunning()) {
                Toast.makeText(this, "Bedienungshilfe (AutoClickService) erst aktivieren", Toast.LENGTH_SHORT).show();
                updateShootButton();
                return;
            }
            shootEnabled = true;
        } else {
            shootEnabled = false;
        }
        updateShootButton();
    }

    // Text des Knopfs: "○ SCHUSS" (aus), "● SCHUSS" (an)
    private void updateShootButton() {
        if (shootButton == null) {
            return;
        }
        boolean on = shootEnabled;
        String text = (on ? "\u25CF" : "\u25CB") + " SCHUSS";
        SpannableString span = new SpannableString(text);
        span.setSpan(new ForegroundColorSpan(on ? Color.rgb(255, 210, 60) : Color.rgb(170, 170, 170)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        shootButton.setText(span);
        GradientDrawable bg = (GradientDrawable) shootButton.getBackground();
        if (bg != null) {
            bg.setColor(on ? Color.argb(205, 155, 95, 15) : Color.argb(170, 30, 30, 30));
        }
    }

    // Löst bei aktivem SCHUSS-Schalter einen ergonomischen Joystick-Wisch entlang der Ziellinie aus
    private void tryAutoShoot() {
        if (!shootEnabled || overlayView == null) {
            return;
        }
        if (!AutoClickService.isRunning()) {
            shootEnabled = false;
            mainHandler.post(this::updateShootButton);
            return;
        }
        // Nur im Querformat (Spiel) auslösen, nicht im Hochformat-Menü
        if (screenWidth <= screenHeight) {
            return;
        }

        long nowUptime = SystemClock.uptimeMillis();
        if (nowUptime < nextShotAllowedMs || !AutoClickService.canStartGesture()) {
            return;
        }

        OverlayView.AimSnapshot aim = overlayView.getAimSnapshot(AIM_MAX_AGE_MS);
        if (aim == null || aim.distancePx < 10f) {
            return;
        }

        float maxRangePx = screenWidth * MAX_SHOOT_RANGE_RATIO;
        if (aim.distancePx > maxRangePx) {
            return;
        }

        float joyX = screenWidth * ATTACK_JOYSTICK_X_RATIO;
        float joyY = screenHeight * ATTACK_JOYSTICK_Y_RATIO;
        float swipeRadius = screenHeight * ATTACK_SWIPE_RADIUS_RATIO;

        AutoClickService.aimSwipe(joyX, joyY, aim.dirX, aim.dirY, swipeRadius);

        long cooldown = ThreadLocalRandom.current()
                .nextLong(SHOOT_COOLDOWN_MIN_MS, SHOOT_COOLDOWN_MAX_MS + 1L);
        nextShotAllowedMs = nowUptime + cooldown;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    1,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(1, createNotification());
        }

        if (intent == null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("resultData");

        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        cleanupCapture();

        if (handlerThread == null) {
            handlerThread = new HandlerThread("ScreenCaptureThread");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
        }

        MediaProjectionManager projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        MediaProjection newProjection = projectionManager.getMediaProjection(resultCode, data);

        if (newProjection != null) {
            mediaProjection = newProjection;
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (mediaProjection == newProjection) {
                        stopSelf();
                    }
                }
            };
            mediaProjection.registerCallback(projectionCallback, handler);
            setupCapture();
        } else {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void cleanupCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            if (projectionCallback != null) {
                mediaProjection.unregisterCallback(projectionCallback);
                projectionCallback = null;
            }
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    private void setupCapture() {
        updateScreenMetrics();

        ImageReader oldReader = imageReader;
        ImageReader newReader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
        );

        newReader.setOnImageAvailableListener(reader -> {
            if (isResizing) return;

            DisplayMetrics currentMetrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(currentMetrics);
            if (currentMetrics.widthPixels != screenWidth || currentMetrics.heightPixels != screenHeight) {
                isResizing = true;
                handler.post(() -> {
                    setupCapture();
                    isResizing = false;
                });
                return;
            }

            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;

                long now = System.currentTimeMillis();
                if (now - lastProcessTime < MIN_FRAME_INTERVAL_MS) {
                    return;
                }
                lastProcessTime = now;

                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();

                long t0 = System.nanoTime();
                List<ColorDetector.Detection> detections = yoloDetector != null
                        ? yoloDetector.detect(
                                buffer,
                                captureWidth,
                                captureHeight,
                                pixelStride,
                                rowStride,
                                screenWidth,
                                screenHeight
                        )
                        : Collections.emptyList();

                long ms = (System.nanoTime() - t0) / 1_000_000;
                String debugInfo = yoloDetector != null ? yoloDetector.getLastDebug() : "Detector null";

                // Läuft eine Aufnahme? Dann dieses Bild (mit Messwerten) im Hintergrund speichern
                if (recorder != null && recorder.isRecording()) {
                    recorder.addFrame(buffer, captureWidth, captureHeight, pixelStride, rowStride,
                            detections, ms, debugInfo);
                }
                overlayView.updateDetections(
                        detections,
                        "KI: " + ms + " ms | Treffer: " + detections.size() + " | " + debugInfo
                );

                tryAutoShoot();
            } catch (Exception e) {
                overlayView.updateDetections(Collections.emptyList(), "ERR: " + e.getMessage());
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, handler);

        imageReader = newReader;

        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "TargetDetectorDisplay",
                    captureWidth,
                    captureHeight,
                    captureDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    handler
            );
        } else {
            virtualDisplay.resize(captureWidth, captureHeight, captureDensity);
            virtualDisplay.setSurface(imageReader.getSurface());
        }

        if (oldReader != null) {
            oldReader.close();
        }
    }

    private Notification createNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Target Detector läuft")
                .setContentText("KI-Bildschirmanalyse ist aktiv")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Target Detector",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        // Laufende Aufnahme sauber abschließen (die ZIP-Datei wird im Hintergrund fertig geschrieben)
        if (recorder != null) {
            recorder.stop();
        }
        mainHandler.removeCallbacks(recTicker);
        if (recButton != null && windowManager != null) {
            windowManager.removeView(recButton);
            recButton = null;
        }
        shootEnabled = false;
        if (shootButton != null && windowManager != null) {
            windowManager.removeView(shootButton);
            shootButton = null;
        }
        cleanupCapture();
        if (yoloDetector != null) {
            yoloDetector.close();
            yoloDetector = null;
        }
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
            overlayView = null;
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
