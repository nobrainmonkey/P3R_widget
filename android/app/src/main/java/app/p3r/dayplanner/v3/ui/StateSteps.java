package app.p3r.dayplanner.v3.ui;

import android.app.Activity;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * Builders for the state wizard's pages. Every page is prefilled from {@link StateDraft}; edits go
 * straight into the draft and are persisted through {@link Callbacks#changed()}.
 *
 * <p>Each page opens like the game's menus: a cyan Latin kicker with its giant outlined
 * watermark over one big question, then borderless rows. A forecast value carries one 「预计」
 * tag; nothing explains the controls.
 */
final class StateSteps {
    /** Wizard pages. */
    enum Page { GOALS, PERSONA, SYNC_INTRO, HUB, DATE, CASH, STATS, LINKS, UNLOCK, CHRISTMAS, EPISODES, DORM, TOWER, CONFIRM }

    /** What a page needs from the wizard. */
    interface Callbacks {
        /** The draft changed (persist it). */
        void changed();

        /** Go to the next page (or back to 修改哪一项？ / the review after a single page). */
        void next();

        /** Re-render the current page. */
        void refresh();

        /** Opens {@code page} on its own; its 完成 comes back to the current page. */
        void open(Page page);
    }

    private final Activity c;
    private final StateDraft draft;
    private final Callbacks callbacks;
    /** Option lists of the page just built, cascaded in when the page is entered. */
    private final List<ViewGroup> cascades = new ArrayList<>();
    /** 「做过」 was answered on the 宿舍活动 page (while every count is still 0). */
    private boolean dormOpen;

    StateSteps(Activity activity, StateDraft draft, Callbacks callbacks) {
        this.c = activity;
        this.draft = draft;
        this.callbacks = callbacks;
    }

    /**
     * Every page this run of the wizard may show, in order. Onboarding asks where the game is
     * first and the goals and the 人格面具 last; 同步现状 starts with 「之后照着安排玩了吗？」;
     * 修改进度 starts on 修改哪一项？. Pages that have nothing to ask for the chosen date are
     * skipped ({@link #applies}).
     */
    static List<Page> skeletonFor(StateDraft draft) {
        List<Page> pages = new ArrayList<>();
        if (draft.mode == StateDraft.Mode.SYNC) {
            pages.add(Page.SYNC_INTRO);
        } else if (draft.mode == StateDraft.Mode.EDIT) {
            pages.add(Page.HUB);
        }
        pages.addAll(Arrays.asList(Page.DATE, Page.CASH, Page.STATS, Page.LINKS, Page.UNLOCK, Page.CHRISTMAS,
            Page.EPISODES, Page.DORM, Page.TOWER));
        if (draft.mode == StateDraft.Mode.ONBOARDING) {
            pages.add(Page.GOALS);
            pages.add(Page.PERSONA);
        }
        pages.add(Page.CONFIRM);
        return pages;
    }

    /**
     * The pages a walk through the wizard shows now: the step counter's total. 修改哪一项？ and
     * the opening 「之后照着安排玩了吗？」 choose the way and are not counted.
     */
    static List<Page> pagesFor(StateDraft draft) {
        List<Page> pages = new ArrayList<>();
        for (Page page : skeletonFor(draft)) {
            if (page != Page.HUB && page != Page.SYNC_INTRO && applies(page, draft)) {
                pages.add(page);
            }
        }
        return pages;
    }

    /**
     * True when a walk through the wizard stops at {@code page}: after 「基本照着玩了」 the
     * forecast fills every detail page, so the walk goes from the date straight to the review.
     */
    static boolean applies(Page page, StateDraft draft) {
        if (page == Page.HUB) {
            return draft.mode == StateDraft.Mode.EDIT;
        }
        if (draft.mode == StateDraft.Mode.SYNC && draft.sync() == StateDraft.SyncAnswer.YES && isDetail(page)) {
            return false;
        }
        return hasContent(page, draft);
    }

    /** The game-state pages between the date and the review. */
    static boolean isDetail(Page page) {
        switch (page) {
            case CASH:
            case STATS:
            case LINKS:
            case UNLOCK:
            case CHRISTMAS:
            case EPISODES:
            case DORM:
            case TOWER:
                return true;
            default:
                return false;
        }
    }

    /** True when {@code page} has something to ask for the chosen date. */
    static boolean hasContent(Page page, StateDraft draft) {
        switch (page) {
            case UNLOCK:
                return !draft.unlockLinks().isEmpty();
            case CHRISTMAS:
                return draft.asksChristmas();
            case EPISODES:
                return !draft.visibleEpisodeTracks().isEmpty();
            case DORM:
                return !draft.dormActivities().isEmpty();
            case TOWER:
                return draft.towerTop() > 0 || !draft.rescuesToAsk().isEmpty();
            default:
                return true;
        }
    }

    /** Short title of a page (spoken with the step counter, and the review's 「还有别的变化？」). */
    static String titleOf(Page page) {
        switch (page) {
            case GOALS:
                return "目标";
            case PERSONA:
                return "人格面具";
            case SYNC_INTRO:
                return "之后玩了多少";
            case HUB:
                return "修改哪一项";
            case DATE:
                return "日期";
            case CASH:
                return "现金";
            case STATS:
                return "个人能力值";
            case LINKS:
                return "社群";
            case UNLOCK:
                return "解锁前的准备";
            case CHRISTMAS:
                return "平安夜";
            case EPISODES:
                return "羁绊剧情";
            case DORM:
                return "宿舍活动";
            case TOWER:
                return "塔尔塔罗斯";
            default:
                return "确认";
        }
    }

    /** The option lists of the page built last (cascaded in on entry, never on a refresh). */
    List<ViewGroup> cascades() {
        return cascades;
    }

    View build(Page page) {
        cascades.clear();
        switch (page) {
            case GOALS:
                return goals();
            case PERSONA:
                return persona();
            case SYNC_INTRO:
                return syncIntro();
            case HUB:
                return hub();
            case DATE:
                return date();
            case CASH:
                return cash();
            case STATS:
                return stats();
            case LINKS:
                return links();
            case UNLOCK:
                return unlock();
            case CHRISTMAS:
                return christmas();
            case EPISODES:
                return episodes();
            case DORM:
                return dorm();
            case TOWER:
                return tower();
            default:
                return draft.observed == null ? ready() : review();
        }
    }

    // ------------------------------------------------------------------ pieces

    /**
     * The page header: a small cyan Latin kicker over the big question, a cyan underline fading to
     * the right, and the kicker again as a huge faint outlined watermark (decoration only).
     */
    private View header(CharSequence question, String kicker, View trailing) {
        FrameLayout frame = new FrameLayout(c);
        frame.setClipChildren(true);
        TextView mark = new Ui.OutlinedText(c, kicker);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        mp.topMargin = -Ui.dp(c, 4);
        mp.rightMargin = -Ui.dp(c, 24);
        frame.addView(mark, mp);
        LinearLayout column = Ui.column(c);
        TextView en = Ui.numeral(c, kicker, 15, Ui.col(c, R.color.p3r_cyan_400));
        en.setLetterSpacing(0.14f);
        en.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        en.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.addView(en, Ui.wrap());
        LinearLayout line = Ui.row(c);
        line.setGravity(Gravity.BOTTOM);
        TextView q = Ui.mixed(c, question, 26, Ui.col(c, R.color.p3r_white), true);
        q.setLineSpacing(0f, 1.08f);
        q.setShadowLayer(0.01f, Ui.dp(c, 2), Ui.dp(c, 2), Ui.alpha(Ui.col(c, R.color.p3r_royal_500), 200));
        if (Build.VERSION.SDK_INT >= 28) {
            q.setAccessibilityHeading(true);
        }
        line.addView(q, Ui.weight(1));
        if (trailing != null) {
            line.addView(trailing, Ui.wrap());
        }
        column.addView(line, top(2));
        View underline = new View(c);
        underline.setBackground(new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Ui.col(c, R.color.p3r_cyan_400), Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 0)}));
        underline.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 3));
        up.topMargin = Ui.dp(c, 8);
        up.rightMargin = Ui.dp(c, 64);
        column.addView(underline, up);
        frame.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    private LinearLayout page(CharSequence question, String kicker) {
        return page(question, kicker, null);
    }

    private LinearLayout page(CharSequence question, String kicker, View trailing) {
        LinearLayout column = Ui.column(c);
        column.addView(header(question, kicker, trailing), Ui.matchWrap());
        return column;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    /** The one small 「预计」 tag of a forecast value. */
    private TextView forecastTag() {
        return forecastTag("预计");
    }

    private TextView forecastTag(CharSequence label) {
        return Ui.tag(c, label, Ui.alpha(Ui.col(c, R.color.p3r_cyan_200), 40), Ui.col(c, R.color.p3r_cyan_200));
    }

    /** Hairline between list rows. */
    private View rule(int topDp) {
        View rule = Ui.fadingRule(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            Math.max(1, Ui.dp(c, 1)));
        p.topMargin = Ui.dp(c, topDp);
        rule.setLayoutParams(p);
        return rule;
    }

    /** A row background that only lights up while pressed. */
    private SlantedPanelDrawable pressable() {
        return new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint)).skew(Ui.dp(c, 8));
    }

    /** Borderless glass for a grid cell (no outline, no corner tick). */
    private SlantedPanelDrawable glass() {
        int cut = Ui.dp(c, 10);
        return new SlantedPanelDrawable()
            .gradient(Ui.col(c, R.color.ui_glass_top), Ui.col(c, R.color.ui_glass_bottom))
            .pressedFill(Ui.col(c, R.color.p3r_navy_700))
            .pressedStroke(Ui.col(c, R.color.p3r_cyan_400))
            .cuts(cut, 0, cut, 0);
    }

    private View chevron(int color) {
        View icon = Ui.icon(c, R.drawable.ui_ic_chevron, color, 18);
        return icon;
    }

    /**
     * A big slanted answer plate (「基本照着玩了」, 「都带」): one tap answers; the chosen one is
     * cyan with a white slab. {@code line} is an optional small second line.
     */
    private LinearLayout answerPlate(String title, String line, boolean selected, Runnable onTap) {
        LinearLayout plate = Ui.row(c);
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 12));
        if (selected) {
            bg.fill(Ui.col(c, R.color.p3r_cyan_400)).pressedFill(Ui.col(c, R.color.p3r_cyan_200))
                .slab(Ui.alpha(Ui.col(c, R.color.p3r_white), 210), Ui.dp(c, 4), Ui.dp(c, 4));
        } else {
            bg.gradient(Ui.col(c, R.color.ui_glass_top), Ui.col(c, R.color.ui_glass_bottom))
                .pressedFill(Ui.col(c, R.color.p3r_navy_700)).pressedStroke(Ui.col(c, R.color.p3r_cyan_400));
        }
        plate.setBackground(bg);
        plate.setMinimumHeight(Ui.dp(c, 64));
        Ui.pad(plate, 24, 10, 22, 12);
        LinearLayout texts = Ui.column(c);
        int ink = Ui.col(c, selected ? R.color.p3r_navy_950 : R.color.p3r_white);
        texts.addView(Ui.mixed(c, title, 19, ink, true));
        if (line != null) {
            TextView small = Ui.mixed(c, line, 13, selected ? Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 200)
                : Ui.col(c, R.color.p3r_ink_muted), false);
            texts.addView(small, top(1));
        }
        plate.addView(texts, Ui.weight(1));
        plate.addView(chevron(selected ? Ui.col(c, R.color.p3r_navy_950) : Ui.col(c, R.color.p3r_cyan_300)));
        plate.setClickable(true);
        plate.setFocusable(true);
        plate.setSelected(selected);
        plate.setContentDescription(title + (line == null ? "" : "，" + line) + (selected ? "，已选" : ""));
        plate.setOnClickListener(v -> onTap.run());
        Motion.pressable(plate);
        return plate;
    }

    /** A column of answer plates, cascaded in on entry. */
    private LinearLayout plates(LinearLayout... items) {
        LinearLayout list = Ui.column(c);
        for (int i = 0; i < items.length; i++) {
            list.addView(items[i], top(i == 0 ? 0 : 12));
        }
        cascades.add(list);
        return list;
    }

    // ------------------------------------------------------------------ 修改哪一项？

    /**
     * 修改进度 starts here: every part of the progress with its value; one tap opens that page
     * alone and 完成 comes back. Changed values turn cyan with a 「已改」 tag.
     */
    private View hub() {
        LinearLayout column = page("修改哪一项？", "PROGRESS");
        Set<String> changed = new HashSet<>();
        for (StateDraft.Change change : draft.changes()) {
            if (change.page != null) {
                changed.add(change.page);
            }
        }
        LinearLayout list = Ui.column(c);
        for (Page page : skeletonFor(draft)) {
            if (!isDetail(page) && page != Page.DATE) {
                continue;
            }
            if (!hasContent(page, draft)) {
                continue;
            }
            if (list.getChildCount() > 0) {
                list.addView(rule(0));
            }
            list.addView(hubRow(page, hubValue(page), changed.contains(page.name())));
        }
        column.addView(list, top(14));
        cascades.add(list);
        return column;
    }

    private View hubRow(Page page, CharSequence value, boolean edited) {
        LinearLayout row = Ui.row(c);
        row.setBackground(pressable());
        row.setMinimumHeight(Ui.dp(c, 64));
        Ui.pad(row, 6, 10, 4, 10);
        LinearLayout texts = Ui.column(c);
        String label = titleOf(page);
        texts.addView(Ui.text(c, label, 17, Ui.col(c, R.color.p3r_white), true));
        TextView v = Ui.mixed(c, value, 14, Ui.col(c, edited ? R.color.p3r_cyan_300 : R.color.p3r_ink_muted),
            edited);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(v, top(2));
        row.addView(texts, Ui.weight(1));
        if (edited) {
            TextView tag = Ui.tag(c, "已改", Ui.col(c, R.color.p3r_cyan_400), Ui.col(c, R.color.p3r_navy_950));
            LinearLayout.LayoutParams tp = Ui.wrap();
            tp.rightMargin = Ui.dp(c, 8);
            row.addView(tag, tp);
        }
        row.addView(chevron(Ui.col(c, R.color.p3r_cyan_300)));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(label + "，" + Zh.plain(value) + (edited ? "，已改" : "") + "，点按修改");
        row.setOnClickListener(x -> callbacks.open(page));
        return row;
    }

    /** The current value of a part of the progress, as 修改哪一项？ lists it. */
    private CharSequence hubValue(Page page) {
        Rules rules = draft.rules;
        switch (page) {
            case DATE:
                return Zh.slot(rules, draft.slot());
            case CASH:
                return draft.cashUnknown() ? CashPad.UNKNOWN_LABEL : Zh.yen(draft.cash());
            case STATS: {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
                    sb.append(i > 0 ? " · " : "").append(rules.stats[i].zh).append(' ').append(Zh.lv(draft.statLv(i)));
                }
                return sb.toString();
            }
            case LINKS:
                return StateDraft.establishedText(draft.established(draft.build()));
            case UNLOCK: {
                StringBuilder sb = new StringBuilder();
                for (int link : draft.unlockLinks()) {
                    sb.append(sb.length() > 0 ? "、" : "").append(rules.links[link].arcanaZh);
                }
                return sb.toString();
            }
            case CHRISTMAS:
                return ChristmasChoice.answerText(rules, draft.christmasLink());
            case EPISODES: {
                StringBuilder sb = new StringBuilder();
                for (int track : draft.visibleEpisodeTracks()) {
                    Rules.Episode e = rules.episodes[track];
                    sb.append(sb.length() > 0 ? " · " : "").append(e.personZh).append(' ')
                        .append(StateDraft.episodesText(e, draft.episodesWatched(track)));
                }
                return sb.toString();
            }
            case DORM: {
                int total = 0;
                for (Rules.Activity activity : draft.dormActivities()) {
                    total += draft.dormCount(activity.idx);
                }
                return total == 0 ? "都还没做" : "做过 " + total + " 次";
            }
            case TOWER: {
                int floor = draft.floor();
                String text = floor > 0 ? "最高 " + floor + "F" : "还没去过";
                Rules.Block block = draft.block();
                if (block != null) {
                    text += " · " + block.zh + " 去过 " + draft.towerVisits() + " 次";
                }
                return text;
            }
            default:
                return "";
        }
    }

    // ------------------------------------------------------------------ 目标

    private View goals() {
        boolean any = !LinkStatus.goalOrder(draft.profile).isEmpty();
        TextView clear = null;
        if (any) {
            clear = Ui.quiet(c, "清空");
            clear.setOnClickListener(v -> {
                for (int i = 0; i < Profile.LINKS; i++) {
                    Goals.setTier(draft.profile, i, Profile.Tier.NONE);
                }
                callbacks.changed();
                callbacks.refresh();
            });
        }
        LinearLayout column = page("想把哪些社群升到 MAX？", "GOAL");
        LinearLayout hint = Ui.row(c);
        hint.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        hint.addView(Ui.muted(c, "点一下想要，再点一下必须"), Ui.weight(1));
        if (clear != null) {
            hint.addView(clear, Ui.wrap());
        }
        column.addView(hint, top(4));
        LinearLayout grid = Ui.column(c);
        LinearLayout row = null;
        int n = 0;
        for (Rules.Link link : draft.rules.links) {
            if (link.kind != Rules.LinkKind.MANUAL) {
                continue;
            }
            if (n % 2 == 0) {
                row = Ui.row(c);
                row.setBaselineAligned(false);
                grid.addView(row, top(n == 0 ? 0 : 10));
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            if (n % 2 == 1) {
                p.leftMargin = Ui.dp(c, 10);
            }
            row.addView(goalCard(link), p);
            n++;
        }
        if (n % 2 == 1 && row != null) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1);
            p.leftMargin = Ui.dp(c, 10);
            row.addView(new View(c), p);
        }
        column.addView(grid, top(6));
        cascades.add(grid);
        return column;
    }

    /** A tarot-like card: the Roman numeral, the arcana and the person; 想要 is cyan, 必须 white. */
    private View goalCard(Rules.Link link) {
        Profile.Tier tier = draft.profile.tier[link.idx];
        boolean want = tier == Profile.Tier.WANT;
        boolean must = tier == Profile.Tier.MUST;
        FrameLayout card = new FrameLayout(c);
        SlantedPanelDrawable bg;
        if (must) {
            bg = new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_white)).pressedFill(Ui.col(c, R.color.p3r_cyan_200))
                .slab(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 4), Ui.dp(c, 4)).skew(Ui.dp(c, 8));
        } else if (want) {
            bg = new SlantedPanelDrawable().fill(Ui.col(c, R.color.p3r_cyan_400)).pressedFill(Ui.col(c, R.color.p3r_cyan_200))
                .slab(Ui.alpha(Ui.col(c, R.color.p3r_white), 210), Ui.dp(c, 3), Ui.dp(c, 3)).skew(Ui.dp(c, 8));
        } else {
            bg = glass();
        }
        card.setBackground(bg);
        card.setMinimumHeight(Ui.dp(c, 84));
        int ink = Ui.col(c, must || want ? R.color.p3r_navy_950 : R.color.p3r_white);
        LinearLayout body = Ui.column(c);
        Ui.pad(body, 16, 8, 12, 10);
        TextView roman = Ui.numeral(c, Zh.roman(link.arcanaNo), 24,
            must || want ? Ui.col(c, R.color.p3r_navy_950) : Ui.col(c, R.color.p3r_cyan_400));
        roman.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        body.addView(roman, Ui.wrap());
        TextView name = Ui.text(c, link.arcanaZh, 17, ink, true);
        body.addView(name, top(2));
        TextView person = Ui.text(c, link.personZh, 13, must || want ? Ui.alpha(ink, 190)
            : Ui.col(c, R.color.p3r_ink_muted), false);
        person.setMaxLines(1);
        person.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(person, top(1));
        card.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        if (must || want) {
            TextView tag = must
                ? Ui.tag(c, Goals.label(tier), Ui.col(c, R.color.p3r_navy_950), Ui.col(c, R.color.p3r_white))
                : Ui.tag(c, Goals.label(tier), Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 60), Ui.col(c, R.color.p3r_navy_950));
            FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
            tp.topMargin = Ui.dp(c, 10);
            tp.rightMargin = Ui.dp(c, 12);
            card.addView(tag, tp);
        }
        card.setClickable(true);
        card.setFocusable(true);
        card.setSelected(must || want);
        card.setContentDescription(link.labelZh + "，目标：" + Goals.label(tier) + "，点按切换");
        card.setOnClickListener(v -> {
            draft.cycleTier(link.idx);
            callbacks.changed();
            callbacks.refresh();
        });
        return card;
    }

    // ------------------------------------------------------------------ 人格面具

    private View persona() {
        LinearLayout column = page("见面时带同阿尔卡那的人格面具吗？", "PERSONA");
        Profile.PersonaMode mode = draft.profile.effectivePersonaMode();
        column.addView(plates(
            answerPlate("都带", "点数 ×1.51", mode == Profile.PersonaMode.ALL, () -> persona(Profile.PersonaMode.ALL)),
            answerPlate("只给目标社群带", "见面前提醒带哪个阿尔卡那", mode == Profile.PersonaMode.GOALS,
                () -> persona(Profile.PersonaMode.GOALS)),
            answerPlate("不带", null, mode == Profile.PersonaMode.NONE, () -> persona(Profile.PersonaMode.NONE))),
            top(22));
        return column;
    }

    private void persona(Profile.PersonaMode mode) {
        draft.setPersonaMode(mode);
        callbacks.changed();
        callbacks.next();
    }

    // ------------------------------------------------------------------ 同步：之后玩了多少

    /** The answers of 「之后照着安排玩了吗？」. */
    static final String SYNC_YES = "基本照着玩了";
    static final String SYNC_PARTLY = "玩了一部分";
    static final String SYNC_NO = "没怎么照着玩";

    /** 「上次记到 10月21日 晚上，之后照着安排玩了吗？」 */
    static String syncQuestion(StateDraft draft) {
        return "上次记到 " + Zh.lastRecorded(draft.rules, draft.base.slot) + "，之后照着安排玩了吗？";
    }

    private View syncIntro() {
        LinearLayout column = page(syncQuestion(draft), "WELCOME BACK");
        StateDraft.SyncAnswer answer = draft.sync();
        column.addView(plates(
            answerPlate(SYNC_YES, null, answer == StateDraft.SyncAnswer.YES, () -> answer(StateDraft.SyncAnswer.YES)),
            answerPlate(SYNC_PARTLY, null, answer == StateDraft.SyncAnswer.PARTLY,
                () -> answer(StateDraft.SyncAnswer.PARTLY)),
            answerPlate(SYNC_NO, null, answer == StateDraft.SyncAnswer.NO, () -> answer(StateDraft.SyncAnswer.NO))),
            top(24));
        return column;
    }

    private void answer(StateDraft.SyncAnswer value) {
        draft.setSync(value);
        callbacks.changed();
        callbacks.next();
    }

    // ------------------------------------------------------------------ 日期

    private View date() {
        LinearLayout column = page("游戏里现在是哪一天？", "CALENDAR");
        Rules rules = draft.rules;
        int day = Rules.dayOf(draft.slot());
        DatePlate plate = new DatePlate(c, false);
        plate.bind(rules, draft.slot());
        column.addView(plate, top(12));

        // The period as two plates; a period the story takes shows why, inside its plate.
        int daySlot = Rules.slot(day, false);
        int eveSlot = Rules.slot(day, true);
        String dayLock = rules.lockOf(daySlot);
        String eveLock = rules.lockOf(eveSlot);
        List<String> labels = Arrays.asList(periodLabel(Zh.period(rules, daySlot), dayLock),
            periodLabel("晚上", eveLock));
        SegmentedControl period = new SegmentedControl(c, labels);
        period.setSelected(Rules.isEvening(draft.slot()) ? 1 : 0);
        period.setSegmentEnabled(0, dayLock == null);
        period.setSegmentEnabled(1, eveLock == null);
        for (int i = 0; i < 2; i++) {
            period.segment(i).setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            period.segment(i).setMinHeight(Ui.dp(c, Ui.TOUCH));
        }
        period.setListener(i -> {
            draft.setSlot(Rules.slot(day, i == 1));
            callbacks.changed();
            callbacks.refresh();
        });
        column.addView(period, top(10));

        int marked = draft.observed == null ? -1 : Rules.dayOf(draft.observed.slot);
        CalendarPicker picker = new CalendarPicker(c, rules, day, marked);
        picker.setListener(d -> {
            boolean evening = Rules.isEvening(draft.slot());
            int slot = Rules.slot(d, evening);
            if (rules.lockOf(slot) != null) {
                slot = Rules.slot(d, !evening);
            }
            draft.setSlot(slot);
            callbacks.changed();
            callbacks.refresh();
        });
        column.addView(picker, top(16));
        return column;
    }

    /** 「晚上」, or 「晚上 · 满月作战」 when the story takes it. */
    private String periodLabel(String period, String lock) {
        String reason = lock == null ? null : draft.rules.lockReasonsZh.get(lock);
        return reason == null || reason.isEmpty() ? period : period + " · " + reason;
    }

    // ------------------------------------------------------------------ 现金

    private View cash() {
        LinearLayout column = page("现在有多少钱？", "MONEY");
        CashPad.Options options = new CashPad.Options();
        options.initial = draft.cashUnknown() ? null : draft.cash();
        options.badge = draft.isForecastCash() ? "预计" : null;
        View pad = CashPad.inline(c, options, amount -> {
            if (amount == null) {
                draft.setCashUnknown();
            } else {
                draft.setCash(amount);
            }
            callbacks.changed();
        });
        column.addView(pad, top(10));
        return column;
    }

    // ------------------------------------------------------------------ 个人能力值

    private View stats() {
        LinearLayout column = page("学力、魅力、勇气现在几级？", "STATUS");
        SimState forecast = draft.sync() == StateDraft.SyncAnswer.PARTLY ? draft.forecast() : null;
        LinearLayout list = Ui.column(c);
        for (Rules.Stat stat : draft.rules.stats) {
            if (stat.idx >= 3) {
                continue;
            }
            if (list.getChildCount() > 0) {
                list.addView(rule(0));
            }
            LinearLayout row = Ui.row(c);
            Ui.pad(row, 0, 14, 0, 14);
            LinearLayout left = Ui.column(c);
            left.addView(Ui.text(c, stat.zh, 18, Ui.col(c, R.color.p3r_white), true));
            if (draft.isForecastStat(stat.idx)) {
                left.addView(forecastTag(), wrapTop(4));
            } else if (forecast != null) {
                int f = stat.levelFor(forecast.statPts[stat.idx]);
                if (f != draft.statLv(stat.idx)) {
                    left.addView(hintChip("预计 " + Zh.lv(f), () -> {
                        draft.setStatLv(stat.idx, f);
                        callbacks.changed();
                        callbacks.refresh();
                    }), wrapTop(4));
                }
            }
            left.setMinimumWidth(Ui.dp(c, 64));
            row.addView(left, Ui.wrap());
            List<String> labels = Arrays.asList("1", "2", "3", "4", "5", "MAX");
            SegmentedControl seg = new SegmentedControl(c, labels);
            seg.setSelected(draft.statLv(stat.idx) - 1);
            for (int i = 0; i < 6; i++) {
                TextView segment = seg.segment(i);
                segment.setContentDescription(stat.zh + " " + Zh.lv(i + 1));
                // Six segments share one row: keep "MAX" on one line.
                segment.setSingleLine(true);
                Ui.pad(segment, 2, 6, 2, 6);
            }
            seg.setListener(i -> {
                draft.setStatLv(stat.idx, i + 1);
                callbacks.changed();
                callbacks.refresh();
            });
            LinearLayout.LayoutParams sp = Ui.weight(1);
            sp.leftMargin = Ui.dp(c, 12);
            row.addView(seg, sp);
            list.addView(row);
        }
        column.addView(list, top(10));
        cascades.add(list);
        return column;
    }

    private LinearLayout.LayoutParams wrapTop(int dp) {
        LinearLayout.LayoutParams p = Ui.wrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    /** 「预计 Lv.4」 after 「玩了一部分」: one tap takes the forecast. */
    private TextView hintChip(String label, Runnable onTap) {
        TextView chip = Ui.tag(c, label, Ui.alpha(Ui.col(c, R.color.p3r_cyan_200), 40), Ui.col(c, R.color.p3r_cyan_200));
        chip.setBackground(new SlantedPanelDrawable().fill(Ui.alpha(Ui.col(c, R.color.p3r_cyan_200), 40))
            .stroke(Ui.col(c, R.color.p3r_cyan_200), Ui.dp(c, 1))
            .pressedFill(Ui.col(c, R.color.ui_cyan_soft)).skew(Ui.dp(c, 5)));
        Ui.pad(chip, 10, 6, 10, 6);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setContentDescription(label + "，点按采用");
        chip.setOnClickListener(v -> onTap.run());
        return chip;
    }

    // ------------------------------------------------------------------ 社群

    private View links() {
        LinearLayout column = page("社群都升到几级了？", "SOCIAL LINK");
        SimState forecast = draft.sync() == StateDraft.SyncAnswer.PARTLY ? draft.forecast() : null;
        List<Integer> order = new ArrayList<>();
        for (Rules.Link link : draft.rules.links) {
            order.add(link.idx);
        }
        LinearLayout grid = Ui.column(c);
        LinearLayout row = null;
        for (Rules.Link link : draft.rules.links) {
            if (link.idx % 2 == 0) {
                row = Ui.row(c);
                grid.addView(row, top(link.idx == 0 ? 0 : 8));
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, Ui.dp(c, 64), 1);
            if (link.idx % 2 == 1) {
                p.leftMargin = Ui.dp(c, 8);
            }
            row.addView(rankCell(link, order, forecast), p);
        }
        column.addView(grid, top(16));
        cascades.add(grid);
        return column;
    }

    private View rankCell(Rules.Link link, List<Integer> order, SimState forecast) {
        int r = draft.rank(link.idx);
        boolean edited = draft.rank[link.idx] != null;
        boolean predicted = draft.isForecastRank(link.idx);
        LinearLayout cell = Ui.row(c);
        SlantedPanelDrawable bg = glass();
        if (edited) {
            bg.cornerTick(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 2));
        }
        cell.setBackground(bg);
        Ui.pad(cell, 10, 4, 8, 4);
        boolean dim = r == 0;
        TextView roman = Ui.numeral(c, Zh.roman(link.arcanaNo), 20,
            dim ? Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 120) : Ui.col(c, R.color.p3r_cyan_400));
        roman.setMinWidth(Ui.dp(c, 32));
        roman.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(roman, Ui.wrap());
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, link.arcanaZh, 15, dim ? Ui.col(c, R.color.p3r_ink_muted) : Ui.col(c, R.color.p3r_white),
            true));
        LinearLayout valueRow = Ui.row(c);
        if (r == StateDraft.UNKNOWN) {
            valueRow.addView(Ui.text(c, StateDraft.UNSURE_RANK, 14, Ui.col(c, R.color.p3r_ink_muted), true), Ui.wrap());
        } else if (r == 0) {
            valueRow.addView(Ui.text(c, "未解锁", 14, Ui.col(c, R.color.p3r_ink_faint), false), Ui.wrap());
        } else {
            valueRow.addView(Ui.numeral(c, Zh.rank(r), 20, Ui.col(c, R.color.p3r_white)), Ui.wrap());
        }
        TextView tag = null;
        if (predicted) {
            tag = forecastTag();
        } else if (forecast != null && forecast.rank[link.idx] != r && r != StateDraft.UNKNOWN) {
            // A compact row: the forecast as 「≈7」 beside the record.
            tag = forecastTag("≈" + Zh.rankValue(forecast.rank[link.idx]));
            tag.setContentDescription("预计 " + Zh.rank(forecast.rank[link.idx]));
        }
        if (tag != null) {
            LinearLayout.LayoutParams tp = Ui.wrap();
            tp.leftMargin = Ui.dp(c, 6);
            valueRow.addView(tag, tp);
        }
        texts.addView(valueRow);
        LinearLayout.LayoutParams tp = Ui.weight(1);
        tp.leftMargin = Ui.dp(c, 4);
        cell.addView(texts, tp);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setContentDescription(link.arcanaZh + "，" + (r == StateDraft.UNKNOWN ? StateDraft.UNSURE_RANK : Zh.rank(r))
            + "，点按修改");
        cell.setOnClickListener(v -> openRankPad(order, link.idx));
        return cell;
    }

    /**
     * The RANK keypad, copied link by link from the game. A first run has no 「?」 (it would be
     * saved as 未解锁 anyway); 同步现状 / 修改进度 call it 「不记得」, which keeps the record.
     */
    private void openRankPad(List<Integer> order, int start) {
        boolean firstRun = draft.mode == StateDraft.Mode.ONBOARDING;
        RankPad pad = RankPad.show(c, draft.rules, order, start, new RankPad.Listener() {
            @Override
            public void onPicked(int linkIdx, int rank) {
                draft.setRank(linkIdx, firstRun && rank == StateDraft.UNKNOWN ? 0 : rank);
                callbacks.changed();
                callbacks.refresh();
            }

            @Override
            public int currentRank(int linkIdx) {
                return draft.rank(linkIdx);
            }
        });
        adaptUnknownKey(pad.dialog() == null ? null : pad.dialog().getWindow().getDecorView(), firstRun);
    }

    /**
     * Hides (first run) or names (「不记得」, unless the pad already does) the pad's 「?」 key,
     * found by its value tag.
     */
    private void adaptUnknownKey(View view, boolean hide) {
        if (view == null) {
            return;
        }
        if (view instanceof TextView && Integer.valueOf(StateDraft.UNKNOWN).equals(view.getTag())) {
            TextView key = (TextView) view;
            if (hide) {
                key.setVisibility(View.INVISIBLE);
                key.setClickable(false);
            } else if (!Zh.plain(key.getText()).contains(StateDraft.UNSURE_RANK)) {
                key.setText(StateDraft.UNSURE_RANK);
                key.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                key.setContentDescription(StateDraft.UNSURE_RANK);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                adaptUnknownKey(group.getChildAt(i), hide);
            }
        }
    }

    // ------------------------------------------------------------------ 解锁前的准备

    private View unlock() {
        LinearLayout column = page("解锁准备做到哪一步了？", "PREPARATION");
        LinearLayout list = Ui.column(c);
        for (int linkIdx : draft.unlockLinks()) {
            Rules.Link link = draft.rules.links[linkIdx];
            int steps = link.unlock.steps.size();
            int done = Math.max(0, Math.min(draft.unlockStep(linkIdx), steps - 1));
            if (list.getChildCount() > 0) {
                list.addView(rule(14));
            }
            LinearLayout head = Ui.row(c);
            head.addView(Ui.text(c, link.labelZh, 17, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
            if (done > 0) {
                TextView reset = Ui.quiet(c, "都还没做");
                reset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                reset.setOnClickListener(v -> setUnlock(linkIdx, 0));
                head.addView(reset, Ui.wrap());
            } else {
                head.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
            }
            list.addView(head, top(list.getChildCount() == 0 ? 0 : 10));
            for (int k = 0; k < steps - 1; k++) {
                final int value = k + 1;
                boolean isDone = k < done;
                list.addView(stepRow(k + 1, link.unlock.steps.get(k).zh, isDone,
                    () -> setUnlock(linkIdx, done == value ? value - 1 : value)), top(4));
            }
        }
        column.addView(list, top(14));
        cascades.add(list);
        return column;
    }

    private void setUnlock(int link, int done) {
        draft.setUnlockStep(link, done);
        callbacks.changed();
        callbacks.refresh();
    }

    /** One preparation step: its number (a cyan check once done) and the step. */
    private View stepRow(int number, String text, boolean done, Runnable onTap) {
        LinearLayout row = Ui.row(c);
        row.setBackground(pressable());
        row.setMinimumHeight(Ui.dp(c, 52));
        Ui.pad(row, 4, 6, 4, 6);
        FrameLayout mark = new FrameLayout(c);
        SlantedPanelDrawable plate = new SlantedPanelDrawable().skew(Ui.dp(c, 5));
        if (done) {
            plate.fill(Ui.col(c, R.color.p3r_cyan_400));
            mark.addView(Ui.icon(c, R.drawable.ui_ic_check, Ui.col(c, R.color.p3r_navy_950), 18),
                new FrameLayout.LayoutParams(Ui.dp(c, 18), Ui.dp(c, 18), Gravity.CENTER));
        } else {
            plate.stroke(Ui.col(c, R.color.p3r_cyan_300), Ui.dp(c, 1.5f));
            TextView n = Ui.numeral(c, String.valueOf(number), 18, Ui.col(c, R.color.p3r_cyan_300));
            mark.addView(n, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        mark.setBackground(plate);
        row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 32)));
        TextView label = Ui.mixed(c, text, 15, Ui.col(c, done ? R.color.p3r_white : R.color.p3r_ink_muted), done);
        LinearLayout.LayoutParams lp = Ui.weight(1);
        lp.leftMargin = Ui.dp(c, 12);
        row.addView(label, lp);
        row.setClickable(true);
        row.setFocusable(true);
        row.setSelected(done);
        row.setContentDescription(text + (done ? "，做完了" : ""));
        row.setOnClickListener(v -> onTap.run());
        return row;
    }

    // ------------------------------------------------------------------ 平安夜

    private View christmas() {
        LinearLayout column = page("平安夜接受了谁的邀约？", "CHRISTMAS EVE");
        String current = draft.christmasLink();
        List<LinearLayout> items = new ArrayList<>();
        items.add(answerPlate(ChristmasChoice.DECLINED, null, current == null, () -> christmas(null)));
        for (Rules.Link link : draft.christmasCandidates()) {
            int r = Math.max(0, draft.rank(link.idx));
            items.add(answerPlate(ChristmasChoice.acceptedText(link), link.arcanaZh + " · " + Zh.rank(r),
                link.id.equals(current), () -> christmas(link.id)));
        }
        column.addView(plates(items.toArray(new LinearLayout[0])), top(22));
        return column;
    }

    private void christmas(String linkId) {
        draft.setChristmasLink(linkId);
        callbacks.changed();
        callbacks.next();
    }

    // ------------------------------------------------------------------ 羁绊剧情

    /** Watched episodes of the opted-in tracks that could have been watched by the chosen date. */
    private View episodes() {
        LinearLayout column = page("羁绊剧情看到第几话了？", "LINK EPISODE");
        LinearLayout list = Ui.column(c);
        for (int track : draft.visibleEpisodeTracks()) {
            Rules.Episode e = draft.rules.episodes[track];
            if (list.getChildCount() > 0) {
                list.addView(rule(14));
            }
            LinearLayout head = Ui.row(c);
            head.addView(Ui.text(c, e.personZh, 17, Ui.col(c, R.color.p3r_white), true), Ui.wrap());
            if (draft.isForecastEpisodes(track)) {
                LinearLayout.LayoutParams tp = Ui.wrap();
                tp.leftMargin = Ui.dp(c, 10);
                head.addView(forecastTag(), tp);
            }
            list.addView(head, top(list.getChildCount() == 0 ? 0 : 12));
            list.addView(watchedChips(e, draft.episodesWatched(track), n -> {
                draft.setEpisodesWatched(track, n);
                callbacks.changed();
                callbacks.refresh();
            }), top(10));
        }
        column.addView(list, top(16));
        cascades.add(list);
        return column;
    }

    /** 「还没看」「1 话」…「全部 4 话」: the count of the track only in the last chip. */
    private View watchedChips(Rules.Episode track, int selected, java.util.function.IntConsumer onPick) {
        FlowLayout chips = new FlowLayout(c, 8, 8);
        for (int n = 0; n <= track.count; n++) {
            final int value = n;
            String label = n == 0 ? "还没看" : n == track.count ? "全部 " + n + " 话" : n + " 话";
            TextView chip = Ui.chip(c, label, n == selected);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            Ui.pad(chip, 12, 6, 12, 6);
            chip.setContentDescription(track.personZh + "：" + StateDraft.episodesText(track, n));
            chip.setOnClickListener(v -> onPick.accept(value));
            chips.addView(chip);
        }
        return chips;
    }

    // ------------------------------------------------------------------ 宿舍活动

    private View dorm() {
        if (draft.noDormYet() && !dormOpen) {
            // Nothing counted yet: one question first, and 都没做过 skips the page.
            LinearLayout column = page("做过宿舍活动吗？", "DORM");
            column.addView(plates(
                answerPlate("都没做过", null, false, callbacks::next),
                answerPlate("做过", null, false, () -> {
                    dormOpen = true;
                    callbacks.refresh();
                })), top(22));
            return column;
        }
        LinearLayout column = page("宿舍活动各做过几次？", "DORM");
        Map<String, List<Rules.Activity>> partners = new LinkedHashMap<>();
        for (Rules.Activity activity : draft.dormActivities()) {
            partners.computeIfAbsent(activity.dormPartnerZh, ignored -> new ArrayList<>()).add(activity);
        }
        LinearLayout list = Ui.column(c);
        for (Map.Entry<String, List<Rules.Activity>> partner : partners.entrySet()) {
            if (list.getChildCount() > 0) {
                list.addView(rule(12));
            }
            LinearLayout head = Ui.row(c);
            head.addView(Ui.text(c, partner.getKey(), 17, Ui.col(c, R.color.p3r_white), true), Ui.wrap());
            String milestone = draft.dormMilestone(partner.getKey());
            if (milestone != null) {
                LinearLayout.LayoutParams mp = Ui.wrap();
                mp.leftMargin = Ui.dp(c, 10);
                head.addView(Ui.tag(c, milestone, Ui.col(c, R.color.p3r_cyan_400), Ui.col(c, R.color.p3r_navy_950)), mp);
            }
            boolean projected = false;
            for (Rules.Activity activity : partner.getValue()) {
                projected |= draft.isForecastDormCount(activity.idx);
            }
            if (projected) {
                LinearLayout.LayoutParams fp = Ui.wrap();
                fp.leftMargin = Ui.dp(c, 8);
                head.addView(forecastTag(), fp);
            }
            list.addView(head, top(list.getChildCount() == 0 ? 0 : 12));
            for (Rules.Activity activity : partner.getValue()) {
                LinearLayout row = Ui.row(c);
                TextView name = Ui.mixed(c, activity.zh, 14, Ui.col(c, R.color.p3r_ice_100), false);
                name.setMaxLines(2);
                row.addView(name, Ui.weight(1));
                SegmentedControl count = new SegmentedControl(c, Arrays.asList("0", "1", "2", "3"));
                count.setSelected(draft.dormCount(activity.idx));
                for (int n = 0; n <= 3; n++) {
                    count.segment(n).setContentDescription(activity.zh + "：" + n + " 次");
                    Ui.pad(count.segment(n), 4, 4, 4, 4);
                }
                count.setListener(value -> {
                    draft.setDormCount(activity.idx, value);
                    callbacks.changed();
                    callbacks.refresh();
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(c, 168),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.leftMargin = Ui.dp(c, 10);
                row.addView(count, cp);
                list.addView(row, top(8));
            }
        }
        column.addView(list, top(16));
        cascades.add(list);
        return column;
    }

    // ------------------------------------------------------------------ 塔尔塔罗斯

    private View tower() {
        LinearLayout column = page("塔尔塔罗斯爬到几层了？", "TARTARUS");
        Rules.Block block = draft.block();
        int top = draft.towerTop();
        if (block != null) {
            column.addView(forecastTagless(block.zh + " " + block.floorsZh), wrapTop(12));
        }
        if (top > 0) {
            Stepper floor = new Stepper(c, 0, top, draft.floor()).hero().typeable("最高到达几层？", "F");
            floor.setFormatter(v -> floorText(v, top));
            floor.setDescriber(v -> v == 0 ? "最高到达：还没去过" : "最高到达 " + v + "F");
            floor.setListener(v -> {
                draft.setFloor(v);
                callbacks.changed();
            });
            column.addView(floor, top(14));
        }
        if (block != null) {
            Profile profile = draft.profile;
            int cap = profile.capFor(block.id);
            LinearLayout visits = Ui.row(c);
            visits.addView(Ui.mixed(c, block.zh + "去过几次？", 16, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
            Stepper stepper = new Stepper(c, 0, Math.max(Math.max(cap, 4), draft.towerVisits()), draft.towerVisits())
                .compact();
            stepper.setFormatter(v -> v + " 次");
            stepper.setListener(v -> {
                draft.setTowerVisits(v);
                callbacks.changed();
            });
            visits.addView(stepper, Ui.wrap());
            column.addView(rule(18));
            column.addView(visits, top(12));
        }
        for (int index : draft.rescuesToAsk()) {
            column.addView(rule(16));
            column.addView(rescueRow(index), top(12));
        }
        return column;
    }

    /** A slanted cyan-outline-free plate for the block of the date ("提齐亚 69–117F"). */
    private TextView forecastTagless(String text) {
        return Ui.tag(c, text, Ui.alpha(Ui.col(c, R.color.p3r_cyan_400), 50), Ui.col(c, R.color.p3r_cyan_200));
    }

    /** "96F" with a faint "/117F" (the top of the date), or "—" before the first visit. */
    private CharSequence floorText(int value, int top) {
        SpannableStringBuilder sb = new SpannableStringBuilder(value == 0 ? "—" : value + "F");
        int start = sb.length();
        sb.append("/").append(String.valueOf(top)).append("F");
        sb.setSpan(new RelativeSizeSpan(0.42f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 170)), start, sb.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /**
     * 「北村文吉救出来了吗？」 with 「期限 10月3日 · 教皇」: [救出了] [没救出] once the window has
     * closed, [救出了] [还没] while it runs. Nothing is selected while the record does not know;
     * a recorded 没救出 is preselected with a small 「上次记录」 tag.
     */
    private View rescueRow(int index) {
        Rules.Rescue rescue = draft.rules.rescues[index];
        boolean open = draft.rescueOpen(index);
        LinearLayout box = Ui.column(c);
        box.addView(Ui.text(c, rescue.zh + "救出来了吗？", 16, Ui.col(c, R.color.p3r_white), true));
        LinearLayout tags = Ui.row(c);
        // The limit evening counts (DESIGN §10.3): the deadline day only, glossary 「期限 10月3日」.
        String about = "期限 " + Zh.date(rescue.toDay);
        if (rescue.link >= 0 && rescue.link < draft.rules.links.length) {
            about += " · " + draft.rules.links[rescue.link].arcanaZh;
        }
        tags.addView(forecastTagless(about), Ui.wrap());
        if (!open && draft.recordSaysFailed(index)) {
            TextView recorded = Ui.tag(c, StateDraft.RECORDED_FAILURE_NOTE, Ui.alpha(Ui.col(c, R.color.p3r_white), 30),
                Ui.col(c, R.color.p3r_ink_muted));
            LinearLayout.LayoutParams rp = Ui.wrap();
            rp.leftMargin = Ui.dp(c, 8);
            tags.addView(recorded, rp);
        }
        box.addView(tags, top(6));
        List<String> labels = open ? Arrays.asList("救出了", "还没") : Arrays.asList("救出了", "没救出");
        int[] values = open ? new int[]{StateDraft.RESCUE_DONE, StateDraft.RESCUE_UNSURE}
            : new int[]{StateDraft.RESCUE_DONE, StateDraft.RESCUE_FAILED};
        SegmentedControl seg = new SegmentedControl(c, labels);
        int answer = draft.rescueAnswer(index);
        int selected = -1;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == answer) {
                selected = i;
            }
        }
        seg.setSelected(selected);
        for (int i = 0; i < labels.size(); i++) {
            seg.segment(i).setContentDescription(rescue.zh + "：" + labels.get(i));
        }
        seg.setListener(i -> {
            if (i < 0) {
                return;
            }
            draft.setRescue(index, values[i]);
            callbacks.changed();
            callbacks.refresh();
        });
        box.addView(seg, top(10));
        return box;
    }

    // ------------------------------------------------------------------ 确认：同步现状 / 修改进度

    /**
     * 「更新这 7 项？」: what saving changes, row by row (a forecast value with its 「预计」 tag);
     * every row opens its page. After 「基本照着玩了」 the other pages are one tap away too.
     */
    private View review() {
        List<StateDraft.Change> changes = draft.changes();
        boolean same = changes.isEmpty() && !draft.profileChanged();
        LinearLayout column = page(same ? "和上次一样，不用改" : "更新这 " + changes.size() + " 项？", "UPDATE");
        if (!changes.isEmpty()) {
            LinearLayout list = Ui.column(c);
            for (StateDraft.Change change : changes) {
                if (list.getChildCount() > 0) {
                    list.addView(rule(0));
                }
                list.addView(changeRow(change));
            }
            column.addView(list, top(12));
            cascades.add(list);
        }
        if (draft.mode == StateDraft.Mode.SYNC && draft.sync() == StateDraft.SyncAnswer.YES) {
            column.addView(Ui.muted(c, "还有别的变化？"), top(22));
            FlowLayout others = new FlowLayout(c, 8, 8);
            for (Page page : skeletonFor(draft)) {
                if (!isDetail(page) || !hasContent(page, draft)) {
                    continue;
                }
                TextView chip = Ui.chip(c, titleOf(page), false);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                chip.setOnClickListener(v -> callbacks.open(page));
                others.addView(chip);
            }
            column.addView(others, top(8));
        }
        return column;
    }

    /** "现金   18,500 → 21,500 日元 [预计]"; a tap opens the value's page. */
    private View changeRow(StateDraft.Change change) {
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, 52));
        Ui.pad(row, 4, 8, 4, 8);
        TextView label = Ui.mixed(c, change.labelZh, 14, Ui.col(c, R.color.p3r_ink_muted), true);
        label.setMaxWidth(Ui.dp(c, 132));
        LinearLayout.LayoutParams lp = Ui.wrap();
        lp.rightMargin = Ui.dp(c, 10);
        row.addView(label, lp);
        // 「10月22日 放学后 → 10月25日 白天」: the new value moves to a line of its own, right-aligned,
        // when both do not fit beside the label (never 「10月25日」/「白天」).
        FlowLayout values = new FlowLayout(c, 8, 2).alignEnd();
        if (change.beforeZh != null) {
            values.addView(Ui.mixed(c, change.beforeZh, 14, Ui.col(c, R.color.p3r_ink_faint), false), Ui.wrap());
            TextView arrow = Ui.text(c, "→", 14, Ui.col(c, R.color.p3r_cyan_400), true);
            values.addView(arrow, Ui.wrap());
        }
        TextView after = Ui.mixed(c, change.afterZh, 16, Ui.col(c, R.color.p3r_white), true);
        after.setGravity(Gravity.END);
        values.addView(after, Ui.wrap());
        if (change.forecast) {
            values.addView(forecastTag(), Ui.wrap());
        }
        row.addView(values, Ui.weight(1));
        String spoken = change.labelZh + "：" + (change.beforeZh == null ? "" : change.beforeZh + " → ")
            + change.afterZh + (change.forecast ? "，预计" : "");
        if (change.page != null) {
            Page page = Page.valueOf(change.page);
            row.setBackground(pressable());
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> callbacks.open(page));
            spoken += "，点按修改";
        }
        row.setContentDescription(spoken);
        return row;
    }

    // ------------------------------------------------------------------ 确认：开始设置

    /**
     * 「准备好了？」: the date plate, cash and the three stats in big numerals (like 今天), the
     * unlocked links, the goals as arcana tags and the 人格面具 answer on one hero panel, every
     * part a tap away from its page; then the other answers of this run.
     */
    private View ready() {
        LinearLayout column = page("准备好了？", "READY");
        Rules rules = draft.rules;
        SimState built = draft.build();
        LinearLayout hero = Ui.column(c);
        hero.setBackground(Ui.heroCard(c));
        Ui.pad(hero, 22, 14, 16, 16);

        DatePlate plate = new DatePlate(c, false);
        plate.bind(rules, draft.slot());
        LinearLayout dateRow = tappable(Page.DATE, Zh.slot(rules, draft.slot()));
        dateRow.addView(plate, Ui.weight(1));
        dateRow.addView(chevron(Ui.col(c, R.color.p3r_cyan_300)));
        hero.addView(dateRow);

        LinearLayout band = Ui.row(c);
        band.setGravity(Gravity.TOP);
        band.setBaselineAligned(false);
        String cash = built.cashExact ? Zh.number(built.cash) : "—";
        View cashCell = resourceCell("现金", cash, built.cashExact ? "日元" : null, Page.CASH);
        band.addView(cashCell, Ui.weight(1.5f));
        for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
            int lv = rules.stats[i].levelFor(built.statPts[i]);
            band.addView(resourceCell(rules.stats[i].zh, Zh.lv(lv), null, Page.STATS), Ui.weight(1));
        }
        hero.addView(band, top(10));
        hero.addView(rule(12));

        hero.addView(summaryLine(Page.LINKS, "社群", Ui.text(c, StateDraft.establishedText(draft.established(built)),
            16, Ui.col(c, R.color.p3r_white), true)), top(6));
        hero.addView(summaryLine(Page.GOALS, "目标", goalTags()), top(2));
        hero.addView(summaryLine(Page.PERSONA, "人格面具",
            Ui.text(c, StateDraft.personaShort(draft.profile.effectivePersonaMode()), 16, Ui.col(c, R.color.p3r_white), true)),
            top(2));
        column.addView(hero, top(14));

        // The other answers of this run (rescues, 羁绊剧情, 宿舍活动, 平安夜).
        List<StateDraft.Change> summary = draft.changes();
        LinearLayout extras = Ui.column(c);
        for (StateDraft.Change change : summary) {
            if (change.page == null || Arrays.asList("DATE", "CASH", "STATS", "LINKS", "GOALS", "PERSONA")
                .contains(change.page)) {
                continue;
            }
            if (extras.getChildCount() > 0) {
                extras.addView(rule(0));
            }
            extras.addView(changeRow(change));
        }
        if (draft.floor() > 0) {
            if (extras.getChildCount() > 0) {
                extras.addView(rule(0));
            }
            extras.addView(changeRow(new StateDraft.Change("塔尔塔罗斯", null, "最高 " + draft.floor() + "F",
                Page.TOWER.name(), false)));
        }
        if (extras.getChildCount() > 0) {
            column.addView(extras, top(12));
        }
        return column;
    }

    /** A clickable row of the 准备好了？ hero that opens {@code page}. */
    private LinearLayout tappable(Page page, String spoken) {
        LinearLayout row = Ui.row(c);
        row.setBackground(pressable());
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(titleOf(page) + "，" + spoken + "，点按修改");
        row.setOnClickListener(v -> callbacks.open(page));
        return row;
    }

    /** A caption over a big numeral (as on 今天's resource band); a tap opens {@code page}. */
    private View resourceCell(String label, String value, String unit, Page page) {
        LinearLayout cell = Ui.column(c);
        cell.addView(Ui.text(c, label, 13, Ui.col(c, R.color.p3r_ink_muted), false));
        LinearLayout valueRow = Ui.row(c);
        valueRow.setGravity(Gravity.BOTTOM);
        valueRow.addView(Ui.numeral(c, value, 24, Ui.col(c, R.color.p3r_white)), Ui.wrap());
        if (unit != null) {
            TextView u = Ui.text(c, unit, 12, Ui.col(c, R.color.p3r_cyan_300), true);
            LinearLayout.LayoutParams up = Ui.wrap();
            up.leftMargin = Ui.dp(c, 3);
            up.bottomMargin = Ui.dp(c, 2);
            valueRow.addView(u, up);
        }
        cell.addView(valueRow);
        cell.setBackground(pressable());
        Ui.pad(cell, 2, 4, 2, 4);
        cell.setClickable(true);
        cell.setFocusable(true);
        cell.setContentDescription(label + " " + ("—".equals(value) ? CashPad.UNKNOWN_LABEL : value + (unit == null ? "" : unit))
            + "，点按修改");
        cell.setOnClickListener(v -> callbacks.open(page));
        return cell;
    }

    /** "社群   已解锁 3 个   ›" on the hero. */
    private View summaryLine(Page page, String label, View value) {
        LinearLayout row = Ui.row(c);
        row.setMinimumHeight(Ui.dp(c, 44));
        TextView caption = Ui.text(c, label, 13, Ui.col(c, R.color.p3r_ink_muted), false);
        caption.setMinWidth(Ui.dp(c, 64));
        row.addView(caption, Ui.wrap());
        LinearLayout.LayoutParams vp = Ui.weight(1);
        vp.leftMargin = Ui.dp(c, 8);
        row.addView(value, vp);
        row.addView(chevron(Ui.col(c, R.color.p3r_cyan_300)));
        row.setBackground(pressable());
        row.setClickable(true);
        row.setFocusable(true);
        String spoken = value instanceof TextView ? Zh.plain(((TextView) value).getText()) : goalsSpoken();
        row.setContentDescription(label + "，" + spoken + "，点按修改");
        row.setOnClickListener(v -> callbacks.open(page));
        return row;
    }

    /** The goals as arcana tags: 必须 on white, 想要 in cyan; 「没选」 when none. */
    private View goalTags() {
        List<Integer> order = LinkStatus.goalOrder(draft.profile);
        if (order.isEmpty()) {
            return Ui.text(c, "没选，都尽量提升", 16, Ui.col(c, R.color.p3r_white), true);
        }
        FlowLayout tags = new FlowLayout(c, 6, 6);
        for (int link : order) {
            boolean must = draft.profile.tier[link] == Profile.Tier.MUST;
            String name = draft.rules.links[link].arcanaZh;
            tags.addView(must
                ? Ui.tag(c, name, Ui.col(c, R.color.p3r_white), Ui.col(c, R.color.p3r_navy_950))
                : Ui.tag(c, name, Ui.col(c, R.color.p3r_cyan_400), Ui.col(c, R.color.p3r_navy_950)));
        }
        Ui.pad(tags, 0, 6, 0, 6);
        return tags;
    }

    private String goalsSpoken() {
        StringBuilder must = new StringBuilder();
        StringBuilder want = new StringBuilder();
        for (int link : LinkStatus.goalOrder(draft.profile)) {
            StringBuilder target = draft.profile.tier[link] == Profile.Tier.MUST ? must : want;
            target.append(target.length() > 0 ? "、" : "").append(draft.rules.links[link].arcanaZh);
        }
        String text = (must.length() > 0 ? "必须 " + must : "") + (must.length() > 0 && want.length() > 0 ? "，" : "")
            + (want.length() > 0 ? "想要 " + want : "");
        return text.isEmpty() ? "没选" : text;
    }
}
