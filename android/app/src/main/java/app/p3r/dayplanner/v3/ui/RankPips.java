package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import app.p3r.dayplanner.R;

/**
 * Ten slanted pips: white up to the confirmed RANK, cyan up to the forecast end RANK, hollow
 * beyond. Shows observed vs forecast progress at a glance on the 社群 cards.
 */
public final class RankPips extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int observed;
    private int projected;

    public RankPips(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void set(int observed, int projected) {
        this.observed = Math.max(0, Math.min(10, observed));
        this.projected = Math.max(this.observed, Math.min(10, projected));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(Ui.dp(getContext(), 8), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Context c = getContext();
        float h = getHeight();
        float gap = Ui.dp(c, 2.5f);
        float skew = h * 0.6f;
        float w = (getWidth() - skew - gap * 9) / 10f;
        for (int i = 0; i < 10; i++) {
            float left = i * (w + gap);
            path.reset();
            path.moveTo(left + skew, 0);
            path.lineTo(left + skew + w, 0);
            path.lineTo(left + w, h);
            path.lineTo(left, h);
            path.close();
            if (i < observed) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Ui.col(c, R.color.p3r_white));
            } else if (i < projected) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Ui.col(c, R.color.p3r_cyan_400));
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(c, 1));
                paint.setColor(Ui.col(c, R.color.p3r_line));
            }
            canvas.drawPath(path, paint);
        }
    }
}
