package com.example.targetdetector;

import android.graphics.PointF;
import android.graphics.RectF;

import com.example.targetdetector.GesturePhysicsProfile.TouchSample;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Erzeugt Touch-Sample-Folgen (Tippen, Wischen, radiale Pad-Steuerung) aus einem
 * {@link GesturePhysicsProfile}.
 *
 * <p>Jede Geste arbeitet auf einer validierten Kopie des Profils, Änderungen am Profil
 * wirken also erst ab der nächsten Geste.</p>
 *
 * <p><b>Nicht thread-sicher:</b> {@link Random} und der Rausch-Cursor sind veränderlicher
 * Zustand. Pro Thread eine eigene Instanz verwenden.</p>
 */
public class ErgonomicMotionSynthesizer {

    // Basiswerte der Kontakt-Hüllkurve (Finger setzt gerade auf bzw. hebt gerade ab)
    private static final double BASE_PRESSURE = 0.15;
    private static final double BASE_DRAG_PRESSURE = 0.20;
    private static final double BASE_TOUCH_MAJOR_PX = 12.0;
    private static final double BASE_TOUCH_MINOR_PX = 9.0;

    // Systematischer Versatz des Kontaktpunkts relativ zur Steuerelement-Mitte
    private static final float TARGET_BIAS_X = 0.04f;
    private static final float TARGET_BIAS_Y = 0.05f;
    private static final float TARGET_EDGE_MARGIN_PX = 2f;

    // Mittlere Fingerausrichtung (rad) und deren Streuung
    private static final double ORIENTATION_MEAN = 0.35;
    private static final double ORIENTATION_SIGMA = 0.08;
    private static final double ORIENTATION_DRIFT = 0.04;

    private static final double MIN_STEP_MS = 2.0;
    private static final double MIN_DRAG_DURATION_MS = 15.0;
    private static final double CORRECTION_TIME_FACTOR = 1.16;
    private static final double TAP_RELEASE_LIFT_PX = 1.4;
    private static final int MAX_CAPACITY_HINT = 4096;

    // Getrennte Rauschkanäle: unterschiedliche Offsets entkoppeln die einzelnen Größen
    private static final double CH_TAP_X = 0.37;
    private static final double CH_TAP_Y = 5.63;
    private static final double CH_TAP_PRESSURE = 1.37;
    private static final double CH_ORIENTATION = 2.71;
    private static final double CH_DRAG_X = 4.13;
    private static final double CH_DRAG_Y = 6.29;
    private static final double CH_DRAG_PRESSURE = 7.21;
    private static final double CH_PAD_RADIUS = 9.43;
    private static final double CH_PAD_ANGLE = 3.31;
    private static final double CH_PAD_X = 1.83;
    private static final double CH_PAD_Y = 8.57;

    private final GesturePhysicsProfile profile;
    private final Random random;
    private final PerlinNoise2D noise;
    private double noiseCursor = 0.0;

    public ErgonomicMotionSynthesizer(GesturePhysicsProfile profile) {
        this(profile, System.nanoTime());
    }

