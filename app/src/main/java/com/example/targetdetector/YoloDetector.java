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
    private static final float CONFIDENCE_THRESHOLD = 0.30f; // neue Enemy-Boxen brauchen mindestens diesen Wert
    private static final float TRACK_SURE_SCORE = 0.50f;      // erst ab diesem Wert gilt ein KI-Treffer als "sicher" für die Verfolgung
    private static final float LOW_THRESHOLD = 0.12f;        // schon bekannte Enemy-Boxen dürfen bis hierher fallen

    // ===== Andere KI-Klassen nutzen (Messung an deinen Aufnahmen: KI erkennt sie sehr sicher) =====
    // 0 = Ball, 1 = Gegner, 2 = Teamkamerad, 5 = ich
    private static final int ME_CLASS_ID = 5;
    // Sieht die KI an derselben Stelle sicherer etwas anderes (Teamkamerad, ich, Ball ...),
    // ist es kein Gegner. Ab diesem Wert darf eine andere Klasse eine Gegner-Box verbieten:
    private static final float VETO_SCORE = 0.35f;
    private static final float VETO_IOU = 0.35f;            // so stark müssen sich die Boxen überlappen
    private static final float VETO_IOU_ME = 0.30f;         // bei "ich" etwas großzügiger
    // KI-"Ich"-Box springt ein, wenn mein Ring gerade nicht zu sehen ist (Effekte, Busch, Gedränge)
    private static final float AI_ME_SCORE = 0.60f;
    private static final float AI_ME_MAX_JUMP = 3.0f;       // höchstens so viele Ringbreiten von meiner letzten Position
    // Rote Ringe als zusätzliche Gegner: AUS. Messung: fast nur Fehl-Boxen auf orangen Kisten,
    // und die KI verpasst sichere Gegner so gut wie nie.
    private static final boolean RED_RINGS_ENABLED = false;
    // Verfolgung per Bildvergleich: AUS. Messung: Sie ließ Boxen auf Büschen stehen, wenn ein Gegner
    // hineinging. Kurze KI-Aussetzer überbrückt das Overlay ohnehin (350 ms halten).
    private static final boolean TRACKING_ENABLED = false;
    // Oberster Bildstreifen (Timer, Punktestand): dort sind nur SICHERE Gegner erlaubt.
    // Messung: Echte Gegner kommen oft von oben ins Bild, aber schwache "bekannte" Boxen
    // blieben dort am Timer hängen.
    private static final float TOP_HUD_RATIO = 0.12f;

    // ===== "Ich" über den grünen Ring unter meiner Figur finden =====
    // Suchbereich (Anteil vom Bild): oben ist HUD, unten rechts sind die grünen Knöpfe
    private static final float RING_X_MIN = 0.08f;
    private static final float RING_X_MAX = 0.92f;
    private static final float RING_Y_MIN = 0.18f;
    private static final float RING_Y_MAX = 0.97f;
    private static final float UI_X = 0.65f;   // rechts davon UND ...
    private static final float UI_Y = 0.62f;   // ... unterhalb davon wird nicht gesucht (Knöpfe)
    // Rote Gegner-Ringe: etwas breiter suchen, aber den roten Zielknopf rechts großzügig auslassen
    private static final float RED_X_MIN = 0.03f;
    private static final float RED_X_MAX = 0.97f;
    private static final float RED_UI_X = 0.72f;
    private static final float RED_UI_Y = 0.40f;
    // Zusätzlich unten rechts (Super- und Gadget-Knopf mit rot-orangem Rand)
    private static final float RED_BTN_X = 0.55f;
    private static final float RED_BTN_Y = 0.62f;
    // Ecke unten links wird für Rot nicht durchsucht (Plakate/Werbebanden, Bewegungs-Joystick)
    private static final float RED_LEFT_X = 0.25f;
    private static final float RED_BOTTOM_Y = 0.70f;
    // Strengere Form für rote Ringe (gegen rote Deko am Rand, Effekte usw.)
    private static final float RED_MIN_W = 0.0375f;
    private static final float RED_MAX_W = 0.0625f;
    private static final float RED_MIN_H = 0.066f;
    private static final float RED_MIN_ASPECT = 1.1f;
    private static final float RED_MAX_ASPECT = 1.45f;
    private static final float RED_CENTER_X_MIN = 0.12f;
    private static final float RED_CENTER_X_MAX = 0.88f;
    // Form des Rings (Anteil von Bildbreite/-höhe)
    private static final float GREEN_CENTER_X_MIN = 0.12f;
    private static final float GREEN_CENTER_X_MAX = 0.81f;
    private static final float RING_MIN_W = 0.030f;
    private static final float RING_MAX_W = 0.075f;    // größte erlaubte Ringbreite (nur nahe meiner letzten Position)
    private static final float RING_MAX_W_NEW = 0.060f; // ohne Vorwissen strenger: größere grüne Flächen (z. B. Spielfeldlinien) fliegen raus
    private static final float RING_MIN_H = 0.055f;    // Lebensbalken und Name sind flacher -> fliegen raus
    private static final float RING_MIN_ASPECT = 0.7f;
    private static final float RING_MAX_ASPECT = 4.2f;
    private static final int RING_MIN_PIXELS = 20;
    private static final float RING_MAX_INNER_FILL = 0.45f; // höchstens so viel Grün innen im Ring
    // Aus einem Ring wird die Box der Figur gebaut: so hoch wie 1,5 x Ringbreite
    private static final float ME_BOX_HEIGHT = 1.5f;
    private static final long RING_KEEP_MS = 500;

    // ===== Ring per Ellipse mit FESTER Größe finden =====
    // Der Ring ist immer gleich groß. Seine Größe wird einmal gelernt (Mittelwert über viele Bilder),
    // danach wird eine Ellipse genau dieser Größe auf die grünen Pixel gelegt. Das klappt auch,
    // wenn der Ring zerstückelt ist (Tor-Netz, Busch, Figur davor, Teamkamerad daneben).
    // Alle Boxen (ich und Gegner) bekommen diese feste Größe -> kein Schrumpfen/Pumpen mehr.
    private static final float RING_ASPECT = 1.28f;        // Ring ist so viel breiter als hoch (gemessen)
    private static final float RING_BOX_SCALE = 1.0f;      // Größe ist jetzt direkt der sichtbare Außenrand
    private static final float RING_SCORE_LOCAL = 0.30f;   // Mindestwert nahe meiner letzten Position
    private static final float RING_SCORE_GLOBAL = 0.45f;  // Mindestwert irgendwo im Bild (strenger)
    private static final float RING_SCORE_SIZE = 0.55f;    // nur so gute Treffer verbessern die Größe
    private static final float RING_SCORE_BOOT = 0.55f;    // Mindestwert beim ersten Lernen der Größe
    private static final long RING_LOCAL_MS = 1500;       // so lange wird zuerst nahe der letzten Position gesucht
    private static final long RING_RESET_MS = 5000;       // so lange nichts gefunden -> Größe neu lernen (neue Karte)
    private static final int BOOT_SAMPLES = 3;             // so viele gute Messungen braucht das erste Lernen
    private static final int GLOBAL_CHECK_EVERY = 4;       // jedes 4. Bild zusätzlich im ganzen Bild nachsehen

    private byte[] ringMask = new byte[0];
    private int[] ringStack = new int[0];
    private byte[] greenMask = new byte[0];
    private float lastRingCx = -1f;
    private float lastRingCy = -1f;
    private long lastRingTime = 0;
    private int lastRedAdded = 0;
    // Breite meines letzten vollständigen Rings (nur fürs erste Lernen, gegen halbe Ringe)
    private float lastGoodRingW = 0f;

    private float ringW = 0f;                 // gelernte Ringbreite in Bildpixeln (0 = noch unbekannt)
    private final List<Float> sizeHistory = new ArrayList<>();
    private int[][] ellOffsets = null;        // Merker: Ellipse für ringW schon gebaut?
    private float ellForW = -1f;
    private int ellForWidth = -1;
    private long lastMatchTime = 0;
    private int frameCounter = 0;
    private float lastRingScore = 0f;
    private boolean lastMeFromAi = false;      // kam meine Box in diesem Bild von der KI statt vom Ring?
    private int lastVetoed = 0;                // so viele Gegner-Boxen wurden in diesem Bild verboten
    // Sichere Erkennungen anderer Klassen in diesem Bild (Bildschirmpixel), z. B. Teamkameraden
    private final List<RectF> otherBoxes = new ArrayList<>();
    private final List<Float> otherScores = new ArrayList<>();
    private final List<Integer> otherClasses = new ArrayList<>();

    // ===== Gegner weiterverfolgen, wenn KI und Ring kurz ausfallen (Treffer-Effekte, Busch) =====
    private static final long TRACK_MAX_MS = 400;      // so lange darf ein Gegner nur per Bildvergleich verfolgt werden
    private static final int TRACK_MIN_HITS = 3;       // nur Gegner, die vorher mehrmals sicher erkannt wurden
    private static final float TRACK_MAX_DIFF = 20f;   // mittlere Grauwert-Abweichung, ab der "nicht mehr gefunden" gilt
    private static final int TRACK_PIXEL_CAP = 60;     // höchstens so viel zählt ein einzelnes Pixel
    private static final float TRACK_SIDE_CUT = 0.2f;  // links/rechts so viel der Box weglassen (Hintergrund)
    private static final int TRACK_MAX = 6;            // höchstens so viele Gegner gleichzeitig verfolgen

    private static class Track {
        RectF box;          // in Bildpixeln
        byte[] tpl;         // Graubild-Ausschnitt (jedes 2. Pixel)
        int tw;
        int th;
        float tplOffX;      // Abstand des Ausschnitts zur Box-Ecke
        float tplOffY;
        int hits;
        long lastDetected;
    }

    private List<Track> tracks = new ArrayList<>();
    private int lastTracked = 0;

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

        List<Candidate> others = new ArrayList<>();
        List<Integer> othersClass = new ArrayList<>();
        for (int i = 0; i < numBoxes; i++) {
            float enemyScore = getOut(enemyChannel, i);
            if (enemyScore > enemyMax) {
                enemyMax = enemyScore;
            }
            float bestOther = 0f;
            int bestOtherClass = -1;
            for (int c = 4; c < numChannels; c++) {
                if (c != enemyChannel) {
                    float v = getOut(c, i);
                    if (v > bestOther) {
                        bestOther = v;
                        bestOtherClass = c - 4;
                    }
                }
            }
            // Sichere Erkennung einer ANDEREN Klasse merken (Teamkamerad, ich, Ball ...)
            if (bestOther >= VETO_SCORE && bestOther > enemyScore) {
                RectF ob = decodeBox(i, normalized, scaleToOutX, scaleToOutY,
                        outputWidth, outputHeight, maxBoxWidth, maxBoxHeight);
                if (ob != null) {
                    others.add(new Candidate(ob, bestOther));
                    othersClass.add(bestOtherClass);
                }
            }
            // Schnelltest: die meisten Boxen fliegen hier sofort raus
            if (enemyScore < LOW_THRESHOLD) {
                continue;
            }

            boolean strong = enemyScore >= CONFIDENCE_THRESHOLD;
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

        // Andere Klassen: doppelte Boxen entfernen (sicherste zuerst)
        otherBoxes.clear();
        otherScores.clear();
        otherClasses.clear();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < others.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, (a, b) -> Float.compare(others.get(b).score, others.get(a).score));
        RectF aiMe = null;
        float aiMeScore = 0f;
        for (int idx : order) {
            Candidate c = others.get(idx);
            boolean dup = false;
            for (RectF k : otherBoxes) {
                if (calculateIoU(c.box, k) > IOU_THRESHOLD) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            otherBoxes.add(c.box);
            otherScores.add(c.score);
            otherClasses.add(othersClass.get(idx));
            if (othersClass.get(idx) == ME_CLASS_ID && aiMe == null) {
                aiMe = c.box;
                aiMeScore = c.score;
            }
        }

        // Gegner-Box verbieten, wenn die KI an derselben Stelle sicherer etwas anderes sieht,
        // oder wenn sie nur schwach erkannt ganz oben im Timer-/Punktestand-Streifen liegt
        lastVetoed = 0;
        float topHud = outputHeight * TOP_HUD_RATIO;
        for (int k = kept.size() - 1; k >= 0; k--) {
            Candidate c = kept.get(k);
            boolean weakInHud = c.box.bottom < topHud && c.score < CONFIDENCE_THRESHOLD;
            if (weakInHud || vetoedByOther(c.box, c.score)) {
                kept.remove(k);
                lastVetoed++;
            }
        }

        // "Ich" über den grünen Ring suchen (unabhängig von der KI)
        RectF meBox = null;
        lastMeFromAi = false;
        RectF ring = findMeRing(width, height, pixelStride, rowStride, bufferLimit, nowMs);
        if (ring != null) {
            meBox = boxFromRing(ring, scaleToOutX, scaleToOutY);
        } else if (aiMe != null && aiMeScore >= AI_ME_SCORE) {
            // Ring gerade nicht zu sehen: die KI-"Ich"-Box springt ein (Mitte und Fußhöhe passen laut
            // Messung auf 1-2 % genau zum Ring). Größe bleibt die gelernte Ringgröße.
            meBox = meBoxFromAi(aiMe, scaleToOutX, scaleToOutY, nowMs);
            lastMeFromAi = meBox != null;
        }
        if (meBox != null) {
            // Hält die KI mich selbst für einen Gegner, wird diese Gegner-Box entfernt
            for (int k = kept.size() - 1; k >= 0; k--) {
                if (calculateIoU(meBox, kept.get(k).box) > 0.4f) {
                    kept.remove(k);
                }
            }
        }

        // Alle Gegner-Boxen dieses Bildes (Bildschirmpixel)
        List<RectF> enemyOut = new ArrayList<>();
        List<Boolean> enemySure = new ArrayList<>();   // sicher erkannt? (nur solche Gegner dürfen später verfolgt werden)
        for (Candidate k : kept) {
            enemyOut.add(normalizeBox(k.box, scaleToOutX, scaleToOutY));
            enemySure.add(k.score >= TRACK_SURE_SCORE);
        }

        // Rote Ringe = Gegner. Nur ergänzen, wo die KI selbst keinen Gegner gefunden hat.
        lastRedAdded = 0;
        List<Ring> redRings = RED_RINGS_ENABLED
                ? findRings(true, width, height, pixelStride, rowStride, bufferLimit)
                : new ArrayList<>();
        for (Ring red : redRings) {
            RectF eb = boxFromRing(red.box, scaleToOutX, scaleToOutY);
            boolean known = false;
            for (Candidate k : kept) {
                if (k.box.contains(eb.centerX(), eb.centerY())
                        || eb.contains(k.box.centerX(), k.box.centerY())
                        || calculateIoU(k.box, eb) > 0.1f) {
                    known = true;
                    break;
                }
            }
            if (!known && meBox != null && calculateIoU(meBox, eb) > 0.3f) {
                known = true;
            }
            if (!known) {
                enemyOut.add(normalizeBox(eb, scaleToOutX, scaleToOutY));
                enemySure.add(true);
                lastRedAdded++;
            }
        }

        // Gegner, die KI und Ring gerade verloren haben, per Bildvergleich weiterverfolgen
        updateTracks(enemyOut, enemySure, meBox, width, height, pixelStride, rowStride, bufferLimit,
                scaleToOutX, scaleToOutY, nowMs);

        for (RectF b : enemyOut) {
            results.add(new ColorDetector.Detection(b, ColorDetector.TargetType.ENEMY));
        }

        if (meBox != null) {
            results.add(new ColorDetector.Detection(meBox, ColorDetector.TargetType.PLAYER));
        }

        // Enemy-Boxen dieses Bildes für das nächste Bild merken
        // Nur SICHERE Boxen zählen als "bekannt". Sonst könnte sich eine schwache Fehl-Box
        // (z. B. auf einem Busch) über die niedrige Schwelle selbst ewig am Leben halten.
        boolean anyStrong = false;
        for (Candidate k : kept) {
            if (k.score >= CONFIDENCE_THRESHOLD) {
                if (!anyStrong) {
                    lastBoxes.clear();
                    anyStrong = true;
                }
                lastBoxes.add(k.box);
            }
        }
        if (anyStrong) {
            lastBoxesTime = nowMs;
        }

        long tEnd = System.nanoTime();
        lastDebug = String.format(
                Locale.US,
                "%s Kopie:%d Umr:%d Modell:%d Nach:%d ms | Enemy:%.2f Ring:%d%% Gr:%.0f%s Veto:%d Verf:+%d",
                usingGpu ? "GPU" : "CPU",
                (tCopy - tStart) / 1_000_000,
                (tPre - tCopy) / 1_000_000,
                (tRun - tPre) / 1_000_000,
                (tEnd - tRun) / 1_000_000,
                enemyMax,
                Math.round(lastRingScore * 100f),
                ringW,
                lastMeFromAi ? " KI-Ich" : "",
                lastVetoed,
                lastTracked
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

    // Helles Grün des eigenen Rings. Büsche haben kaum Rot und kaum Blau (R < 40 oder B < 25), Teamkameraden sind blau, Gegner rot
    private static boolean isRingGreen(int r, int g, int b) {
        return r >= 40 && r <= 210 && b >= 25 && g >= 150 && g - r >= 70 && g - b >= 70;
    }

    // Kräftiges Rot des Gegner-Rings. Pinke Blöcke haben deutlich mehr Blau und fliegen raus
    private static boolean isRingRed(int r, int g, int b) {
        return r >= 200 && r - g >= 110 && r - b >= 90 && b <= 130;
    }

    // Ein gefundener Ring: Umrandung in Bildpixeln und Anzahl seiner Pixel
    private static class Ring {
        final RectF box;
        final int pixels;

        Ring(RectF box, int pixels) {
            this.box = box;
            this.pixels = pixels;
        }
    }

    // Ein Ring ist innen größtenteils nicht farbig (dort steht die Figur). Ein Busch oder Block ist innen voll.
    private boolean isHollow(int minX, int minY, int maxX, int maxY, int width, byte[] mask) {
        int w = maxX - minX + 1;
        int h = maxY - minY + 1;
        int ix0 = minX + (int) (w * 0.3f);
        int ix1 = maxX - (int) (w * 0.3f);
        int iy0 = minY + (int) (h * 0.3f);
        int iy1 = maxY - (int) (h * 0.3f);
        int total = 0;
        int filled = 0;
        for (int y = iy0; y <= iy1; y++) {
            for (int x = ix0; x <= ix1; x++) {
                total++;
                if (mask[y * width + x] != 0) {
                    filled++;
                }
            }
        }
        return total > 0 && filled < total * RING_MAX_INNER_FILL;
    }

    // Sucht alle ringförmigen Flecken in einer Farbe (grün = ich, rot = Gegner)
    private List<Ring> findRings(boolean red, int width, int height, int pixelStride, int rowStride, int limit) {
        List<Ring> rings = new ArrayList<>();
        int n = width * height;
        if (ringMask.length < n) {
            ringMask = new byte[n];
            ringStack = new int[n];
        }

        int x0 = (int) (width * (red ? RED_X_MIN : RING_X_MIN));
        int x1 = (int) (width * (red ? RED_X_MAX : RING_X_MAX));
        int y0 = (int) (height * RING_Y_MIN);
        int y1 = (int) (height * RING_Y_MAX);
        // Bereich unten rechts mit den Knöpfen (beim Rot größer wegen des roten Zielknopfs)
        int uiX = (int) (width * (red ? RED_UI_X : UI_X));
        int uiY = (int) (height * (red ? RED_UI_Y : UI_Y));
        int leftX = (int) (width * RED_LEFT_X);
        int btnX = (int) (width * RED_BTN_X);
        int btnY = (int) (height * RED_BTN_Y);
        int bottomY = (int) (height * RED_BOTTOM_Y);

        // Schritt 1: alle Pixel in der gesuchten Farbe markieren
        for (int y = y0; y < y1; y++) {
            int rowStart = y * rowStride;
            int base = y * width;
            for (int x = x0; x < x1; x++) {
                byte m = 0;
                boolean skip = (x > uiX && y > uiY)
                        || (red && x < leftX && y > bottomY)
                        || (red && x > btnX && y > btnY);
                if (!skip) {
                    int idx = rowStart + x * pixelStride;
                    if (idx + 2 < limit) {
                        int r = frameBytes[idx] & 0xFF;
                        int g = frameBytes[idx + 1] & 0xFF;
                        int b = frameBytes[idx + 2] & 0xFF;
                        if (red ? isRingRed(r, g, b) : isRingGreen(r, g, b)) {
                            m = 1;
                        }
                    }
                }
                ringMask[base + x] = m;
            }
        }

        float minW = width * RING_MIN_W;
        float maxW = width * RING_MAX_W;
        float minH = height * RING_MIN_H;

        // Schritt 2: zusammenhängende Flecken finden
        List<int[]> comps = new ArrayList<>();
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

                if (count >= 3) {
                    comps.add(new int[]{minX, minY, maxX, maxY, count});
                }
            }
        }

        // Schritt 4: nur ringförmige Flecken behalten
        for (int[] c : comps) {
            int minX = c[0], minY = c[1], maxX = c[2], maxY = c[3], count = c[4];
            float w = maxX - minX + 1;
            float h = maxY - minY + 1;
            float aspect = w / h;
            // Mein Ring ist nie ganz am Bildrand (rechts stehen grüne Würfel/Deko)
            if (!red) {
                float cxRel = (minX + maxX) * 0.5f / width;
                if (cxRel < GREEN_CENTER_X_MIN || cxRel > GREEN_CENTER_X_MAX) {
                    continue;
                }
            }
            if (count < RING_MIN_PIXELS || w < minW || w > maxW || h < minH
                    || aspect < RING_MIN_ASPECT || aspect > RING_MAX_ASPECT) {
                continue;
            }
            // Gegner-Ringe sind genauso groß wie meiner und leicht oval. Rote Deko hat andere Formen.
            if (red) {
                float cxRel = (minX + maxX) * 0.5f / width;
                if (w < width * RED_MIN_W || w > width * RED_MAX_W || h < height * RED_MIN_H
                        || aspect < RED_MIN_ASPECT || aspect > RED_MAX_ASPECT
                        || cxRel < RED_CENTER_X_MIN || cxRel > RED_CENTER_X_MAX) {
                    continue;
                }
            }
            // Ring muss innen hohl sein, sonst ist es ein Busch, Block o. Ä.
            if (!isHollow(minX, minY, maxX, maxY, width, ringMask)) {
                continue;
            }
            rings.add(new Ring(new RectF(minX, minY, maxX + 1, maxY + 1), count));
        }
        return rings;
    }

    // ===================== MICH FINDEN =====================

    // Prüfpunkte einer Ellipse (als Abstand im Bildpuffer), für eine bestimmte Ringgröße
    private static class Ell {
        int[] band;   // auf dem grünen Ringband
        int[] inner;  // innen (dort steht die Figur, muss NICHT grün sein)
        int[] outer;  // knapp außerhalb (muss NICHT grün sein, sonst ist es eine grüne Fläche)
        int mx;       // so weit reicht die Ellipse nach links/rechts
        int my;       // so weit reicht die Ellipse nach oben/unten
    }

    private static Ell buildEll(float w, int width) {
        float rx = w * 0.5f;
        float ry = w / RING_ASPECT * 0.5f;
        Ell e = new Ell();
        e.band = ellipsePoints(rx, ry, new float[]{0.78f, 0.90f}, 40, width);
        e.inner = ellipsePoints(rx, ry, new float[]{0f, 0.3f, 0.5f}, 16, width);
        e.outer = ellipsePoints(rx, ry, new float[]{1.3f}, 32, width);
        e.mx = (int) Math.ceil(rx * 1.3f) + 1;
        e.my = (int) Math.ceil(ry * 1.3f) + 1;
        return e;
    }

    private static int[] ellipsePoints(float rx, float ry, float[] factors, int n, int width) {
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        for (float f : factors) {
            for (int k = 0; k < n; k++) {
                double t = 2.0 * Math.PI * k / n;
                int dx = Math.round((float) (f * rx * Math.cos(t)));
                int dy = Math.round((float) (f * ry * Math.sin(t)));
                set.add(dy * width + dx);
            }
        }
        int[] out = new int[set.size()];
        int i = 0;
        for (Integer v : set) {
            out[i++] = v;
        }
        return out;
    }

    // Ellipse für die gelernte Ringgröße (wird nur neu gebaut, wenn sich die Größe ändert)
    private Ell ellFor(int width) {
        if (ellOffsets == null || Math.abs(ellForW - ringW) > 0.01f || ellForWidth != width) {
            ellCache = buildEll(ringW, width);
            ellOffsets = new int[0][];
            ellForW = ringW;
            ellForWidth = width;
        }
        return ellCache;
    }

    private Ell ellCache = null;
    private float bootW = 0f;           // beim ersten Lernen gemessene Größe (Grenze für spätere Änderungen)
    private int[] greenSum = new int[0]; // Summen-Tabelle der grünen Pixel (zum schnellen Überspringen)

    // Markiert alle hellgrünen Ring-Pixel im Suchbereich und baut die Summen-Tabelle
    private void buildGreenMask(int width, int height, int pixelStride, int rowStride, int limit) {
        int n = width * height;
        if (greenMask.length != n) {
            greenMask = new byte[n];
        }
        int x0 = (int) (width * 0.03f);
        int x1 = (int) (width * 0.97f);
        int y0 = (int) (height * RING_Y_MIN);
        int y1 = (int) (height * RING_Y_MAX);
        int uiX = (int) (width * UI_X);
        int uiY = (int) (height * UI_Y);
        for (int y = y0; y < y1; y++) {
            int rowStart = y * rowStride;
            int base = y * width;
            for (int x = x0; x < x1; x++) {
                byte m = 0;
                if (!(x > uiX && y > uiY)) {
                    int idx = rowStart + x * pixelStride;
                    if (idx + 2 < limit && isRingGreen(frameBytes[idx] & 0xFF,
                            frameBytes[idx + 1] & 0xFF, frameBytes[idx + 2] & 0xFF)) {
                        m = 1;
                    }
                }
                greenMask[base + x] = m;
            }
        }
        // Summen-Tabelle: Anzahl grüner Pixel im Rechteck (0,0)-(x,y)
        int sw = width + 1;
        if (greenSum.length != sw * (height + 1)) {
            greenSum = new int[sw * (height + 1)];
        }
        for (int y = 0; y < height; y++) {
            int rowSum = 0;
            int base = y * width;
            int s = (y + 1) * sw;
            for (int x = 0; x < width; x++) {
                rowSum += greenMask[base + x];
                greenSum[s + x + 1] = greenSum[s - sw + x + 1] + rowSum;
            }
        }
    }

    private int greenCount(int x0, int y0, int x1, int y1, int width) {
        int sw = width + 1;
        return greenSum[(y1 + 1) * sw + x1 + 1] - greenSum[y0 * sw + x1 + 1]
                - greenSum[(y1 + 1) * sw + x0] + greenSum[y0 * sw + x0];
    }

    // Wie gut passt die Ellipse an dieser Stelle? (Ringband grün, innen und außen nicht)
    private float ellScore(Ell e, int c) {
        int b = 0;
        for (int d : e.band) {
            if (greenMask[c + d] != 0) {
                b++;
            }
        }
        float bs = (float) b / e.band.length;
        if (bs < 0.25f) {
            return -99f;
        }
        int in = 0;
        for (int d : e.inner) {
            if (greenMask[c + d] != 0) {
                in++;
            }
        }
        int out = 0;
        for (int d : e.outer) {
            if (greenMask[c + d] != 0) {
                out++;
            }
        }
        return bs - 0.6f * in / e.inner.length - 0.6f * out / e.outer.length;
    }

    // Sucht die beste Stelle für die Ellipse in einem Rechteck. Liefert {x, y, Wert} oder null.
    // distPenalty > 0: Stellen weit weg von (px, py) werden abgewertet.
    private float[] searchEllipse(Ell e, int x0, int y0, int x1, int y1, int step, int width, int height,
                                  float px, float py, float distPenalty) {
        x0 = Math.max(x0, e.mx);
        y0 = Math.max(y0, e.my);
        x1 = Math.min(x1, width - 1 - e.mx);
        y1 = Math.min(y1, height - 1 - e.my);
        int uiX = (int) (width * UI_X);
        int uiY = (int) (height * UI_Y);
        int need = (int) (e.band.length * 0.25f);
        float bestVal = -99f;
        float[] best = null;
        for (int y = y0; y <= y1; y += step) {
            for (int x = x0; x <= x1; x += step) {
                if (x > uiX && y > uiY) {
                    continue;
                }
                // Zu wenig Grün in der Umgebung -> kann kein Ring sein (spart viel Rechenzeit)
                if (greenCount(x - e.mx, y - e.my, x + e.mx, y + e.my, width) < need) {
                    continue;
                }
                float s = ellScore(e, y * width + x);
                if (s < -50f) {
                    continue;
                }
                float v = s;
                if (distPenalty > 0f) {
                    v -= distPenalty * (float) Math.hypot(x - px, y - py);
                }
                if (v > bestVal) {
                    bestVal = v;
                    best = new float[]{x, y, s};
                }
            }
        }
        return best;
    }

    private float medianSize() {
        List<Float> sorted = new ArrayList<>(sizeHistory);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    // Misst die Ringbreite DIREKT: auf Höhe der Ringmitte vom äußersten grünen Pixel links bis
    // zum äußersten rechts (die Seiten des Rings sind fast immer sichtbar, oben steht die Figur).
    // Messung an deinen Aufnahmen: sehr genau (Median 35 px, die Hälfte aller Werte 34-36 px).
    // Nach SIZE_LOCK_SAMPLES Messungen wird die Größe FESTGEHALTEN (bis zur nächsten Karte).
    private void updateSize(float[] best, int width, int height) {
        if (sizeLocked) {
            return;
        }
        float w0 = ringW;
        int cx = (int) best[0];
        int cy = (int) best[1];
        int x0 = Math.max(0, (int) (cx - 0.8f * w0));
        int x1 = Math.min(width - 1, (int) (cx + 0.8f * w0));
        int measured = 0;
        for (int y = Math.max(0, cy - 2); y <= Math.min(height - 1, cy + 2); y++) {
            int left = -1;
            int right = -1;
            int base = y * width;
            for (int x = x0; x <= x1; x++) {
                if (greenMask[base + x] != 0) {
                    if (left < 0) {
                        left = x;
                    }
                    right = x;
                }
            }
            // Beide Seiten des Rings müssen zu sehen sein
            if (left >= 0 && left < cx - 0.3f * w0 && right > cx + 0.3f * w0) {
                measured = Math.max(measured, right - left + 1);
            }
        }
        if (measured <= 0 || measured < bootW * 0.75f || measured > bootW * 1.3f) {
            return;
        }
        sizeHistory.add((float) measured);
        if (sizeHistory.size() >= SIZE_LOCK_SAMPLES) {
            ringW = medianSize();
            sizeLocked = true;
        }
    }

    private static final int SIZE_LOCK_SAMPLES = 9;   // so viele direkte Messungen, dann Größe fest
    private boolean sizeLocked = false;

    // Größe noch unbekannt: Ring wie bisher über zusammenhängende Flecken finden
    // und dabei die Größe lernen (erst nach mehreren guten Messungen)
    private RectF bootstrapRing(int width, int height, int pixelStride, int rowStride, int limit, long nowMs) {
        boolean hasLast = lastRingCx >= 0f && nowMs - lastRingTime <= RING_KEEP_MS;
        RectF comp = pickMeRing(findRings(false, width, height, pixelStride, rowStride, limit),
                hasLast, width, height);
        lastRingScore = 0f;
        if (comp == null) {
            return null;
        }
        lastGoodRingW = lastGoodRingW <= 0f ? comp.width() : lastGoodRingW * 0.8f + comp.width() * 0.2f;
        lastRingCx = comp.centerX();
        lastRingCy = comp.centerY();
        lastRingTime = nowMs;

        buildGreenMask(width, height, pixelStride, rowStride, limit);
        float bestW = 0f;
        float bestS = -99f;
        int r = Math.max(3, (int) (comp.width() * 0.3f));
        for (int i = 0; i <= 8; i++) {
            float w = comp.width() * (0.85f + 0.05f * i);
            Ell e = buildEll(w, width);
            float[] res = searchEllipse(e, (int) comp.centerX() - r, (int) comp.centerY() - r,
                    (int) comp.centerX() + r, (int) comp.centerY() + r, 1, width, height, 0f, 0f, 0f);
            if (res != null && res[2] > bestS) {
                bestS = res[2];
                bestW = w;
            }
        }
        lastRingScore = Math.max(0f, bestS);
        if (bestS >= RING_SCORE_BOOT) {
            sizeHistory.add(bestW);
            lastMatchTime = nowMs;
            if (sizeHistory.size() >= BOOT_SAMPLES) {
                ringW = medianSize();
                bootW = ringW;
                sizeHistory.clear();   // ab jetzt zählen nur die direkten Breiten-Messungen
            }
        }
        return comp;
    }

    // Findet meinen Ring. Liefert die Ring-Umrandung (Bildpixel) oder null.
    private RectF findMeRing(int width, int height, int pixelStride, int rowStride, int limit, long nowMs) {
        frameCounter++;
        // Lange nichts gefunden (neue Runde, andere Karte): Größe neu lernen
        if (ringW > 0f && nowMs - lastMatchTime > RING_RESET_MS) {
            ringW = 0f;
            bootW = 0f;
            sizeLocked = false;
            sizeHistory.clear();
            ellOffsets = null;
            lastGoodRingW = 0f;
        }
        if (ringW <= 0f) {
            return bootstrapRing(width, height, pixelStride, rowStride, limit, nowMs);
        }

        buildGreenMask(width, height, pixelStride, rowStride, limit);
        Ell e = ellFor(width);

        // 1) Zuerst nahe meiner letzten Position suchen (kleines Fenster, so weit bewegt man sich kaum).
        //    Mein Lebensbalken liegt weiter oben und damit außerhalb des Fensters.
        float[] best = null;
        boolean recent = lastRingCx >= 0f && nowMs - lastMatchTime <= RING_LOCAL_MS;
        if (recent) {
            int r = (int) Math.max(8f, ringW * 0.5f);
            float[] local = searchEllipse(e, (int) lastRingCx - r, (int) lastRingCy - r,
                    (int) lastRingCx + r, (int) lastRingCy + r, 1, width, height,
                    lastRingCx, lastRingCy, 0.25f / ringW);
            if (local != null && local[2] >= RING_SCORE_LOCAL) {
                best = local;
            }
        }

        // 2) Nichts gefunden (oder jedes 4. Bild zur Kontrolle): im ganzen Bild suchen, aber strenger
        if (best == null || frameCounter % GLOBAL_CHECK_EVERY == 0) {
            int step = best == null ? 2 : 3;
            float[] global = searchEllipse(e, (int) (width * RING_X_MIN), (int) (height * RING_Y_MIN),
                    (int) (width * RING_X_MAX), (int) (height * RING_Y_MAX), step, width, height, 0f, 0f, 0f);
            if (global != null) {
                // fein nachsuchen
                global = searchEllipse(e, (int) global[0] - step, (int) global[1] - step,
                        (int) global[0] + step, (int) global[1] + step, 1, width, height, 0f, 0f, 0f);
            }
            if (global != null && global[2] >= RING_SCORE_GLOBAL) {
                if (best == null) {
                    best = global;
                } else if (Math.hypot(global[0] - best[0], global[1] - best[1]) > ringW
                        && global[2] > best[2] + 0.2f) {
                    // Woanders passt es VIEL besser: die lokale Suche hing an etwas Falschem
                    best = global;
                }
            }
        }

        if (best == null) {
            lastRingScore = 0f;
            return null;
        }
        lastRingScore = best[2];
        if (best[2] >= RING_SCORE_SIZE) {
            updateSize(best, width, height);
        }
        lastRingCx = best[0];
        lastRingCy = best[1];
        lastMatchTime = nowMs;
        lastRingTime = nowMs;
        float w = ringW * RING_BOX_SCALE;
        float h = w / RING_ASPECT;
        return new RectF(best[0] - w * 0.5f, best[1] - h * 0.5f, best[0] + w * 0.5f, best[1] + h * 0.5f);
    }

    // Wählt aus den gefundenen grünen Flecken meinen Ring aus (nur fürs erste Lernen der Größe)
    private RectF pickMeRing(List<Ring> rings, boolean hasLast, int width, int height) {
        RectF best = null;
        float bestScore = 0f;
        for (Ring ring : rings) {
            // Nur ein Teil des Rings sichtbar (Explosion, Effekt)? Dann lieber nicht nehmen
            if (lastGoodRingW > 0f && ring.box.width() < lastGoodRingW * 0.75f) {
                continue;
            }
            float cx = ring.box.centerX();
            float cy = ring.box.centerY();
            boolean nearLast = hasLast && Math.hypot(cx - lastRingCx, cy - lastRingCy) < ring.box.width();
            // Große Ringe nur dort, wo ich eben noch war
            if (!nearLast && ring.box.width() > width * RING_MAX_W_NEW) {
                continue;
            }
            float score = ring.pixels;
            if (nearLast) {
                score *= 3f;
            }
            float dxc = cx / width - 0.5f;
            float dyc = cy / height - 0.55f;
            score *= 1.5f - Math.min(1f, (float) Math.hypot(dxc, dyc));
            if (score > bestScore) {
                bestScore = score;
                best = ring.box;
            }
        }
        return best;
    }

    // Gibt es eine sichere Erkennung einer anderen Klasse, die genau hier liegt und sicherer ist?
    // (score = Gegner-Wert der Box; 1 = "immer prüfen", z. B. für verfolgte Boxen)
    private boolean vetoedByOther(RectF box, float score) {
        for (int i = 0; i < otherBoxes.size(); i++) {
            float os = otherScores.get(i);
            if (os < score && score < 1f) {
                continue;
            }
            float limit = otherClasses.get(i) == ME_CLASS_ID ? VETO_IOU_ME : VETO_IOU;
            if (calculateIoU(box, otherBoxes.get(i)) > limit) {
                return true;
            }
        }
        return false;
    }

    // Baut meine Box aus der KI-"Ich"-Box (Bildschirmpixel). Nur, wenn sie zu meiner letzten Position passt.
    private RectF meBoxFromAi(RectF ai, float scaleX, float scaleY, long nowMs) {
        float cxImg = ai.centerX() / scaleX;
        float bottomImg = ai.bottom / scaleY;
        float w = ringW > 0f ? ringW * RING_BOX_SCALE : ai.width() / scaleX;
        float h = w / RING_ASPECT;
        float ringCy = bottomImg - h * 0.5f;
        // Zu weit weg von meiner letzten Position? Dann lieber nichts (vermutlich eine Verwechslung)
        if (lastRingCx >= 0f && nowMs - lastMatchTime <= RING_LOCAL_MS && ringW > 0f
                && Math.hypot(cxImg - lastRingCx, ringCy - lastRingCy) > ringW * AI_ME_MAX_JUMP) {
            return null;
        }
        // Damit die Ring-Suche im nächsten Bild hier weitersucht
        lastRingCx = cxImg;
        lastRingCy = ringCy;
        if (ringW > 0f) {
            lastMatchTime = nowMs;
        }
        RectF ring = new RectF(cxImg - w * 0.5f, ringCy - h * 0.5f, cxImg + w * 0.5f, ringCy + h * 0.5f);
        return boxFromRing(ring, scaleX, scaleY);
    }

    // Gegner-Box auf die feste Ringgröße bringen: Mitte und Fußhöhe kommen von der KI bzw. dem roten Ring,
    // Breite und Höhe vom gelernten Ring. So haben alle Boxen immer dieselbe ruhige Größe.
    private RectF normalizeBox(RectF b, float scaleX, float scaleY) {
        if (ringW <= 0f) {
            return b;
        }
        float w = ringW * RING_BOX_SCALE * scaleX;
        float h = ringW * RING_BOX_SCALE * ME_BOX_HEIGHT * scaleY;
        float cx = b.centerX();
        return new RectF(cx - w * 0.5f, Math.max(0f, b.bottom - h), cx + w * 0.5f, b.bottom);
    }

    // Baut aus einem Ring (Bildpixel) die Box der Figur darüber (Bildschirmpixel)
    private static RectF boxFromRing(RectF ring, float scaleX, float scaleY) {
        float w = ring.width();
        float cx = ring.centerX();
        float left = (cx - w * 0.5f) * scaleX;
        float right = (cx + w * 0.5f) * scaleX;
        float bottom = ring.bottom * scaleY;
        float top = (ring.bottom - w * ME_BOX_HEIGHT) * scaleY;
        return new RectF(left, Math.max(0f, top), right, bottom);
    }

    private int gray(int x, int y, int pixelStride, int rowStride, int limit) {
        int idx = y * rowStride + x * pixelStride;
        if (idx < 0 || idx + 2 >= limit) {
            return 0;
        }
        return ((frameBytes[idx] & 0xFF) + 2 * (frameBytes[idx + 1] & 0xFF) + (frameBytes[idx + 2] & 0xFF)) >> 2;
    }

    // Merkt sich das aktuelle Aussehen des Gegners (jedes 2. Pixel als Graubild)
    private void captureTemplate(Track t, int width, int height, int pixelStride, int rowStride, int limit) {
        // Nur die innere Mitte der Box merken (die Figur selbst). Der Rand ist meist Boden/Busch
        // und würde sonst auch dann noch passen, wenn der Gegner längst weg ist.
        float bw = t.box.width();
        int x0 = Math.max(0, (int) (t.box.left + bw * TRACK_SIDE_CUT));
        int y0 = Math.max(0, (int) t.box.top);
        int x1 = Math.min(width - 1, (int) (t.box.right - bw * TRACK_SIDE_CUT));
        int y1 = Math.min(height - 1, (int) t.box.bottom);
        int tw = (x1 - x0) / 2;
        int th = (y1 - y0) / 2;
        if (tw < 4 || th < 4) {
            t.tpl = null;
            return;
        }
        byte[] tpl = new byte[tw * th];
        for (int j = 0; j < th; j++) {
            for (int i = 0; i < tw; i++) {
                tpl[j * tw + i] = (byte) gray(x0 + 2 * i, y0 + 2 * j, pixelStride, rowStride, limit);
            }
        }
        t.tpl = tpl;
        t.tw = tw;
        t.th = th;
        t.tplOffX = x0 - t.box.left;   // Lage des Ausschnitts innerhalb der Box merken
        t.tplOffY = y0 - t.box.top;
    }

    // Abweichung des gemerkten Aussehens an Position (x0, y0); bricht ab, sobald es schlechter als limitSad wird
    private long sad(Track t, int x0, int y0, long limitSad, int pixelStride, int rowStride, int limit) {
        long sum = 0;
        for (int j = 0; j < t.th; j++) {
            int y = y0 + 2 * j;
            int row = j * t.tw;
            for (int i = 0; i < t.tw; i++) {
                int d = gray(x0 + 2 * i, y, pixelStride, rowStride, limit) - (t.tpl[row + i] & 0xFF);
                if (d < 0) {
                    d = -d;
                }
                // Gedeckelt: verdeckte Stellen (Treffer-Blitz, Schadenszahl) zählen nicht übermäßig
                sum += Math.min(d, TRACK_PIXEL_CAP);
            }
            if (sum >= limitSad) {
                return sum;
            }
        }
        return sum;
    }

    // Sucht den Gegner in der Nähe seiner letzten Position. Liefert die neue Box oder null.
    private RectF findByTemplate(Track t, int width, int height, int pixelStride, int rowStride, int limit) {
        int bw = 2 * t.tw;
        int bh = 2 * t.th;
        int sx = (int) (t.box.left + t.tplOffX);
        int sy = (int) (t.box.top + t.tplOffY);
        int range = Math.max(6, (int) (t.box.width() * 0.5f));

        long best = Long.MAX_VALUE;
        int bestX = sx;
        int bestY = sy;
        // Grob suchen (2er-Schritte) ...
        for (int dy = -range; dy <= range; dy += 2) {
            for (int dx = -range; dx <= range; dx += 2) {
                int x = sx + dx;
                int y = sy + dy;
                if (x < 0 || y < 0 || x + bw >= width || y + bh >= height) {
                    continue;
                }
                long v = sad(t, x, y, best, pixelStride, rowStride, limit);
                if (v < best) {
                    best = v;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        // ... dann fein um die beste Stelle
        int cx = bestX;
        int cy = bestY;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if ((dx == 0 && dy == 0) || x < 0 || y < 0 || x + bw >= width || y + bh >= height) {
                    continue;
                }
                long v = sad(t, x, y, best, pixelStride, rowStride, limit);
                if (v < best) {
                    best = v;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        if (best == Long.MAX_VALUE) {
            return null;
        }
        float meanDiff = (float) best / (t.tw * t.th);
        if (meanDiff > TRACK_MAX_DIFF) {
            return null; // nicht mehr gefunden (z. B. unsichtbar im Busch)
        }
        float dx = bestX - sx;
        float dy = bestY - sy;
        return new RectF(t.box.left + dx, t.box.top + dy, t.box.right + dx, t.box.bottom + dy);
    }

    // Ordnet die Gegner dieses Bildes den bekannten Gegnern zu und verfolgt verlorene weiter
    private void updateTracks(List<RectF> enemyOut, List<Boolean> enemySure, RectF meBox, int width, int height,
                              int pixelStride, int rowStride, int limit,
                              float scaleX, float scaleY, long nowMs) {
        List<Track> next = new ArrayList<>();
        boolean[] used = new boolean[tracks.size()];
        lastTracked = 0;

        // 1) Erkannte Gegner: passenden alten Eintrag suchen, Aussehen neu merken
        for (int n = 0; n < enemyOut.size(); n++) {
            RectF out = enemyOut.get(n);
            boolean sure = enemySure.get(n);
            RectF img = new RectF(out.left / scaleX, out.top / scaleY, out.right / scaleX, out.bottom / scaleY);
            int best = -1;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < tracks.size(); i++) {
                if (used[i]) {
                    continue;
                }
                RectF tb = tracks.get(i).box;
                float dist = (float) Math.hypot(img.centerX() - tb.centerX(), img.centerY() - tb.centerY());
                if (dist < Math.max(tb.width(), tb.height()) * 0.6f && dist < bestDist) {
                    best = i;
                    bestDist = dist;
                }
            }
            Track t;
            if (best >= 0) {
                used[best] = true;
                t = tracks.get(best);
                if (sure) {
                    t.hits++;
                }
            } else {
                t = new Track();
                t.hits = sure ? 1 : 0;
            }
            t.box = img;
            t.lastDetected = nowMs;
            captureTemplate(t, width, height, pixelStride, rowStride, limit);
            next.add(t);
        }

        // 2) Verlorene, aber vorher sichere Gegner per Bildvergleich suchen (nur wenn eingeschaltet)
        for (int i = 0; i < (TRACKING_ENABLED ? tracks.size() : 0); i++) {
            if (used[i]) {
                continue;
            }
            Track t = tracks.get(i);
            if (t.tpl == null || t.hits < TRACK_MIN_HITS || nowMs - t.lastDetected > TRACK_MAX_MS) {
                continue;
            }
            RectF moved = findByTemplate(t, width, height, pixelStride, rowStride, limit);
            if (moved == null) {
                continue;
            }
            RectF out = new RectF(moved.left * scaleX, moved.top * scaleY, moved.right * scaleX, moved.bottom * scaleY);
            // Nicht auf mich, nicht auf etwas, das die KI sicher als etwas anderes erkennt
            // (Teamkamerad, Ball ...), und nicht doppelt auf einen schon erkannten Gegner legen
            boolean clash = (meBox != null && calculateIoU(meBox, out) > 0.3f) || vetoedByOther(out, 1f);
            for (RectF e : enemyOut) {
                if (clash) {
                    break;
                }
                if (calculateIoU(e, out) > 0.3f) {
                    clash = true;
                }
            }
            if (clash) {
                continue;
            }
            t.box = moved; // Aussehen NICHT neu merken, sonst wandert die Box langsam weg
            next.add(t);
            enemyOut.add(out);
            lastTracked++;
        }

        if (next.size() > TRACK_MAX) {
            next = new ArrayList<>(next.subList(0, TRACK_MAX));
        }
        tracks = next;
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