package com.example.targetdetector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class YoloDetector {

    private static final String TAG = "YoloDetector";
    private static final String MODEL_FILE = "best.tflite";
    private static final int TARGET_CLASS_ID = 1; // 1 = Enemy
    private static final float CONFIDENCE_THRESHOLD = 0.25f; // neue Enemy-Boxen brauchen mindestens diesen Wert
    private static final float LOW_THRESHOLD = 0.12f;        // schon bekannte Enemy-Boxen dürfen bis hierher fallen

    // ===== "Ich" über den grünen Ring unter meiner Figur finden =====
    // Suchbereich (Anteil vom Bild): oben ist HUD, unten rechts sind die grünen Knöpfe
    private static final float RING_X_MIN = 0.08f;
    private static final float RING_X_MAX = 0.92f;
    private static final float RING_Y_MIN = 0.18f;
    private static final float RING_Y_MAX = 0.97f;
    private static final float UI_X = 0.65f;   // rechts davon UND ...
    private static final float UI_Y = 0.62f;   // ... unterhalb davon wird nicht gesucht (Knöpfe)
    // Form des Rings (Anteil von Bildbreite/-höhe)
    private static final float RING_MIN_W = 0.030f;
    private static final float RING_MAX_W = 0.066f;    // Ring ist im Spiel immer etwa gleich groß; größere grüne Flächen (z. B. Spielfeldlinien) fliegen raus
    private static final float RING_MIN_H = 0.045f;    // Lebensbalken und Name sind flacher -> fliegen raus
    private static final float RING_MIN_ASPECT = 0.7f;
    private static final float RING_MAX_ASPECT = 4.2f;
    private static final int RING_MIN_PIXELS = 20;
    private static final float RING_MAX_INNER_FILL = 0.45f; // höchstens so viel Grün innen im Ring
    // Aus dem Ring wird die Box meiner Figur gebaut: so hoch wie 1,5 x Ringbreite
    private static final float ME_BOX_HEIGHT = 1.5f;
    private static final long RING_KEEP_MS = 500;

    private byte[] ringMask = new byte[0];
    private int[] ringStack = new int[0];
    private float lastRingCx = -1f;
    private float lastRingCy = -1f;
    private long lastRingTime = 0;
    private int lastRingPixels = 0;

    private static final long KEEP_MS = 300;                 // so lange merkt sich die KI die letzten Boxen
    private static final float IOU_THRESHOLD = 0.45f;
    private static final float MAX_BOX_RATIO = 0.20f; // Max. 20 % der Bildschirmbreite/-höhe
    private static final float PAD_VALUE = 114f / 255f;
    private static final float INV_255 = 1f / 255f;

    private Interpreter interpreter;
    private MappedByteBuffer modelBuffer;
    private GpuDelegate gpuDelegate;
    private boolean usingGpu = false;
    private boolean initFailed = false;
    private int inputWidth = 640;
    private int inputHeight = 640;
    private boolean isInputNchw = false;
    private boolean isOutputChannelsFirst = true;
    private int numChannels = 5;
    private int numBoxes = 8400;

    private ByteBuffer inputBuffer;
    private FloatBuffer floatBufferView;
    private float[][][] outputBuffer;

    // Layout-Cache (wird nur neu berechnet, wenn sich die Bildgröße ändert)
    private int lastWidth = -1;
    private int lastHeight = -1;
    private float scale = 1f;
    private int scaledW = 1;
    private int scaledH = 1;
    private int padX = 0;
    private int padY = 0;
    private int[] sxMap = new int[0];
    private int[] syMap = new int[0];
    private byte[] frameBytes = new byte[0];
    private float[] rowFloats = new float[0];
    private float[] rowR = new float[0];
    private float[] rowG = new float[0];
    private float[] rowB = new float[0];

    // 0 = unbekannt, 1 = normalisiert (0..1), 2 = Pixel
    private int coordMode = 0;

    private String lastDebug = "Init...";

    // Letzte erkannte Enemy-Boxen (gegen Aussetzer, wenn der Wert kurz einbricht)
    private final List<RectF> lastBoxes = new ArrayList<>();
    private long lastBoxesTime = 0;

    private static class Candidate {
        final RectF box;
        final float score;

        Candidate(RectF box, float score) {
            this.box = box;
            this.score = score;
        }
    }

    public YoloDetector(Context context) {
        try {
            // Nur die Modelldatei laden. Der Interpreter wird erst beim ersten Bild
            // erstellt, weil die GPU auf demselben Thread laufen muss wie die Erkennung.
            modelBuffer = loadModelFile(context);
            lastDebug = "Modell geladen";
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Laden von " + MODEL_FILE, e);
            lastDebug = "LOAD_ERR: " + e.getMessage();
            initFailed = true;
        }
    }

    private void ensureInitialized() {
        if (interpreter != null || initFailed || modelBuffer == null) {
            return;
        }
        try {
            interpreter = createInterpreter(true);

            int[] inShape = interpreter.getInputTensor(0).shape();
            if (inShape.length == 4) {
                if (inShape[1] == 3) {
                    isInputNchw = true;
                    inputHeight = inShape[2];
                    inputWidth = inShape[3];
                } else {
                    isInputNchw = false;
                    inputHeight = inShape[1];
                    inputWidth = inShape[2];
                }
            }

            int[] outShape = interpreter.getOutputTensor(0).shape();
            if (outShape.length == 3 && outShape[0] > 0 && outShape[1] > 0 && outShape[2] > 0) {
                outputBuffer = new float[outShape[0]][outShape[1]][outShape[2]];
                isOutputChannelsFirst = outShape[1] < outShape[2];
                numChannels = isOutputChannelsFirst ? outShape[1] : outShape[2];
                numBoxes = isOutputChannelsFirst ? outShape[2] : outShape[1];
            }

            int totalFloats = 3 * inputWidth * inputHeight;
            inputBuffer = ByteBuffer.allocateDirect(4 * totalFloats);
            inputBuffer.order(ByteOrder.nativeOrder());
            floatBufferView = inputBuffer.asFloatBuffer();
            lastWidth = -1;
            lastHeight = -1;
            lastDebug = "OK Ch:" + numChannels + " Box:" + numBoxes;
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Starten der KI", e);
            lastDebug = "LOAD_ERR: " + e.getMessage();
            initFailed = true;
            closeInterpreter();
        }
    }

    // Versucht zuerst die GPU, bei jedem Problem automatisch Prozessor (CPU)
    private Interpreter createInterpreter(boolean tryGpu) {
        if (tryGpu) {
            try (CompatibilityList compat = new CompatibilityList()) {
                if (compat.isDelegateSupportedOnThisDevice()) {
                    Interpreter.Options gpuOptions = new Interpreter.Options();
                    gpuDelegate = new GpuDelegate(compat.getBestOptionsForThisDevice());
                    gpuOptions.addDelegate(gpuDelegate);
                    Interpreter gpuInterpreter = new Interpreter(modelBuffer, gpuOptions);
                    usingGpu = true;
                    return gpuInterpreter;
                }
            } catch (Throwable t) {
                Log.e(TAG, "GPU nicht nutzbar, wechsle auf CPU", t);
                closeGpu();
            }
        }
        usingGpu = false;
        Interpreter.Options cpuOptions = new Interpreter.Options();
        cpuOptions.setNumThreads(4);
        return new Interpreter(modelBuffer, cpuOptions);
    }

    private void closeGpu() {
        if (gpuDelegate != null) {
            try {
                gpuDelegate.close();
            } catch (Throwable ignored) {
            }
            gpuDelegate = null;
        }
    }

    private void closeInterpreter() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        closeGpu();
    }

    public String getLastDebug() {
        return lastDebug;
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_FILE);
        try (FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
            FileChannel fileChannel = inputStream.getChannel();
            return fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.getStartOffset(),
                    fileDescriptor.getDeclaredLength()
            );
        } finally {
            fileDescriptor.close();
        }
    }

    // Berechnet Skalierung, Rand (Letterbox) und Pixel-Zuordnung einmalig pro Bildgröße
    private void prepareLayout(int width, int height) {
        scale = Math.min((float) inputWidth / width, (float) inputHeight / height);
        scaledW = Math.max(1, Math.round(width * scale));
        scaledH = Math.max(1, Math.round(height * scale));
        padX = (inputWidth - scaledW) / 2;
        padY = (inputHeight - scaledH) / 2;

        sxMap = new int[scaledW];
        for (int x = 0; x < scaledW; x++) {
            sxMap[x] = Math.min(width - 1, Math.max(0, (int) (x / scale)));
        }
        syMap = new int[scaledH];
        for (int y = 0; y < scaledH; y++) {
            syMap[y] = Math.min(height - 1, Math.max(0, (int) (y / scale)));
        }

        // Gesamten Eingabepuffer einmal mit der grauen Randfarbe füllen
        int totalFloats = 3 * inputWidth * inputHeight;
        for (int i = 0; i < totalFloats; i++) {
            floatBufferView.put(i, PAD_VALUE);
        }

        lastWidth = width;
        lastHeight = height;
    }

    public synchronized List<ColorDetector.Detection> detect(
            ByteBuffer buffer,
            int width,
            int height,
            int pixelStride,
            int rowStride,
            int outputWidth,
            int outputHeight
    ) {
        List<ColorDetector.Detection> results = new ArrayList<>();
        ensureInitialized();
        int requiredChannel = 4 + TARGET_CLASS_ID;
        if (interpreter == null || outputBuffer == null || buffer == null
                || width <= 0 || height <= 0 || numChannels <= requiredChannel) {
            if (interpreter != null && numChannels <= requiredChannel) {
                lastDebug = "Zu wenig Kanaele: " + numChannels;
            }
            return results;
        }

        long tStart = System.nanoTime();

        if (width != lastWidth || height != lastHeight) {
            prepareLayout(width, height);
        }

        int bufferLimit = buffer.limit();
        int planeSize = inputWidth * inputHeight;

        // Schritt 1: das ganze Bild auf einmal aus dem Grafikspeicher kopieren
        if (frameBytes.length < bufferLimit) {
            frameBytes = new byte[bufferLimit];
        }
        buffer.position(0);
        buffer.get(frameBytes, 0, bufferLimit);
        long tCopy = System.nanoTime();

        // Schritt 2: Pixel in Kommazahlen für die KI umrechnen (grauer Rand bleibt unverändert)
        if (rowFloats.length < scaledW * 3) {
            rowFloats = new float[scaledW * 3];
        }
        if (rowR.length < scaledW) {
            rowR = new float[scaledW];
            rowG = new float[scaledW];
            rowB = new float[scaledW];
        }

        for (int y = 0; y < scaledH; y++) {
            int rowStart = syMap[y] * rowStride;
            if (rowStart < 0 || rowStart >= bufferLimit) {
                continue;
            }
            int rowIndex = (padY + y) * inputWidth + padX;

            if (!isInputNchw) {
                int o = 0;
                for (int x = 0; x < scaledW; x++) {
                    int idx = rowStart + sxMap[x] * pixelStride;
                    if (idx + 2 < bufferLimit) {
                        rowFloats[o] = (frameBytes[idx] & 0xFF) * INV_255;
                        rowFloats[o + 1] = (frameBytes[idx + 1] & 0xFF) * INV_255;
                        rowFloats[o + 2] = (frameBytes[idx + 2] & 0xFF) * INV_255;
                    } else {
                        rowFloats[o] = PAD_VALUE;
                        rowFloats[o + 1] = PAD_VALUE;
                        rowFloats[o + 2] = PAD_VALUE;
                    }
                    o += 3;
                }
                floatBufferView.position(rowIndex * 3);
                floatBufferView.put(rowFloats, 0, scaledW * 3);
            } else {
                // Modell mit getrennten Farbebenen (1, 3, H, W): erst ganze Zeile je Farbe sammeln,
                // dann jede Zeile mit einem einzigen Befehl in den Eingabepuffer schreiben
                for (int x = 0; x < scaledW; x++) {
                    int idx = rowStart + sxMap[x] * pixelStride;
                    if (idx + 2 < bufferLimit) {
                        rowR[x] = (frameBytes[idx] & 0xFF) * INV_255;
                        rowG[x] = (frameBytes[idx + 1] & 0xFF) * INV_255;
                        rowB[x] = (frameBytes[idx + 2] & 0xFF) * INV_255;
                    } else {
                        rowR[x] = PAD_VALUE;
                        rowG[x] = PAD_VALUE;
                        rowB[x] = PAD_VALUE;
                    }
                }
                floatBufferView.position(rowIndex);
                floatBufferView.put(rowR, 0, scaledW);
                floatBufferView.position(planeSize + rowIndex);
                floatBufferView.put(rowG, 0, scaledW);
                floatBufferView.position(2 * planeSize + rowIndex);
                floatBufferView.put(rowB, 0, scaledW);
            }
        }

        inputBuffer.rewind();
        long tPre = System.nanoTime();

        try {
            interpreter.run(inputBuffer, outputBuffer);
        } catch (RuntimeException e) {
            if (!usingGpu) {
                throw e;
            }
            // GPU klappt mit diesem Modell nicht: automatisch auf CPU umstellen
            Log.e(TAG, "GPU-Lauf fehlgeschlagen, wechsle auf CPU", e);
            closeInterpreter();
            interpreter = createInterpreter(false);
            inputBuffer.rewind();
            interpreter.run(inputBuffer, outputBuffer);
        }
        long tRun = System.nanoTime();

        // Einmalig prüfen, ob Koordinaten normalisiert (0..1) oder in Pixeln (0..640) ausgegeben werden
        if (coordMode == 0) {
            float maxCoord = 0f;
            for (int i = 0; i < numBoxes; i++) {
                float cx = getOut(0, i);
                float cy = getOut(1, i);
                if (cx > maxCoord) maxCoord = cx;
                if (cy > maxCoord) maxCoord = cy;
            }
            coordMode = maxCoord <= 2.0f ? 1 : 2;
        }
        boolean normalized = coordMode == 1;

        float scaleToOutX = (float) outputWidth / width;
        float scaleToOutY = (float) outputHeight / height;
        float maxBoxWidth = outputWidth * MAX_BOX_RATIO;
        float maxBoxHeight = outputHeight * MAX_BOX_RATIO;
        List<Candidate> candidates = new ArrayList<>();

        int enemyChannel = 4 + TARGET_CLASS_ID;
        float enemyMax = 0f;

        long nowMs = tStart / 1_000_000;
        if (nowMs - lastBoxesTime > KEEP_MS) {
            lastBoxes.clear();
        }

        for (int i = 0; i < numBoxes; i++) {
            // Schnelltest: die meisten Boxen fliegen hier sofort raus
            float enemyScore = getOut(enemyChannel, i);
            if (enemyScore > enemyMax) {
                enemyMax = enemyScore;
            }
            if (enemyScore < LOW_THRESHOLD) {
                continue;
            }

            boolean strong = enemyScore >= CONFIDENCE_THRESHOLD;
            float bestOther = 0f;
            for (int c = 4; c < numChannels; c++) {
                if (c != enemyChannel) {
                    float v = getOut(c, i);
                    if (v > bestOther) {
                        bestOther = v;
                    }
                }
            }
            // Neue Boxen: Enemy muss klar die beste Klasse sein.
            // Schwache Boxen (schon bekannt): Enemy darf knapp hinter der besten Klasse liegen.
            if (strong ? bestOther > enemyScore : bestOther > enemyScore * 1.25f) {
                continue;
            }
            RectF decodedBox = decodeBox(i, normalized, scaleToOutX, scaleToOutY,
                    outputWidth, outputHeight, maxBoxWidth, maxBoxHeight);
            if (decodedBox != null && (strong || nearPreviousBox(decodedBox))) {
                candidates.add(new Candidate(decodedBox, enemyScore));
            }
        }

        // Doppelte Enemy-Boxen per NMS herausfiltern
        Collections.sort(candidates, (a, b) -> Float.compare(b.score, a.score));
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : candidates) {
            boolean suppressed = false;
            for (Candidate k : kept) {
                if (calculateIoU(c.box, k.box) > IOU_THRESHOLD) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                kept.add(c);
            }
        }

        // "Ich" über den grünen Ring suchen (unabhängig von der KI)
        RectF meBox = null;
        RectF ring = findMeRing(width, height, pixelStride, rowStride, bufferLimit, nowMs);
        if (ring != null) {
            float w = ring.width();
            float cx = ring.centerX();
            float left = (cx - w * 0.5f) * scaleToOutX;
            float right = (cx + w * 0.5f) * scaleToOutX;
            float bottom = ring.bottom * scaleToOutY;
            float top = (ring.bottom - w * ME_BOX_HEIGHT) * scaleToOutY;
            meBox = new RectF(left, Math.max(0f, top), right, bottom);

            // Hält die KI mich selbst für einen Gegner, wird diese Gegner-Box entfernt
            for (int k = kept.size() - 1; k >= 0; k--) {
                if (calculateIoU(meBox, kept.get(k).box) > 0.4f) {
                    kept.remove(k);
                }
            }
        }

        for (Candidate k : kept) {
            results.add(new ColorDetector.Detection(k.box, ColorDetector.TargetType.ENEMY));
        }
        if (meBox != null) {
            results.add(new ColorDetector.Detection(meBox, ColorDetector.TargetType.PLAYER));
        }

        // Enemy-Boxen dieses Bildes für das nächste Bild merken
        if (!kept.isEmpty()) {
            lastBoxes.clear();
            for (Candidate k : kept) {
                lastBoxes.add(k.box);
            }
            lastBoxesTime = nowMs;
        }

        long tEnd = System.nanoTime();
        lastDebug = String.format(
                Locale.US,
                "%s Kopie:%d Umr:%d Modell:%d Nach:%d ms | Enemy:%.2f Ring:%d",
                usingGpu ? "GPU" : "CPU",
                (tCopy - tStart) / 1_000_000,
                (tPre - tCopy) / 1_000_000,
                (tRun - tPre) / 1_000_000,
                (tEnd - tRun) / 1_000_000,
                enemyMax,
                lastRingPixels
        );

        return results;
    }

    private RectF decodeBox(
            int boxIndex,
            boolean normalized,
            float scaleToOutX,
            float scaleToOutY,
            int outputWidth,
            int outputHeight,
            float maxBoxWidth,
            float maxBoxHeight
    ) {
        float cx = getOut(0, boxIndex);
        float cy = getOut(1, boxIndex);
        float bw = getOut(2, boxIndex);
        float bh = getOut(3, boxIndex);

        if (normalized) {
            cx *= inputWidth;
            cy *= inputHeight;
            bw *= inputWidth;
            bh *= inputHeight;
        }

        float x1 = cx - bw * 0.5f;
        float y1 = cy - bh * 0.5f;
        float x2 = cx + bw * 0.5f;
        float y2 = cy + bh * 0.5f;

        float left = Math.max(0f, ((x1 - padX) / scale) * scaleToOutX);
        float top = Math.max(0f, ((y1 - padY) / scale) * scaleToOutY);
        float right = Math.min(outputWidth, ((x2 - padX) / scale) * scaleToOutX);
        float bottom = Math.min(outputHeight, ((y2 - padY) / scale) * scaleToOutY);

        float boxWidth = right - left;
        float boxHeight = bottom - top;
        if (boxWidth <= 0f || boxHeight <= 0f
                || boxWidth > maxBoxWidth || boxHeight > maxBoxHeight) {
            return null;
        }
        return new RectF(left, top, right, bottom);
    }

    // Helles Grün des eigenen Rings. Büsche haben fast kein Rot (R < 40), Teamkameraden sind blau, Gegner rot
    private static boolean isRingGreen(int r, int g, int b) {
        return r >= 40 && r <= 210 && g >= 150 && g - r >= 70 && g - b >= 70;
    }

    // Ein Ring ist innen größtenteils nicht grün (dort stehe ich). Ein Busch ist innen voll grün.
    private boolean isHollow(int minX, int minY, int maxX, int maxY, int width) {
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        int ix0 = minX + (int) (w * 0.3f);
        int ix1 = maxX - (int) (w * 0.3f);
        int iy0 = minY + (int) (h * 0.3f);
        int iy1 = maxY - (int) (h * 0.3f);
        int total = 0;
        int green = 0;
        for (int y = iy0; y <= iy1; y++) {
            for (int x = ix0; x <= ix1; x++) {
                total++;
                if (ringMask[y * width + x] != 0) {
                    green++;
                }
            }
        }
        return total > 0 && green < total * RING_MAX_INNER_FILL;
    }

    // Sucht den grünen Ring unter meiner Figur. Liefert seine Umrandung in Bildpixeln oder null.
    private RectF findMeRing(int width, int height, int pixelStride, int rowStride, int limit, long nowMs) {
        int n = width * height;
        if (ringMask.length < n) {
            ringMask = new byte[n];
            ringStack = new int[n];
        }

        int x0 = (int) (width * RING_X_MIN);
        int x1 = (int) (width * RING_X_MAX);
        int y0 = (int) (height * RING_Y_MIN);
        int y1 = (int) (height * RING_Y_MAX);
        int uiX = (int) (width * UI_X);
        int uiY = (int) (height * UI_Y);

        // Schritt 1: alle hellgrünen Pixel markieren
        for (int y = y0; y < y1; y++) {
            int rowStart = y * rowStride;
            int base = y * width;
            for (int x = x0; x < x1; x++) {
                byte m = 0;
                if (!(x > uiX && y > uiY)) {
                    int idx = rowStart + x * pixelStride;
                    if (idx + 2 < limit) {
                        int r = frameBytes[idx] & 0xFF;
                        int g = frameBytes[idx + 1] & 0xFF;
                        int b = frameBytes[idx + 2] & 0xFF;
                        if (isRingGreen(r, g, b)) {
                            m = 1;
                        }
                    }
                }
                ringMask[base + x] = m;
            }
        }

        boolean hasLast = lastRingCx >= 0f && nowMs - lastRingTime <= RING_KEEP_MS;
        float minW = width * RING_MIN_W;
        float maxW = width * RING_MAX_W;
        float minH = height * RING_MIN_H;

        RectF best = null;
        float bestScore = 0f;
        int bestPixels = 0;

        // Schritt 2: zusammenhängende grüne Flecken finden und den ringförmigsten nehmen
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int start = y * width + x;
                if (ringMask[start] != 1) {
                    continue;
                }
                int sp = 0;
                ringStack[sp++] = start;
                ringMask[start] = 2;
                int minX = x, maxX = x, minY = y, maxY = y, count = 0;

                while (sp > 0) {
                    int p = ringStack[--sp];
                    int px = p % width;
                    int py = p / width;
                    count++;
                    if (px < minX) minX = px;
                    if (px > maxX) maxX = px;
                    if (py < minY) minY = py;
                    if (py > maxY) maxY = py;

                    for (int dy = -1; dy <= 1; dy++) {
                        int ny = py + dy;
                        if (ny < y0 || ny >= y1) {
                            continue;
                        }
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = px + dx;
                            if (nx < x0 || nx >= x1) {
                                continue;
                            }
                            int q = ny * width + nx;
                            if (ringMask[q] == 1) {
                                ringMask[q] = 2;
                                ringStack[sp++] = q;
                            }
                        }
                    }
                }

                float w = maxX - minX + 1;
                float h = maxY - minY + 1;
                float aspect = w / h;
                if (count < RING_MIN_PIXELS || w < minW || w > maxW || h < minH
                        || aspect < RING_MIN_ASPECT || aspect > RING_MAX_ASPECT) {
                    continue;
                }

                float cx = (minX + maxX) * 0.5f;
                float cy = (minY + maxY) * 0.5f;

                // Ring muss innen hohl sein, sonst ist es ein Busch o. Ä.
                if (!isHollow(minX, minY, maxX, maxY, width)) {
                    continue;
                }
                float score = count;
                // Nah an der letzten Ring-Position: sehr wahrscheinlich wieder ich
                if (hasLast && Math.hypot(cx - lastRingCx, cy - lastRingCy) < w) {
                    score *= 3f;
                }
                // Etwas Vorrang für die Bildmitte (Kamera folgt mir)
                float dxc = cx / width - 0.5f;
                float dyc = cy / height - 0.55f;
                score *= 1.5f - Math.min(1f, (float) Math.hypot(dxc, dyc));

                if (score > bestScore) {
                    bestScore = score;
                    bestPixels = count;
                    best = new RectF(minX, minY, maxX + 1, maxY + 1);
                }
            }
        }

        if (best != null) {
            lastRingCx = best.centerX();
            lastRingCy = best.centerY();
            lastRingTime = nowMs;
        }
        lastRingPixels = bestPixels;
        return best;
    }

    private boolean nearPreviousBox(RectF box) {
        for (RectF previous : lastBoxes) {
            if (calculateIoU(box, previous) > 0.2f) {
                return true;
            }
        }
        return false;
    }

    private float getOut(int channel, int boxIndex) {
        if (isOutputChannelsFirst) {
            return outputBuffer[0][channel][boxIndex];
        } else {
            return outputBuffer[0][boxIndex][channel];
        }
    }

    private float calculateIoU(RectF a, RectF b) {
        float interLeft = Math.max(a.left, b.left);
        float interTop = Math.max(a.top, b.top);
        float interRight = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        float interW = Math.max(0f, interRight - interLeft);
        float interH = Math.max(0f, interBottom - interTop);
        float interArea = interW * interH;

        float areaA = Math.max(0f, a.width()) * Math.max(0f, a.height());
        float areaB = Math.max(0f, b.width()) * Math.max(0f, b.height());
        float union = areaA + areaB - interArea;
        return union > 0f ? interArea / union : 0f;
    }

    public synchronized void close() {
        closeInterpreter();
    }
}