    /** Deterministische Variante mit festem Seed, z. B. für reproduzierbare Tests. */
    public ErgonomicMotionSynthesizer(GesturePhysicsProfile profile, long seed) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.random = new Random(seed);
        this.noise = new PerlinNoise2D(seed ^ 0x5DEECE66DL);
    }

    // ------------------------------------------------------------------------------------
    // Öffentliche API
    // ------------------------------------------------------------------------------------

    /** Berechnet einen ergonomisch verteilten Kontaktpunkt innerhalb eines UI-Steuerelements. */
    public PointF sampleTargetPoint(RectF bounds) {
        Objects.requireNonNull(bounds, "bounds");
        return sampleTargetPoint(bounds, snapshot());
    }

    /** Erzeugt eine Pause zwischen zwei aufeinanderfolgenden Interaktionen. */
    public long sampleInterActionDelayMs() {
        GesturePhysicsProfile p = snapshot();
        return Math.round(p.actionDelayMinMs
                + random.nextDouble() * (p.actionDelayMaxMs - p.actionDelayMinMs));
    }

    /** Synthetisiert eine Tippgeste mit Druck-/Flächen-Hüllkurve und Log-Normal-Dauer. */
    public List<TouchSample> synthesizeTap(RectF controlBounds, long startTimeMs) {
        Objects.requireNonNull(controlBounds, "controlBounds");
        GesturePhysicsProfile p = snapshot();
        return synthesizeTapAt(sampleTargetPoint(controlBounds, p), p, startTimeMs);
    }

    /**
     * Synthetisiert eine Wischgeste entlang einer kubischen Bézier-Kurve mit
     * Minimum-Jerk-Geschwindigkeitsprofil, Feder-Dämpfer-Nachführung und fBm-Drift.
     */
    public List<TouchSample> synthesizeDragGesture(PointF start, PointF target,
                                                   double nominalDurationMs, long startTimeMs) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(target, "target");
        GesturePhysicsProfile p = snapshot();

        double dx = target.x - start.x;
        double dy = target.y - start.y;
        double dist = Math.hypot(dx, dy);
        if (dist < 1.0) {
            return synthesizeTapAt(new PointF(start.x, start.y), p, startTimeMs);
        }

        double nominalMs = finiteAtLeast(nominalDurationMs, MIN_DRAG_DURATION_MS);
        double distanceScale = 1.0 + (random.nextDouble() * 2.0 - 1.0) * p.distanceDispersionRatio;
        boolean correction = dist > 45.0 && random.nextDouble() < p.correctionOvershootChance;
        double overshootRatio = p.overshootMinRatio
                + random.nextDouble() * (p.overshootMaxRatio - p.overshootMinRatio);
        double primaryScale = correction ? distanceScale + overshootRatio : distanceScale;

        PointF primaryEnd = new PointF(
                (float) (start.x + dx * primaryScale),
                (float) (start.y + dy * primaryScale));
        PointF finalGoal = new PointF(
                (float) (start.x + dx * distanceScale),
                (float) (start.y + dy * distanceScale));

        // Senkrechte zur Bewegungsrichtung, skaliert mit der Distanz (= -dy, dx)
        double arcSign = random.nextBoolean() ? 1.0 : -1.0;
        double arcRatio = p.arcCurvatureMinRatio
                + random.nextDouble() * (p.arcCurvatureMaxRatio - p.arcCurvatureMinRatio);
        double perpX = -dy * arcRatio * arcSign;
        double perpY = dx * arcRatio * arcSign;

        PointF ctrl1 = new PointF(
                (float) (start.x + (primaryEnd.x - start.x) * 0.30 + perpX),
                (float) (start.y + (primaryEnd.y - start.y) * 0.30 + perpY));
        PointF ctrl2 = new PointF(
                (float) (start.x + (primaryEnd.x - start.x) * 0.72 + perpX * 0.85),
                (float) (start.y + (primaryEnd.y - start.y) * 0.72 + perpY * 0.85));

        double motionEndMs = correction ? nominalMs * CORRECTION_TIME_FACTOR : nominalMs;
        DragPath path = new DragPath(start, ctrl1, ctrl2, primaryEnd, finalGoal,
                nominalMs, motionEndMs, correction);
        double endMs = motionEndMs + Math.max(0.0, p.settleWindowMs);
        double orientation = sampleBaseOrientation();

        double posX = start.x;
        double posY = start.y;
        double velX = 0.0;
        double velY = 0.0;
        PointF ref = new PointF();

        List<TouchSample> samples = new ArrayList<>(capacityHint(endMs, p));
        double t = 0.0;
        while (true) {
            // 1) Aktuellen Zustand zum Zeitpunkt t ausgeben (erstes Sample liegt exakt am Start)
            double envelope = Math.min(attackEnvelope(t, p), releaseEnvelope(endMs - t, p));
            float pressure = (float) clamp(BASE_DRAG_PRESSURE
                    + envelope * (p.peakPressure - BASE_DRAG_PRESSURE)
                    + channel(CH_DRAG_PRESSURE, p) * 0.05, 0.1, 1.0);

            samples.add(new TouchSample(
                    startTimeMs + Math.round(t),
                    (float) (posX + channel(CH_DRAG_X, p) * p.microDriftAmplitudePx),
                    (float) (posY + channel(CH_DRAG_Y, p) * p.microDriftAmplitudePx),
                    pressure,
                    contactSize(BASE_TOUCH_MAJOR_PX, p.peakTouchMajorPx, envelope),
                    contactSize(BASE_TOUCH_MINOR_PX, p.peakTouchMinorPx, envelope),
                    driftedOrientation(orientation, p)));

            if (t >= endMs) break;

            // 2) Einen Schritt integrieren; Referenz am Schrittende reduziert den Nachlauf
            double dtMs = nextStepMs(p, t, endMs);
            double dtSec = dtMs / 1000.0;
            path.reference(t + dtMs, ref);

            double errX = ref.x - posX;
            double errY = ref.y - posY;
            double errMag = Math.hypot(errX, errY);

            double microScale = (p.microSmoothingThresholdPx > 0.0 && errMag < p.microSmoothingThresholdPx)
                    ? p.microSmoothingFactor
                      + (1.0 - p.microSmoothingFactor) * (errMag / p.microSmoothingThresholdPx)
                    : 1.0;

            double accX = p.springStiffnessKp * errX * microScale - p.springDampingKd * velX;
            double accY = p.springStiffnessKp * errY * microScale - p.springDampingKd * velY;
            double accScale = limitScale(Math.hypot(accX, accY), p.maxAccelPxPerSec2);
            accX *= accScale;
            accY *= accScale;

            // Semi-implizites Euler-Verfahren: erst Geschwindigkeit, dann Position
            velX += accX * dtSec;
            velY += accY * dtSec;
            double velScale = limitScale(Math.hypot(velX, velY), p.maxVelocityPxPerSec);
            velX *= velScale;
            velY *= velScale;

            posX += velX * dtSec;
            posY += velY * dtSec;

            t += dtMs;
            advanceNoise(p, dtMs);
        }
        return samples;
    }

    /** Synthetisiert eine radiale Pad-Steuerung in Polarkoordinaten mit weichem Ein- und Auslaufen. */
    public List<TouchSample> synthesizeRadialPadGesture(PointF center, float maxRadius,
                                                        double startAngleRad, double endAngleRad,
                                                        double holdDurationMs, long startTimeMs) {
        Objects.requireNonNull(center, "center");
        GesturePhysicsProfile p = snapshot();

        double holdMs = finiteAtLeast(holdDurationMs, 0.0);
        double rampUpMs = Math.max(0.0, p.padRampUpMs);
        double rampDownMs = Math.max(0.0, p.padRampDownMs);
        double totalMs = rampUpMs + holdMs + rampDownMs;
        double radius = Math.max(0.0, maxRadius);
        double deltaAngle = wrapAngle(endAngleRad - startAngleRad);
        double orientation = sampleBaseOrientation();

        List<TouchSample> samples = new ArrayList<>(capacityHint(totalMs, p));
        double t = 0.0;
        while (true) {
            // Radius im Halten-Bereich; Ein-/Auslauf skalieren ihn, damit es an den
            // Phasengrenzen keine Sprünge gibt
            double holdRadiusRatio = p.padMinRadiusRatio
                    + (channel(CH_PAD_RADIUS, p) + 1.0) * 0.5 * (p.padMaxRadiusRatio - p.padMinRadiusRatio);

            double phaseFactor;
            double angleProgress;
            if (t < rampUpMs) {
                phaseFactor = smoothStep(t / rampUpMs);
                angleProgress = 0.0;
            } else if (t <= rampUpMs + holdMs) {
                phaseFactor = 1.0;
                angleProgress = holdMs > 0.0 ? smoothStep((t - rampUpMs) / holdMs) : 1.0;
            } else {
                double u = rampDownMs > 0.0 ? (t - rampUpMs - holdMs) / rampDownMs : 1.0;
                phaseFactor = 1.0 - 0.75 * smoothStep(u);
                angleProgress = 1.0;
            }

            double radiusRatio = holdRadiusRatio * phaseFactor;
            double angle = startAngleRad + deltaAngle * angleProgress
                    + channel(CH_PAD_ANGLE, p) * 0.035;
            double r = radius * radiusRatio;
            double driftAmp = p.microDriftAmplitudePx * 0.7;

            float x = (float) (center.x + Math.cos(angle) * r + channel(CH_PAD_X, p) * driftAmp);
            float y = (float) (center.y + Math.sin(angle) * r + channel(CH_PAD_Y, p) * driftAmp);
            float pressure = (float) clamp(0.25 + radiusRatio * 0.55, 0.15, 0.95);

            samples.add(new TouchSample(
                    startTimeMs + Math.round(t),
                    x, y, pressure,
                    (float) p.peakTouchMajorPx,
                    (float) p.peakTouchMinorPx,
                    driftedOrientation(orientation, p)));

            if (t >= totalMs) break;
            double dtMs = nextStepMs(p, t, totalMs);
            t += dtMs;
            advanceNoise(p, dtMs);
        }
        return samples;
    }

    // ------------------------------------------------------------------------------------
    // Interne Gestenbausteine
    // ------------------------------------------------------------------------------------

    private GesturePhysicsProfile snapshot() {
        return profile.copy().validate();
    }

    private PointF sampleTargetPoint(RectF bounds, GesturePhysicsProfile p) {
        RectF b = new RectF(bounds);
        b.sort(); // vertauschte Kanten tolerieren, Eingabe nicht verändern

        float w = b.width();
        float h = b.height();
        double centerX = b.centerX() + w * TARGET_BIAS_X;
        double centerY = b.centerY() + h * TARGET_BIAS_Y;

        float marginX = Math.min(TARGET_EDGE_MARGIN_PX, w * 0.25f);
        float marginY = Math.min(TARGET_EDGE_MARGIN_PX, h * 0.25f);

        float x = (float) clamp(centerX + random.nextGaussian() * w * p.targetGaussianSigmaRatio,
                b.left + marginX, b.right - marginX);
        float y = (float) clamp(centerY + random.nextGaussian() * h * p.targetGaussianSigmaRatio,
                b.top + marginY, b.bottom - marginY);
        return new PointF(x, y);
    }

    private List<TouchSample> synthesizeTapAt(PointF point, GesturePhysicsProfile p, long startTimeMs) {
        double holdMs = clamp(
                Math.exp(Math.log(p.tapMedianMs) + p.tapLogSigma * random.nextGaussian()),
                p.tapMinMs, p.tapMaxMs);

        double rollAngle = random.nextDouble() * Math.PI * 2.0;
        double rollDist = random.nextDouble() * p.tapMaxRollPx;
        double rollX = Math.cos(rollAngle) * rollDist;
        double rollY = Math.sin(rollAngle) * rollDist;
        double orientation = sampleBaseOrientation();

        List<TouchSample> samples = new ArrayList<>(capacityHint(holdMs, p));
        double t = 0.0;
        while (true) {
            double progress = holdMs > 0.0 ? t / holdMs : 1.0;
            double releaseLinear = releaseLinear(holdMs - t, p);

            // Ein- und Auslauf überlagern sich korrekt, auch wenn die Haltezeit kürzer
            // als Attack + Release ist
            double envelope = Math.min(attackEnvelope(t, p), smoothStep(releaseLinear))
                    * (1.0 + 0.04 * channel(CH_TAP_PRESSURE, p));
            double releaseLift = (1.0 - releaseLinear) * TAP_RELEASE_LIFT_PX;

            float pressure = (float) clamp(BASE_PRESSURE + envelope * (p.peakPressure - BASE_PRESSURE), 0.05, 1.0);
            float x = (float) (point.x + rollX * progress
                    + channel(CH_TAP_X, p) * p.microDriftAmplitudePx);
            float y = (float) (point.y + rollY * progress - releaseLift
                    + channel(CH_TAP_Y, p) * p.microDriftAmplitudePx);

            samples.add(new TouchSample(
                    startTimeMs + Math.round(t),
                    x, y, pressure,
                    contactSize(BASE_TOUCH_MAJOR_PX, p.peakTouchMajorPx, envelope),
                    contactSize(BASE_TOUCH_MINOR_PX, p.peakTouchMinorPx, envelope),
                    driftedOrientation(orientation, p)));

            if (t >= holdMs) break; // letztes Sample liegt exakt am Ende der Haltezeit
            double dtMs = nextStepMs(p, t, holdMs);
            t += dtMs;
            advanceNoise(p, dtMs);
        }
        return samples;
    }

    /** Referenzbahn einer Wischgeste: Bézier-Hauptbewegung plus optionale Korrektur. */
    private static final class DragPath {
        final PointF start, ctrl1, ctrl2, primaryEnd, finalGoal;
        final double nominalMs, motionEndMs;
        final boolean correction;

        DragPath(PointF start, PointF ctrl1, PointF ctrl2, PointF primaryEnd, PointF finalGoal,
                 double nominalMs, double motionEndMs, boolean correction) {
            this.start = start;
            this.ctrl1 = ctrl1;
            this.ctrl2 = ctrl2;
            this.primaryEnd = primaryEnd;
            this.finalGoal = finalGoal;
            this.nominalMs = nominalMs;
            this.motionEndMs = motionEndMs;
            this.correction = correction;
        }

        void reference(double t, PointF out) {
            if (!correction || t <= nominalMs) {
                double tau = clamp(t / nominalMs, 0.0, 1.0);
                evalCubicBezier(start, ctrl1, ctrl2, primaryEnd, minimumJerk(Math.pow(tau, 0.88)), out);
            } else {
                double span = Math.max(1.0, motionEndMs - nominalMs);
                double s = minimumJerk(clamp((t - nominalMs) / span, 0.0, 1.0));
                out.set((float) (primaryEnd.x + (finalGoal.x - primaryEnd.x) * s),
                        (float) (primaryEnd.y + (finalGoal.y - primaryEnd.y) * s));
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Zeitraster, Rauschen, Hüllkurven
    // ------------------------------------------------------------------------------------

    /**
     * Nächster Zeitschritt. Ein Rest kürzer als {@link #MIN_STEP_MS} wird an den Schritt
     * angehängt, damit das letzte Sample exakt auf {@code endMs} liegt, ohne doppelte
     * Zeitstempel zu erzeugen.
     */
    private double nextStepMs(GesturePhysicsProfile p, double t, double endMs) {
        double remaining = endMs - t;
        double dt = nextSampleIntervalMs(p);
        return (remaining - dt < MIN_STEP_MS) ? remaining : dt;
    }

    private double nextSampleIntervalMs(GesturePhysicsProfile p) {
        double jitter = (random.nextDouble() * 2.0 - 1.0) * p.sampleIntervalJitterMs;
        return Math.max(MIN_STEP_MS, p.sampleIntervalMs() + jitter);
    }

    /** Rausch-Cursor proportional zur verstrichenen Zeit statt pro Sample vorschieben. */
    private void advanceNoise(GesturePhysicsProfile p, double dtMs) {
        double base = p.sampleIntervalMs();
        noiseCursor += base > 0.0 ? p.microDriftFrequency * (dtMs / base) : p.microDriftFrequency;
    }

    private double channel(double offset, GesturePhysicsProfile p) {
        return fbm(noiseCursor, offset, p.microDriftOctaves);
    }

    // Fraktales Perlin-Rauschen (fBm), normiert auf [-1, 1]
    private double fbm(double x, double y, int octaves) {
        double sum = 0.0;
        double amp = 1.0;
        double freq = 1.0;
        double maxAmp = 0.0;
        for (int i = 0; i < octaves; i++) {
            sum += noise.noise2D(x * freq, y * freq) * amp;
            maxAmp += amp;
            amp *= 0.5;
            freq *= 2.0;
        }
        return maxAmp > 0.0 ? sum / maxAmp : 0.0;
    }

    private double sampleBaseOrientation() {
        return ORIENTATION_MEAN + random.nextGaussian() * ORIENTATION_SIGMA;
    }

    private float driftedOrientation(double base, GesturePhysicsProfile p) {
        return (float) normalizeOrientation(base + channel(CH_ORIENTATION, p) * ORIENTATION_DRIFT);
    }

    private static double attackEnvelope(double t, GesturePhysicsProfile p) {
        return p.tapAttackMs > 0.0 ? smoothStep(t / p.tapAttackMs) : 1.0;
    }

    private static double releaseLinear(double remainingMs, GesturePhysicsProfile p) {
        return p.tapReleaseMs > 0.0 ? clamp(remainingMs / p.tapReleaseMs, 0.0, 1.0) : 1.0;
    }

    private static double releaseEnvelope(double remainingMs, GesturePhysicsProfile p) {
        return smoothStep(releaseLinear(remainingMs, p));
    }

    private static float contactSize(double base, double peak, double envelope) {
        return (float) Math.max(0.0, base + envelope * (peak - base));
    }

    private static int capacityHint(double durationMs, GesturePhysicsProfile p) {
        double step = Math.max(MIN_STEP_MS, p.sampleIntervalMs());
        return (int) Math.min(MAX_CAPACITY_HINT, durationMs / step + 2);
    }

    // ------------------------------------------------------------------------------------
    // Mathematik
    // ------------------------------------------------------------------------------------

    private static void evalCubicBezier(PointF p0, PointF p1, PointF p2, PointF p3, double t, PointF out) {
        double u = 1.0 - t;
        double b0 = u * u * u;
        double b1 = 3.0 * u * u * t;
        double b2 = 3.0 * u * t * t;
        double b3 = t * t * t;
        out.set((float) (b0 * p0.x + b1 * p1.x + b2 * p2.x + b3 * p3.x),
                (float) (b0 * p0.y + b1 * p1.y + b2 * p2.y + b3 * p3.y));
    }

    private static double minimumJerk(double t) {
        double t3 = t * t * t;
        return t3 * (10.0 - 15.0 * t + 6.0 * t * t);
    }

    private static double smoothStep(double t) {
        t = clamp(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /** Faktor, der einen Betrag auf {@code limit} begrenzt (1.0, wenn kein Limit greift). */
    private static double limitScale(double magnitude, double limit) {
        return (limit > 0.0 && magnitude > limit) ? limit / magnitude : 1.0;
    }

    /** Winkel auf (-π, π]. */
    private static double wrapAngle(double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }

    /** Orientierung auf den von MotionEvent erwarteten Bereich [-π/2, π/2]. */
    private static double normalizeOrientation(double a) {
        double r = a % Math.PI;
        if (r > Math.PI / 2.0) r -= Math.PI;
        else if (r < -Math.PI / 2.0) r += Math.PI;
        return r;
    }

    private static double finiteAtLeast(double value, double min) {
        return (Double.isNaN(value) || Double.isInfinite(value)) ? min : Math.max(min, value);
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
