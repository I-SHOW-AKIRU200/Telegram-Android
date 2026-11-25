package org.telegram.messenger.translation;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.net.URI;

public final class VoIPMicDebug {
    public static final int SAMPLE_RATE = 16000;
    private static final int CHUNK_SAMPLES = 1600;
    private static final int SAMPLE_COUNT = SAMPLE_RATE * 8;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MicLevelListener listener;
    private volatile boolean running;
    private volatile boolean sending;
    private volatile boolean cancelSending;
    private volatile AudioRecord vcRecorder;
    private volatile AudioRecord micRecorder;
    private volatile AudioRecord sendingRecorder;
    private volatile WebSocket activeSocket;
    private Thread vcThread;
    private Thread micThread;
    private volatile SourceStats vcStats = new SourceStats();
    private volatile SourceStats micStats = new SourceStats();

    public interface MicLevelListener {
        void onStatus(String status);
    }

    private static final class SourceStats {
        volatile double rms;
        volatile double peak;
        volatile String state = "waiting";
    }

    public VoIPMicDebug(MicLevelListener listener) {
        this.listener = listener;
    }

    public synchronized void start(Context context) {
        if (sending) {
            report("Backend test is in progress");
            return;
        }
        if (running) {
            stop();
            return;
        }
        vcStats = new SourceStats();
        micStats = new SourceStats();
        running = true;
        vcRecorder = openRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION");
        micRecorder = openRecorder(MediaRecorder.AudioSource.MIC, "MIC");
        vcThread = recordThread(vcRecorder, "VOICE_COMMUNICATION", vcStats);
        micThread = recordThread(micRecorder, "MIC", micStats);
        vcThread.start();
        micThread.start();
        reportMetrics();
    }

    public synchronized void stop() {
        cancelSending = true;
        stopRecorders();
        AudioRecord recorder = sendingRecorder;
        stopRecorder(recorder);
        WebSocket socket = activeSocket;
        if (socket != null) {
            socket.disconnect();
        }
        report(recorder == null
                ? "Mic debug stopped; monitor AudioRecords released"
                : "Mic debug stopped; upload canceled, releasing capture");
    }

    public void sendEightSeconds(Context context, String backendUrl, String secret) {
        synchronized (this) {
            if (sending) {
                report("Backend test is already running");
                return;
            }
            sending = true;
            cancelSending = false;
        }
        new Thread(() -> runBackendTest(context, backendUrl, secret), "VoIPMicBackendTest").start();
    }

