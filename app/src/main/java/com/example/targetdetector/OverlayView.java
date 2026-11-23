package com.example.targetdetector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {

    private static final int COLOR_PLAYER = Color.rgb(155, 255, 155);
    private static final int COLOR_TEAMMATE = Color.rgb(155, 190, 255);
    private static final int COLOR_ENEMY = Color.rgb(255, 155, 155);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<ColorDetector.Detection> detections = new ArrayList<>();
    private String debugText = "Starte...";

    public OverlayView(Context context) {
        super(context);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);

        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(42f);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);
    }

    public void updateDetections(List<ColorDetector.Detection> newDetections, String status) {
        post(() -> {
            detections = new ArrayList<>(newDetections);
            debugText = status;
            invalidate();
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (debugText != null) {
            canvas.drawText(debugText, 40f, 120f, textPaint);
        }

        if (detections.isEmpty()) {
            return;
        }

        for (ColorDetector.Detection detection : detections) {
            if (detection.type == ColorDetector.TargetType.PLAYER) {
                paint.setColor(COLOR_PLAYER);
            } else if (detection.type == ColorDetector.TargetType.TEAMMATE) {
                paint.setColor(COLOR_TEAMMATE);
            } else {
                paint.setColor(COLOR_ENEMY);
            }

            RectF box = detection.box;
            canvas.drawRect(box, paint);
        }
    }
}
