package org.telegram.messenger.translation;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

public final class VoIPMicDebug {
    public static final int SAMPLE_RATE = 16000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private Thread vcThread;
    private Thread micThread;
    private AudioRecord vcRecorder;
    private AudioRecord micRecorder;
    private final MicLevelListener listener;
    private final Map<String, String> sourceStatus = new HashMap<>();

    public interface MicLevelListener {
        void onStatus(String status);
    }

    public VoIPMicDebug(MicLevelListener listener) {
        this.listener = listener;
    }

    public void start(Context context) {
        if (running) {
            stop();
            return;
        }
        running = true;
        vcRecorder = openRecorder(context, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION");
        micRecorder = openRecorder(context, MediaRecorder.AudioSource.MIC, "MIC");
        vcThread = recordThread(vcRecorder, "VOICE_COMMUNICATION");
        micThread = recordThread(micRecorder, "MIC");
        vcThread.start();
        micThread.start();
        mainHandler.post(() -> listener.onStatus("Starting mic debug: VC=" +
                (vcRecorder != null ? "initialized" : "error") +
                ", MIC=" + (micRecorder != null ? "initialized" : "error")));
    }

    public void stop() {
        running = false;
        if (vcRecorder != null) {
            vcRecorder.release();
            vcRecorder = null;
        }
        if (micRecorder != null) {
            micRecorder.release();
            micRecorder = null;
        }
        listener.onStatus("Mic debug stopped");
    }

    private AudioRecord openRecorder(Context context, int source, String name) {
        int minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );
        if (minBytes <= 0) {
            setStatus(name + ": error min buffer " + minBytes);
            return null;
        }
        try {
            AudioRecord recorder = new AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBytes, 3200)
            );
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                setStatus(name + ": initialization failed");
                return null;
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release();
                setStatus(name + ": start failed");
                return null;
            }
            setStatus(name + ": started");
            return recorder;
        } catch (Throwable error) {
            setStatus(name + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
            return null;
        }
    }

    private Thread recordThread(AudioRecord recorder, String name) {
        return new Thread(() -> {
            if (recorder == null) {
                return;
            }
            short[] buffer = new short[1600];
            while (running) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    setStatus(name + ": read error " + read);
                    continue;
                }
                double sumSquares = 0;
                for (int i = 0; i < read; i++) {
                    sumSquares += (double) buffer[i] * buffer[i];
                }
                double rms = Math.sqrt(sumSquares / read);
                setStatus(name + ": RMS " + String.format(Locale.US, "%.1f", rms));
            }
        }, "VoIPMicDebug-" + name);
    }

    private void setStatus(String status) {
        if (!running && !status.contains("stopped")) {
            return;
        }
        int separator = status.indexOf(':');
        if (separator > 0) {
            sourceStatus.put(status.substring(0, separator), status.substring(separator + 1).trim());
            mainHandler.post(() -> listener.onStatus(
                    "VC: " + valueFor("VOICE_COMMUNICATION") +
                            "\nMIC: " + valueFor("MIC")
            ));
        } else {
            mainHandler.post(() -> listener.onStatus(status));
        }
    }

    private String valueFor(String source) {
        String value = sourceStatus.get(source);
        return value == null ? "waiting" : value;
    }
}