    private void runBackendTest(Context context, String backendUrl, String secret) {
        WebSocket socket = null;
        AudioRecord recorder = null;
        try {
            stopRecorders();
            int source = selectSource();
            String sourceName = source == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    ? "VOICE_COMMUNICATION" : "MIC";
            report("Connecting; selected " + sourceName + " (higher observed RMS)");

            LinkedBlockingQueue<String> incoming = new LinkedBlockingQueue<>();
            String streamUrl = toStreamUrl(backendUrl);
            socket = new WebSocketFactory()
                    .setConnectionTimeout(15_000)
                    .createSocket(streamUrl)
                    .setHeader("X-App-Secret", secret)
                    .addListener(new WebSocketAdapter() {
                        @Override
                        public void onTextMessage(WebSocket ws, String text) {
                            incoming.offer(text);
                        }

                        @Override
                        public void onError(WebSocket ws, WebSocketException exception) {
                            incoming.offer(errorJson("websocket_error", describe(exception, secret)));
                        }

                        @Override
                        public void onDisconnected(WebSocket ws,
                                                   com.neovisionaries.ws.client.WebSocketFrame serverCloseFrame,
                                                   com.neovisionaries.ws.client.WebSocketFrame clientCloseFrame,
                                                   boolean closedByServer) {
                            incoming.offer(errorJson("disconnected", "WebSocket disconnected"));
                        }
                    })
                    .connect();
            activeSocket = socket;
            socket.sendText(new JSONObject()
                    .put("type", "config")
                    .put("from", "en")
                    .put("to", "hi")
                    .put("voice", false)
                    .toString());

            try {
                recorder = openStreamingRecorder(source, sourceName);
            } catch (RuntimeException firstError) {
                source = source == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        ? MediaRecorder.AudioSource.MIC : MediaRecorder.AudioSource.VOICE_COMMUNICATION;
                sourceName = source == MediaRecorder.AudioSource.VOICE_COMMUNICATION
                        ? "VOICE_COMMUNICATION" : "MIC";
                report(sourceName + " fallback after primary recorder error");
                recorder = openStreamingRecorder(source, sourceName);
            }
            sendingRecorder = recorder;
            report("Recording and streaming 8 seconds from " + sourceName);

            short[] samples = new short[CHUNK_SAMPLES];
            int sentSamples = 0;
            while (sentSamples < SAMPLE_COUNT) {
                if (cancelSending) {
                    return;
                }
                int wanted = Math.min(samples.length, SAMPLE_COUNT - sentSamples);
                int read = recorder.read(samples, 0, wanted);
                if (read <= 0) {
                    throw new IllegalStateException("AudioRecord read failed: " + read);
                }
                if (cancelSending) {
                    return;
                }
                byte[] pcm = toPcm16Le(samples, read);
                socket.sendBinary(pcm);
                sentSamples += read;
                report("Streaming " + (sentSamples / SAMPLE_RATE) + " / 8 seconds from " + sourceName);
            }
            recorder.stop();
            recorder.release();
            recorder = null;
            socket.sendText("{\"type\":\"commit\"}");
            awaitCaption(incoming, secret);
        } catch (Exception error) {
            report(cancelSending
                    ? "Backend test stopped; recorder released"
                    : "Backend test failed: " + describe(error, secret));
        } finally {
            if (recorder != null) {
                if (sendingRecorder == recorder) {
                    sendingRecorder = null;
                }
                releaseRecorder(recorder);
            }
            if (socket != null) {
                try {
                    socket.sendText("{\"type\":\"close\"}");
                    socket.disconnect();
                } catch (RuntimeException error) {
                    report("Backend WebSocket close failed: " + describe(error, secret));
                }
                if (activeSocket == socket) {
                    activeSocket = null;
                }
            }
            synchronized (this) {
                sending = false;
            }
        }
    }

