package com.limelight.ui;

import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.imageview.ShapeableImageView;
import com.limelight.R;

import java.io.File;

/**
 * The connection scene: the game's cover on the true-black ground, a living signal halo
 * while the host works, and the wait broken into a few phases a player can follow.
 * <p>
 * It replaces the system progress spinner. The native library reports a dozen fine-grained
 * stages by stable English name; this folds them into a short, honest story and eases a
 * single progress line forward — never backward — as they arrive.
 */
public class ConnectionOverlay {

    /**
     * The phases the wait is told as, most general first. The weight is where the progress
     * line rests once the phase begins (out of 1000); the label is what the player reads.
     */
    private enum Phase {
        PREPARING(40, R.string.conn_phase_preparing),
        LAUNCHING(120, R.string.conn_phase_launching),
        REACHING(300, R.string.conn_phase_reaching),
        NEGOTIATING(520, R.string.conn_phase_negotiating),
        MEDIA(700, R.string.conn_phase_media),
        OPENING(880, R.string.conn_phase_opening),
        READY(1000, R.string.conn_phase_ready);

        final int weight;
        final int labelRes;

        Phase(int weight, int labelRes) {
            this.weight = weight;
            this.labelRes = labelRes;
        }
    }

    private final View root;
    private final ShapeableImageView cover;
    private final TextView coverTitle;
    private final TextView title;
    private final TextView pcName;
    private final TextView phaseText;
    private final ProgressBar progress;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ObjectAnimator progressAnimator;
    private Phase phase = Phase.PREPARING;
    private boolean dismissed = false;

    public ConnectionOverlay(View overlayRoot) {
        this.root = overlayRoot;
        this.cover = overlayRoot.findViewById(R.id.connectionCover);
        this.coverTitle = overlayRoot.findViewById(R.id.connectionCoverTitle);
        this.title = overlayRoot.findViewById(R.id.connectionTitle);
        this.pcName = overlayRoot.findViewById(R.id.connectionPcName);
        this.phaseText = overlayRoot.findViewById(R.id.connectionPhase);
        this.progress = overlayRoot.findViewById(R.id.connectionProgress);
        applyPhase(Phase.PREPARING);
    }

    /**
     * Fill the scene in once the game is known. The cover is decoded off the main thread;
     * until it arrives (or if the host has none) the game's name stands in its place.
     */
    public void bind(final File boxArt, String appName, String hostName) {
        title.setText(appName != null ? appName : "");
        if (TextUtils.isEmpty(hostName)) {
            pcName.setVisibility(View.GONE);
        } else {
            pcName.setText(hostName);
        }
        showCoverTitle(appName);

        if (boxArt == null || !boxArt.exists()) {
            return;
        }

        final String path = boxArt.getAbsolutePath();
        new Thread(() -> {
            final Bitmap bmp = BitmapFactory.decodeFile(path);
            if (bmp == null) {
                return;
            }
            ui.post(() -> {
                if (dismissed) {
                    bmp.recycle();
                    return;
                }
                cover.setImageBitmap(bmp);
                coverTitle.setVisibility(View.GONE);
            });
        }, "ConnectionCoverDecode").start();
    }

    /** Advance the story from a native stage name (or the app-launch phase). */
    public void onStage(String stage) {
        applyPhase(phaseFor(stage));
    }

    /** The stream is live: run the line home, then fade the scene away. */
    public void onConnected() {
        applyPhase(Phase.READY);
        root.animate()
                .alpha(0f)
                .setStartDelay(160)
                .setDuration(260)
                .withEndAction(this::dismiss)
                .start();
    }

    public boolean isShowing() {
        return !dismissed && root.getVisibility() == View.VISIBLE;
    }

    /** Take the scene down at once, without a fade (cancel, failure, teardown). */
    public void dismiss() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
        root.setVisibility(View.GONE);
    }

    private void applyPhase(Phase next) {
        // The line only ever moves forward, so a fast phase can't yank it back.
        if (next.weight >= phase.weight) {
            phase = next;
        }
        phaseText.setText(phaseText.getResources().getString(next.labelRes));
        animateProgressTo(phase.weight);
    }

    private void animateProgressTo(int target) {
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
        progressAnimator = ObjectAnimator.ofInt(progress, "progress", progress.getProgress(), target);
        progressAnimator.setDuration(520);
        progressAnimator.setInterpolator(new DecelerateInterpolator());
        progressAnimator.start();
    }

    private void showCoverTitle(String appName) {
        if (cover.getDrawable() == null) {
            coverTitle.setText(appName != null ? appName : "");
            coverTitle.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Fold the native library's fine-grained stages into a phase. The names are stable,
     * locale-independent constants from LiGetStageName(); anything unrecognised (notably
     * the app-launch step, which arrives as the game's own name) is the launch phase.
     */
    private static Phase phaseFor(String stage) {
        if (stage == null) {
            return Phase.LAUNCHING;
        }
        switch (stage) {
            case "platform initialization":
            case "name resolution":
                return Phase.REACHING;
            case "audio stream initialization":
            case "RTSP handshake":
            case "control stream initialization":
                return Phase.NEGOTIATING;
            case "video stream initialization":
            case "input stream initialization":
                return Phase.MEDIA;
            case "control stream establishment":
            case "video stream establishment":
            case "audio stream establishment":
            case "input stream establishment":
                return Phase.OPENING;
            default:
                return Phase.LAUNCHING;
        }
    }
}
