package com.example.targetdetector;

import android.graphics.RectF;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ColorDetector {

    public enum TargetType {
        PLAYER,
        TEAMMATE,
        ENEMY,
        ME
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
            ByteBuffer buffer,
            int width,
            int height,
            int pixelStride,
            int rowStride,
            int outputWidth,
            int outputHeight
    ) {
        List<Detection> detections = new ArrayList<>();
        if (buffer == null || width <= 0 || height <= 0) return detections;

        float scaleX = (float) outputWidth / width;
        float scaleY = (float) outputHeight / height;

        int step = 3;
        float mergeDistance = 40f;
        int minHitsRequired = 4;

        List<Cluster> clusters = new ArrayList<>();
        int bufferLimit = buffer.limit();

        for (int y = 0; y < height; y += step) {
            int rowOffset = y * rowStride;
            for (int x = 0; x < width; x += step) {
                int offset = rowOffset + x * pixelStride;
                if (offset + 2 >= bufferLimit) {
                    continue;
                }

                int r = buffer.get(offset) & 0xFF;
                int g = buffer.get(offset + 1) & 0xFF;
                int b = buffer.get(offset + 2) & 0xFF;

                TargetType type = classifyFast(r, g, b);
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

    // Schnelle Ganzzahl-Erkennung (entspricht S >= 65%, V >= 45%)
    private static TargetType classifyFast(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        if (max < 115) {
            return null;
        }

        int min = Math.min(r, Math.min(g, b));
        int delta = max - min;
        if (delta * 100 < max * 65) {
            return null;
        }

        // Grün (PLAYER, Hue ca. 85-155)
        if (max == g) {
            if (Math.abs(b - r) * 12 <= delta * 7) {
                return TargetType.PLAYER;
            }
            return null;
        }

        // Blau (TEAMMATE, Hue ca. 190-245)
        if (max == b) {
            if (r >= g) {
                if ((r - g) * 12 <= delta) {
                    return TargetType.TEAMMATE;
                }
            } else {
                if ((g - r) * 6 <= delta * 5) {
                    return TargetType.TEAMMATE;
                }
            }
            return null;
        }

        // Rot (ENEMY, Hue <= 12 oder >= 348)
        if (Math.abs(g - b) * 5 <= delta) {
            return TargetType.ENEMY;
        }

        return null;
    }
}
