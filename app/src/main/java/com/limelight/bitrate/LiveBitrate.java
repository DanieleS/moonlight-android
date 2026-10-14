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
 * sent, the menu offers nothing, and the controller is never fed. A host whose {@code /bitrate}
 * turns out to be missing after all (an HTTP 404) is taken at its word for the rest of the stream.
 * A refusal in the reply itself, such as Vibepollo's "no active session for this client" (a 404
 * status inside an HTTP 200), is only a failed change: the menu may try again, and the controller
 * gives up after {@link #MAX_ADAPTIVE_FAILURES} in a row.
 *
 * <p>Bitrates here are the settings' kind, the whole stream's budget; they are turned into what
 * the host's encoder wants only on the way out ({@link BitrateSteps#toEncoderKbps}). Changes last
 * for this stream only: the bitrate in the settings is left as it is.
 *
 * <p>A host with a bitrate limit of its own ({@code max_bitrate}) applies it to a starting
 * stream's whole budget, before error correction and audio come off, but to a live change's
 * encoder bitrate. Nothing tells this end the limit up front, and a stream started above it runs
 * lower than this end believes. The first request the host clamps gives it away; from then on
 * every request is held to it, the way a starting stream's is, and the request it clamped is
 * asked again from where the stream really was.
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

        /**
         * Adaptation switched itself off, after the host refused too many changes in a row. The
         * chosen bitrate has been asked for again; {@code restored} says whether the host took it.
         */
        void onAdaptiveStopped(boolean restored);
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
    private final int warpFactor;
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
    /** The host's own bitrate limit, once a request has run into it; 0 until then. */
    private int hostCapKbps;

    /**
     * @param warpFactor the frame-pacing warp factor the stream asked for, 0 for none: the host
     *                   multiplied the starting bitrate by it, so live changes are too
     */
    public static LiveBitrate create(NvHTTP http, Listener listener, AdaptiveBitrateController.Mode mode,
                                     int fps, int audioChannels, int startKbps, int warpFactor) {
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
        return new LiveBitrate(host, listener, mode, fps, audioChannels, startKbps, warpFactor,
                worker, mainHandler::post, SystemClock::uptimeMillis);
    }

    LiveBitrate(Host host, Listener listener, AdaptiveBitrateController.Mode mode, int fps,
                int audioChannels, int startKbps, int warpFactor, ExecutorService worker, Executor main,
                Clock clock) {
        this.host = host;
        this.listener = listener;
        this.mode = mode;
        this.fps = fps;
        this.audioChannels = audioChannels;
        this.startKbps = startKbps;
        this.warpFactor = warpFactor;
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
            restoreChosen();
        });
    }

    /**
     * Go back to the chosen bitrate, held to the host's limit if one is known.
     *
     * @return whether the stream is there now
     */
    private boolean restoreChosen() {
        int target = withinHostCap(chosenKbps);
        if (currentKbps == target) {
            return true;
        }
        Integer now = send(target, 0);
        if (now == null) {
            return false;
        }
        currentKbps = now;
        main.execute(() -> listener.onBitrateChanged(now, false));
        return true;
    }

    /** A bitrate picked from the menu. It becomes the chosen one, and the controller's ceiling. */
    public void setManual(int kbps) {
        submit(() -> {
            if (!supported) {
                return;
            }
            Integer now = send(kbps, 0);
            if (now == null) {
                main.execute(() -> listener.onBitrateChangeFailed(kbps));
                return;
            }
            currentKbps = now;
            chosenKbps = kbps;
            if (controller != null) {
                // Lower than the pick when the host's limit held it down: the limit is the ceiling.
                controller.onManualChange(now, clock.nowMs());
            }
            main.execute(() -> listener.onBitrateChanged(now, false));
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
        controller = new AdaptiveBitrateController(mode, withinHostCap(chosenKbps), currentKbps, fps,
                clock.nowMs());
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
        // A step down is a step from where the stream is; should the host's limit turn out to
        // have held the stream below where this end thought, it is taken from there instead.
        Integer now = send(decision.bitrateKbps, decision.bitrateKbps < from ? from : 0);
        if (now == null) {
            c.onFailed(clock.nowMs());
            if (adaptive && ++adaptiveFailures >= MAX_ADAPTIVE_FAILURES) {
                LimeLog.warning("Live bitrate: adaptive off after " + adaptiveFailures + " failed changes");
                adaptive = false;
                controller = null;
                // Don't leave the stream wherever the controller had taken it, unannounced.
                boolean restored = restoreChosen();
                main.execute(() -> listener.onAdaptiveStopped(restored));
            }
            return;
        }
        adaptiveFailures = 0;
        c.onApplied(now, clock.nowMs());
        if (hostCapKbps > 0) {
            c.capCeiling(hostCapKbps);
        }
        currentKbps = now;
        LimeLog.info("Live bitrate: " + from + " -> " + now + " kbps (" + decision.reason + ")");
        main.execute(() -> listener.onBitrateChanged(now, true));
    }

    private int withinHostCap(int kbps) {
        return hostCapKbps > 0 ? Math.min(kbps, hostCapKbps) : kbps;
    }

    /**
     * Ask the host for {@code kbps}, as the settings mean it, held to the host's limit if one is
     * known.
     *
     * <p>When the host clamps the request, that reveals its limit, and that the stream has been
     * running at the limit rather than at the bitrate this end thought: the host held the starting
     * budget to it. The encoder has just been set to the limit itself, above where the stream was,
     * so the request is made again at once: an absolute one held to the limit, a step taken from
     * the limit by the same proportion.
     *
     * @param stepFromKbps for a step relative to the stream's bitrate, the bitrate it was taken
     *                     from; 0 for an absolute bitrate
     * @return the bitrate the stream is at now, the settings' kind, or null when it could not be
     *         set
     */
    private Integer send(int kbps, int stepFromKbps) {
        int wireKbps = withinHostCap(kbps);
        int encoderKbps = toEncoder(wireKbps);
        Integer applied = request(encoderKbps);
        if (applied == null || applied >= encoderKbps) {
            return applied == null ? null : wireKbps;
        }

        if (hostCapKbps == 0 || applied < hostCapKbps) {
            hostCapKbps = applied;
        }
        int corrected = stepFromKbps > 0
                ? (int) ((long) Math.min(stepFromKbps, hostCapKbps) * kbps / stepFromKbps)
                : kbps;
        corrected = withinHostCap(corrected);
        LimeLog.info("Live bitrate: host capped " + encoderKbps + " kbps to " + applied
                + "; its limit is " + hostCapKbps + " kbps, asking for " + corrected + " instead");
        // With warp, even this can exceed the limit once multiplied; the host clamps it again,
        // which is the closest it can come.
        return request(toEncoder(corrected)) == null ? null : corrected;
    }

    private int toEncoder(int kbps) {
        return BitrateSteps.toEncoderKbps(kbps, audioChannels, startKbps, warpFactor);
    }

    /** @return the encoder bitrate the host applied, or null when it could not be set */
    private Integer request(int encoderKbps) {
        try {
            int applied = host.setRuntimeBitrate(encoderKbps);
            if (applied <= 0) {
                LimeLog.warning("Live bitrate: host did not apply " + encoderKbps + " kbps");
                return null;
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
