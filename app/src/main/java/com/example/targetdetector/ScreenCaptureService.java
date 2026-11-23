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
    private static final long MIN_FRAME_INTERVAL_MS = 40; // nur eine kurze Pause zwischen zwei Bildern
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
                overlayView.updateDetections(
                        detections,
                        "KI: " + ms + " ms | Treffer: " + detections.size() + " | " + debugInfo
                );

                if (AutoClickService.isRunning()) {
                    for (ColorDetector.Detection d : detections) {
                        if (d.type == ColorDetector.TargetType.ENEMY) {
                            AutoClickService.click(d.box.centerX(), d.box.centerY());
                            break;
                        }
                    }
                }
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
