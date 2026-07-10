package com.limelight.companion;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.widget.TextView;

import com.limelight.R;

import java.util.Locale;

/**
 * Placeholder companion surface rendered on the secondary panel.
 *
 * It draws nothing meaningful yet: a pure black background plus a tick counter and an
 * elapsed-time readout, enough to prove that rendering and the refresh cycle work on the
 * secondary panel of the target device.
 */
public class CompanionPresentation extends android.app.Presentation {

    private static final long TICK_INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CompanionState state;

    private TextView ticksView;
    private TextView uptimeView;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            state.tick();
            render();
            handler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    public CompanionPresentation(Context outerContext, Display display, CompanionState state) {
        super(outerContext, display);
        this.state = state;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.companion_placeholder);
        ticksView = findViewById(R.id.companionTicks);
        uptimeView = findViewById(R.id.companionUptime);
    }

    @Override
    protected void onStart() {
        super.onStart();

        render();
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MS);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tickRunnable);
        super.onStop();
    }

    private void render() {
        if (ticksView == null || uptimeView == null) {
            return;
        }

        ticksView.setText(getContext().getString(R.string.companion_placeholder_ticks, state.getTicks()));
        uptimeView.setText(getContext().getString(R.string.companion_placeholder_uptime, formatUptime()));
    }

    private String formatUptime() {
        long elapsedSeconds = state.getUptimeSeconds();
        return String.format(Locale.US, "%02d:%02d:%02d",
                elapsedSeconds / 3600,
                (elapsedSeconds % 3600) / 60,
                elapsedSeconds % 60);
    }
}
