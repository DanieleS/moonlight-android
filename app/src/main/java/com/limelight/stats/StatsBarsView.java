package com.limelight.stats;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.limelight.R;

/**
 * Time played per day (a week, a month) or per month (a year), as CouchPilot draws it: one
 * series, so one ink — the bar in focus at full strength, the others a quieter step of it. The
 * bar in focus is today's until one is picked, by touch or, while the view has focus, with the
 * d-pad's left and right. A week's bars carry their value above them ("3:20", "45′").
 *
 * <p>In compact form it is the per-game sheet's twelve-week sparkline: no labels, the last bar
 * (this week) in focus, and nothing to pick.
 *
 * <p>Drawn by hand rather than with a charting library: there are at most 31 rounded bars and a
 * row of letters, and drawing them here keeps the d-pad selection, the labels on exactly the
 * days CouchPilot labels, and the app's palette all in one place.
 */
public class StatsBarsView extends View {

    /** Told which bar is in focus now, or -1 when none is. */
    public interface OnSelectionChangedListener {
        void onSelectionChanged(int index);
    }

    private long[] values = new long[0];
    private String[] labels;
    private String[] valueLabels;
    private int defaultIndex = -1;
    private int selected = -1;
    private boolean compact;
    private OnSelectionChangedListener listener;

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint onPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelOnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;

    public StatsBarsView(Context context) {
        this(context, null);
    }

    public StatsBarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float scaled = getResources().getDisplayMetrics().scaledDensity;

        dimPaint.setColor(ContextCompat.getColor(context, R.color.outline));
        onPaint.setColor(ContextCompat.getColor(context, R.color.signal));
        labelPaint.setColor(ContextCompat.getColor(context, R.color.content_muted));
        labelPaint.setTextSize(10 * scaled);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelOnPaint.set(labelPaint);
        labelOnPaint.setColor(ContextCompat.getColor(context, R.color.content_primary));
    }

    /**
     * @param labels      one per bar, or null for none (an empty string leaves a bar unlabelled)
     * @param valueLabels one per bar drawn above it, or null for none
     * @param focusIndex  the bar in focus until another is picked (today's), or -1
     */
    public void setData(long[] values, String[] labels, String[] valueLabels, int focusIndex) {
        this.values = values != null ? values.clone() : new long[0];
        this.labels = labels;
        this.valueLabels = valueLabels;
        this.defaultIndex = focusIndex;
        this.selected = -1;
        invalidate();
        notifySelection();
    }

    /** The sparkline form: no labels, no picking. */
    public void setCompact(boolean compact) {
        this.compact = compact;
        setFocusable(!compact);
        invalidate();
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.listener = listener;
    }

    /** The bar in focus: the one picked, or the default (today's); -1 for none. */
    public int getFocusIndex() {
        if (selected >= 0 && selected < values.length) {
            return selected;
        }
        return defaultIndex >= 0 && defaultIndex < values.length ? defaultIndex : -1;
    }

    private void select(int index) {
        int clamped = Math.max(0, Math.min(values.length - 1, index));
        if (clamped == selected) {
            return;
        }
        selected = clamped;
        invalidate();
        notifySelection();
    }

    private void notifySelection() {
        if (listener != null) {
            listener.onSelectionChanged(getFocusIndex());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!compact && values.length > 0) {
            int current = getFocusIndex();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // Picking starts from the bar in focus, or from the right end, where today is.
                select(current < 0 ? values.length - 1 : current - 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                select(current < 0 ? values.length - 1 : current + 1);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (compact || values.length == 0) {
            return super.onTouchEvent(event);
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float width = getWidth() - getPaddingLeft() - getPaddingRight();
            if (width > 0) {
                int index = (int) ((event.getX() - getPaddingLeft()) / (width / values.length));
                // A second tap on the picked bar puts the focus back on today, as in CouchPilot.
                if (index == selected) {
                    selected = -1;
                    invalidate();
                    notifySelection();
                } else {
                    select(index);
                }
            }
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = values.length;
        if (n == 0) {
            return;
        }
        float left = getPaddingLeft();
        float top = getPaddingTop();
        float width = getWidth() - left - getPaddingRight();
        float height = getHeight() - top - getPaddingBottom();
        if (width <= 0 || height <= 0) {
            return;
        }

        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float textHeight = metrics.descent - metrics.ascent;
        float valueSpace = !compact && valueLabels != null ? textHeight + 4 * density : 0;
        float labelSpace = !compact && labels != null ? textHeight + 6 * density : 0;
        float barArea = height - valueSpace - labelSpace;
        if (barArea <= 0) {
            return;
        }

        long max = 1;
        for (long v : values) {
            max = Math.max(max, v);
        }

        float cell = width / n;
        float barWidth = cell * (compact ? 0.72f : n > 14 ? 0.7f : 0.56f);
        float radius = Math.min((n > 14 ? 2 : 4) * density, barWidth / 2);
        float minHeight = 2 * density;
        int focus = compact ? n - 1 : getFocusIndex();
        float baseline = top + valueSpace + barArea;

        for (int i = 0; i < n; i++) {
            boolean on = i == focus;
            float cx = left + cell * i + cell / 2;
            float barHeight = Math.max(minHeight, values[i] * barArea / (float) max);
            rect.set(cx - barWidth / 2, baseline - barHeight, cx + barWidth / 2, baseline);
            canvas.drawRoundRect(rect, radius, radius, on ? onPaint : dimPaint);

            if (valueSpace > 0 && i < valueLabels.length && valueLabels[i] != null && !valueLabels[i].isEmpty()) {
                canvas.drawText(valueLabels[i], cx, baseline - barHeight - 4 * density - metrics.descent,
                        on ? labelOnPaint : labelPaint);
            }
            if (labelSpace > 0 && i < labels.length && labels[i] != null && !labels[i].isEmpty()) {
                canvas.drawText(labels[i], cx, baseline + 6 * density - metrics.ascent,
                        on ? labelOnPaint : labelPaint);
            }
        }
    }
}
