package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 社群 tab, after the game's Social Link menu: the page header with the number of links that
 * reach MAX, three slanted sub-tabs (社群 | 羁绊剧情 | 队友特性, the last choice remembered) and
 * under 社群 the goal strip and the 22 arcana tiles. A tile's corner chip cycles
 * 未选 → 想要 → 必须 at once and is saved shortly after the last tap; the tile opens 社群详情 (tier,
 * 朋友/恋人, why a goal falls short). Views are kept per link and updated in place.
 */
final class LinksPage {
    static final String STORY_TAG = "剧情自动推进";
    /** {@link #STORY_TAG} where the whole tag does not fit on one line. */
    static final String STORY_TAG_SHORT = "自动推进";
    /** The sub-tabs, in order. */
    static final String[] SECTIONS = {"社群", "羁绊剧情", "队友特性"};
    static final int SECTION_LINKS = 0;
    static final int SECTION_EPISODES = 1;
    static final int SECTION_DORM = 2;
    /** A tier chip's taps are saved once, this long after the last one. */
    static final long TIER_SAVE_DELAY_MS = 800;
    /** Shown when the rule pack could not be read (HomeActivity normally shows its retry state). */
    static final String RULES_MISSING = "游戏资料没读出来，稍后再打开看看";
    /** Before 开始设置, above the tiles. */
    static final String SETUP_FIRST = "先完成开始设置，\n就能看到每个社群能升到哪";
    /** The goal strip while there are no goals. */
    static final String NO_GOALS = "想冲 MAX 的社群，点卡片右上角设成目标";

    private static final Profile.Tier[] TIERS = Profile.Tier.values();

    private final HomeHost host;
    private final Context c;
    private final PageScroll scroll;
    private final LinearLayout column;

    private Rules builtFor;
    private UiData data;
    private TextView summaryCount;
    private TextView summaryEstimate;
    private View summary;
    private BusyStripe busy;
    private View setup;
    private SubTabs tabs;
    private final LinearLayout[] panes = new LinearLayout[SECTIONS.length];
    private final boolean[] sectionShown = new boolean[SECTIONS.length];
    private LinearLayout goalRow;
    private GoalStrip goalStrip;
    private TextView noGoals;
    private TextView reorder;
    private LinkTile[] tiles = new LinkTile[0];
    private EpisodeRow[] episodeRows = new EpisodeRow[0];
    private int section = -1;
    private boolean pipsPlayed;

    /** Tier taps not saved yet (link → tier), in the order of the first tap. */
    private final Map<Integer, Profile.Tier> pendingTiers = new LinkedHashMap<>();
    private final Runnable flushTiers = this::flushTiers;

    LinksPage(HomeHost host) {
        this.host = host;
        this.c = host.activity();
        scroll = new PageScroll(c);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        column = Ui.column(c);
        Ui.pad(column, Ui.GUTTER, 12, Ui.GUTTER, 24);
        scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    View view() {
        return scroll;
    }

    void setBottomInset(int px) {
        scroll.setPadding(0, 0, 0, px);
    }

    // ------------------------------------------------------------------ bind

    void bind(UiData d) {
        data = d;
        int y = scroll.getScrollY();
        if (d.rules == null) {
            // Never reached through HomeActivity (it shows its retry state instead); no crash anyway.
            builtFor = null;
            column.removeAllViews();
            column.addView(Ui.pageHeader(c, "社群", "SOCIAL LINK", null));
            column.addView(Ui.body(c, RULES_MISSING), spaced(16));
            return;
        }
        if (builtFor != d.rules) {
            build(d);
        }
        update(d);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, topDp);
        return p;
    }

