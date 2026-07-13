package com.limelight.companion;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.video.PerfStats;
import com.limelight.nvstream.http.AppMetadata;

import java.util.ArrayList;
import java.util.List;

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

    /** Matches the padding on the idle surface in @layout/companion_surface. */
    private static final int IDLE_PADDING_DP = 28;

    private final CompanionState state;

    private View idleView;
    private TextView idleTitleView;
    private TextView idleMetaView;
    private TextView idleDescriptionView;
    private ScrollView idleDescriptionScrollView;
    /** The title we last drew, so a re-render for late-arriving art doesn't rewind the reader. */
    private String renderedTitle;
    /** The description HTML we last parsed, for the same reason. */
    private String renderedDescriptionHtml;
    private DescriptionImages descriptionImages;
    private ImageView backdropView;
    private View backdropScrimView;
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
        idleTitleView = findViewById(R.id.companionIdleTitle);
        idleMetaView = findViewById(R.id.companionIdleMeta);
        idleDescriptionView = findViewById(R.id.companionIdleDescription);
        idleDescriptionScrollView = findViewById(R.id.companionIdleDescriptionScroll);
        descriptionImages = new DescriptionImages(idleDescriptionView, this::onDescriptionImageLoaded);
        backdropView = findViewById(R.id.companionBackdrop);
        backdropScrimView = findViewById(R.id.companionBackdropScrim);
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
        // Only give up the listener if it is still ours: during a re-host the replacement panel
        // has already registered by the time this one stops.
        state.clearListener(this);
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
            renderIdle();
            return;
        }

        idleView.setVisibility(View.GONE);
        // The hero backdrop belongs to the idle library surface, not the stats dashboard.
        setBackdrop(null);
        // Nor is there any point animating a description nobody can see while a game is running.
        descriptionImages.setAnimating(false);
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

    // The idle surface spotlights whatever game the library has centred. Title always shows;
    // the meta line and description appear only when the host gave us metadata for it.
    private void renderIdle() {
        if (idleTitleView == null) {
            return;
        }

        String title = state.getBrowsingTitle();
        AppMetadata meta = state.getBrowsingMetadata();

        setBackdrop(state.getBrowsingBackground());

        // A new game starts its description from the top; a re-render of the same one (the hero
        // art landing, say) leaves the reader where they were.
        boolean spotlightChanged = !TextUtils.equals(title, renderedTitle);
        renderedTitle = title;

        if (title == null || title.isEmpty()) {
            idleTitleView.setText(R.string.app_label);
            idleMetaView.setVisibility(View.GONE);
            idleDescriptionScrollView.setVisibility(View.GONE);
            return;
        }

        idleTitleView.setText(title);

        String metaLine = meta != null ? buildMetaLine(meta) : "";
        if (metaLine.isEmpty()) {
            idleMetaView.setVisibility(View.GONE);
        } else {
            idleMetaView.setText(metaLine);
            idleMetaView.setVisibility(View.VISIBLE);
        }

        // Re-parsing the HTML on every push would be wasted work, and re-setting the text would
        // fight the reader's scroll position, so only touch it when the description itself changed.
        String html = meta != null ? meta.getDescription() : null;
        if (!TextUtils.equals(html, renderedDescriptionHtml)) {
            renderedDescriptionHtml = html;
            // The images belong to the description being replaced: let them go with it.
            descriptionImages.reset();
            CharSequence description = toRichText(html);
            idleDescriptionView.setText(description);
            idleDescriptionScrollView.setVisibility(
                    description.length() == 0 ? View.GONE : View.VISIBLE);
        } else {
            // Coming back from a stream, the images are still the right ones: play them again.
            descriptionImages.setAnimating(true);
        }
        if (spotlightChanged) {
            idleDescriptionScrollView.scrollTo(0, 0);
        }
    }

    // Show or hide the hero backdrop and its legibility scrim together.
    private void setBackdrop(android.graphics.Bitmap background) {
        if (backdropView == null) {
            return;
        }
        if (background != null) {
            backdropView.setImageBitmap(background);
            backdropView.setVisibility(View.VISIBLE);
            backdropScrimView.setVisibility(View.VISIBLE);
        } else {
            backdropView.setImageDrawable(null);
            backdropView.setVisibility(View.GONE);
            backdropScrimView.setVisibility(View.GONE);
        }
    }

    // "2020 · Action, RPG · 88%" — only the parts we actually have.
    private String buildMetaLine(AppMetadata meta) {
        List<String> parts = new ArrayList<>(3);

        String year = meta.getReleaseYear();
        if (!year.isEmpty()) {
            parts.add(year);
        }

        List<String> genres = meta.getGenres();
        if (genres != null && !genres.isEmpty()) {
            int limit = Math.min(3, genres.size());
            parts.add(TextUtils.join(", ", genres.subList(0, limit)));
        }

        int score = meta.getCommunityScore();
        if (score >= 0) {
            parts.add(score + "%");
        }

        return TextUtils.join("  ·  ", parts);
    }

    // An image has landed: draw the description again, and put the reader back where they were —
    // an image arriving is no reason to lose their place.
    private void onDescriptionImageLoaded() {
        if (idleDescriptionView == null || renderedDescriptionHtml == null) {
            return;
        }
        int scrollY = idleDescriptionScrollView.getScrollY();
        idleDescriptionView.setText(toRichText(renderedDescriptionHtml));
        idleDescriptionScrollView.post(() -> idleDescriptionScrollView.scrollTo(0, scrollY));
    }

    // Playnite stores descriptions as HTML. Parsed, not stripped: the bold, the italics, the
    // paragraph breaks and the screenshots the author put there are all worth having.
    @SuppressWarnings("deprecation")
    private CharSequence toRichText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        // Images are fitted to the column the description is actually drawn in. Before the first
        // layout there is no width to ask for, so fall back to the panel minus the surface's own
        // padding — an underestimate, which overflows nothing.
        DisplayMetrics panel = getContext().getResources().getDisplayMetrics();
        int column = idleDescriptionView.getWidth()
                - idleDescriptionView.getPaddingLeft() - idleDescriptionView.getPaddingRight();
        if (column <= 0) {
            column = panel.widthPixels - Math.round(2 * IDLE_PADDING_DP * panel.density);
        }
        // Half the panel is as much as one screenshot may take: enough to be worth looking at,
        // little enough that the text around it stays in sight.
        descriptionImages.setBounds(column, panel.heightPixels / 2);

        Spanned parsed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, descriptionImages, null)
                : Html.fromHtml(html, descriptionImages, null);
        SpannableStringBuilder text = new SpannableStringBuilder(parsed);

        // An image that hasn't arrived (or never will) draws as nothing, but the block it sits in
        // is still there. Squeeze every run of whitespace spanning more than one line back down to
        // a single blank line — a paragraph break, no more — so no chasm is left behind.
        int i = text.length();
        while (i > 0) {
            if (!Character.isWhitespace(text.charAt(i - 1))) {
                i--;
                continue;
            }
            int runEnd = i;
            int newlines = 0;
            while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
                if (text.charAt(i - 1) == '\n') {
                    newlines++;
                }
                i--;
            }
            if (newlines >= 2 && runEnd - i > 2) {
                text.replace(i, runEnd, "\n\n");
            }
        }

        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = 0;
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        return text.subSequence(start, end);
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
