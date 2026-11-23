package com.example.targetdetector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

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

public class YoloDetector {

    private static final String TAG = "YoloDetector";
    private static final String MODEL_FILE = "best.tflite";
    private static final float CONFIDENCE_THRESHOLD = 0.35f;
    private static final float IOU_THRESHOLD = 0.45f;
    private static final float MAX_BOX_RATIO = 0.20f; // Max. 20 % der Bildschirmbreite/-höhe

    private Interpreter interpreter;
    private int inputWidth = 640;
    private int inputHeight = 640;
    private boolean isInputNchw = false;
    private boolean isOutputChannelsFirst = true;
    private int numChannels = 5;
    private int numBoxes = 8400;

    private ByteBuffer inputBuffer;
    private FloatBuffer floatBufferView;
    private float[] floatInput;
    private float[][][] outputBuffer;

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
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            interpreter = new Interpreter(loadModelFile(context), options);

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
            floatInput = new float[totalFloats];
            inputBuffer = ByteBuffer.allocateDirect(4 * totalFloats);
            inputBuffer.order(ByteOrder.nativeOrder());
            floatBufferView = inputBuffer.asFloatBuffer();
        } catch (Exception e) {
            Log.e(TAG, "Fehler beim Laden von " + MODEL_FILE, e);
            close();
        }
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
        if (interpreter == null || outputBuffer == null || buffer == null
                || width <= 0 || height <= 0 || numChannels < 5) {
            return results;
        }

        // Letterbox-Skalierung (Seitenverhältnis bleibt wie beim Training erhalten)
        float scale = Math.min((float) inputWidth / width, (float) inputHeight / height);
        int scaledW = Math.max(1, Math.round(width * scale));
        int scaledH = Math.max(1, Math.round(height * scale));
        int padX = (inputWidth - scaledW) / 2;
        int padY = (inputHeight - scaledH) / 2;

        int bufferLimit = buffer.limit();
        int planeSize = inputWidth * inputHeight;
        float padVal = 114f / 255f;

        for (int iy = 0; iy < inputHeight; iy++) {
            boolean insideY = iy >= padY && iy < padY + scaledH;
            int sy = insideY
                    ? Math.min(height - 1, Math.max(0, (int) ((iy - padY) / scale)))
                    : 0;
            int rowOffset = sy * rowStride;

            for (int ix = 0; ix < inputWidth; ix++) {
                float r = padVal;
                float g = padVal;
                float b = padVal;

                if (insideY && ix >= padX && ix < padX + scaledW) {
                    int sx = Math.min(width - 1, Math.max(0, (int) ((ix - padX) / scale)));
                    int offset = rowOffset + sx * pixelStride;
                    if (offset >= 0 && offset + 2 < bufferLimit) {
                        r = (buffer.get(offset) & 0xFF) / 255.0f;
                        g = (buffer.get(offset + 1) & 0xFF) / 255.0f;
                        b = (buffer.get(offset + 2) & 0xFF) / 255.0f;
                    }
                }

                int pixelIndex = iy * inputWidth + ix;
                if (isInputNchw) {
                    floatInput[pixelIndex] = r;
                    floatInput[planeSize + pixelIndex] = g;
                    floatInput[2 * planeSize + pixelIndex] = b;
                } else {
                    int base = pixelIndex * 3;
                    floatInput[base] = r;
                    floatInput[base + 1] = g;
                    floatInput[base + 2] = b;
                }
            }
        }

        floatBufferView.rewind();
        floatBufferView.put(floatInput);
        inputBuffer.rewind();

        interpreter.run(inputBuffer, outputBuffer);

        // Prüfen, ob Koordinaten normalisiert (0..1) oder in Pixeln (0..640) ausgegeben werden
        float maxCoord = 0f;
        for (int i = 0; i < numBoxes; i++) {
            float cx = getOut(0, i);
            float cy = getOut(1, i);
            if (cx > maxCoord) maxCoord = cx;
            if (cy > maxCoord) maxCoord = cy;
        }
        boolean normalized = maxCoord <= 2.0f;

        float scaleToOutX = (float) outputWidth / width;
        float scaleToOutY = (float) outputHeight / height;
        float maxBoxWidth = outputWidth * MAX_BOX_RATIO;
        float maxBoxHeight = outputHeight * MAX_BOX_RATIO;
        List<Candidate> candidates = new ArrayList<>();

        for (int i = 0; i < numBoxes; i++) {
            float maxScore = 0f;
            for (int c = 4; c < numChannels; c++) {
                float score = getOut(c, i);
                if (score > maxScore) {
                    maxScore = score;
                }
            }

            if (maxScore < CONFIDENCE_THRESHOLD) {
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

            candidates.add(new Candidate(new RectF(left, top, right, bottom), maxScore));
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
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}
