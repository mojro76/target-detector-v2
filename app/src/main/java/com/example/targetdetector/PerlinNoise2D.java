package com.example.targetdetector;

/**
 * 2D-Perlin-Rauschen nach Ken Perlins "Improved Noise" (2002).
 *
 * <p>Eigenschaften:
 * <ul>
 *   <li>Gleicher Seed ergibt immer dasselbe Rauschen (deterministisch).</li>
 *   <li>Instanzen sind unveränderlich und damit threadsicher.</li>
 *   <li>{@link #noise2D(double, double)} liefert Werte im Bereich [-1, 1].</li>
 *   <li>An ganzzahligen Gitterpunkten ist der Wert immer 0.</li>
 *   <li>Keine kurze Periode: Die Gradienten werden per Hash aus den Gitterkoordinaten
 *       bestimmt statt über eine 256er-Permutationstabelle. Das Muster wiederholt sich
 *       daher auch bei stetig wachsenden Koordinaten praktisch nicht.</li>
 *   <li>NaN als Eingabe ergibt NaN.</li>
 * </ul>
 */
public final class PerlinNoise2D {

    /**
     * Theoretisches Maximum von 2D-Perlin-Rauschen mit Einheitsgradienten ist sqrt(0.5);
     * dieser Faktor skaliert es auf exakt 1.
     */
    private static final double SCALE = Math.sqrt(2.0);

    /** 8 normierte Gradienten im 45°-Abstand für richtungsunabhängiges Rauschen. */
    private static final double D = Math.sqrt(0.5);
    private static final double[] GRAD_X = { D, -D,  D, -D, 1, -1, 0,  0 };
    private static final double[] GRAD_Y = { D,  D, -D, -D, 0,  0, 1, -1 };

    // Multiplikatoren für die Koordinaten-Durchmischung (ungerade 64-Bit-Konstanten)
    private static final long K_X = 0x9E3779B97F4A7C15L;
    private static final long K_Y = 0xC2B2AE3D27D4EB4FL;

    private final long seedHash;

    public PerlinNoise2D(long seed) {
        this.seedHash = mix64(seed);
    }

    /** Perlin-Rauschen an der Stelle (x, y) im Bereich [-1, 1]. */
    public double noise2D(double x, double y) {
        double floorX = Math.floor(x);
        double floorY = Math.floor(y);

        long xi = (long) floorX;
        long yi = (long) floorY;

        double xf = x - floorX;
        double yf = y - floorY;

        double u = fade(xf);
        double v = fade(yf);

        double n00 = grad(hash(xi,     yi    ), xf,       yf);
        double n10 = grad(hash(xi + 1, yi    ), xf - 1.0, yf);
        double n01 = grad(hash(xi,     yi + 1), xf,       yf - 1.0);
        double n11 = grad(hash(xi + 1, yi + 1), xf - 1.0, yf - 1.0);

        double value = lerp(v, lerp(u, n00, n10), lerp(u, n01, n11)) * SCALE;
        // Rundungsfehler können die Grenze minimal überschreiten
        return clamp(value, -1.0, 1.0);
    }

    /** Verschobenes Perlin-Rauschen im Bereich [0, 1]. */
    public double noise01(double x, double y) {
        return (noise2D(x, y) + 1.0) * 0.5;
    }

    /**
     * Fraktales Rauschen (fBm) im Bereich [-1, 1].
     *
     * @param octaves     Anzahl der Oktaven, mindestens 1
     * @param lacunarity  Frequenzfaktor je Oktave, &gt; 0 (üblich: 2.0)
     * @param persistence Amplitudenfaktor je Oktave, &gt; 0 (üblich: 0.5)
     */
    public double fractal(double x, double y, int octaves, double lacunarity, double persistence) {
        if (octaves < 1) {
            throw new IllegalArgumentException("octaves muss >= 1 sein, war: " + octaves);
        }
        if (!(lacunarity > 0.0) || Double.isInfinite(lacunarity)) {
            throw new IllegalArgumentException("lacunarity muss endlich und > 0 sein, war: " + lacunarity);
        }
        if (!(persistence > 0.0) || Double.isInfinite(persistence)) {
            throw new IllegalArgumentException("persistence muss endlich und > 0 sein, war: " + persistence);
        }

        double sum = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double maxAmplitude = 0.0;

        for (int i = 0; i < octaves; i++) {
            // Versatz je Oktave, damit nicht alle Oktaven am Ursprung gemeinsam 0 sind
            double offset = i * 19.19;
            sum += noise2D(x * frequency + offset, y * frequency + offset) * amplitude;
            maxAmplitude += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }
        return sum / maxAmplitude;
    }

    /** Fraktales Rauschen mit Standardwerten (lacunarity 2.0, persistence 0.5). */
    public double fractal(double x, double y, int octaves) {
        return fractal(x, y, octaves, 2.0, 0.5);
    }

    // ------------------------------------------------------------------------------------

    /** Gradientenindex 0..7 für einen Gitterpunkt, abhängig vom Seed. */
    private int hash(long xi, long yi) {
        long h = mix64(seedHash + xi * K_X);
        h = mix64(h + yi * K_Y);
        return (int) (h >>> 61); // oberste 3 Bits: am besten durchmischt
    }

    /** 64-Bit-Finalizer aus MurmurHash3: kleine Eingabeänderungen ändern alle Ausgabebits. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return z ^ (z >>> 33);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int index, double x, double y) {
        return GRAD_X[index] * x + GRAD_Y[index] * y;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }
}