    /** The views that stay for this rule pack: header, sub-tabs, one tile per link, one row per track. */
    private void build(UiData d) {
        Rules rules = d.rules;
        builtFor = rules;
        column.removeAllViews();
        summary = summaryView();
        column.addView(Ui.pageHeader(c, "社群", "SOCIAL LINK", summary));
        busy = new BusyStripe(c);
        column.addView(busy, spaced(8));

        setup = setupBanner();
        column.addView(setup, spaced(16));

        boolean[] available = {true, rules.episodes.length > 0, !DormTargets.partners(rules).isEmpty()};
        if (section < 0) {
            section = UiPrefs.linksSection(c);
        }
        if (section >= SECTIONS.length || !available[section]) {
            section = SECTION_LINKS;
        }
        tabs = new SubTabs(c, available, this::selectSection);
        column.addView(tabs, spaced(16));
        tabs.setVisibility(available[1] || available[2] ? View.VISIBLE : View.GONE);

        // 社群: goal strip and the tarot grid.
        LinearLayout links = Ui.column(c);
        goalRow = goalRow();
        links.addView(goalRow, Ui.matchWrap());
        tiles = new LinkTile[rules.links.length];
        LinearLayout row = null;
        for (Rules.Link link : rules.links) {
            if (link.idx % 2 == 0) {
                row = Ui.row(c);
                row.setGravity(Gravity.TOP);
                row.setBaselineAligned(false);
                links.addView(row, spaced(link.idx == 0 ? 12 : 10));
            }
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (link.idx % 2 == 1) {
                cardParams.leftMargin = Ui.dp(c, 10);
            }
            tiles[link.idx] = new LinkTile(link);
            row.addView(tiles[link.idx], cardParams);
        }
        panes[SECTION_LINKS] = links;

        // 羁绊剧情.
        LinearLayout episodes = Ui.column(c);
        TextView note = Ui.caption(c, "羁绊剧情不加社群点数");
        episodes.addView(note, Ui.matchWrap());
        episodeRows = new EpisodeRow[rules.episodes.length];
        for (Rules.Episode track : rules.episodes) {
            if (track.idx > 0) {
                View rule = Ui.fadingRule(c);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, Ui.dp(c, 1)));
                episodes.addView(rule, rp);
            }
            episodeRows[track.idx] = new EpisodeRow(track);
            episodes.addView(episodeRows[track.idx], spaced(track.idx == 0 ? 8 : 0));
        }
        panes[SECTION_EPISODES] = episodes;

        // 队友特性: rebuilt on every bind (nine small tiles).
        panes[SECTION_DORM] = Ui.column(c);

        for (int i = 0; i < panes.length; i++) {
            column.addView(panes[i], spaced(12));
            panes[i].setVisibility(i == section ? View.VISIBLE : View.GONE);
        }
        tabs.setSelected(section, false);
        sectionShown[section] = true;
    }

    private void update(UiData d) {
        Profile profile = shown(d.profile());
        boolean planning = d.snap != null && d.snap.planning;
        boolean stale = planning || (d.snap != null && d.plan() != null && !d.snap.planCurrent);
        busy.setVisibility(planning ? View.VISIBLE : View.GONE);
        setup.setVisibility(d.hasState() ? View.GONE : View.VISIBLE);
        updateSummary(d);
        updateGoalStrip(d.rules, profile);
        for (LinkTile tile : tiles) {
            tile.bind(LinkStatus.of(d.rules, profile, d.observed(), d.plan(), tile.link.idx), profile, stale,
                d.hasState());
        }
        for (EpisodeRow row : episodeRows) {
            row.bind(d, profile);
        }
        LinearLayout dorm = panes[SECTION_DORM];
        dorm.removeAllViews();
        View grid = DormTargets.grid(host, d);
        if (grid != null) {
            dorm.addView(grid, Ui.matchWrap());
        }
    }

    /** The profile as shown: the saved one with the tier taps not saved yet. */
    private Profile shown(Profile saved) {
        if (pendingTiers.isEmpty()) {
            return saved;
        }
        Profile p = saved.copy();
        for (Map.Entry<Integer, Profile.Tier> e : pendingTiers.entrySet()) {
            if (p.tier[e.getKey()] != e.getValue()) {
                Goals.setTier(p, e.getKey(), e.getValue());
            }
        }
        return p;
    }

    // ------------------------------------------------------------------ sub-tabs

    private void selectSection(int index) {
        if (index == section) {
            return;
        }
        flushTiers();
        boolean forward = index > section;
        section = index;
        UiPrefs.setLinksSection(c, index);
        for (int i = 0; i < panes.length; i++) {
            panes[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        Motion.tabIn(panes[index], forward);
        if (!sectionShown[index]) {
            sectionShown[index] = true;
            Motion.cascade(panes[index]);
        }
    }

    /**
     * 社群 | 羁绊剧情 | 队友特性 in the game's menu style: the selected one sits on a cyan
     * parallelogram with a white slab and navy ink, which slides over on a change.
     */
    private static final class SubTabs extends LinearLayout {
        private final List<TextView> labels = new ArrayList<>();
        private final SlantedPanelDrawable slab = new SlantedPanelDrawable();
        private float indicator = -1;
        private android.animation.ValueAnimator slide;

        SubTabs(Context c, boolean[] available, IntConsumer onSelect) {
            super(c);
            setOrientation(HORIZONTAL);
            setWillNotDraw(false);
            for (int i = 0; i < SECTIONS.length; i++) {
                final int index = i;
                TextView label = Ui.text(c, SECTIONS[i], 15, Ui.col(c, R.color.p3r_ink_muted), true);
                label.setGravity(Gravity.CENTER);
                label.setMinHeight(Ui.dp(c, Ui.TOUCH));
                label.setSingleLine(true);
                label.setClickable(true);
                label.setFocusable(true);
                label.setBackground(new SlantedPanelDrawable().fill(0)
                    .pressedFill(Ui.col(c, R.color.ui_cyan_faint)).skew(Ui.dp(c, 10)));
                Ui.pad(label, 14, 6, 14, 6);
                label.setOnClickListener(v -> {
                    setSelected(index, true);
                    onSelect.accept(index);
                });
                label.setVisibility(available[i] ? VISIBLE : GONE);
                LayoutParams p = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                p.rightMargin = Ui.dp(c, 6);
                addView(label, p);
                labels.add(label);
            }
        }

        void setSelected(int index, boolean animate) {
            Context c = getContext();
            for (int i = 0; i < labels.size(); i++) {
                boolean on = i == index;
                TextView label = labels.get(i);
                label.setTextColor(on ? Ui.col(c, R.color.p3r_navy_950) : Ui.col(c, R.color.p3r_ink_muted));
                label.setSelected(on);
                label.setAccessibilityDelegate(new AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(View v, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(v, info);
                        info.setClassName("android.widget.Button");
                        info.setSelected(on);
                    }
                });
            }
            if (slide != null) {
                slide.cancel();
            }
            if (animate && indicator >= 0 && Ui.animationsEnabled() && isLaidOut()) {
                final float from = indicator;
                slide = android.animation.ValueAnimator.ofFloat(0f, 1f);
                slide.setDuration(220);
                slide.setInterpolator(Motion.SETTLE);
                slide.addUpdateListener(a -> {
                    indicator = from + (index - from) * (float) a.getAnimatedValue();
                    invalidate();
                });
                slide.start();
            } else {
                indicator = index;
                invalidate();
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (indicator >= 0 && !labels.isEmpty()) {
                int lower = Math.max(0, Math.min(labels.size() - 1, (int) Math.floor(indicator)));
                int upper = Math.min(labels.size() - 1, lower + 1);
                float t = indicator - lower;
                TextView a = labels.get(lower);
                TextView b = labels.get(upper);
                if (a.getWidth() > 0 && (b.getWidth() > 0 || t == 0)) {
                    float left = a.getLeft() + (b.getLeft() - a.getLeft()) * t;
                    float right = a.getRight() + (b.getRight() - a.getRight()) * t;
                    Context c = getContext();
                    slab.fill(Ui.col(c, R.color.p3r_cyan_400)).skew(Ui.dp(c, 10))
                        .slab(Ui.alpha(Ui.col(c, R.color.p3r_white), 220), Ui.dp(c, 3), Ui.dp(c, 3));
                    slab.setBounds(Math.round(left), a.getTop(), Math.round(right) + Ui.dp(c, 3), a.getBottom() + Ui.dp(c, 3));
                    slab.draw(canvas);
                }
            }
            super.dispatchDraw(canvas);
        }
    }

    // ------------------------------------------------------------------ header

    /** 「MAX 15」 at the right of the title, with the page's one 「预计」 tag when it is a forecast. */
    private View summaryView() {
        LinearLayout line = Ui.row(c);
        line.setGravity(Gravity.BOTTOM);
        summaryEstimate = Ui.tag(c, "预计", Ui.alpha(Ui.col(c, R.color.p3r_cyan_200), 40), Ui.col(c, R.color.p3r_cyan_200));
        LinearLayout.LayoutParams ep = Ui.wrap();
        ep.bottomMargin = Ui.dp(c, 7);
        ep.rightMargin = Ui.dp(c, 8);
        line.addView(summaryEstimate, ep);
        TextView label = Ui.numeral(c, "MAX", 18, Ui.col(c, R.color.p3r_cyan_300));
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.bottomMargin = Ui.dp(c, 5);
        lp.rightMargin = Ui.dp(c, 6);
        line.addView(label, lp);
        summaryCount = Ui.numeral(c, "0", 40, Ui.col(c, R.color.p3r_white));
        line.addView(summaryCount, Ui.wrap());
        for (int i = 0; i < line.getChildCount(); i++) {
            line.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        line.setFocusable(true);
        return line;
    }

    private void updateSummary(UiData d) {
        SimState obs = d.observed();
        Plan plan = d.plan();
        int now = 0;
        int end = 0;
        for (int i = 0; i < d.rules.links.length; i++) {
            int rank = obs == null || i >= obs.rank.length ? 0 : obs.rank[i];
            int projected = plan == null ? rank : Math.max(rank, plan.summary.endRanks[i]);
            now += rank >= 10 ? 1 : 0;
            end += projected >= 10 ? 1 : 0;
        }
        boolean forecast = end > now;
        summaryCount.setText(String.valueOf(end));
        summaryEstimate.setVisibility(forecast ? View.VISIBLE : View.GONE);
        summary.setContentDescription((forecast ? "预计升到 MAX 的社群 " : "升到 MAX 的社群 ") + end + " 个");
        summary.setVisibility(d.hasState() ? View.VISIBLE : View.INVISIBLE);
    }

    /** Before 开始设置: one line and the button, instead of 22 empty forecasts. */
    private View setupBanner() {
        LinearLayout card = Ui.column(c);
        card.setBackground(Ui.heroCard(c));
        Ui.pad(card, 22, 16, 18, 18);
        TextView text = Ui.text(c, SETUP_FIRST, 16, Ui.col(c, R.color.p3r_white), true);
        card.addView(text);
        TextView start = Ui.primary(c, "开始设置");
        start.setOnClickListener(v -> host.openState("onboarding"));
        card.addView(start, spaced(14));
        return card;
    }

    // ------------------------------------------------------------------ goal strip

    /** 「目标」, the goals in priority order as small plates, and 「调整顺序」. */
    private LinearLayout goalRow() {
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, 48));
        TextView label = Ui.text(c, "目标", 13, Ui.col(c, R.color.p3r_ink_muted), true);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.rightMargin = Ui.dp(c, 10);
        row.addView(label, lp);
        goalStrip = new GoalStrip(c);
        goalStrip.setClickable(true);
        goalStrip.setFocusable(true);
        goalStrip.setOnClickListener(v -> openOrder());
        row.addView(goalStrip, Ui.weight(1));
        noGoals = Ui.caption(c, NO_GOALS);
        row.addView(noGoals, Ui.weight(1));
        reorder = Ui.quiet(c, "调整顺序");
        reorder.setTextSize(14);
        Ui.pad(reorder, 10, 4, 6, 4);
        reorder.setOnClickListener(v -> openOrder());
        row.addView(reorder, Ui.wrap());
        return row;
    }

    private void updateGoalStrip(Rules rules, Profile profile) {
        List<Integer> goals = LinkStatus.goalOrder(profile);
        boolean any = !goals.isEmpty();
        goalStrip.setVisibility(any ? View.VISIBLE : View.GONE);
        reorder.setVisibility(any ? View.VISIBLE : View.GONE);
        noGoals.setVisibility(any ? View.GONE : View.VISIBLE);
        goalStrip.removeAllViews();
        StringBuilder spoken = new StringBuilder("目标顺序：");
        for (int i = 0; i < goals.size(); i++) {
            int link = goals.get(i);
            String text = (i + 1) + " " + rules.links[link].arcanaZh;
            goalStrip.addView(goalPlate(text, profile.tier[link]));
            spoken.append(i == 0 ? "" : "、").append(text);
        }
        goalStrip.setContentDescription(spoken.toString());
    }

    /** A goal in the strip: 必须 a white plate with navy ink, 想要 a cyan outline. */
    private TextView goalPlate(String text, Profile.Tier tier) {
        boolean must = tier == Profile.Tier.MUST;
        TextView plate = Ui.text(c, Ui.latin(c, text), 13, must ? Ui.col(c, R.color.p3r_navy_950)
            : Ui.col(c, R.color.p3r_cyan_300), true);
        plate.setSingleLine(true);
        plate.setGravity(Gravity.CENTER);
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 5));
        if (must) {
            bg.fill(Ui.col(c, R.color.p3r_white));
        } else {
            bg.stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 200), Ui.dp(c, 1.2f));
        }
        plate.setBackground(bg);
        Ui.pad(plate, 9, 2, 9, 3);
        plate.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return plate;
    }

    /**
     * One line of plates: those that fit are shown in order, the rest are counted in a cyan
     * 「+3」 drawn at the end.
     */
    private static final class GoalStrip extends ViewGroup {
        private final Paint more = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int shown;
        private final int gap;

        GoalStrip(Context c) {
            super(c);
            setWillNotDraw(false);
            gap = Ui.dp(c, 6);
            more.setTypeface(Ui.numerals(c));
            more.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16,
                c.getResources().getDisplayMetrics()));
            more.setColor(Ui.col(c, R.color.p3r_cyan_300));
        }

        private String moreText(int hidden) {
            return "+" + hidden;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int n = getChildCount();
            int height = 0;
            for (int i = 0; i < n; i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                height = Math.max(height, child.getMeasuredHeight());
            }
            int x = 0;
            shown = 0;
            for (int i = 0; i < n; i++) {
                int w = getChildAt(i).getMeasuredWidth();
                int hidden = n - i - 1;
                int reserve = hidden > 0 ? gap + Math.round(more.measureText(moreText(hidden))) : 0;
                if (x + w + reserve > width) {
                    break;
                }
                x += w + gap;
                shown++;
            }
            setMeasuredDimension(width, resolveSize(Math.max(height, Ui.dp(getContext(), 32)), heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int x = 0;
            int h = b - t;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (i < shown) {
                    int top = (h - child.getMeasuredHeight()) / 2;
                    child.layout(x, top, x + child.getMeasuredWidth(), top + child.getMeasuredHeight());
                    x += child.getMeasuredWidth() + gap;
                } else {
                    child.layout(0, 0, 0, 0);
                }
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            for (int i = 0; i < shown; i++) {
                View child = getChildAt(i);
                drawChild(canvas, child, getDrawingTime());
            }
            int hidden = getChildCount() - shown;
            if (hidden > 0) {
                float x = 0;
                for (int i = 0; i < shown; i++) {
                    x += getChildAt(i).getWidth() + gap;
                }
                Paint.FontMetrics fm = more.getFontMetrics();
                float y = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
                canvas.drawText(moreText(hidden), x, y, more);
            }
        }
    }

    // ------------------------------------------------------------------ tier chip (debounced)

    /** One tap on a tile's chip: 未选 → 想要 → 必须 → 未选, shown at once, saved after the last tap. */
    private void cycleTier(int link) {
        UiData d = host.data() != null ? host.data() : data;
        if (d == null) {
            return;
        }
        Profile current = shown(d.profile());
        pendingTiers.put(link, Goals.next(current.tier[link]));
        update(d);
        tiles[link].popChip();
        scroll.removeCallbacks(flushTiers);
        scroll.postDelayed(flushTiers, TIER_SAVE_DELAY_MS);
    }

    /**
     * Saves the tier taps not saved yet, onto the latest profile (one save, so a quick 想要 → 必须 →
     * 未选 never replans with the tiers in between).
     */
    void flushTiers() {
        scroll.removeCallbacks(flushTiers);
        UiData d = host.data();
        if (pendingTiers.isEmpty() || d == null) {
            return;
        }
        Profile edited = d.profile().copy();
        boolean changed = false;
        for (Map.Entry<Integer, Profile.Tier> e : pendingTiers.entrySet()) {
            if (edited.tier[e.getKey()] != e.getValue()) {
                Goals.setTier(edited, e.getKey(), e.getValue());
                changed = true;
            }
        }
        pendingTiers.clear();
        if (changed) {
            host.saveProfile(edited);
        }
    }

    /** Hiding the page (another tab, the app in the background) saves pending taps at once. */
    private final class PageScroll extends ScrollView {
        PageScroll(Context context) {
            super(context);
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (changedView != this) {
                return;
            }
            if (visibility != VISIBLE) {
                flushTiers();
            } else if (!pipsPlayed && builtFor != null) {
                pipsPlayed = true;
                for (LinkTile tile : tiles) {
                    tile.pips.playFill();
                }
            }
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != VISIBLE) {
                flushTiers();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            flushTiers();
            super.onDetachedFromWindow();
        }
    }

    // ------------------------------------------------------------------ tile

    /**
     * An arcana tile like a tarot card: the card's numeral as a big faint watermark, the arcana
     * and person, the RANK now (→ the forecast end RANK), ten pips and at most one footer line.
     * 必须 is an inverted white plate, 想要 a cyan blade on the left, 未选 plain glass.
     */
    private final class LinkTile extends LinearLayout {
        final Rules.Link link;
        final boolean story;
        private final TextView name;
        private final TextView chip;
        private final LinearLayout tap;
        private final TextView person;
        private final TextView lover;
        private final TextView kicker;
        private final TextView value;
        private final TextView lockText;
        private final TextView maxStamp;
        private final TextView end;
        final RankPips pips;
        private final TextView foot;
        private Profile.Tier shownTier;

        LinkTile(Rules.Link link) {
            super(c);
            this.link = link;
            this.story = link.kind == Rules.LinkKind.STORY;
            setOrientation(VERTICAL);
            setMinimumHeight(Ui.dp(c, 150));
            Ui.pad(this, 14, 2, 4, 12);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> openDetail(link.idx));

            LinearLayout top = Ui.row(c);
            name = Ui.text(c, link.arcanaZh, 20, Ui.col(c, R.color.p3r_white), true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np = Ui.weight(1);
            np.topMargin = Ui.dp(c, 8);
            top.addView(name, np);
            tap = Ui.row(c);
            tap.setGravity(Gravity.CENTER);
            tap.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
            if (story) {
                // Story links advance with the story: no goal tier to pick. The tag keeps the tier
                // chip's inset from the card edge (a 48 dp chip centred in 64 dp).
                chip = storyBadge();
                LinearLayout.LayoutParams sp = Ui.wrap();
                sp.rightMargin = Ui.dp(c, 8);
                tap.addView(chip, sp);
            } else {
                tap.setMinimumWidth(Ui.dp(c, 64));
                chip = Ui.text(c, "", 13, Ui.col(c, R.color.p3r_white), true);
                chip.setGravity(Gravity.CENTER);
                chip.setSingleLine(true);
                chip.setMinWidth(Ui.dp(c, 48));
                Ui.pad(chip, 8, 3, 8, 3);
                tap.addView(chip);
                tap.setClickable(true);
                tap.setFocusable(true);
                tap.setOnClickListener(v -> cycleTier(link.idx));
            }
            top.addView(tap, Ui.wrap());
            addView(top, Ui.matchWrap());

            LinearLayout who = Ui.row(c);
            person = Ui.text(c, link.personZh, 13, Ui.col(c, R.color.p3r_ink_muted), false);
            person.setSingleLine(true);
            person.setEllipsize(TextUtils.TruncateAt.END);
            who.addView(person, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            lover = Ui.tag(c, "恋人", 0, 0);
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.leftMargin = Ui.dp(c, 6);
            lp.rightMargin = Ui.dp(c, 10);
            who.addView(lover, lp);
            addView(who, Ui.matchWrap());

            View spacer = new View(c);
            addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));

            kicker = Ui.numeral(c, "RANK", 12, Ui.col(c, R.color.p3r_ink_faint));
            kicker.setLetterSpacing(0.12f);
            LinearLayout.LayoutParams kp = Ui.wrap();
            kp.topMargin = Ui.dp(c, 10);
            addView(kicker, kp);
            LinearLayout rank = Ui.row(c);
            rank.setGravity(Gravity.BOTTOM);
            value = Ui.numeral(c, "", 34, Ui.col(c, R.color.p3r_white));
            rank.addView(value, Ui.wrap());
            lockText = Ui.text(c, "未解锁", 15, Ui.col(c, R.color.p3r_ink_muted), true);
            LinearLayout.LayoutParams lkp = Ui.wrap();
            lkp.bottomMargin = Ui.dp(c, 3);
            rank.addView(lockText, lkp);
            maxStamp = Ui.tag(c, "MAX", Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_navy_950));
            maxStamp.setTextSize(20);
            Ui.pad(maxStamp, 12, 0, 14, 1);
            LinearLayout.LayoutParams mp = Ui.wrap();
            mp.bottomMargin = Ui.dp(c, 3);
            rank.addView(maxStamp, mp);
            end = Ui.mixed(c, "", 26, Ui.col(c, R.color.p3r_cyan_400), false);
            LinearLayout.LayoutParams ep = Ui.wrap();
            ep.leftMargin = Ui.dp(c, 8);
            rank.addView(end, ep);
            rank.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            addView(rank, Ui.matchWrap());

            pips = new RankPips(c);
            LinearLayout.LayoutParams pp = Ui.matchWrap();
            pp.topMargin = Ui.dp(c, 4);
            pp.rightMargin = Ui.dp(c, 10);
            addView(pips, pp);

            foot = Ui.mixed(c, "", 13, Ui.col(c, R.color.p3r_cyan_300), false);
            foot.setMaxLines(2);
            foot.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams fp = Ui.matchWrap();
            fp.topMargin = Ui.dp(c, 4);
            fp.rightMargin = Ui.dp(c, 8);
            addView(foot, fp);
        }

        /** {@code recorded} is false before 开始设置: then the tile is just the card and its chip. */
        void bind(LinkStatus s, Profile profile, boolean stale, boolean recorded) {
            Profile.Tier tier = profile.tier[link.idx];
            boolean must = tier == Profile.Tier.MUST && !story;
            setBackground(background(tier));
            int ink = Ui.col(c, must ? R.color.p3r_navy_950 : R.color.p3r_white);
            int muted = must ? Ui.col(c, R.color.p3r_navy_700) : Ui.col(c, R.color.p3r_ink_muted);
            int forecast = Ui.col(c, must ? R.color.p3r_royal_500 : R.color.p3r_cyan_400);
            name.setTextColor(ink);
            person.setTextColor(muted);
            kicker.setTextColor(must ? Ui.alpha(Ui.col(c, R.color.p3r_navy_700), 200) : Ui.col(c, R.color.p3r_ink_faint));
            value.setTextColor(ink);
            lockText.setTextColor(muted);
            end.setTextColor(forecast);
            boolean romance = link.romanceable && !story && Dialogue.romanceRoute(profile, link.idx);
            lover.setVisibility(romance ? VISIBLE : GONE);
            lover.setTextColor(must ? Ui.col(c, R.color.p3r_navy_800) : Ui.col(c, R.color.p3r_ice_100));
            lover.setBackground(new SlantedPanelDrawable().skew(Ui.dp(c, 5))
                .fill(must ? Ui.alpha(Ui.col(c, R.color.p3r_navy_800), 40) : Ui.alpha(Ui.col(c, R.color.p3r_white), 44)));
            if (!story) {
                styleChip(tier, must);
                tap.setContentDescription(link.arcanaZh + " 目标：" + Goals.label(tier) + "，点按切换");
            }

            boolean max = s.observed >= 10;
            boolean up = s.projected > s.observed;
            kicker.setVisibility(max ? GONE : VISIBLE);
            value.setVisibility(!max && s.observed > 0 ? VISIBLE : GONE);
            value.setText(Zh.rankValue(s.observed));
            lockText.setVisibility(s.observed <= 0 ? VISIBLE : GONE);
            maxStamp.setVisibility(max ? VISIBLE : GONE);
            if (must) {
                maxStamp.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_navy_950)).skew(Ui.dp(c, 5)));
                maxStamp.setTextColor(Ui.col(c, R.color.p3r_white));
            } else {
                maxStamp.setBackground(new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_white)).skew(Ui.dp(c, 5)));
                maxStamp.setTextColor(Ui.col(c, R.color.p3r_navy_950));
            }
            end.setVisibility(up ? VISIBLE : GONE);
            end.setText(Ui.latin(c, "→ " + Zh.rankValue(s.projected)));
            end.setAlpha(stale ? 0.4f : 1f);
            pips.set(s.observed, s.projected);
            pips.setInverted(must);
            pips.setForecastAlpha(stale ? 0.4f : 1f);

            String footText = story ? "" : s.footZh;
            foot.setVisibility(footText.isEmpty() ? GONE : VISIBLE);
            foot.setText(Ui.latin(c, footText));
            int footInk;
            if (s.lost) {
                footInk = must ? Ui.col(c, R.color.ui_holiday) : Ui.col(c, R.color.p3r_danger);
            } else if (s.blocker != null) {
                footInk = muted;
            } else {
                footInk = must ? Ui.col(c, R.color.p3r_royal_600) : Ui.col(c, R.color.p3r_cyan_300);
            }
            foot.setTextColor(footInk);
            foot.setAlpha(stale && !s.lost && s.blocker == null ? 0.4f : 1f);

            if (!recorded) {
                kicker.setVisibility(GONE);
                value.setVisibility(GONE);
                lockText.setVisibility(GONE);
                maxStamp.setVisibility(GONE);
                end.setVisibility(GONE);
                pips.setVisibility(GONE);
                foot.setVisibility(GONE);
            } else {
                pips.setVisibility(VISIBLE);
            }
            setMinimumHeight(Ui.dp(c, recorded ? 150 : 96));

            boolean idle = recorded && s.observed <= 0 && s.projected <= 0 && tier == Profile.Tier.NONE && !s.lost;
            setAlpha(idle ? 0.7f : 1f);

            String rankText = up ? Zh.rankStep(s.observed, s.projected) : Zh.rank(s.observed);
            StringBuilder spoken = new StringBuilder(link.labelZh).append("，").append(rankText);
            if (story) {
                spoken.append("，").append(STORY_TAG);
            } else if (!footText.isEmpty()) {
                spoken.append("，").append(footText);
            }
            setContentDescription(spoken.toString());
            shownTier = tier;
        }

        private Drawable background(Profile.Tier tier) {
            int cut = Ui.dp(c, 14);
            SlantedPanelDrawable panel;
            int mark;
            if (tier == Profile.Tier.MUST && !story) {
                panel = new SlantedPanelDrawable()
                    .gradient(Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_cyan_200))
                    .pressedFill(Ui.col(c, R.color.p3r_cyan_300))
                    .cornerTick(Ui.col(c, R.color.p3r_royal_500), Ui.dp(c, 2))
                    .cuts(cut, 0, cut, 0);
                mark = Ui.alpha(Ui.col(c, R.color.p3r_navy_800), 26);
            } else {
                panel = Ui.pressableCard(c);
                if (tier == Profile.Tier.WANT && !story) {
                    panel.accent(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 5));
                }
                mark = Ui.alpha(Ui.col(c, R.color.p3r_white), 30);
            }
            Drawable numeral = new NumeralMark(LinkStatus.numeral(link), Ui.numerals(c), mark,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 72, c.getResources().getDisplayMetrics()),
                Ui.dp(c, 10), Ui.dp(c, 2));
            return new LayerDrawable(new Drawable[]{panel, numeral});
        }

        private void styleChip(Profile.Tier tier, boolean onWhite) {
            SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 5));
            if (tier == Profile.Tier.MUST) {
                bg.fill(Ui.col(c, R.color.p3r_navy_950));
                chip.setTextColor(Ui.col(c, R.color.p3r_white));
                chip.setText(Goals.label(tier));
                chip.setTextSize(13);
            } else if (tier == Profile.Tier.WANT) {
                bg.fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 120))
                    .stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 1.5f));
                chip.setTextColor(Ui.col(c, R.color.p3r_cyan_400));
                chip.setText(Goals.label(tier));
                chip.setTextSize(13);
            } else {
                bg.fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 90))
                    .stroke(Ui.alpha(Ui.col(c, R.color.p3r_line), 160), Ui.dp(c, 1));
                chip.setTextColor(Ui.col(c, R.color.p3r_ink_muted));
                chip.setText("＋");
                chip.setTextSize(15);
            }
            chip.setBackground(bg);
        }

        /** A short pop on the chip after a tap (skipped without animations). */
        void popChip() {
            if (!Ui.animationsEnabled()) {
                return;
            }
            chip.animate().cancel();
            chip.setScaleX(1f);
            chip.setScaleY(1f);
            chip.animate().scaleX(1.12f).scaleY(1.12f).setDuration(60).setInterpolator(Motion.EASE)
                .withEndAction(() -> chip.animate().scaleX(1f).scaleY(1f).setDuration(90)
                    .setInterpolator(Motion.SETTLE).start()).start();
        }

        /**
         * "剧情自动推进" in place of the tier chip of a story link (not tappable), always one line:
         * where it would not fit (a large font), the short {@link #STORY_TAG_SHORT}.
         */
        private TextView storyBadge() {
            TextView badge = Ui.text(c, STORY_TAG, 12, Ui.col(c, R.color.p3r_ink_muted), true);
            badge.setGravity(Gravity.CENTER);
            badge.setMaxWidth(Ui.dp(c, 96));
            badge.setSingleLine(true);
            badge.setBackground(new SlantedPanelDrawable().skew(Ui.dp(c, 5))
                .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 120)));
            Ui.pad(badge, 8, 3, 8, 3);
            float full = badge.getPaint().measureText(STORY_TAG) + badge.getPaddingLeft() + badge.getPaddingRight();
            if (full > badge.getMaxWidth()) {
                badge.setText(STORY_TAG_SHORT);
            }
            return badge;
        }
    }

    /** The arcana numeral as a big faint italic watermark in the lower right of a tile. */
    static final class NumeralMark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String word;
        private final float right;
        private final float bottom;

        NumeralMark(String word, android.graphics.Typeface face, int color, float size, float right, float bottom) {
            this.word = word;
            this.right = right;
            this.bottom = bottom;
            paint.setTypeface(face);
            paint.setTextSize(size);
            paint.setColor(color);
            paint.setTextAlign(Paint.Align.RIGHT);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty() || word == null || word.isEmpty()) {
                return;
            }
            int save = canvas.save();
            canvas.clipRect(b);
            canvas.drawText(word, b.right - right, b.bottom - bottom, paint);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ 社群详情

    /**
     * 社群详情: numeral, arcana and person; RANK now → forecast with the pips; the one line that
     * matters (when it reaches the target, or why a goal falls short with 「调整顺序」, or the rescue
     * to record with 「去修改进度」); then 目标 and, for a romanceable link, the route. Every control
     * saves at once.
     */
    void openDetail(int linkIdx) {
        flushTiers();
        UiData d = host.data();
        if (d == null || d.rules == null) {
            return;
        }
        Rules.Link link = d.rules.links[linkIdx];
        Profile profile = d.profile();
        LinkStatus s = LinkStatus.of(d.rules, profile, d.observed(), d.plan(), linkIdx);
        boolean story = link.kind == Rules.LinkKind.STORY;
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 24);

        LinearLayout head = Ui.row(c);
        head.setGravity(Gravity.BOTTOM);
        TextView numeral = Ui.numeral(c, LinkStatus.numeral(link), 48, Ui.col(c, R.color.p3r_cyan_400));
        numeral.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(numeral, Ui.wrap());
        LinearLayout names = Ui.column(c);
        names.addView(Ui.text(c, link.arcanaZh, 22, Ui.col(c, R.color.p3r_white), true));
        names.addView(Ui.text(c, link.personZh, 14, Ui.col(c, R.color.p3r_ink_muted), false));
        LinearLayout.LayoutParams nl = Ui.weight(1);
        nl.leftMargin = Ui.dp(c, 16);
        head.addView(names, nl);
        sheet.addView(head);

        String rankText = s.projected > s.observed ? Zh.rankStep(s.observed, s.projected) : Zh.rank(s.observed);
        sheet.addView(Ui.mixed(c, rankText, 20, Ui.col(c, R.color.p3r_white), true), spaced(16));
        RankPips pips = new RankPips(c).height(12);
        pips.set(s.observed, s.projected);
        sheet.addView(pips, spaced(6));

        if (story) {
            sheet.addView(Ui.muted(c, "这个社群跟着剧情推进"), spaced(14));
            host.showSheet(sheet);
            return;
        }
        View status = detailStatus(link, s);
        if (status != null) {
            sheet.addView(status, spaced(14));
        }

        sheet.addView(fieldLabel("目标"), spaced(20));
        SegmentedControl tier = new SegmentedControl(c, Arrays.asList("未选", "想要", "必须"));
        tier.setSelected(profile.tier[linkIdx].ordinal());
        for (int i = 0; i < TIERS.length; i++) {
            tier.segment(i).setContentDescription(link.arcanaZh + " 目标：" + Goals.label(TIERS[i]));
        }
        tier.setListener(i -> {
            Profile edited = host.data().profile().copy();
            if (edited.tier[linkIdx] != TIERS[i]) {
                Goals.setTier(edited, linkIdx, TIERS[i]);
                host.saveProfile(edited);
            }
        });
        sheet.addView(tier, spaced(6));

        if (link.romanceable) {
            sheet.addView(fieldLabel("回答按哪条路线？"), spaced(18));
            SegmentedControl route = new SegmentedControl(c, Arrays.asList("朋友", "恋人"));
            route.setSelected(Dialogue.romanceRoute(profile, linkIdx) ? 1 : 0);
            for (int i = 0; i < 2; i++) {
                route.segment(i).setContentDescription(link.arcanaZh + " 路线：" + (i == 0 ? "朋友" : "恋人"));
            }
            route.setListener(i -> {
                Profile edited = host.data().profile().copy();
                edited.route[linkIdx] = i == 1 ? Profile.Route.ROMANCE : Profile.Route.FRIEND;
                host.saveProfile(edited);
            });
            sheet.addView(route, spaced(6));
        }
        host.showSheet(sheet);
    }

    private TextView fieldLabel(String text) {
        return Ui.text(c, text, 15, Ui.col(c, R.color.p3r_ice_100), true);
    }

    /** The detail sheet's status line, with its action where there is one; null when nothing to say. */
    private View detailStatus(Rules.Link link, LinkStatus s) {
        if (s.lost) {
            if (s.rescueFailed) {
                return Ui.text(c, "没救出失踪者，这个社群无法继续了", 15, Ui.col(c, R.color.p3r_danger), true);
            }
            LinearLayout box = Ui.column(c);
            box.addView(Ui.text(c, "救出" + s.rescue.zh + "了吗？", 16, Ui.col(c, R.color.p3r_white), true));
            TextView fix = Ui.secondary(c, "去修改进度");
            fix.setOnClickListener(v -> {
                host.dismissSheet();
                host.openState("edit");
            });
            box.addView(fix, spaced(10));
            return box;
        }
        if (s.observed >= s.target || !s.planned) {
            return null;
        }
        String goalText = s.target >= 10 ? "MAX" : Zh.rank(s.target);
        if (s.targetSlot >= 0) {
            return Ui.mixed(c, LinkStatus.reachLine(Zh.date(Rules.dayOf(s.targetSlot)), goalText), 16,
                Ui.col(c, R.color.p3r_cyan_300), true);
        }
        if (s.projected >= s.target) {
            return Ui.mixed(c, s.footZh, 16, Ui.col(c, R.color.p3r_cyan_300), true);
        }
        if (!s.goal) {
            return null;
        }
        LinearLayout box = Ui.column(c);
        box.addView(Ui.mixed(c, LinkStatus.shortfall(goalText, s.observed, s.projected, s.blocker), 15,
            Ui.col(c, R.color.p3r_ice_100), false));
        TextView order = Ui.quiet(c, "调整顺序 ›");
        order.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        Ui.pad(order, 0, 4, 12, 4);
        order.setOnClickListener(v -> openOrder());
        box.addView(order, Ui.wrap());
        return box;
    }

    // ------------------------------------------------------------------ 目标顺序

    /** 目标顺序: 必须 then 想要, each goal with ↑ / ↓ where a move within its tier is possible. */
    void openOrder() {
        flushTiers();
        if (host.data() == null || host.data().rules == null) {
            return;
        }
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 24);
        sheet.addView(SheetChrome.title(c, "目标顺序"));
        LinearLayout list = Ui.column(c);
        sheet.addView(list, spaced(4));
        renderOrder(list, -1, 0);
        host.showSheet(sheet);
    }

    private void renderOrder(LinearLayout list, int moved, int delta) {
        list.removeAllViews();
        UiData d = host.data();
        Profile p = d.profile();
        List<Integer> goals = LinkStatus.goalOrder(p);
        if (goals.isEmpty()) {
            list.addView(Ui.muted(c, "还没有目标"), spaced(8));
            return;
        }
        Profile.Tier group = null;
        View movedRow = null;
        View otherRow = null;
        for (int i = 0; i < goals.size(); i++) {
            int link = goals.get(i);
            Profile.Tier tier = p.tier[link];
            if (tier != group) {
                group = tier;
                TextView head = tier == Profile.Tier.MUST
                    ? Ui.tag(c, "必须", Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_navy_950))
                    : Ui.tag(c, "想要", Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 60), Ui.col(c, R.color.p3r_cyan_200));
                LinearLayout.LayoutParams hp = Ui.wrap();
                hp.topMargin = Ui.dp(c, i == 0 ? 8 : 18);
                hp.bottomMargin = Ui.dp(c, 2);
                list.addView(head, hp);
            } else {
                View rule = Ui.fadingRule(c);
                list.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, Ui.dp(c, 1))));
            }
            View row = orderRow(d.rules, p, goals, i, list);
            list.addView(row, Ui.matchWrap());
            if (link == moved) {
                movedRow = row;
            } else if (moved >= 0 && i - delta >= 0 && i - delta < goals.size() && goals.get(i - delta) == moved) {
                otherRow = row;
            }
        }
        if (movedRow != null && Ui.animationsEnabled()) {
            View a = movedRow;
            View b = otherRow;
            a.post(() -> {
                float h = a.getHeight() + Math.max(1, Ui.dp(c, 1));
                slide(a, -delta * h);
                if (b != null) {
                    slide(b, delta * h);
                }
            });
        }
    }

    private static void slide(View row, float from) {
        row.setTranslationY(from);
        row.animate().translationY(0f).setDuration(150).setInterpolator(Motion.SETTLE).start();
    }

    private View orderRow(Rules rules, Profile p, List<Integer> goals, int position, LinearLayout list) {
        int link = goals.get(position);
        Rules.Link l = rules.links[link];
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, 56));
        TextView index = Ui.numeral(c, String.valueOf(position + 1), 28, Ui.col(c, R.color.p3r_cyan_400));
        index.setMinWidth(Ui.dp(c, 40));
        row.addView(index, Ui.wrap());
        LinearLayout names = Ui.column(c);
        TextView arcana = Ui.text(c, l.arcanaZh, 17, Ui.col(c, R.color.p3r_white), true);
        arcana.setSingleLine(true);
        names.addView(arcana);
        TextView person = Ui.text(c, l.personZh, 13, Ui.col(c, R.color.p3r_ink_muted), false);
        person.setSingleLine(true);
        person.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(person);
        LinearLayout.LayoutParams np = Ui.weight(1);
        np.leftMargin = Ui.dp(c, 8);
        row.addView(names, np);
        boolean canUp = position > 0 && p.tier[goals.get(position - 1)] == p.tier[link];
        boolean canDown = position + 1 < goals.size() && p.tier[goals.get(position + 1)] == p.tier[link];
        row.addView(arrow(R.drawable.ui_ic_up, "上移 " + l.arcanaZh, canUp, () -> move(list, link, -1)));
        row.addView(arrow(R.drawable.ui_ic_down, "下移 " + l.arcanaZh, canDown, () -> move(list, link, +1)));
        return row;
    }

    private void move(LinearLayout list, int link, int delta) {
        Profile edited = host.data().profile().copy();
        if (Goals.move(edited, link, delta)) {
            host.saveProfile(edited);
            renderOrder(list, link, delta);
        }
    }

    /** ↑ / ↓; a move that is not possible keeps its place but is not shown. */
    private View arrow(int icon, String description, boolean enabled, Runnable action) {
        ImageView view = new ImageView(c);
        view.setImageDrawable(Ui.tinted(c, icon, Ui.col(c, R.color.p3r_white)));
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setContentDescription(description);
        view.setEnabled(enabled);
        view.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        view.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_soft))
            .skew(Ui.dp(c, 6)));
        view.setOnClickListener(v -> action.run());
        int size = Ui.dp(c, 48);
        view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return view;
    }

    // ------------------------------------------------------------------ 羁绊剧情

    /**
     * One 羁绊剧情 track: the person, what the last episode unlocks, the episodes watched as 「1/5」
     * with pips, and the opt-in switch. Opting in once the game is under way first asks how many
     * episodes were already watched.
     */
    private final class EpisodeRow extends LinearLayout {
        private final Rules.Episode track;
        private final TextView progress;
        private final EpisodePips pips;
        private final SwitchMark mark;
        private boolean checked;

        EpisodeRow(Rules.Episode track) {
            super(c);
            this.track = track;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(Ui.dp(c, 72));
            Ui.pad(this, 4, 14, 6, 14);
            setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
                .skew(Ui.dp(c, 8)));
            // Name, 「2/5」 and the pips on one line; the reward gets the whole width under them.
            LinearLayout texts = Ui.column(c);
            LinearLayout head = Ui.row(c);
            head.setGravity(Gravity.BOTTOM);
            head.addView(Ui.text(c, track.personZh, 18, Ui.col(c, R.color.p3r_white), true), Ui.wrap());
            progress = Ui.numeral(c, "", 18, Ui.col(c, R.color.p3r_cyan_300));
            LinearLayout.LayoutParams gp = Ui.wrap();
            gp.leftMargin = Ui.dp(c, 12);
            gp.bottomMargin = Ui.dp(c, 3);
            head.addView(progress, gp);
            pips = new EpisodePips(c, track.count);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(Ui.dp(c, 11 * track.count + 6), Ui.dp(c, 9));
            pp.leftMargin = Ui.dp(c, 8);
            pp.bottomMargin = Ui.dp(c, 7);
            head.addView(pips, pp);
            texts.addView(head, Ui.matchWrap());
            String reward = rewardLine(track);
            if (reward != null) {
                TextView line = Ui.mixed(c, reward, 13, Ui.col(c, R.color.p3r_ink_muted), false);
                LinearLayout.LayoutParams rp = Ui.wrap();
                rp.topMargin = Ui.dp(c, 4);
                texts.addView(line, rp);
            }
            addView(texts, Ui.weight(1));
            mark = new SwitchMark(c);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 26));
            mp.leftMargin = Ui.dp(c, 16);
            addView(mark, mp);
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> toggle());
        }

        void bind(UiData d, Profile profile) {
            int watched = StateDraft.watched(d.rules, d.observed(), track.idx);
            progress.setText(watched + "/" + track.count);
            pips.setWatched(watched);
            setChecked(profile.wantsEpisodes(track.id));
        }

        void setChecked(boolean on) {
            checked = on;
            mark.setOn(on);
            setContentDescription(track.personZh + " · 共 " + track.count + " 话" + (on ? "，已开启" : "，已关闭"));
        }

        private void toggle() {
            flushTiers();
            UiData d = host.data();
            boolean on = !checked;
            setChecked(on);
            Profile edited = d.profile().copy();
            edited.episodeTracks = on ? SettingsPage.with(edited.episodeTracks, track.id)
                : SettingsPage.without(edited.episodeTracks, track.id);
            if (on && d.observed() != null) {
                askWatched(d, track, edited, this);
            } else {
                host.saveProfile(edited);
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.Switch");
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }

    /** Small slanted pips, white for each episode watched. */
    private static final class EpisodePips extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int count;
        private int watched;

        EpisodePips(Context c, int count) {
            super(c);
            this.count = count;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setWatched(int value) {
            watched = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float h = getHeight();
            float gap = Ui.dp(c, 3);
            float skew = h * 0.6f;
            float w = (getWidth() - skew - gap * (count - 1)) / Math.max(1, count);
            for (int i = 0; i < count; i++) {
                float left = i * (w + gap);
                path.reset();
                path.moveTo(left + skew, 0);
                path.lineTo(left + skew + w, 0);
                path.lineTo(left + w, h);
                path.lineTo(left, h);
                path.close();
                if (i < watched) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Ui.col(c, R.color.p3r_white));
                } else {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Ui.dp(c, 1));
                    paint.setColor(Ui.col(c, R.color.p3r_line));
                }
                canvas.drawPath(path, paint);
            }
        }
    }

    /** The drawn on/off switch of a track row: a parallelogram track and knob. */
    private static final class SwitchMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean on;

        SwitchMark(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setOn(boolean value) {
            on = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Context c = getContext();
            float w = getWidth();
            float h = getHeight();
            float skew = h * 0.35f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? Ui.col(c, R.color.ui_cyan_soft) : Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 200));
            parallelogram(0, 0, w, h, skew);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 1.5f));
            paint.setColor(on ? Ui.col(c, R.color.p3r_cyan_400) : Ui.col(c, R.color.p3r_ink_faint));
            canvas.drawPath(path, paint);
            float inset = Ui.dp(c, 4);
            float knob = w * 0.46f;
            float left = on ? w - knob - inset + skew * 0.2f : inset;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(on ? Ui.col(c, R.color.p3r_cyan_400) : Ui.col(c, R.color.p3r_ink_muted));
            parallelogram(left, inset, left + knob - skew * 0.2f, h - inset, skew * 0.7f);
            canvas.drawPath(path, paint);
        }

        private void parallelogram(float l, float t, float r, float b, float skew) {
            path.reset();
            path.moveTo(l + skew, t);
            path.lineTo(r, t);
            path.lineTo(r - skew, b);
            path.lineTo(l, b);
            path.close();
        }
    }

    /**
     * 「已经看了几话？」 with chips 0..count. The answer goes into the progress record (a checkpoint
     * saved together with the opted-in profile), so episodes already seen are not planned again;
     * an unchanged count only saves the profile. Closing the sheet without an answer leaves the
     * track off.
     */
    private void askWatched(UiData d, Rules.Episode track, Profile edited, EpisodeRow row) {
        SimState observed = d.observed();
        int current = StateDraft.watched(d.rules, observed, track.idx);
        boolean[] answered = {false};
        LinearLayout sheet = Ui.column(c);
        Ui.pad(sheet, 20, 12, 20, 24);
        sheet.addView(SheetChrome.title(c, Outcomes.episodeName(track)));
        sheet.addView(Ui.text(c, "已经看了几话？", 22, Ui.col(c, R.color.p3r_white), true), spaced(10));
        sheet.addView(watchedChips(c, track, current, n -> {
            answered[0] = true;
            host.dismissSheet();
            if (n == current) {
                host.saveProfile(edited);
                return;
            }
            host.saveCheckpoint(withWatched(d.rules, observed, track, n), edited,
                "已记录：" + Outcomes.episodeName(track) + " · " + StateDraft.episodesText(track, n));
        }), spaced(16));
        host.showSheet(sheet, () -> {
            if (!answered[0]) {
                row.setChecked(false);
            }
        });
    }

    /**
     * The recorded state with {@code n} episodes of {@code track} watched, fit for a checkpoint;
     * everything else, a recorded 没救出 included, stays as recorded.
     */
    static SimState withWatched(Rules rules, SimState observed, Rules.Episode track, int n) {
        SimState state = observed.copy();
        if (state.episodes.length < rules.episodes.length) {
            state.episodes = Arrays.copyOf(state.episodes, rules.episodes.length);
        }
        state.episodes[track.idx] = Math.max(0, Math.min(track.count, n));
        return state;
    }

    /**
     * Chips "还没看 / 1 话 / … / 全部 5 话" for the episodes of a track already watched, all of one
     * width. Tabular figures give every number the same box, so 「1 话」 is spaced like 「2 话」
     * (Barlow's proportional "1" is narrow and sat close to its unit).
     */
    static FlowLayout watchedChips(Context c, Rules.Episode track, int selected, IntConsumer onPick) {
        FlowLayout chips = new FlowLayout(c, 8, 8).evenColumns();
        for (int n = 0; n <= track.count; n++) {
            final int value = n;
            String label = n == 0 ? "还没看" : n == track.count ? "全部 " + n + " 话" : n + " 话";
            TextView chip = Ui.chip(c, label, n == selected);
            chip.setFontFeatureSettings("tnum");
            // Centred in a column: a narrower inset and a size smaller let four chips share a
            // line, 「全部 3 话」 still clear of the slanted edges.
            chip.setTextSize(13);
            Ui.pad(chip, 8, 6, 8, 6);
            chip.setContentDescription(track.personZh + "：" + StateDraft.episodesText(track, n));
            chip.setOnClickListener(v -> onPick.accept(value));
            chips.addView(chip);
        }
        return chips;
    }

    /** English names of the Personas the 羁绊剧情 unlock, for guide lookups (p3r_compendium.json). */
    private static final Map<String, String> PERSONA_EN = new LinkedHashMap<>();

    static {
        PERSONA_EN.put("苏尔特尔", "Surt");
        PERSONA_EN.put("荷鲁斯", "Horus");
        PERSONA_EN.put("白虎", "Byakko");
        PERSONA_EN.put("米迦勒", "Michael");
        PERSONA_EN.put("地狱天使", "Hell Biker");
    }

    /** English name of a Persona a track unlocks (tests). */
    static String personaEn(String nameZh) {
        return PERSONA_EN.get(nameZh);
    }

    private static final Pattern PERSONA = Pattern.compile("合体人格面具「([^「」]+)」");
    private static final Pattern ITEM = Pattern.compile("拿到道具「([^「」]+)」");

    /**
     * What the end of a track gives, from the pack's reward sentence: 「看完可以合体「苏尔特尔」Surt」
     * (the English name for guide lookups), 「看完可以拿到「顺平的棒球帽」」; else the sentence as a
     * clause; null without one.
     */
    static String rewardLine(Rules.Episode track) {
        String reward = Zh.clause(track.rewardZh);
        if (reward.isEmpty()) {
            return null;
        }
        Matcher persona = PERSONA.matcher(reward);
        if (persona.find()) {
            String en = PERSONA_EN.get(persona.group(1));
            return "看完可以合体「" + persona.group(1) + "」" + (en == null ? "" : en);
        }
        Matcher item = ITEM.matcher(reward);
        if (item.find()) {
            return "看完可以拿到「" + item.group(1) + "」";
        }
        return reward;
    }
}
