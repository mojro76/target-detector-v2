package com.example.targetdetector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
- Einstellungen für die Erzeugung von Touch-Gesten.
 *
- <p>Die Felder sind öffentlich und veränderbar (kompatibel zu bestehendem Code).
- Empfohlene Nutzung:
- <pre>{@code
- GesturePhysicsProfile snapshot = profile.copy().validate();
- // ... Geste ausschließlich mit "snapshot" berechnen
- }</pre>
- So kann eine Änderung am Profil (auch aus einem anderen Thread) keine
- Geste mittendrin mit gemischten alten und neuen Werten beeinflussen.
 *
- <p>Hinweis zu Android-Injektions-Ebenen: Kontakt-Metadaten ({@link #peakPressure},
- {@link #peakTouchMajorPx}, {@link #peakTouchMinorPx}) und exakte Sample-Deltas
- ({@link #sampleIntervalJitterMs}) wirken nur bei direkter {@code MotionEvent}- oder
- {@code uinput}-Injektion. Bei {@code AccessibilityService.dispatchGesture()} interpoliert
- das Android-Framework den {@code Path} selbst.
 */
public class GesturePhysicsProfile {

    /**
- Konservative Stabilitätsgrenze für die Feder-Simulation: Eigenfrequenz × Zeitschritt.
- Bei explizitem/semi-implizitem Euler wird es oberhalb von ~2 instabil; 1.0 lässt Reserve.
     */
    public static final double MAX_SPRING_OMEGA_DT = 1.0;

    // Abtastrate & Zeit-Varianz
    public double sampleRateHz = 120.0;              // ~8.33 ms Basis-Intervall
    public double sampleIntervalJitterMs = 0.8;      // Dezente Takt-Schwankung pro Sample (< halbes Intervall)
    public double actionDelayMinMs = 18.0;           // Minimale Verzögerung zwischen Gesten
    public double actionDelayMaxMs = 50.0;           // Maximale Verzögerung zwischen Gesten

    // Trägheitsmodell (Second-Order Spring-Damper)
    public double springStiffnessKp = 400.0;         // Federkonstante k_p
    public double springDampingKd = 38.0;            // Dämpfung k_d (ζ = k_d / (2·√k_p) ≈ 0,95)

    // Feinbereich-Glättung & ruhiger Subpixel-Drift (fBm / Perlin)
    public double microSmoothingThresholdPx = 5.0;   // Schwellenwert für Feinbereich-Dämpfung
    public double microSmoothingFactor = 0.60;       // Dämpfungsfaktor bei kleinen Distanzen (0..1)
    public double microDriftAmplitudePx = 0.35;      // Subpixel-Drift (unter 0,5 px -> kein sichtbares Zittern)
    public double microDriftFrequency = 0.12;        // Langsame Drift-Änderung im Noise-Raum
    public int microDriftOctaves = 2;                // Oktavenzahl für fraktales Rauschen (1..6)

    // Kinematische Grenzwerte (Slew-Rate & Ausschwingen)
    public double maxVelocityPxPerSec = 4200.0;      // Maximale Wischgeschwindigkeit
    public double maxAccelPxPerSec2 = 38000.0;       // Maximale Beschleunigung
    public double settleWindowMs = 28.0;             // Ausschwingzeit der Feder am Zielpunkt

    // Radiale Pad-Steuerung (Virtuelles Steuerkreuz / Analog-Pad)
    public double padRampUpMs = 60.0;                // Sanftes Auslenken aus der Mitte
    public double padRampDownMs = 42.0;              // Rückführungsrampe vor dem Loslassen
    public double padMinRadiusRatio = 0.90;          // Untere Radius-Variation auf Kreisbahnen
    public double padMaxRadiusRatio = 1.02;          // Obere Radius-Variation auf Kreisbahnen

    // Kontaktphysik-Hüllkurve für Tippgesten (Taps)
    public double tapMinMs = 42.0;                   // Untere Kappungsgrenze der Haltedauer (≥ 2 Frames @ 60 Hz)
    public double tapMedianMs = 62.0;                // Median der Log-Normal-Haltedauer
    public double tapMaxMs = 145.0;                  // Obere Kappungsgrenze der Haltedauer
    public double tapLogSigma = 0.22;                // Streuung der Haltedauer (0 = immer gleich lang)
    public double tapAttackMs = 20.0;                // Aufsetz-Phase der Fingerkuppe
    public double tapReleaseMs = 15.0;               // Abhebe-Phase der Fingerkuppe
    public double tapMaxRollPx = 1.8;                // Maximales Abrollen während des Haltens
    public double peakPressure = 0.82;               // Spitzendruck (normiert, 0..1)
    public double peakTouchMajorPx = 38.0;           // Lange Achse der Kontaktfläche
    public double peakTouchMinorPx = 29.0;           // Kurze Achse der Kontaktfläche (≤ Major)

    // Ergonomische Kurven- & Trefferstreuung
    public double arcCurvatureMinRatio = 0.03;       // Minimale Bogenkrümmung (kubische Bézier-Kurve)
    public double arcCurvatureMaxRatio = 0.09;       // Maximale Bogenkrümmung
    public double distanceDispersionRatio = 0.03;    // Natürliche Distanz-Streuung (+/- 3 %)
    public double correctionOvershootChance = 0.22;  // Wahrscheinlichkeit einer kleinen Zielkorrektur (0..1)
    public double overshootMinRatio = 0.03;          // Minimale Overshoot-Distanz relativ zur Gesamtlänge
    public double overshootMaxRatio = 0.07;          // Maximale Overshoot-Distanz relativ zur Gesamtlänge
    public double targetGaussianSigmaRatio = 0.16;   // 2D-Gauß-Streuung innerhalb von UI-Elementen

    /** Profil mit Standardwerten. */
    public GesturePhysicsProfile() {
    }

    /** Kopier-Konstruktor. */
    public GesturePhysicsProfile(GesturePhysicsProfile other) {
        this.sampleRateHz = other.sampleRateHz;
        this.sampleIntervalJitterMs = other.sampleIntervalJitterMs;
        this.actionDelayMinMs = other.actionDelayMinMs;
        this.actionDelayMaxMs = other.actionDelayMaxMs;

        this.springStiffnessKp = other.springStiffnessKp;
        this.springDampingKd = other.springDampingKd;

        this.microSmoothingThresholdPx = other.microSmoothingThresholdPx;
        this.microSmoothingFactor = other.microSmoothingFactor;
        this.microDriftAmplitudePx = other.microDriftAmplitudePx;
        this.microDriftFrequency = other.microDriftFrequency;
        this.microDriftOctaves = other.microDriftOctaves;

        this.maxVelocityPxPerSec = other.maxVelocityPxPerSec;
        this.maxAccelPxPerSec2 = other.maxAccelPxPerSec2;
        this.settleWindowMs = other.settleWindowMs;

        this.padRampUpMs = other.padRampUpMs;
        this.padRampDownMs = other.padRampDownMs;
        this.padMinRadiusRatio = other.padMinRadiusRatio;
        this.padMaxRadiusRatio = other.padMaxRadiusRatio;

        this.tapMinMs = other.tapMinMs;
        this.tapMedianMs = other.tapMedianMs;
        this.tapMaxMs = other.tapMaxMs;
        this.tapLogSigma = other.tapLogSigma;
        this.tapAttackMs = other.tapAttackMs;
        this.tapReleaseMs = other.tapReleaseMs;
        this.tapMaxRollPx = other.tapMaxRollPx;
        this.peakPressure = other.peakPressure;
        this.peakTouchMajorPx = other.peakTouchMajorPx;
        this.peakTouchMinorPx = other.peakTouchMinorPx;

        this.arcCurvatureMinRatio = other.arcCurvatureMinRatio;
        this.arcCurvatureMaxRatio = other.arcCurvatureMaxRatio;
        this.distanceDispersionRatio = other.distanceDispersionRatio;
        this.correctionOvershootChance = other.correctionOvershootChance;
        this.overshootMinRatio = other.overshootMinRatio;
        this.overshootMaxRatio = other.overshootMaxRatio;
        this.targetGaussianSigmaRatio = other.targetGaussianSigmaRatio;
    }

    /** Unabhängige Kopie, z. B. als fester Schnappschuss für eine Geste. */
    public GesturePhysicsProfile copy() {
        return new GesturePhysicsProfile(this);
    }

    /** Basis-Abtastintervall in Millisekunden (aus {@link #sampleRateHz}). */
    public double sampleIntervalMs() {
        return 1000.0 / sampleRateHz;
    }

    /** Dämpfungsgrad ζ der Feder (1 = kritisch gedämpft, &lt; 1 = schwingt über, &gt; 1 = träge). */
    public double springDampingRatio() {
        return springDampingKd / (2.0 * Math.sqrt(springStiffnessKp));
    }

    /**
- Prüft alle Werte und meldet sämtliche Verstöße auf einmal.
     *
- @return dieses Profil (für Verkettung)
- @throws IllegalArgumentException wenn mindestens ein Wert ungültig ist
     */
    public GesturePhysicsProfile validate() {
        List<String> errors = new ArrayList<>();

        // Abtastrate & Zeit
        positive(errors, "sampleRateHz", sampleRateHz);
        nonNegative(errors, "sampleIntervalJitterMs", sampleIntervalJitterMs);
        if (isFinite(sampleRateHz) && sampleRateHz > 0 && isFinite(sampleIntervalJitterMs)
                && sampleIntervalJitterMs >= sampleIntervalMs() / 2.0) {
            errors.add(String.format(Locale.ROOT,
                    "sampleIntervalJitterMs (%.3f) muss kleiner als das halbe Intervall (%.3f ms) sein, "
                            + "sonst können Zeitstempel rückwärts laufen",
                    sampleIntervalJitterMs, sampleIntervalMs() / 2.0));
        }
        nonNegative(errors, "actionDelayMinMs", actionDelayMinMs);
        nonNegative(errors, "actionDelayMaxMs", actionDelayMaxMs);
        minMax(errors, "actionDelayMinMs", actionDelayMinMs, "actionDelayMaxMs", actionDelayMaxMs);

        // Feder-Dämpfer
        positive(errors, "springStiffnessKp", springStiffnessKp);
        nonNegative(errors, "springDampingKd", springDampingKd);
        if (isFinite(springStiffnessKp) && springStiffnessKp > 0 && isFinite(sampleRateHz) && sampleRateHz > 0) {
            double omegaDt = Math.sqrt(springStiffnessKp) * (sampleIntervalMs() / 1000.0);
            if (omegaDt > MAX_SPRING_OMEGA_DT) {
                errors.add(String.format(Locale.ROOT,
                        "springStiffnessKp (%.1f) ist für %.1f Hz zu hoch: ω·dt = %.3f > %.1f "
                                + "(Simulation kann instabil werden)",
                        springStiffnessKp, sampleRateHz, omegaDt, MAX_SPRING_OMEGA_DT));
            }
        }

        // Feinbereich & fBm-Oktaven
        nonNegative(errors, "microSmoothingThresholdPx", microSmoothingThresholdPx);
        inRange(errors, "microSmoothingFactor", microSmoothingFactor, 0.0, 1.0);
        nonNegative(errors, "microDriftAmplitudePx", microDriftAmplitudePx);
        nonNegative(errors, "microDriftFrequency", microDriftFrequency);
        if (microDriftOctaves < 1 || microDriftOctaves > 6) {
            errors.add("microDriftOctaves (" + microDriftOctaves + ") muss im Bereich [1, 6] liegen");
        }

        // Kinematik
        positive(errors, "maxVelocityPxPerSec", maxVelocityPxPerSec);
        positive(errors, "maxAccelPxPerSec2", maxAccelPxPerSec2);
        nonNegative(errors, "settleWindowMs", settleWindowMs);

        // Pad
        nonNegative(errors, "padRampUpMs", padRampUpMs);
        nonNegative(errors, "padRampDownMs", padRampDownMs);
        positive(errors, "padMinRadiusRatio", padMinRadiusRatio);
        positive(errors, "padMaxRadiusRatio", padMaxRadiusRatio);
        minMax(errors, "padMinRadiusRatio", padMinRadiusRatio, "padMaxRadiusRatio", padMaxRadiusRatio);

        // Taps
        positive(errors, "tapMinMs", tapMinMs);
        positive(errors, "tapMedianMs", tapMedianMs);
        positive(errors, "tapMaxMs", tapMaxMs);
        minMax(errors, "tapMinMs", tapMinMs, "tapMedianMs", tapMedianMs);
        minMax(errors, "tapMedianMs", tapMedianMs, "tapMaxMs", tapMaxMs);
        nonNegative(errors, "tapLogSigma", tapLogSigma);
        nonNegative(errors, "tapAttackMs", tapAttackMs);
        nonNegative(errors, "tapReleaseMs", tapReleaseMs);
        if (isFinite(tapAttackMs) && isFinite(tapReleaseMs) && isFinite(tapMinMs)
                && tapAttackMs + tapReleaseMs > tapMinMs) {
            errors.add(String.format(Locale.ROOT,
                    "tapAttackMs + tapReleaseMs (%.1f) darf nicht länger als tapMinMs (%.1f) sein",
                    tapAttackMs + tapReleaseMs, tapMinMs));
        }
        nonNegative(errors, "tapMaxRollPx", tapMaxRollPx);
        if (!isFinite(peakPressure) || peakPressure <= 0.0 || peakPressure > 1.0) {
            errors.add(format("peakPressure", peakPressure, "muss im Bereich (0, 1] liegen"));
        }
        positive(errors, "peakTouchMajorPx", peakTouchMajorPx);
        positive(errors, "peakTouchMinorPx", peakTouchMinorPx);
        minMax(errors, "peakTouchMinorPx", peakTouchMinorPx, "peakTouchMajorPx", peakTouchMajorPx);

        // Kurven & Streuung
        nonNegative(errors, "arcCurvatureMinRatio", arcCurvatureMinRatio);
        nonNegative(errors, "arcCurvatureMaxRatio", arcCurvatureMaxRatio);
        minMax(errors, "arcCurvatureMinRatio", arcCurvatureMinRatio, "arcCurvatureMaxRatio", arcCurvatureMaxRatio);
        if (!isFinite(distanceDispersionRatio) || distanceDispersionRatio < 0.0 || distanceDispersionRatio >= 1.0) {
            errors.add(format("distanceDispersionRatio", distanceDispersionRatio, "muss im Bereich [0, 1) liegen"));
        }
        inRange(errors, "correctionOvershootChance", correctionOvershootChance, 0.0, 1.0);
        nonNegative(errors, "overshootMinRatio", overshootMinRatio);
        nonNegative(errors, "overshootMaxRatio", overshootMaxRatio);
        minMax(errors, "overshootMinRatio", overshootMinRatio, "overshootMaxRatio", overshootMaxRatio);
        nonNegative(errors, "targetGaussianSigmaRatio", targetGaussianSigmaRatio);

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "Ungültiges GesturePhysicsProfile (" + errors.size() + " Fehler):\n  - "
                            + String.join("\n  - ", errors));
        }
        return this;
    }

    // ---- Prüf-Helfer ---------------------------------------------------------

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static String format(String name, double value, String rule) {
        return String.format(Locale.ROOT, "%s (%s) %s", name, value, rule);
    }

    private static void positive(List<String> errors, String name, double value) {
        if (!isFinite(value) || value <= 0.0) {
            errors.add(format(name, value, "muss eine endliche Zahl > 0 sein"));
        }
    }

    private static void nonNegative(List<String> errors, String name, double value) {
        if (!isFinite(value) || value < 0.0) {
            errors.add(format(name, value, "muss eine endliche Zahl >= 0 sein"));
        }
    }

    private static void inRange(List<String> errors, String name, double value, double min, double max) {
        if (!isFinite(value) || value < min || value > max) {
            errors.add(format(name, value, "muss im Bereich [" + min + ", " + max + "] liegen"));
        }
    }

    private static void minMax(List<String> errors, String minName, double min, String maxName, double max) {
        if (isFinite(min) && isFinite(max) && min > max) {
            errors.add(String.format(Locale.ROOT, "%s (%s) darf nicht größer als %s (%s) sein",
                    minName, min, maxName, max));
        }
    }

    // ---- Datenobjekt ---------------------------------------------------------

    /** Ein einzelner, unveränderlicher Touch-Messpunkt. */
    public static final class TouchSample {
        public final long timeMs;
        public final float x;
        public final float y;
        public final float pressure;
        public final float touchMajor;
        public final float touchMinor;
        public final float orientation;

        public TouchSample(long timeMs, float x, float y, float pressure,
                           float touchMajor, float touchMinor, float orientation) {
            this.timeMs = timeMs;
            this.x = x;
            this.y = y;
            this.pressure = pressure;
            this.touchMajor = touchMajor;
            this.touchMinor = touchMinor;
            this.orientation = orientation;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "TouchSample{t=%d ms, x=%.2f, y=%.2f, p=%.3f, major=%.1f, minor=%.1f, orient=%.3f}",
                    timeMs, x, y, pressure, touchMajor, touchMinor, orientation);
        }
    }
}
