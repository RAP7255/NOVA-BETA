package com.example.nova.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;

public class RadarView extends View {
    private Paint circlePaint;
    private Paint sweepPaint;
    private float sweepAngle = 0;
    private Handler handler = new Handler();

    public RadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        circlePaint = new Paint();
        circlePaint.setColor(Color.CYAN);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(3f);
        circlePaint.setAntiAlias(true);

        sweepPaint = new Paint();
        sweepPaint.setColor(Color.argb(150, 0, 255, 255));
        sweepPaint.setStyle(Paint.Style.FILL);
        sweepPaint.setAntiAlias(true);

        // Animation loop
        handler.post(animationRunnable);
    }

    private Runnable animationRunnable = new Runnable() {
        @Override
        public void run() {
            sweepAngle += 3; // rotate speed
            if (sweepAngle >= 360) sweepAngle = 0;
            invalidate();
            handler.postDelayed(this, 30); // ~33 FPS
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int cx = getWidth() / 2;
        int cy = getHeight() / 2;
        int radius = Math.min(cx, cy) - 20;

        // Draw radar circles
        for (int i = 1; i <= 4; i++) {
            canvas.drawCircle(cx, cy, radius * i / 4f, circlePaint);
        }

        // Draw sweep (arc sector)
        canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                sweepAngle, 45, true, sweepPaint);

        // Optional: draw center dot
        canvas.drawCircle(cx, cy, 8, sweepPaint);
    }
}
