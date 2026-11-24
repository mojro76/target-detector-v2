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

    // Farben so gewählt, dass die Ring-Suche im YoloDetector die eigenen Ecken NICHT als Ring sieht
    // (auch nicht an den Kanten, wo sich die Farbe mit Busch/Boden mischt). Auf Screenshots getestet.
    private static final int COLOR_PLAYER = Color.rgb(0, 200, 255);    // türkis-blau = ich
    private static final int COLOR_TEAMMATE = Color.rgb(155, 190, 255);
    private static final int COLOR_ENEMY = Color.rgb(255, 60, 255);    // pink-lila = Gegner
    private static final int COLOR_AIM_LINE = Color.rgb(255, 190, 40); // gelb-orange

    // Verliert die KI einen Gegner kurz (z. B. beim Treffer-Effekt), bleibt seine Box so lange stehen
    private static final long HOLD_MS = 350;
    // Ab dieser Überlappung gelten zwei Boxen derselben Art als derselbe Gegner
    private static final float DUPLICATE_IOU = 0.3f;
    // Größenänderungen unter 15 % werden ignoriert (gegen Pumpen der Box)
    private static final float SIZE_TOLERANCE = 0.15f;
    // Positionsänderungen unter 8 Pixel werden ignoriert (gegen Zittern)
    private static final float CENTER_DEADZONE_PX = 8f;
    // Länge der Ecken im Verhältnis zur Boxgröße
    private static final float CORNER_RATIO = 0.35f;
    // Durchsichtigkeit der Ecken (0 = unsichtbar, 255 = voll sichtbar)
    private static final int CORNER_ALPHA = 245;

    // Linienstart: Mitte meiner grünen Box (0.5 = genau die Mitte, größer = tiefer)
    private static final float ME_AIM_Y_FACTOR = 0.5f;
    // Linienziel: 65 % der Gegner-Boxhöhe von oben (Körper statt Name/Lebensbalken)
    private static final float AIM_Y_FACTOR = 0.65f;
    // Wird "ich" kurz nicht erkannt, bleibt die letzte Position so lange gültig
    private static final long ME_HOLD_MS = 600;
    // Ich-Box vorausschieben (Messung: angezeigte Box zeigte die Position von vor ~115 ms).
    // Nur beim Laufen (ab 70 px/s), höchstens 0,6 Boxbreiten, Tempo geglättet.
    private static final float LEAD_MS = 115f;
    private static final float VEL_SMOOTH = 0.3f;
    private static final float MIN_SPEED = 0.07f;      // px pro ms
    private static final float MAX_LEAD_RATIO = 0.6f;

    // Ziellinie: dünn und leicht durchsichtig, damit die KI sie kaum sieht
    private static final float AIM_LINE_WIDTH = 3f;
    private static final int AIM_LINE_ALPHA = 170;
    // Neuer Gegner wird erst Ziel, wenn er mindestens 20 % näher ist als das alte Ziel
    private static final float TARGET_SWITCH_RATIO = 0.8f;
    // Linienenden: unter 6 px Bewegung bleiben sie stehen, über 120 px springen sie sofort
    private static final float LINE_DEADZONE_PX = 6f;
    private static final float LINE_SNAP_PX = 120f;
    // Dazwischen leichte Glättung (1.0 = keine Glättung, kleiner = ruhiger aber träger)
    private static final float LINE_FOLLOW = 0.6f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<ColorDetector.Detection> detections = new ArrayList<>();
    private String debugText = "Starte...";
    // Wann jede Box zuletzt wirklich gesehen wurde (gleiche Reihenfolge wie detections)
    private List<Long> seenTimes = new ArrayList<>();
    // Wie oft jede Box hintereinander gesehen wurde. Nur bestätigte Boxen werden gehalten,
    // damit kurz aufblitzende Fehl-Boxen nicht 350 ms stehen bleiben.
    private List<Integer> hitCounts = new ArrayList<>();
    private static final int MIN_HITS_FOR_HOLD = 3;

    // Meine letzte bekannte Position
    private RectF meBox = null;
    private long meTime = 0;
    // Letzte echte (nicht vorausgeschobene) Position und geglättetes Tempo
    private RectF meRaw = null;
    private long meRawTime = 0;
    private float meVx = 0f;
    private float meVy = 0f;

    // Zustand der Ziellinie
    private boolean hasLine = false;
    private float lineStartX, lineStartY, lineEndX, lineEndY;
    private float targetCx, targetCy;
    private boolean hasTarget = false;

    public OverlayView(Context context) {
        super(context);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4f);
        paint.setStrokeCap(Paint.Cap.SQUARE);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(AIM_LINE_WIDTH);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(COLOR_AIM_LINE);
        linePaint.setAlpha(AIM_LINE_ALPHA);

        textPaint.setColor(Color.YELLOW);
        textPaint.setTextSize(30f);
        textPaint.setShadowLayer(3f, 1f, 1f, Color.BLACK);
    }

    public void updateDetections(List<ColorDetector.Detection> newDetections, String status) {
        final List<ColorDetector.Detection> filtered = removeDuplicates(newDetections);
        post(() -> {
            debugText = status;
            long now = SystemClock.uptimeMillis();

            // "Ich" getrennt von den Gegnern behandeln
            List<ColorDetector.Detection> others = new ArrayList<>();
            RectF freshMe = null;
            for (ColorDetector.Detection d : filtered) {
                if (isMe(d.type)) {
                    freshMe = d.box;
                } else {
                    others.add(d);
                }
            }

            // Neue Boxen sofort übernehmen, kurz verschwundene Gegner noch HOLD_MS lang halten
            detections = stabilize(others, detections, now);

            // Sicherheitsnetz: liegt "ich" auf einem Gegner, ist es keine echte Position von mir
            if (freshMe != null && !overlapsEnemy(freshMe, detections)) {
                updateMe(freshMe, now);
            }

            updateLine(now);
            invalidate();
        });
    }

    // "Ich" kann je nach YoloDetector-Version als ME oder PLAYER kommen
    private static boolean isMe(ColorDetector.TargetType type) {
        return type == ColorDetector.TargetType.ME || type == ColorDetector.TargetType.PLAYER;
    }

    private static boolean sameKind(ColorDetector.TargetType a, ColorDetector.TargetType b) {
        return a == b || (isMe(a) && isMe(b));
    }

    private boolean meValid(long now) {
        return meBox != null && now - meTime <= ME_HOLD_MS;
    }

    private static boolean overlapsEnemy(RectF me, List<ColorDetector.Detection> list) {
        for (ColorDetector.Detection d : list) {
            if (d.type != ColorDetector.TargetType.ENEMY) {
                continue;
            }
            if (iou(me, d.box) > 0.25f
                    || d.box.contains(me.centerX(), me.centerY())
                    || me.contains(d.box.centerX(), d.box.centerY())) {
                return true;
            }
        }
        return false;
    }

    private void updateMe(RectF fresh, long now) {
        float dt = now - meRawTime;
        if (meRaw != null && dt > 0f && dt < 200f
                && Math.hypot(fresh.centerX() - meRaw.centerX(), fresh.centerY() - meRaw.centerY()) <= fresh.width()) {
            meVx = VEL_SMOOTH * (fresh.centerX() - meRaw.centerX()) / dt + (1f - VEL_SMOOTH) * meVx;
            meVy = VEL_SMOOTH * (fresh.centerY() - meRaw.centerY()) / dt + (1f - VEL_SMOOTH) * meVy;
        } else {
            meVx = 0f;
            meVy = 0f;
        }
        meRaw = fresh;
        meRawTime = now;
        if (Math.hypot(meVx, meVy) >= MIN_SPEED) {
            float ox = meVx * LEAD_MS;
            float oy = meVy * LEAD_MS;
            float m = (float) Math.hypot(ox, oy);
            float lim = MAX_LEAD_RATIO * fresh.width();
            if (m > lim) {
                ox *= lim / m;
                oy *= lim / m;
            }
            fresh = new RectF(fresh);
            fresh.offset(ox, oy);
        }

        if (meBox == null) {
            meBox = new RectF(fresh);
        } else {
            // Gleiche Ruhe-Regeln wie bei den Gegnern: Mini-Zittern und Mini-Größenänderung ignorieren
            float cx = fresh.centerX();
            float cy = fresh.centerY();
            if (Math.hypot(cx - meBox.centerX(), cy - meBox.centerY()) < CENTER_DEADZONE_PX) {
                cx = meBox.centerX();
                cy = meBox.centerY();
            }
            float w = fresh.width();
            float h = fresh.height();
            if (meBox.width() > 0f && Math.abs(w - meBox.width()) / meBox.width() < SIZE_TOLERANCE) {
                w = meBox.width();
            }
            if (meBox.height() > 0f && Math.abs(h - meBox.height()) / meBox.height() < SIZE_TOLERANCE) {
                h = meBox.height();
            }
            meBox = new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        }
        meTime = now;
    }

    // Wählt das Ziel (mit Wechsel-Puffer) und bewegt die Linienenden ruhig nach
    private void updateLine(long now) {
        if (!meValid(now)) {
            hasLine = false;
            hasTarget = false;
            return;
        }

        float sx = meBox.centerX();
        float sy = meBox.top + meBox.height() * ME_AIM_Y_FACTOR;

        RectF nearest = null;
        float nearestDist = Float.MAX_VALUE;
        RectF current = null;
        float currentDist = Float.MAX_VALUE;
        float currentMove = Float.MAX_VALUE;

        for (ColorDetector.Detection d : detections) {
            if (d.type != ColorDetector.TargetType.ENEMY) {
                continue;
            }
            float dist = (float) Math.hypot(d.box.centerX() - sx, aimY(d.box) - sy);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = d.box;
            }
            // Ist das hier noch derselbe Gegner wie beim letzten Mal?
            if (hasTarget) {
                float move = (float) Math.hypot(d.box.centerX() - targetCx, d.box.centerY() - targetCy);
                float gate = Math.max(d.box.width(), d.box.height()) * 0.8f;
                if (move < gate && move < currentMove) {
                    currentMove = move;
                    current = d.box;
                    currentDist = dist;
                }
            }
        }

        if (nearest == null) {
            hasLine = false;
            hasTarget = false;
            return;
        }

        // Beim alten Ziel bleiben, solange der neue Gegner nicht deutlich näher ist
        RectF target = nearest;
        if (current != null && current != nearest && nearestDist > currentDist * TARGET_SWITCH_RATIO) {
            target = current;
        }
        boolean targetChanged = !hasTarget || target != current;
        targetCx = target.centerX();
        targetCy = target.centerY();
        hasTarget = true;

        float ex = target.centerX();
        float ey = aimY(target);

        if (!hasLine) {
            lineStartX = sx;
            lineStartY = sy;
            lineEndX = ex;
            lineEndY = ey;
            hasLine = true;
            return;
        }

        float[] s = smooth(lineStartX, lineStartY, sx, sy);
        lineStartX = s[0];
        lineStartY = s[1];

        if (targetChanged) {
            // Neues Ziel: Linienende springt sofort, statt langsam rüberzugleiten
            lineEndX = ex;
            lineEndY = ey;
        } else {
            float[] e = smooth(lineEndX, lineEndY, ex, ey);
            lineEndX = e[0];
            lineEndY = e[1];
        }
    }

    private static float[] smooth(float oldX, float oldY, float newX, float newY) {
        float d = (float) Math.hypot(newX - oldX, newY - oldY);
        if (d < LINE_DEADZONE_PX) {
            return new float[]{oldX, oldY};
        }
        if (d > LINE_SNAP_PX) {
            return new float[]{newX, newY};
        }
        return new float[]{
                oldX + (newX - oldX) * LINE_FOLLOW,
                oldY + (newY - oldY) * LINE_FOLLOW
        };
    }

    // Vergleicht jede neue Box mit der passenden Box vom letzten Mal.
    // Kleine Änderungen bei Größe und Position werden ignoriert, große sofort übernommen.
    // Alte Boxen ohne neuen Treffer bleiben bis zu HOLD_MS stehen (gegen kurzes Verschwinden).
    private List<ColorDetector.Detection> stabilize(
            List<ColorDetector.Detection> fresh,
            List<ColorDetector.Detection> previous,
            long now
    ) {
        List<ColorDetector.Detection> result = new ArrayList<>();
        List<Long> resultSeen = new ArrayList<>();
        List<Integer> resultHits = new ArrayList<>();
        boolean[] used = new boolean[previous.size()];

        for (ColorDetector.Detection d : fresh) {
            float dcx = d.box.centerX();
            float dcy = d.box.centerY();
            float dw = d.box.width();
            float dh = d.box.height();

            int best = -1;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < previous.size(); i++) {
                ColorDetector.Detection p = previous.get(i);
                if (used[i] || !sameKind(p.type, d.type)) {
                    continue;
                }
                float dist = (float) Math.hypot(dcx - p.box.centerX(), dcy - p.box.centerY());
                float gate = Math.max(p.box.width(), p.box.height()) * 0.6f;
                if (dist < gate && dist < bestDist) {
                    best = i;
                    bestDist = dist;
                }
            }

            if (best < 0) {
                result.add(d);
                resultSeen.add(now);
                resultHits.add(1);
                continue;
            }
            used[best] = true;
            RectF p = previous.get(best).box;

            float cx = dcx;
            float cy = dcy;
            if (bestDist < CENTER_DEADZONE_PX) {
                cx = p.centerX();
                cy = p.centerY();
            }

            float w = dw;
            float h = dh;
            if (p.width() > 0f && Math.abs(dw - p.width()) / p.width() < SIZE_TOLERANCE) {
                w = p.width();
            }
            if (p.height() > 0f && Math.abs(dh - p.height()) / p.height() < SIZE_TOLERANCE) {
                h = p.height();
            }

            RectF box = new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            result.add(new ColorDetector.Detection(box, d.type));
            resultSeen.add(now);
            resultHits.add((best < hitCounts.size() ? hitCounts.get(best) : 0) + 1);
        }

        // Nicht wiedergefundene Boxen noch kurz behalten, wenn sie nicht auf einer neuen Box liegen
        for (int i = 0; i < previous.size(); i++) {
            if (used[i] || i >= seenTimes.size()) {
                continue;
            }
            long seen = seenTimes.get(i);
            int hits = i < hitCounts.size() ? hitCounts.get(i) : 0;
            // Nur bestätigte Boxen halten, und nur kurz
            if (now - seen > HOLD_MS || hits < MIN_HITS_FOR_HOLD) {
                continue;
            }
            ColorDetector.Detection p = previous.get(i);
            // Steht eine neue Box derselben Art in der Nähe, ist das derselbe Gegner,
            // der sich nur schnell bewegt hat -> alte Box nicht als "Geist" stehen lassen
            boolean covered = false;
            float near = Math.max(p.box.width(), p.box.height()) * 1.5f;
            for (ColorDetector.Detection r : result) {
                if (!sameKind(p.type, r.type)) {
                    continue;
                }
                if (iou(p.box, r.box) > DUPLICATE_IOU
                        || Math.hypot(p.box.centerX() - r.box.centerX(),
                        p.box.centerY() - r.box.centerY()) < near) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                result.add(p);
                resultSeen.add(seen);
                resultHits.add(hits);
            }
        }

        seenTimes = resultSeen;
        hitCounts = resultHits;
        return result;
    }

    // Die KI liefert die sicherste Box zuerst. Jede weitere Box derselben Art, die stark
    // mit einer schon behaltenen überlappt oder deren Mitte darin liegt, wird verworfen.
    private List<ColorDetector.Detection> removeDuplicates(List<ColorDetector.Detection> list) {
        List<ColorDetector.Detection> result = new ArrayList<>();
        for (ColorDetector.Detection d : list) {
            boolean duplicate = false;
            for (ColorDetector.Detection k : result) {
                if (!sameKind(k.type, d.type)) {
                    continue;
                }
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

    // Zeichnet nur die vier Ecken der Box, damit die KI möglichst wenig vom Overlay sieht
    private void drawCorners(Canvas canvas, RectF b) {
        float len = Math.min(b.width(), b.height()) * CORNER_RATIO;

        canvas.drawLine(b.left, b.top, b.left + len, b.top, paint);
        canvas.drawLine(b.left, b.top, b.left, b.top + len, paint);
        canvas.drawLine(b.right, b.top, b.right - len, b.top, paint);
        canvas.drawLine(b.right, b.top, b.right, b.top + len, paint);
        canvas.drawLine(b.left, b.bottom, b.left + len, b.bottom, paint);
        canvas.drawLine(b.left, b.bottom, b.left, b.bottom - len, paint);
        canvas.drawLine(b.right, b.bottom, b.right - len, b.bottom, paint);
        canvas.drawLine(b.right, b.bottom, b.right, b.bottom - len, paint);
    }

    private static float aimY(RectF b) {
        return b.top + b.height() * AIM_Y_FACTOR;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (debugText != null) {
            canvas.drawText(debugText, 40f, 100f, textPaint);
        }

        // Gegner (pink-lila) und Teamkameraden (blau)
        for (ColorDetector.Detection detection : detections) {
            if (detection.type == ColorDetector.TargetType.TEAMMATE) {
                paint.setColor(COLOR_TEAMMATE);
            } else {
                paint.setColor(COLOR_ENEMY);
            }
            paint.setAlpha(CORNER_ALPHA);
            drawCorners(canvas, detection.box);
        }

        long now = SystemClock.uptimeMillis();

        // Türkis-blaue Box um mich
        if (meValid(now)) {
            paint.setColor(COLOR_PLAYER);
            paint.setAlpha(CORNER_ALPHA);
            drawCorners(canvas, meBox);
        }

        // Ziellinie von mir zum gewählten Gegner
        if (hasLine && meValid(now)) {
            canvas.drawLine(lineStartX, lineStartY, lineEndX, lineEndY, linePaint);
        }
    }
}
