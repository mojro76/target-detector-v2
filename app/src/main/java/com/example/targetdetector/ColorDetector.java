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

    private static class Cluster {
        float minX, minY, maxX, maxY;
        int hitCount;
        final TargetType type;

        Cluster(float x, float y, TargetType type) {
            this.minX = x;
            this.maxX = x;
            this.minY = y;
            this.maxY = y;
            this.hitCount = 1;
            this.type = type;
        }

        void add(float x, float y) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            hitCount++;
        }

        boolean isNear(float x, float y, float mergeDistance) {
            return x >= (minX - mergeDistance) && x <= (maxX + mergeDistance)
                    && y >= (minY - mergeDistance) && y <= (maxY + mergeDistance);
        }
    }

    public static List<Detection> detect(
            Bitmap bitmap,
            int outputWidth,
            int outputHeight
    ) {
        List<Detection> detections = new ArrayList<>();
        if (bitmap == null) return detections;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        float scaleX = (float) outputWidth / width;
        float scaleY = (float) outputHeight / height;

        // step = 3 passt exakt zur halben Aufnahme-Auflösung aus ScreenCaptureService
        int step = 3;
        float mergeDistance = 40f;
        int minHitsRequired = 4;

        List<Cluster> clusters = new ArrayList<>();
        float[] hsv = new float[3];

        for (int y = 0; y < height; y += step) {
            int rowOffset = y * width;
            for (int x = 0; x < width; x += step) {
                int pixel = pixels[rowOffset + x];
                TargetType type = classify(pixel, hsv);

                if (type == null) {
                    continue;
                }

                float scaledX = x * scaleX;
                float scaledY = y * scaleY;

                Cluster matchedCluster = null;
                for (Cluster cluster : clusters) {
                    if (cluster.type == type && cluster.isNear(scaledX, scaledY, mergeDistance)) {
                        matchedCluster = cluster;
                        break;
                    }
                }

                if (matchedCluster != null) {
                    matchedCluster.add(scaledX, scaledY);
                } else {
                    clusters.add(new Cluster(scaledX, scaledY, type));
                }
            }
        }

        mergeOverlappingClusters(clusters, mergeDistance * 0.5f);

        for (Cluster c : clusters) {
            if (c.hitCount < minHitsRequired) {
                continue;
            }

            float padding = 10f;
            float left = Math.max(0, c.minX - padding);
            float top = Math.max(0, c.minY - padding);
            float right = Math.min(outputWidth, c.maxX + padding);
            float bottom = Math.min(outputHeight, c.maxY + padding);

            // Große Flächen / Videos (> 20% der Bildschirmbreite oder -höhe) ignorieren
            float boxWidth = right - left;
            float boxHeight = bottom - top;
            if (boxWidth > outputWidth * 0.20f || boxHeight > outputHeight * 0.20f) {
                continue;
            }

            detections.add(new Detection(new RectF(left, top, right, bottom), c.type));
        }

        return detections;
    }

    private static void mergeOverlappingClusters(List<Cluster> clusters, float margin) {
        boolean merged;
        do {
            merged = false;
            for (int i = 0; i < clusters.size(); i++) {
                Cluster a = clusters.get(i);
                for (int j = i + 1; j < clusters.size(); j++) {
                    Cluster b = clusters.get(j);
                    if (a.type != b.type) continue;

                    boolean overlapX = a.minX - margin <= b.maxX && a.maxX + margin >= b.minX;
                    boolean overlapY = a.minY - margin <= b.maxY && a.maxY + margin >= b.minY;

                    if (overlapX && overlapY) {
                        a.minX = Math.min(a.minX, b.minX);
                        a.minY = Math.min(a.minY, b.minY);
                        a.maxX = Math.max(a.maxX, b.maxX);
                        a.maxY = Math.max(a.maxY, b.maxY);
                        a.hitCount += b.hitCount;
                        clusters.remove(j);
                        merged = true;
                        break;
                    }
                }
                if (merged) break;
            }
        } while (merged);
    }

    private static TargetType classify(int color, float[] hsv) {
        Color.colorToHSV(color, hsv);

        float hue = hsv[0];
        float saturation = hsv[1];
        float value = hsv[2];

        if (saturation < 0.65f || value < 0.45f) {
            return null;
        }

        if (hue >= 85 && hue <= 155) {
            return TargetType.PLAYER;
        }

        if (hue >= 190 && hue <= 245) {
            return TargetType.TEAMMATE;
        }

        if (hue <= 12 || hue >= 348) {
            return TargetType.ENEMY;
        }

        return null;
    }
}
