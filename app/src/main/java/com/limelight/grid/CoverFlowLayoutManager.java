package com.limelight.grid;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A horizontal layout manager that grows the cover nearest the centre and shrinks the rest,
 * the way a console library's carousel does. The transform is purely visual — scale, fade
 * and draw order — so the layout positions, and therefore the d-pad and the context menu,
 * behave like any other horizontal list.
 *
 * The tiles are the same tiles the grid uses; only their scale changes here.
 */
public class CoverFlowLayoutManager extends LinearLayoutManager {
    private static final float CENTER_SCALE = 1.2f;
    private static final float SIDE_SCALE = 0.72f;
    private static final float SIDE_FADE = 0.55f;
    // How many covers out from the centre a cover reaches its smallest size. Measuring the
    // falloff in whole covers, rather than in pixels, keeps a cover two along looking the same
    // on any screen width.
    private static final float SPREAD = 2f;

    public CoverFlowLayoutManager(Context context) {
        super(context, HORIZONTAL, false);
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }

    @Override
    public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
        int scrolled = super.scrollHorizontallyBy(dx, recycler, state);
        transformChildren();
        return scrolled;
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        super.onLayoutChildren(recycler, state);
        transformChildren();
    }

    private void transformChildren() {
        int count = getChildCount();
        if (count == 0) {
            return;
        }

        float center = getWidth() / 2f;
        View sample = getChildAt(0);
        float itemWidth = sample.getWidth();                                   // the unscaled cover
        float pitch = getDecoratedRight(sample) - getDecoratedLeft(sample);    // cover + gap
        if (pitch <= 0) {
            return;
        }
        float gap = pitch - itemWidth;

        // First pass: scale, fade and draw order follow the distance from the centre, measured
        // in whole covers.
        float[] mids = new float[count];
        float[] scales = new float[count];
        int anchor = 0;
        float anchorDist = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            float childMid = (getDecoratedLeft(child) + getDecoratedRight(child)) / 2f;
            float n = Math.abs(childMid - center) / pitch;                     // 0 at centre, 1 a cover out
            float t = Math.min(1f, n / SPREAD);
            float scale = CENTER_SCALE + (SIDE_SCALE - CENTER_SCALE) * t;
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(1f - SIDE_FADE * t);
            child.setTranslationZ(-n);                                         // the centre cover draws on top
            mids[i] = childMid;
            scales[i] = scale;
            float dist = Math.abs(childMid - center);
            if (dist < anchorDist) {
                anchorDist = dist;
                anchor = i;
            }
        }

        // Second pass: scaling shrinks each cover about its own centre, so the fixed layout gap
        // would leave the big centre cover further from its neighbours than they are from each
        // other. Re-space the covers outward from the centred one so every visible gap is equal.
        float half = itemWidth / 2f;
        getChildAt(anchor).setTranslationX(0f);
        float prevMid = mids[anchor];
        float prevScale = scales[anchor];
        for (int i = anchor + 1; i < count; i++) {
            float targetMid = prevMid + prevScale * half + gap + scales[i] * half;
            getChildAt(i).setTranslationX(targetMid - mids[i]);
            prevMid = targetMid;
            prevScale = scales[i];
        }
        prevMid = mids[anchor];
        prevScale = scales[anchor];
        for (int i = anchor - 1; i >= 0; i--) {
            float targetMid = prevMid - prevScale * half - gap - scales[i] * half;
            getChildAt(i).setTranslationX(targetMid - mids[i]);
            prevMid = targetMid;
            prevScale = scales[i];
        }
    }

    @Override
    public boolean onRequestChildFocus(RecyclerView parent, RecyclerView.State state,
                                       View child, View focused) {
        // Bring the focused cover to the centre rather than merely scrolling it into view.
        int childMid = (getDecoratedLeft(child) + getDecoratedRight(child)) / 2;
        parent.smoothScrollBy(childMid - getWidth() / 2, 0);
        return true;
    }
}
