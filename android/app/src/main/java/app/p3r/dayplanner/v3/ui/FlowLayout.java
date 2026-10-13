package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Minimal wrapping row layout for chips (the framework has no flow layout). Children are placed
 * left to right with fixed horizontal/vertical spacing and wrap to a new line when full; with
 * {@link #alignEnd} each line sits at the right edge and its children are centred on the line.
 */
public final class FlowLayout extends ViewGroup {
    private final int hSpacing;
    private final int vSpacing;
    private boolean alignEnd;

    public FlowLayout(Context context, int hSpacingDp, int vSpacingDp) {
        super(context);
        this.hSpacing = Ui.dp(context, hSpacingDp);
        this.vSpacing = Ui.dp(context, vSpacingDp);
    }

    /** Lines at the right edge, children centred vertically on their line (a value 「A → B」). */
    public FlowLayout alignEnd() {
        alignEnd = true;
        requestLayout();
        return this;
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
        int y = getPaddingTop();
        int i = 0;
        while (i < getChildCount()) {
            // One line: the children from i up to (not including) end.
            int x = 0;
            int lineHeight = 0;
            int end = i;
            for (; end < getChildCount(); end++) {
                View child = getChildAt(end);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                int w = child.getMeasuredWidth();
                if (x > 0 && x + w > maxWidth) {
                    break;
                }
                x += w + hSpacing;
                lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
            }
            int lineWidth = Math.max(0, x - hSpacing);
            int left = getPaddingLeft() + (alignEnd ? Math.max(0, maxWidth - lineWidth) : 0);
            for (int k = i; k < end; k++) {
                View child = getChildAt(k);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                int w = child.getMeasuredWidth();
                int h = child.getMeasuredHeight();
                int top = alignEnd ? y + (lineHeight - h) / 2 : y;
                child.layout(left, top, left + w, top + h);
                left += w + hSpacing;
            }
            y += lineHeight + vSpacing;
            i = end;
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
