package com.limelight.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.core.content.ContextCompat;

import com.limelight.R;
import com.limelight.utils.UiHelper;

/**
 * A soft, even {@code signal} halo behind the connection cover — the one hue the palette
 * spends on what is live, and a connecting session is exactly that. A blurred rounded
 * rectangle the size of the cover, drawn a little larger so only the bleed shows past the
 * opaque art, breathing slowly in and out. No rotation, no wedge: just a glow.
 * <p>
 * The view is sized larger than the cover it sits behind; {@link #INSET_DP} is how far the
 * halo's rectangle is held off each edge, and must match the margin the layout gives the
 * cover inside this view.
 */
public class CoverGlowView extends View {

    /** Kept in step with the cover's inset inside this view in the layout. */
    private static final float INSET_DP = 28f;
    private static final float CORNER_DP = 12f;
    private static final float BLUR_DP = 22f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float inset;
    private final float corner;
    private RectF rect;

    private float glow = 0.4f;
    private ObjectAnimator animator;

    public CoverGlowView(Context context) {
        this(context, null);
    }

    public CoverGlowView(Context context, AttributeSet attrs) {
        super(context, attrs);

        inset = UiHelper.dpToPx(context, INSET_DP);
        corner = UiHelper.dpToPx(context, CORNER_DP);

        paint.setColor(ContextCompat.getColor(context, R.color.signal));
        paint.setStyle(Paint.Style.FILL);
        paint.setMaskFilter(new BlurMaskFilter(UiHelper.dpToPx(context, BLUR_DP), BlurMaskFilter.Blur.NORMAL));

        // A blur mask filter needs a software layer to render.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rect = new RectF(inset, inset, w - inset, h - inset);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (rect == null) {
            return;
        }
        paint.setAlpha((int) (glow * 255));
        canvas.drawRoundRect(rect, corner, corner, paint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startGlow();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopGlow();
        super.onDetachedFromWindow();
    }

    private void startGlow() {
        if (animator != null) {
            return;
        }
        animator = ObjectAnimator.ofFloat(this, "glow", 0.4f, 0.95f);
        animator.setDuration(1700);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.start();
    }

    /** Stop breathing — called when the scene is taken down. */
    public void stopGlow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    public float getGlow() {
        return glow;
    }

    public void setGlow(float glow) {
        this.glow = glow;
        invalidate();
    }
}
