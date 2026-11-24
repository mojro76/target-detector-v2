package com.example.targetdetector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
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
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

public class ScreenCaptureService extends Service {

    private static final String CHANNEL_ID = "target_detector_channel";
    // Kurze Pause zwischen zwei Bildern. Kleiner = Boxen öfter aktuell, aber Handy wärmer.
    // Wird das Handy zu heiß, Wert erhöhen (z. B. 30 oder 40).
    private static final long MIN_FRAME_INTERVAL_MS = 15;
    private static final float CAPTURE_LONG_SIDE = 640f; // lange Bildseite passend zur KI

    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;

    private WindowManager windowManager;
    private OverlayView overlayView;
    private YoloDetector yoloDetector;

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
        updateScreenMetrics();
        setupOverlay();
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
