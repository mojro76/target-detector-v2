package com.example.targetdetector;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

public class ColorDetector {

    public enum TargetType {
        PLAYER,
        TEAMMATE,
        ENEMY
    }

    public static class Detection {
        public final RectF box;
        public final TargetType type;

        public Detection(RectF box, TargetType type) {
            this.box = box;
            this.type = type;
        }
    }

    public static List<Detection> detect(
            Bitmap bitmap,
            int outputWidth,
            int outputHeight
    ) {
        List<Detection> detections = new ArrayList<>();

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int step = 5;

        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int pixel = bitmap.getPixel(x, y);

                TargetType type = classify(pixel);

                if (type == null) {
                    continue;
                }

                int left = Math.max(0, x - 22);
                int top = Math.max(0, y - 22);
                int right = Math.min(width, x + 22);
                int bottom = Math.min(height, y + 22);

                RectF box = new RectF(
                        left,
                        top,
                        right,
                        bottom
                );

                if (!overlapsExisting(box, detections, type)) {
                    detections.add(new Detection(box, type));
                }
            }
        }

        return detections;
    }

    private static TargetType classify(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        float hue = hsv[0];
        float saturation = hsv[1];
        float value = hsv[2];

        if (saturation < 0.45f || value < 0.35f) {
            return null;
        }

        if (hue >= 70 && hue <= 170) {
            return TargetType.PLAYER;
        }

        if (hue >= 180 && hue <= 260) {
            return TargetType.TEAMMATE;
        }

        if (hue <= 20 || hue >= 340) {
            return TargetType.ENEMY;
        }

        return null;
    }

    private static boolean overlapsExisting(
            RectF candidate,
            List<Detection> detections,
            TargetType type
    ) {
        for (Detection detection : detections) {
            if (detection.type != type) {
                continue;
            }

            RectF intersection = new RectF(candidate);

            if (intersection.intersect(detection.box)) {
                return true;
            }
        }

        return false;
    }
}
