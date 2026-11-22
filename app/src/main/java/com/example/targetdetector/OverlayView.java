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

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<ColorDetector.Detection> detections = new ArrayList<>();

    public OverlayView(Context context) {
        super(context);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);
    }

    public void updateDetections(List<ColorDetector.Detection> newDetections) {
        post(() -> {
            detections = new ArrayList<>(newDetections);
            invalidate();
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (detections.isEmpty()) {
            return;
        }

        for (ColorDetector.Detection detection : detections) {
            if (detection.type == ColorDetector.TargetType.PLAYER) {
                paint.setColor(Color.GREEN);
            } else if (detection.type == ColorDetector.TargetType.TEAMMATE) {
                paint.setColor(Color.BLUE);
            } else {
                paint.setColor(Color.RED);
            }

            RectF box = detection.box;
            canvas.drawRect(box, paint);
        }
    }
}
