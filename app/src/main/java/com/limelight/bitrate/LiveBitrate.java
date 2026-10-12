package com.limelight.bitrate;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.NvHTTP;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A running stream's bitrate, changed while it plays: by hand from the in-game menu, and, when
 * the adaptive bitrate setting is on, by an {@link AdaptiveBitrateController} fed the decoder's
 * once-a-second numbers.
 *
 * <p>Both go through the host's {@code /bitrate}, which only Vibepollo-family hosts have. Whether
 * this one does is asked once, when the stream starts; until the answer is yes nothing else is
 * sent, the menu offers nothing, and the controller is never fed. A host that later answers
 * {@code /bitrate} with a 404 is taken at its word for the rest of the stream.
 *
 * <p>Bitrates here are the settings' kind, the whole stream's budget; they are turned into what
 * the host's encoder wants only on the way out ({@link BitrateSteps#toEncoderKbps}). Changes last
 * for this stream only: the bitrate in the settings is left as it is.
 *
 * <p>Requests run on one worker thread, in order, so a change by hand and one from the controller
 * never cross. The controller lives on that thread too.
 */
public class LiveBitrate {

    /** What the host is asked. {@link NvHTTP} in the app, a fake in the tests. */
    public interface Host {
        boolean supportsRuntimeBitrate();

        /** @return the encoder bitrate applied */
        int setRuntimeBitrate(int encoderKbps) throws IOException, XmlPullParserException;
    }

    /** Called on the main thread. */
    public interface Listener {
        /** The host took a new bitrate: from the menu when {@code adaptive} is false. */
        void onBitrateChanged(int kbps, boolean adaptive);

        /** A bitrate asked for from the menu could not be set. */
        void onBitrateChangeFailed(int kbps);
    }

    interface Clock {
        long nowMs();
    }

    /** Requests from the controller that may fail in a row before it gives up for the stream. */
    static final int MAX_ADAPTIVE_FAILURES = 3;

    private final Host host;
    private final Listener listener;
    private final AdaptiveBitrateController.Mode mode;
    private final int fps;
    private final int audioChannels;
    private final int startKbps;
    private final ExecutorService worker;
    private final Executor main;
    private final Clock clock;

    private volatile boolean supported;
    private volatile boolean stopped;
    private volatile boolean adaptive;
    private volatile int currentKbps;
    /** The bitrate chosen, in the settings or from the menu, which the controller stays under. */
    private volatile int chosenKbps;
    /** Set while a sample is with the worker, so a slow request makes samples drop, not queue. */
    private final AtomicBoolean sampleQueued = new AtomicBoolean();

    // Worker thread only.
    private AdaptiveBitrateController controller;
    private int adaptiveFailures;

