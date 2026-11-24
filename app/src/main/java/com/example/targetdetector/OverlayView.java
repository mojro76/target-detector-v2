package com.example.targetdetector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {

    private static final int COLOR_PLAYER = Color.rgb(155, 255, 155);
    private static final int COLOR_TEAMMATE = Color.rgb(155, 190, 255);
    private static final int COLOR_ENEMY = Color.rgb(255, 155, 155);

    // Findet die KI in einem Bild gar nichts, bleiben die letzten Boxen so lange stehen (gegen Flackern)
    private static final long HOLD_MS = 120;
    // Ab dieser Überlappung gelten zwei Boxen als derselbe Gegner
    private static final float DUPLICATE_IOU = 0.3f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<ColorDetector.Detection> detections = new ArrayList<>();
    private String debugText = "Starte...";
    private long lastHitTime = 0;

    public OverlayView(Context context) {
        super(context);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);

        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(42f);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);
    }

    public void updateDetections(List<ColorDetector.Detection> newDetections, String status) {
        final List<ColorDetector.Detection> filtered = removeDuplicates(newDetections);
        post(() -> {
            debugText = status;
            long now = SystemClock.uptimeMillis();
            if (!filtered.isEmpty()) {
                // Neue Boxen sofort übernehmen, ohne Verzögerung
                detections = filtered;
                lastHitTime = now;
            } else if (now - lastHitTime > HOLD_MS) {
                detections = filtered;
            }
            invalidate();
        });
    }

    // Die KI liefert die sicherste Box zuerst. Jede weitere Box, die stark mit einer
    // schon behaltenen überlappt oder deren Mitte darin liegt, wird verworfen.
    private List<ColorDetector.Detection> removeDuplicates(List<ColorDetector.Detection> list) {
        List<ColorDetector.Detection> result = new ArrayList<>();
        for (ColorDetector.Detection d : list) {
            boolean duplicate = false;
            for (ColorDetector.Detection k : result) {
                if (iou(d.box, k.box) > DUPLICATE_IOU
                        || k.box.contains(d.box.centerX(), d.box.centerY())
                        || d.box.contains(k.box.centerX(), k.box.centerY())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                result.add(d);
            }
        }
        return result;
    }

    private static float iou(RectF a, RectF b) {
        float interW = Math.max(0f, Math.min(a.right, b.right) - Math.max(a.left, b.left));
        float interH = Math.max(0f, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
        float inter = interW * interH;
        float union = a.width() * a.height() + b.width() * b.height() - inter;
        return union > 0f ? inter / union : 0f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (debugText != null) {
            canvas.drawText(debugText, 40f, 120f, textPaint);
        }

        for (ColorDetector.Detection detection : detections) {
            if (detection.type == ColorDetector.TargetType.PLAYER) {
                paint.setColor(COLOR_PLAYER);
            } else if (detection.type == ColorDetector.TargetType.TEAMMATE) {
                paint.setColor(COLOR_TEAMMATE);
            } else {
                paint.setColor(COLOR_ENEMY);
            }
            canvas.drawRect(detection.box, paint);
        }
    }
}
