package com.example.targetdetector;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Nimmt für kurze Zeit genau die Bilder auf, die auch die KI bekommt (640 px, verlustfreies PNG),
 * und schreibt dazu pro Bild die Messwerte (Zeit, KI-Dauer, Boxen, Debug-Text) in eine log.csv.
 * Alles landet als EINE ZIP-Datei in "Download/TargetDetector" (keine Speicher-Erlaubnis nötig).
 *
 * Das Speichern läuft in einem eigenen Hintergrund-Thread, damit die Erkennung nicht langsamer wird.
 * Kommt der Hintergrund-Thread nicht hinterher, werden Bilder ausgelassen (in log.csv vermerkt).
 *
 * Hinweis: Die Bildschirmaufnahme enthält auch das eigene Overlay (Ecken, Linie, Text),
 * genau wie bei der KI. So sieht man später exakt, was die Erkennung gesehen hat.
 */
public class FrameRecorder {

    private static final String TAG = "FrameRecorder";

    // Einstellungen (werden in der App unter "Aufnahme" gesetzt)
    public static final String PREFS = "settings";
    public static final String KEY_SECONDS = "rec_seconds";   // Dauer in Sekunden
    public static final String KEY_EVERY = "rec_every";       // jedes n-te Bild speichern
    public static final String KEY_BUTTON = "rec_button";     // Aufnahme-Knopf im Spiel anzeigen
    public static final int DEFAULT_SECONDS = 20;
    public static final int DEFAULT_EVERY = 2;

    private static final int POOL_SIZE = 6;       // so viele Bilder dürfen gleichzeitig auf das Speichern warten
    private static final int MAX_FRAMES = 900;    // Sicherheitsgrenze pro Aufnahme
    private static final String FOLDER = "TargetDetector";

    public interface Listener {
        void onRecordingChanged(boolean recording);
    }

    private static class Job {
        byte[] rgba;
        int w;
        int h;
        String name;
        boolean end;
        String logText;
        int saved;
        int dropped;
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Listener listener;

    // Zustand der laufenden Aufnahme (geschützt durch "this")
    private volatile boolean recording = false;
    private long startMs;
    private long durationMs;
    private int every;
    private int width;
    private int height;
    private int frameCounter;
    private int savedCount;
    private int droppedCount;
    private int allocated;
    private StringBuilder log;
    private ArrayBlockingQueue<byte[]> freePool;
    private LinkedBlockingQueue<Job> jobs;

    public FrameRecorder(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isRecording() {
        return recording;
    }

    public long getDurationMs() {
        return durationMs;
    }

    /** Verbleibende Sekunden (aufgerundet), 0 wenn keine Aufnahme läuft. */
    public int secondsLeft() {
        if (!recording) {
            return 0;
        }
        long left = durationMs - (SystemClock.uptimeMillis() - startMs);
        return (int) Math.max(0, (left + 999) / 1000);
    }

    public synchronized void toggle() {
        if (recording) {
            stopLocked();
        } else {
            start();
        }
    }

    public synchronized void start() {
        if (recording) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int seconds = prefs.getInt(KEY_SECONDS, DEFAULT_SECONDS);
        every = Math.max(1, prefs.getInt(KEY_EVERY, DEFAULT_EVERY));
        durationMs = Math.max(1, seconds) * 1000L;
        startMs = SystemClock.uptimeMillis();
        width = 0;
        height = 0;
        frameCounter = 0;
        savedCount = 0;
        droppedCount = 0;
        allocated = 0;
        freePool = new ArrayBlockingQueue<>(POOL_SIZE);
        jobs = new LinkedBlockingQueue<>();

        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        String fileName = "aufnahme_" + stamp + ".zip";

        log = new StringBuilder();
        log.append("# Target Detector Aufnahme ").append(stamp).append('\n');
        log.append("# Dauer_s=").append(seconds).append(" jedes_n_te_Bild=").append(every).append('\n');
        log.append("bild_nr;zeit_ms;ki_ms;datei;boxen;debug\n");

        final LinkedBlockingQueue<Job> myJobs = jobs;
        final ArrayBlockingQueue<byte[]> myPool = freePool;
        Thread writer = new Thread(() -> writeZip(fileName, myJobs, myPool), "FrameRecorderWriter");
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();

        recording = true;
        notifyListener(true);
        toast("Aufnahme läuft (" + seconds + " s)");
    }

    public synchronized void stop() {
        if (recording) {
            stopLocked();
        }
    }

    private void stopLocked() {
        recording = false;
        Job end = new Job();
        end.end = true;
        end.logText = log.toString();
        end.saved = savedCount;
        end.dropped = droppedCount;
        jobs.add(end);
        log = null;
        notifyListener(false);
    }

    /**
     * Wird für jedes ausgewertete Bild aufgerufen (vom Aufnahme-Thread).
     * Kopiert das Bild nur, wenn es gespeichert werden soll; der Rest passiert im Hintergrund.
     */
    public synchronized void addFrame(ByteBuffer buffer, int w, int h, int pixelStride, int rowStride,
                                      List<ColorDetector.Detection> detections, long kiMs, String debug) {
        if (!recording) {
            return;
        }
        long t = SystemClock.uptimeMillis() - startMs;
        if (t > durationMs || savedCount >= MAX_FRAMES) {
            stopLocked();
            return;
        }
        frameCounter++;
        if (width == 0) {
            width = w;
            height = h;
        }

        String name = "-";
        if ((frameCounter - 1) % every == 0) {
            if (w != width || h != height) {
                name = "groesse_geaendert";
            } else {
                byte[] arr = freePool.poll();
                if (arr == null && allocated < POOL_SIZE) {
                    arr = new byte[w * h * 4];
                    allocated++;
                }
                if (arr == null) {
                    // Speicher-Thread kommt nicht hinterher: dieses Bild auslassen
                    droppedCount++;
                    name = "ausgelassen";
                } else if (copyPixels(buffer, w, h, pixelStride, rowStride, arr)) {
                    name = String.format(Locale.US, "f%05d.png", frameCounter);
                    Job job = new Job();
                    job.rgba = arr;
                    job.w = w;
                    job.h = h;
                    job.name = name;
                    jobs.add(job);
                    savedCount++;
                } else {
                    freePool.offer(arr);
                    name = "kopierfehler";
                }
            }
        }

        log.append(frameCounter).append(';')
                .append(t).append(';')
                .append(kiMs).append(';')
                .append(name).append(';')
                .append(boxesText(detections)).append(';')
                .append(debug == null ? "" : debug.replace(';', ',').replace('\n', ' '))
                .append('\n');
    }

    // Kopiert das Bild Zeile für Zeile in ein dichtes RGBA-Feld (ohne Zeilen-Auffüllung)
    private static boolean copyPixels(ByteBuffer buffer, int w, int h, int pixelStride, int rowStride, byte[] out) {
        ByteBuffer src = buffer.duplicate();
        int limit = src.limit();
        int rowBytes = w * 4;
        for (int y = 0; y < h; y++) {
            int rowStart = y * rowStride;
            if (pixelStride == 4) {
                if (rowStart + rowBytes > limit) {
                    return false;
                }
                src.position(rowStart);
                src.get(out, y * rowBytes, rowBytes);
            } else {
                for (int x = 0; x < w; x++) {
                    int idx = rowStart + x * pixelStride;
                    if (idx + 3 >= limit) {
                        return false;
                    }
                    int o = y * rowBytes + x * 4;
                    out[o] = src.get(idx);
                    out[o + 1] = src.get(idx + 1);
                    out[o + 2] = src.get(idx + 2);
                    out[o + 3] = (byte) 255;
                }
            }
        }
        return true;
    }

    // Boxen als Text: Typ:links,oben,rechts,unten (Bildschirmpixel), mehrere mit | getrennt
    private static String boxesText(List<ColorDetector.Detection> detections) {
        if (detections == null || detections.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ColorDetector.Detection d : detections) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            String type;
            if (d.type == ColorDetector.TargetType.ENEMY) {
                type = "G";        // Gegner
            } else if (d.type == ColorDetector.TargetType.TEAMMATE) {
                type = "T";        // Teamkamerad
            } else {
                type = "I";        // ich
            }
            sb.append(type).append(':')
                    .append(Math.round(d.box.left)).append(',')
                    .append(Math.round(d.box.top)).append(',')
                    .append(Math.round(d.box.right)).append(',')
                    .append(Math.round(d.box.bottom));
        }
        return sb.toString();
    }

