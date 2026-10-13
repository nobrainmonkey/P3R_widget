package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Minimal wrapping row layout for chips (the framework has no flow layout). Children are placed
 * left to right with fixed horizontal/vertical spacing and wrap to a new line when full.
 */
public final class FlowLayout extends ViewGroup {
    private final int hSpacing;
    private final int vSpacing;

    public FlowLayout(Context context, int hSpacingDp, int vSpacingDp) {
        super(context);
        this.hSpacing = Ui.dp(context, hSpacingDp);
        this.vSpacing = Ui.dp(context, vSpacingDp);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        boolean unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED;
        int x = 0;
        int lineHeight = 0;
        int height = 0;
        int widest = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int childWidthSpec = unbounded
                ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                : MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST);
            child.measure(childWidthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int w = child.getMeasuredWidth();
            if (!unbounded && x > 0 && x + w > maxWidth) {
                height += lineHeight + vSpacing;
                x = 0;
                lineHeight = 0;
            }
            x += w + hSpacing;
            widest = Math.max(widest, x - hSpacing);
            lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
        }
        height += lineHeight;
        int contentWidth = widest + getPaddingLeft() + getPaddingRight();
        int width;
        switch (MeasureSpec.getMode(widthMeasureSpec)) {
            case MeasureSpec.EXACTLY:
                width = MeasureSpec.getSize(widthMeasureSpec);
                break;
            case MeasureSpec.AT_MOST:
                width = Math.min(MeasureSpec.getSize(widthMeasureSpec), contentWidth);
                break;
            default:
                width = contentWidth;
                break;
        }
        setMeasuredDimension(width, resolveSize(height + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxWidth = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0;
        int y = getPaddingTop();
        int lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (x > 0 && x + w > maxWidth) {
                y += lineHeight + vSpacing;
                x = 0;
                lineHeight = 0;
            }
            int left = getPaddingLeft() + x;
            child.layout(left, y, left + w, y + h);
            x += w + hSpacing;
            lineHeight = Math.max(lineHeight, h);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
