package pt.xsoulp.domotica;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** A restrained, self-contained halo used behind the door icon. */
public final class DoorHaloView extends View {
    private static final float[] PARTICLE_X = {-0.91f, -0.76f, -0.48f, 0.52f, 0.79f, 0.93f, 0.31f};
    private static final float[] PARTICLE_Y = {-0.14f, 0.58f, -0.78f, -0.82f, -0.45f, 0.31f, 0.88f};
    private static final float[] PARTICLE_PHASE = {0.1f, 0.64f, 0.36f, 0.82f, 0.25f, 0.51f, 0.93f};

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int haloColor;
    private final ValueAnimator animator;
    private float phase;
    private float centerX;
    private float centerY;
    private float baseRadius;

    public DoorHaloView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray values = context.obtainStyledAttributes(attrs, R.styleable.DoorHaloView);
        haloColor = values.getColor(R.styleable.DoorHaloView_haloColor, Color.rgb(255, 185, 72));
        values.recycle();

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.35f));
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(4800L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            invalidate();
        });
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        centerX = width / 2f;
        centerY = height / 2f;
        baseRadius = Math.min(width, height) * 0.315f;
        glowPaint.setShader(new RadialGradient(
                centerX, centerY, baseRadius * 1.65f,
                new int[]{withAlpha(haloColor, 100), withAlpha(haloColor, 30), withAlpha(haloColor, 0)},
                new float[]{0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!animator.isStarted()) {
            animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pulse = 0.5f - 0.5f * (float) Math.cos(phase * Math.PI * 2.0);
        float radius = baseRadius * (1f + pulse * 0.045f);

        float glowScale = radius / baseRadius;
        glowPaint.setAlpha((int) (190 + pulse * 50));
        canvas.save();
        canvas.scale(glowScale, glowScale, centerX, centerY);
        canvas.drawCircle(centerX, centerY, baseRadius * 1.65f, glowPaint);
        canvas.restore();

        ringPaint.setColor(withAlpha(haloColor, (int) (205 + pulse * 40)));
        canvas.drawCircle(centerX, centerY, radius, ringPaint);

        float orbit = radius * 1.07f;
        for (int i = 0; i < PARTICLE_X.length; i++) {
            float twinkle = 0.5f + 0.5f * (float) Math.sin(
                    (phase + PARTICLE_PHASE[i]) * Math.PI * 2.0
            );
            particlePaint.setColor(withAlpha(haloColor, (int) (55 + twinkle * 175)));
            float particleRadius = dp(0.7f + twinkle * 0.75f);
            canvas.drawCircle(
                    centerX + PARTICLE_X[i] * orbit,
                    centerY + PARTICLE_Y[i] * orbit,
                    particleRadius,
                    particlePaint
            );
        }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
