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

    // Einstellungen für das flüssige Mitlaufen
    private static final long HOLD_MS = 250;              // so lange bleibt eine verpasste Box stehen
    private static final float LATENCY_MS = 60f;          // geschätzte Verzögerung der KI (Vorausschau)
    private static final float MAX_EXTRAPOLATION_MS = 200f; // so weit wird höchstens vorausgerechnet
    private static final float MAX_SPEED = 2.0f;          // max. Geschwindigkeit in Pixel pro ms
    private static final float POS_SMOOTH_MS = 35f;       // Weichheit der Bewegung
    private static final float SIZE_SMOOTH_MS = 90f;      // Weichheit der Boxgröße

    private static class Track {
        ColorDetector.TargetType type;
        float cx, cy, w, h;         // gezeichnete Box (Mitte und Größe)
        float mcx, mcy, mw, mh;     // letzte Messung der KI
        float vx, vy;               // Geschwindigkeit in Pixel pro ms
        long measTime;              // Zeitpunkt der letzten Messung
        long lastSeen;              // Zeitpunkt, an dem die KI die Box zuletzt gesehen hat
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Track> tracks = new ArrayList<>();
    private String debugText = "Starte...";
    private long lastDrawTime = 0;

    public OverlayView(Context context) {
        super(context);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);

        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(42f);
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK);
    }

    public void updateDetections(List<ColorDetector.Detection> newDetections, String status) {
        final List<ColorDetector.Detection> copy = new ArrayList<>(newDetections);
        post(() -> {
            debugText = status;
            applyDetections(copy, SystemClock.uptimeMillis());
            invalidate();
        });
    }

    private void applyDetections(List<ColorDetector.Detection> list, long now) {
        boolean[] used = new boolean[tracks.size()];
        List<Track> created = new ArrayList<>();

        for (ColorDetector.Detection d : list) {
            RectF b = d.box;
            float dcx = b.centerX();
            float dcy = b.centerY();
            float dw = b.width();
            float dh = b.height();

            // Suche die passende bestehende Box (gleiche Art, nah an der erwarteten Position)
            int best = -1;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < tracks.size(); i++) {
                Track t = tracks.get(i);
                if (used[i] || t.type != d.type) {
                    continue;
                }
                float el = Math.min(now - t.measTime, MAX_EXTRAPOLATION_MS);
                float pcx = t.mcx + t.vx * el;
                float pcy = t.mcy + t.vy * el;
                float dist = (float) Math.hypot(dcx - pcx, dcy - pcy);
                float gate = Math.max(Math.max(t.mw, t.mh), Math.max(dw, dh)) * 0.8f;
                if (dist < gate && dist < bestDist) {
                    best = i;
                    bestDist = dist;
                }
            }

            if (best >= 0) {
                used[best] = true;
                Track t = tracks.get(best);
                float dt = now - t.measTime;
                if (dt >= 15f) {
                    float rvx = clamp((dcx - t.mcx) / dt, -MAX_SPEED, MAX_SPEED);
                    float rvy = clamp((dcy - t.mcy) / dt, -MAX_SPEED, MAX_SPEED);
                    t.vx = 0.5f * t.vx + 0.5f * rvx;
                    t.vy = 0.5f * t.vy + 0.5f * rvy;
                }
                t.mcx = dcx;
                t.mcy = dcy;
                t.mw = dw;
                t.mh = dh;
                t.measTime = now;
                t.lastSeen = now;
            } else {
                Track t = new Track();
                t.type = d.type;
                t.cx = dcx;
                t.cy = dcy;
                t.w = dw;
                t.h = dh;
                t.mcx = dcx;
                t.mcy = dcy;
                t.mw = dw;
                t.mh = dh;
                t.vx = 0f;
                t.vy = 0f;
                t.measTime = now;
                t.lastSeen = now;
                created.add(t);
            }
        }

        tracks.addAll(created);

        // Boxen, die schon zu lange nicht mehr gesehen wurden, entfernen
        tracks.removeIf(t -> now - t.lastSeen > HOLD_MS);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (debugText != null) {
            canvas.drawText(debugText, 40f, 120f, textPaint);
        }

        if (tracks.isEmpty()) {
            lastDrawTime = 0;
            return;
        }

        long now = SystemClock.uptimeMillis();
        float dt = lastDrawTime == 0 ? 16f : clamp(now - lastDrawTime, 1f, 50f);
        lastDrawTime = now;

        float posA = 1f - (float) Math.exp(-dt / POS_SMOOTH_MS);
        float sizeA = 1f - (float) Math.exp(-dt / SIZE_SMOOTH_MS);

        for (Track t : tracks) {
            // Wohin bewegt sich der Gegner gerade? (letzte Messung + Geschwindigkeit + KI-Verzögerung)
            float el = Math.min((now - t.measTime) + LATENCY_MS, MAX_EXTRAPOLATION_MS);
            float tx = t.mcx + t.vx * el;
            float ty = t.mcy + t.vy * el;

            // Gezeichnete Box gleitet weich zum Ziel
            t.cx += (tx - t.cx) * posA;
            t.cy += (ty - t.cy) * posA;
            t.w += (t.mw - t.w) * sizeA;
            t.h += (t.mh - t.h) * sizeA;

            if (t.type == ColorDetector.TargetType.PLAYER) {
                paint.setColor(COLOR_PLAYER);
            } else if (t.type == ColorDetector.TargetType.TEAMMATE) {
                paint.setColor(COLOR_TEAMMATE);
            } else {
                paint.setColor(COLOR_ENEMY);
            }
            // Verpasste Boxen werden etwas blasser
            paint.setAlpha(now - t.lastSeen > 60 ? 140 : 255);

            canvas.drawRect(
                    t.cx - t.w / 2f,
                    t.cy - t.h / 2f,
                    t.cx + t.w / 2f,
                    t.cy + t.h / 2f,
                    paint
            );
        }

        postInvalidateOnAnimation();
    }
}
