package com.example.targetdetector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;
import java.nio.ByteBuffer;

public class TemplateDetector {

    private Mat templateGray;
    private boolean initialized = false;

    public boolean init(Context context, String assetName) {
        if (!OpenCVLoader.initLocal()) {
            return false;
        }
        try (InputStream is = context.getAssets().open(assetName)) {
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap == null) return false;

            Mat raw = new Mat();
            Mat gray = new Mat();
            Utils.bitmapToMat(bitmap, raw);
            Imgproc.cvtColor(raw, gray, Imgproc.COLOR_RGBA2GRAY);

            // Auf halbe Größe skalieren (passend zu captureWidth / captureHeight)
            templateGray = new Mat();
            Imgproc.resize(
                    gray,
                    templateGray,
                    new Size(Math.max(1, gray.cols() / 2.0), Math.max(1, gray.rows() / 2.0))
            );

            raw.release();
            gray.release();
            bitmap.recycle();
            initialized = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public RectF detect(
            ByteBuffer buffer,
            int width,
            int height,
            int pixelStride,
            int rowStride,
            int outputWidth,
            int outputHeight,
            double threshold
    ) {
        if (!initialized || templateGray == null || buffer == null) return null;
        if (width < templateGray.cols() || height < templateGray.rows()) return null;

        Mat rgba = null;
        Mat gray = null;
        Mat result = null;

        try {
            byte[] data = new byte[width * height * 4];
            int rowBytes = width * 4;

            if (rowStride == rowBytes && pixelStride == 4) {
                buffer.position(0);
                buffer.get(data, 0, data.length);
            } else {
                for (int y = 0; y < height; y++) {
                    buffer.position(y * rowStride);
                    buffer.get(data, y * rowBytes, rowBytes);
                }
            }

            rgba = new Mat(height, width, CvType.CV_8UC4);
            rgba.put(0, 0, data);

            gray = new Mat();
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

            result = new Mat();
            Imgproc.matchTemplate(gray, templateGray, result, Imgproc.TM_CCOEFF_NORMED);

            Core.MinMaxLocResult mmr = Core.minMaxLoc(result);
            if (mmr.maxVal >= threshold) {
                Point loc = mmr.maxLoc;
                float scaleX = (float) outputWidth / width;
                float scaleY = (float) outputHeight / height;

                float left = (float) loc.x * scaleX;
                float top = (float) loc.y * scaleY;
                float right = (float) (loc.x + templateGray.cols()) * scaleX;
                float bottom = (float) (loc.y + templateGray.rows()) * scaleY;

                return new RectF(left, top, right, bottom);
            }
        } catch (Exception ignored) {
        } finally {
            if (rgba != null) rgba.release();
            if (gray != null) gray.release();
            if (result != null) result.release();
        }

        return null;
    }

    public void release() {
        if (templateGray != null) {
            templateGray.release();
            templateGray = null;
        }
        initialized = false;
    }
}
