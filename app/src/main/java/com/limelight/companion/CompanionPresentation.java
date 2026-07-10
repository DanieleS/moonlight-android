package com.limelight.companion;

import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.video.PerfStats;

/**
 * The companion surface rendered on the secondary panel.
 *
 * While a stream is running it draws the performance stats, which would otherwise sit on top of
 * the game. The rest of the time it shows nothing but the app name.
 *
 * It never polls: {@link CompanionState} pushes, roughly once a second, and only while it is on
 * screen.
 */
public class CompanionPresentation extends android.app.Presentation implements CompanionState.Listener {

    private final CompanionState state;

    private View idleView;
    private View statsView;
    private TextView resolutionView;
    private TextView fpsView;
    private TextView latencyView;
    private TextView decodeTimeView;
    private TextView lossView;
    private TextView bandwidthView;
    private TextView framesView;
    private View hostRowView;
    private TextView hostLatencyView;
    private TextView decoderView;

    public CompanionPresentation(Context outerContext, Display display, CompanionState state) {
        super(outerContext, display);
        this.state = state;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.companion_surface);
        idleView = findViewById(R.id.companionIdle);
        statsView = findViewById(R.id.companionStats);
        resolutionView = findViewById(R.id.companionResolution);
        fpsView = findViewById(R.id.companionFps);
        latencyView = findViewById(R.id.companionLatency);
        decodeTimeView = findViewById(R.id.companionDecodeTime);
        lossView = findViewById(R.id.companionLoss);
        bandwidthView = findViewById(R.id.companionBandwidth);
        framesView = findViewById(R.id.companionFrames);
        hostRowView = findViewById(R.id.companionHostRow);
        hostLatencyView = findViewById(R.id.companionHostLatency);
        decoderView = findViewById(R.id.companionDecoder);
    }

    @Override
    protected void onStart() {
        super.onStart();

        state.setListener(this);
        render();
    }

    @Override
    protected void onStop() {
        state.setListener(null);
        super.onStop();
    }

    @Override
    public void onCompanionStateChanged() {
        render();
    }

    private void render() {
        if (statsView == null) {
            return;
        }

        PerfStats stats = state.getStats();
        if (stats == null) {
            statsView.setVisibility(View.GONE);
            idleView.setVisibility(View.VISIBLE);
            return;
        }

        idleView.setVisibility(View.GONE);
        statsView.setVisibility(View.VISIBLE);

        Context context = getContext();
        resolutionView.setText(context.getString(R.string.companion_resolution, stats.width, stats.height));
        fpsView.setText(String.valueOf(Math.round(stats.totalFps)));
        latencyView.setText(context.getString(R.string.companion_value_latency,
                stats.networkLatencyMs, stats.networkLatencyVarianceMs));
        decodeTimeView.setText(context.getString(R.string.companion_value_ms, stats.decodeTimeMs));
        lossView.setText(context.getString(R.string.companion_value_percent, stats.networkDropsPercent));
        bandwidthView.setText(formatBandwidth(stats));
        framesView.setText(context.getString(R.string.companion_value_frames,
                stats.receivedFps, stats.renderedFps));
        decoderView.setText(stats.decoder);

        if (stats.hasHostProcessingLatency) {
            hostRowView.setVisibility(View.VISIBLE);
            hostLatencyView.setText(context.getString(R.string.companion_value_host_latency,
                    stats.minHostProcessingLatencyMs,
                    stats.maxHostProcessingLatencyMs,
                    stats.avgHostProcessingLatencyMs));
        } else {
            // Not every host reports it, and an empty row reads better than a row of dashes.
            hostRowView.setVisibility(View.GONE);
        }
    }

    private String formatBandwidth(PerfStats stats) {
        Context context = getContext();
        if (!stats.hasBandwidth()) {
            return context.getString(R.string.companion_value_unavailable);
        }
        if (stats.bandwidthKbps >= 1000) {
            return context.getString(R.string.companion_value_mbps, stats.bandwidthKbps / 1024f);
        }
        return context.getString(R.string.companion_value_kbps, stats.bandwidthKbps);
    }
}