    private void awaitCaption(LinkedBlockingQueue<String> incoming, String secret) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            if (cancelSending) {
                report("Backend test stopped; recorder released");
                return;
            }
            String raw = incoming.poll(1, TimeUnit.SECONDS);
            if (raw == null) {
                continue;
            }
            JSONObject message = new JSONObject(raw);
            String type = message.optString("type");
            if ("partial".equals(type)) {
                report("Transcript: " + message.optString("text"));
            } else if ("caption".equals(type)) {
                report("Original: " + message.optString("original") +
                        "\nTranslation: " + message.optString("translated"));
                return;
            } else if ("error".equals(type)) {
                report("Backend error " + message.optString("code") +
                        ": " + message.optString("message"));
                return;
            }
        }
        throw new IllegalStateException("Timed out waiting for backend transcript and translation");
    }

    private AudioRecord openRecorder(int source, String name) {
        int minimumBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimumBytes <= 0) {
            setState(name, "buffer error " + minimumBytes);
            return null;
        }
        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimumBytes, CHUNK_SAMPLES * 2));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("initialization failed");
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("start failed");
            }
            setState(name, "recording");
            return recorder;
        } catch (RuntimeException error) {
            if (recorder != null) {
                releaseRecorder(recorder);
            }
            setState(name, error.getClass().getSimpleName() + ": " + error.getMessage());
            return null;
        }
    }

    private AudioRecord openStreamingRecorder(int source, String name) {
        int minimumBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimumBytes <= 0) {
            throw new IllegalStateException("AudioRecord buffer size error: " + minimumBytes);
        }
        AudioRecord recorder = new AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimumBytes, CHUNK_SAMPLES * 2)
        );
        try {
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException(name + " AudioRecord initialization failed");
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException(name + " AudioRecord did not start");
            }
            return recorder;
        } catch (RuntimeException error) {
            releaseRecorder(recorder);
            throw error;
        }
    }

    private Thread recordThread(AudioRecord recorder, String name, SourceStats stats) {
        return new Thread(() -> {
            if (recorder == null) {
                return;
            }
            short[] samples = new short[CHUNK_SAMPLES];
            try {
                while (running) {
                    int read = recorder.read(samples, 0, samples.length);
                    if (!running) {
                        break;
                    }
                    if (read <= 0) {
                        setState(name, "read error " + read);
                        continue;
                    }
                    double squares = 0;
                    double peak = 0;
                    for (int i = 0; i < read; i++) {
                        double absolute = Math.abs((double) samples[i]) / 32768.0;
                        squares += absolute * absolute;
                        peak = Math.max(peak, absolute);
                    }
                    stats.rms = Math.sqrt(squares / read);
                    stats.peak = peak;
                    stats.state = formatLevel(stats.rms, stats.peak);
                    reportMetrics();
                }
            } catch (RuntimeException error) {
                if (running) {
                    setState(name, error.getClass().getSimpleName() + ": " + error.getMessage());
                }
            }
        }, "VoIPMicDebug-" + name);
    }

    private synchronized void stopRecorders() {
        running = false;
        AudioRecord vc = vcRecorder;
        AudioRecord mic = micRecorder;
        vcRecorder = null;
        micRecorder = null;
        stopRecorder(vc);
        stopRecorder(mic);
        joinThread(vcThread);
        joinThread(micThread);
        releaseRecorder(vc);
        releaseRecorder(mic);
        vcThread = null;
        micThread = null;
    }

    private void setState(String source, String state) {
        (source.startsWith("VOICE_COMMUNICATION") ? vcStats : micStats).state = state;
        reportMetrics();
    }

    private void reportMetrics() {
        report("VOICE_COMMUNICATION: " + vcStats.state +
                "\nMIC: " + micStats.state);
    }

    private String formatLevel(double rms, double peak) {
        double dbfs = rms == 0 ? -120.0 : Math.max(-120.0, 20.0 * Math.log10(rms));
        return String.format(Locale.US, "RMS FS %.5f | peak FS %.5f | %.1f dBFS", rms, peak, dbfs);
    }

    private int selectSource() {
        boolean vcHasSamples = vcStats.state.startsWith("RMS FS");
        boolean micHasSamples = micStats.state.startsWith("RMS FS");
        if (!vcHasSamples && micHasSamples) {
            return MediaRecorder.AudioSource.MIC;
        }
        if (vcHasSamples && micHasSamples && micStats.rms > vcStats.rms) {
            return MediaRecorder.AudioSource.MIC;
        }
        return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    }

    private void report(String text) {
        mainHandler.post(() -> listener.onStatus(text));
    }

    private static byte[] toPcm16Le(short[] samples, int length) {
        ByteBuffer bytes = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < length; i++) {
            bytes.putShort(samples[i]);
        }
        return bytes.array();
    }

    private static String toStreamUrl(String backendUrl) throws Exception {
        String input = backendUrl.trim();
        if (input.isEmpty()) {
            throw new IllegalArgumentException("Backend URL is empty");
        }
        if (input.startsWith("https://")) {
            input = "wss://" + input.substring("https://".length());
        } else if (input.startsWith("http://")) {
            input = "ws://" + input.substring("http://".length());
        }
        if (!input.startsWith("wss://") && !input.startsWith("ws://")) {
            throw new IllegalArgumentException("Backend URL must start with https:// or wss://");
        }
        while (input.endsWith("/")) {
            input = input.substring(0, input.length() - 1);
        }
        if (!input.endsWith("/stream")) {
            input += "/stream";
        }
        URI uri = URI.create(input);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("Backend URL has no host");
        }
        return input;
    }

    private static String errorJson(String code, String message) {
        try {
            return new JSONObject().put("type", "error").put("code", code).put("message", message).toString();
        } catch (JSONException ignored) {
            return "{\"type\":\"error\",\"code\":\"websocket_error\",\"message\":\"WebSocket error\"}";
        }
    }

    private static String describe(Throwable error, String secret) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        if (secret != null && !secret.isEmpty()) {
            message = message.replace(secret, "[redacted]");
        }
        return message;
    }

    private void stopRecorder(AudioRecord recorder) {
        if (recorder != null && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                recorder.stop();
            } catch (IllegalStateException error) {
                report("AudioRecord stop failed: " + error.getMessage());
            }
        }
    }

    private void releaseRecorder(AudioRecord recorder) {
        if (recorder == null) {
            return;
        }
        stopRecorder(recorder);
        try {
            recorder.release();
        } catch (IllegalStateException error) {
            report("AudioRecord release failed: " + error.getMessage());
        }
    }

    private static void joinThread(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(500);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