    // Hintergrund-Thread: PNG erzeugen und in die ZIP-Datei im Download-Ordner schreiben
    private void writeZip(String fileName, LinkedBlockingQueue<Job> myJobs, ArrayBlockingQueue<byte[]> myPool) {
        ContentResolver resolver = context.getContentResolver();
        Uri uri = null;
        ZipOutputStream zip = null;
        int written = 0;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("Datei konnte nicht angelegt werden");
            }
            OutputStream os = resolver.openOutputStream(uri);
            if (os == null) {
                throw new IllegalStateException("Datei konnte nicht geöffnet werden");
            }
            zip = new ZipOutputStream(new BufferedOutputStream(os, 1 << 16));

            Bitmap bmp = null;
            ByteArrayOutputStream png = new ByteArrayOutputStream(512 * 1024);
            CRC32 crc = new CRC32();
            Job end = null;
            while (end == null) {
                Job job = myJobs.take();
                if (job.end) {
                    end = job;
                    break;
                }
                if (bmp == null || bmp.getWidth() != job.w || bmp.getHeight() != job.h) {
                    if (bmp != null) {
                        bmp.recycle();
                    }
                    bmp = Bitmap.createBitmap(job.w, job.h, Bitmap.Config.ARGB_8888);
                }
                // Byte-Reihenfolge von ARGB_8888 im Speicher ist R,G,B,A -> passt direkt
                bmp.copyPixelsFromBuffer(ByteBuffer.wrap(job.rgba, 0, job.w * job.h * 4));
                myPool.offer(job.rgba); // Feld ist frei für das nächste Bild

                png.reset();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
                byte[] data = png.toByteArray();

                // PNG ist schon gepackt: ungepackt in die ZIP legen (spart Rechenzeit)
                crc.reset();
                crc.update(data, 0, data.length);
                ZipEntry entry = new ZipEntry(job.name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
                written++;
            }
            if (bmp != null) {
                bmp.recycle();
            }

            ZipEntry logEntry = new ZipEntry("log.csv");
            zip.putNextEntry(logEntry);
            zip.write(end.logText.getBytes("UTF-8"));
            zip.closeEntry();
            zip.finish();
            zip.close();
            zip = null;

            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);

            String msg = "Aufnahme gespeichert: Download/" + FOLDER + "/" + fileName
                    + " (" + written + " Bilder"
                    + (end.dropped > 0 ? ", " + end.dropped + " ausgelassen" : "") + ")";
            toast(msg);
        } catch (Throwable e) {
            Log.e(TAG, "Aufnahme fehlgeschlagen", e);
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Throwable ignored) {
                }
            }
            toast("Aufnahme fehlgeschlagen: " + e.getMessage());
        }
    }

    private void notifyListener(boolean rec) {
        final Listener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onRecordingChanged(rec));
        }
    }

    private void toast(String msg) {
        mainHandler.post(() -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
    }
}
