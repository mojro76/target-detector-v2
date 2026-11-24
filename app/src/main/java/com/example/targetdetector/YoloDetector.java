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
    private static final float CONFIDENCE_THRESHOLD = 0.25f;
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

    // 0 = unbekannt, 1 = normalisiert (0..1), 2 = Pixel
    private int coordMode = 0;

    private String lastDebug = "Init...";

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
        if (interpreter == null || outputBuffer == null || buffer == null
                || width <= 0 || height <= 0 || numChannels <= 4 + TARGET_CLASS_ID) {
            if (interpreter != null && numChannels <= 4 + TARGET_CLASS_ID) {
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
                for (int x = 0; x < scaledW; x++) {
                    int idx = rowStart + sxMap[x] * pixelStride;
                    if (idx + 2 >= bufferLimit) {
                        continue;
                    }
                    int pixelIndex = rowIndex + x;
                    floatBufferView.put(pixelIndex, (frameBytes[idx] & 0xFF) * INV_255);
                    floatBufferView.put(planeSize + pixelIndex, (frameBytes[idx + 1] & 0xFF) * INV_255);
                    floatBufferView.put(2 * planeSize + pixelIndex, (frameBytes[idx + 2] & 0xFF) * INV_255);
                }
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

        for (int i = 0; i < numBoxes; i++) {
            // Schnelltest: Enemy-Wert zuerst prüfen, die meisten Boxen fliegen hier raus
            float enemyScore = getOut(enemyChannel, i);
            if (enemyScore > enemyMax) {
                enemyMax = enemyScore;
            }
            if (enemyScore < CONFIDENCE_THRESHOLD) {
                continue;
            }

            // Enemy muss die beste Klasse dieser Box sein
            boolean isBest = true;
            for (int c = 4; c < numChannels; c++) {
                if (c != enemyChannel && getOut(c, i) > enemyScore) {
                    isBest = false;
                    break;
                }
            }
            if (!isBest) {
                continue;
            }

            float cx = getOut(0, i);
            float cy = getOut(1, i);
            float bw = getOut(2, i);
            float bh = getOut(3, i);

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
                continue;
            }

            candidates.add(new Candidate(new RectF(left, top, right, bottom), enemyScore));
        }

        // Doppelte Boxen per NMS herausfiltern
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
                results.add(new ColorDetector.Detection(c.box, ColorDetector.TargetType.ENEMY));
            }
        }

        long tEnd = System.nanoTime();
        lastDebug = String.format(
                Locale.US,
                "%s Kopie:%d Umr:%d Modell:%d Nach:%d ms | Enemy:%.2f",
                usingGpu ? "GPU" : "CPU",
                (tCopy - tStart) / 1_000_000,
                (tPre - tCopy) / 1_000_000,
                (tRun - tPre) / 1_000_000,
                (tEnd - tRun) / 1_000_000,
                enemyMax
        );

        return results;
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
