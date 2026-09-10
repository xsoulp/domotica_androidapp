package pt.xsoulp.domotica;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/** Animated luminous halo used behind the door icon. */
public final class DoorHaloView extends View {
    private static final float[] PARTICLE_X = {
            -0.94f, -0.82f, -0.62f, -0.38f, 0.18f, 0.48f,
            0.72f, 0.94f, 0.86f, 0.52f, 0.12f, -0.54f
    };
    private static final float[] PARTICLE_Y = {
            -0.12f, 0.48f, -0.68f, 0.86f, -0.94f, -0.78f,
            -0.52f, 0.18f, 0.58f, 0.84f, 0.96f, 0.72f
    };
    private static final float[] PARTICLE_PHASE = {
            0.10f, 0.64f, 0.36f, 0.82f, 0.25f, 0.51f,
            0.93f, 0.44f, 0.73f, 0.04f, 0.58f, 0.31f
    };
    private static final float[] PARTICLE_SPEED = {
            1.0f, 1.7f, 1.3f, 2.1f, 1.5f, 1.9f,
            1.2f, 2.3f, 1.6f, 2.0f, 1.4f, 1.8f
    };

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparklePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringBounds = new RectF();
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
        sparklePaint.setStyle(Paint.Style.STROKE);
        sparklePaint.setStrokeCap(Paint.Cap.ROUND);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeCap(Paint.Cap.ROUND);
        highlightPaint.setStrokeWidth(dp(2.1f));
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(3600L);
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

        ringBounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        highlightPaint.setColor(withAlpha(haloColor, (int) (150 + pulse * 90)));
        canvas.drawArc(ringBounds, phase * 360f - 28f, 24f, false, highlightPaint);

        float orbit = radius * 1.07f;
        for (int i = 0; i < PARTICLE_X.length; i++) {
            float twinkle = 0.5f + 0.5f * (float) Math.sin(
                    (phase * PARTICLE_SPEED[i] + PARTICLE_PHASE[i]) * Math.PI * 2.0
            );
            float x = centerX + PARTICLE_X[i] * orbit;
            float y = centerY + PARTICLE_Y[i] * orbit;
            particlePaint.setColor(withAlpha(haloColor, (int) (65 + twinkle * 190)));
            canvas.drawCircle(x, y, dp(0.75f + twinkle * 1.05f), particlePaint);

            if (twinkle > 0.78f) {
                float flare = dp(1.8f + (twinkle - 0.78f) * 13f);
                sparklePaint.setStrokeWidth(dp(0.65f + twinkle * 0.45f));
                sparklePaint.setColor(withAlpha(Color.WHITE, (int) (90 + twinkle * 165)));
                canvas.drawLine(x - flare, y, x + flare, y, sparklePaint);
                canvas.drawLine(x, y - flare, x, y + flare, sparklePaint);
            }
        }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
