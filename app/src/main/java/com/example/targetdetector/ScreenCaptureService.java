package com.example.targetdetector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
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
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;

public class ScreenCaptureService extends Service {

    private static final String CHANNEL_ID = "target_detector_channel";

    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private OverlayView overlayView;
    private WindowManager windowManager;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    10,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            );
        } else {
            startForeground(10, notification);
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayView = new OverlayView(this);

        WindowManager.LayoutParams overlayParams =
                new WindowManager.LayoutParams(
                        screenWidth,
                        screenHeight,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                );

        windowManager.addView(overlayView, overlayParams);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || overlayView == null || mediaProjection != null) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", 0);

        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra("resultData", Intent.class);
        } else {
            resultData = intent.getParcelableExtra("resultData");
        }

        if (resultData != null) {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

            mediaProjection = manager.getMediaProjection(resultCode, resultData);

            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            };
            mediaProjection.registerCallback(
                    projectionCallback,
                    new Handler(Looper.getMainLooper())
            );

            startCapture();
        }

        return START_NOT_STICKY;
    }

    private void startCapture() {
        handlerThread = new HandlerThread("ScreenCaptureThread");
        handlerThread.start();

        Handler handler = new Handler(handlerThread.getLooper());

        imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
        );

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            try {
                Bitmap bitmap = imageToBitmap(image);
                if (bitmap != null) {
                    overlayView.updateDetections(
                            ColorDetector.detect(bitmap, screenWidth, screenHeight)
                    );
                    bitmap.recycle();
                }
            } finally {
                image.close();
            }
        }, handler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "TargetDetectorDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler
        );
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];

        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * screenWidth;

        Bitmap fullBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
        );

        fullBitmap.copyPixelsFromBuffer(plane.getBuffer());

        Bitmap cropped = Bitmap.createBitmap(
                fullBitmap,
                0,
                0,
                screenWidth,
                screenHeight
        );

        if (cropped != fullBitmap) {
            fullBitmap.recycle();
        }

        return cropped;
    }

    private Notification createNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Target Detector läuft")
                .setContentText("Bildschirmanalyse ist aktiv")
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

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }

        if (imageReader != null) {
            imageReader.close();
        }

        if (mediaProjection != null) {
            if (projectionCallback != null) {
                mediaProjection.unregisterCallback(projectionCallback);
            }
            mediaProjection.stop();
        }

        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (handlerThread != null) {
            handlerThread.quitSafely();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
