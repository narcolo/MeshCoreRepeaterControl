package com.example.meshcorerepeatercontrol.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class SignalView extends View {

    private static final int COLOR_INACTIVE = 0xFFD0D0D0;

    private int level = 0; // 1, 2, or 3
    private int color = COLOR_INACTIVE;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    public SignalView(Context context) {
        super(context);
    }

    public SignalView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SignalView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setSignal(int level, int color) {
        this.level = level;
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float bottom = h * 0.85f;

        paint.setStyle(Paint.Style.FILL);
        float dotRadius = w * 0.08f;

        // Draw 3 arcs from inner to outer
        paint.setStyle(Paint.Style.STROKE);
        float strokeWidth = w * 0.09f;
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);

        float[] radii = {w * 0.22f, w * 0.36f, w * 0.50f};

        for (int i = 0; i < 3; i++) {
            float r = radii[i];
            arcRect.set(cx - r, bottom - r, cx + r, bottom + r);
            paint.setColor((i < level) ? color : COLOR_INACTIVE);
            canvas.drawArc(arcRect, -135, 90, false, paint);
        }

        // Draw dot at bottom center
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(level > 0 ? color : COLOR_INACTIVE);
        canvas.drawCircle(cx, bottom, dotRadius, paint);
    }
}