    public static LiveBitrate create(NvHTTP http, Listener listener, AdaptiveBitrateController.Mode mode,
                                     int fps, int audioChannels, int startKbps) {
        Host host = new Host() {
            @Override
            public boolean supportsRuntimeBitrate() {
                return http.supportsRuntimeBitrate();
            }

            @Override
            public int setRuntimeBitrate(int encoderKbps) throws IOException, XmlPullParserException {
                return http.setRuntimeBitrate(encoderKbps);
            }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "LiveBitrate");
            thread.setDaemon(true);
            return thread;
        });
        Handler mainHandler = new Handler(Looper.getMainLooper());
        return new LiveBitrate(host, listener, mode, fps, audioChannels, startKbps,
                worker, mainHandler::post, SystemClock::uptimeMillis);
    }

    LiveBitrate(Host host, Listener listener, AdaptiveBitrateController.Mode mode, int fps,
                int audioChannels, int startKbps, ExecutorService worker, Executor main, Clock clock) {
        this.host = host;
        this.listener = listener;
        this.mode = mode;
        this.fps = fps;
        this.audioChannels = audioChannels;
        this.startKbps = startKbps;
        this.worker = worker;
        this.main = main;
        this.clock = clock;
        this.currentKbps = startKbps;
        this.chosenKbps = startKbps;
    }

    /** Ask the host whether it can do this at all, and start adapting if it can and that is wanted. */
    public void start(boolean adaptiveWanted) {
        submit(() -> {
            supported = host.supportsRuntimeBitrate();
            LimeLog.info("Live bitrate: host " + (supported ? "supports" : "does not support")
                    + " runtime bitrate changes");
            if (supported && adaptiveWanted) {
                enableAdaptive();
            }
        });
    }

    public void stop() {
        stopped = true;
        adaptive = false;
        worker.shutdownNow();
    }

    /** Whether the host can change the bitrate mid-stream. False until it has said so. */
    public boolean isSupported() {
        return supported && !stopped;
    }

    public boolean isAdaptive() {
        return adaptive;
    }

    public int getCurrentKbps() {
        return currentKbps;
    }

    public int getChosenKbps() {
        return chosenKbps;
    }

    /**
     * Switch adaptation on or off for the rest of the stream. Off goes back to the chosen bitrate,
     * since that is what a stream without adaptation runs at.
     */
    public void setAdaptive(boolean on) {
        submit(() -> {
            if (!supported) {
                return;
            }
            if (on) {
                enableAdaptive();
                return;
            }
            adaptive = false;
            controller = null;
            LimeLog.info("Live bitrate: adaptive off");
            int chosen = chosenKbps;
            if (currentKbps != chosen && send(chosen) != null) {
                currentKbps = chosen;
                main.execute(() -> listener.onBitrateChanged(chosen, false));
            }
        });
    }

    /** A bitrate picked from the menu. It becomes the chosen one, and the controller's ceiling. */
    public void setManual(int kbps) {
        submit(() -> {
            if (!supported) {
                return;
            }
            Integer applied = send(kbps);
            if (applied == null) {
                main.execute(() -> listener.onBitrateChangeFailed(kbps));
                return;
            }
            currentKbps = kbps;
            chosenKbps = kbps;
            if (controller != null) {
                // Should the host have capped it, the controller learns so from its first probe up.
                controller.onManualChange(kbps, clock.nowMs());
            }
            main.execute(() -> listener.onBitrateChanged(kbps, false));
        });
    }

    /** One second of the stream, from the decoder's thread. Dropped unless adapting. */
    public void onSample(AdaptiveBitrateController.Sample sample) {
        if (!adaptive || stopped || !sampleQueued.compareAndSet(false, true)) {
            return;
        }
        boolean submitted = submit(() -> {
            sampleQueued.set(false);
            adapt(sample);
        });
        if (!submitted) {
            sampleQueued.set(false);
        }
    }

    private void enableAdaptive() {
        if (adaptive && controller != null) {
            return;
        }
        controller = new AdaptiveBitrateController(mode, chosenKbps, currentKbps, fps, clock.nowMs());
        adaptiveFailures = 0;
        adaptive = true;
        LimeLog.info("Live bitrate: adaptive on (" + mode.getPreferenceValue() + "), between "
                + controller.getFloorKbps() + " and " + controller.getCeilingKbps() + " kbps");
    }

    private void adapt(AdaptiveBitrateController.Sample sample) {
        AdaptiveBitrateController c = controller;
        if (!adaptive || c == null) {
            return;
        }
        AdaptiveBitrateController.Decision decision = c.onSample(sample, clock.nowMs());
        if (decision == null) {
            return;
        }
        int from = currentKbps;
        Integer applied = send(decision.bitrateKbps);
        if (applied == null) {
            c.onFailed(clock.nowMs());
            if (adaptive && ++adaptiveFailures >= MAX_ADAPTIVE_FAILURES) {
                LimeLog.warning("Live bitrate: adaptive off after " + adaptiveFailures + " failed changes");
                adaptive = false;
                controller = null;
            }
            return;
        }
        adaptiveFailures = 0;
        c.onApplied(decision.bitrateKbps, clock.nowMs());
        if (applied < BitrateSteps.toEncoderKbps(decision.bitrateKbps, audioChannels, startKbps)) {
            c.capCeiling(decision.bitrateKbps);
        }
        currentKbps = decision.bitrateKbps;
        LimeLog.info("Live bitrate: " + from + " -> " + decision.bitrateKbps + " kbps (" + decision.reason + ")");
        int kbps = decision.bitrateKbps;
        main.execute(() -> listener.onBitrateChanged(kbps, true));
    }

    /**
     * Ask the host for {@code kbps}, as the settings mean it.
     *
     * @return the encoder bitrate the host applied, or null when it could not be set
     */
    private Integer send(int kbps) {
        int encoderKbps = BitrateSteps.toEncoderKbps(kbps, audioChannels, startKbps);
        try {
            int applied = host.setRuntimeBitrate(encoderKbps);
            if (applied <= 0) {
                LimeLog.warning("Live bitrate: host did not apply " + encoderKbps + " kbps");
                return null;
            }
            if (applied < encoderKbps) {
                LimeLog.info("Live bitrate: host capped " + encoderKbps + " kbps to " + applied);
            }
            return applied;
        } catch (FileNotFoundException e) {
            // The endpoint is gone (or never was, whatever the capabilities said).
            LimeLog.warning("Live bitrate: host has no /bitrate, giving up for this stream");
            supported = false;
            adaptive = false;
            controller = null;
            return null;
        } catch (IOException | XmlPullParserException e) {
            LimeLog.warning("Live bitrate: setting " + encoderKbps + " kbps failed: " + e.getMessage());
            return null;
        }
    }

    private boolean submit(Runnable task) {
        if (stopped) {
            return false;
        }
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            // Stopped in the meantime.
            return false;
        }
    }
}
