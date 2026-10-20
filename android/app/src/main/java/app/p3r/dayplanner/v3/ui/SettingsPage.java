package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.SimState;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 规划设置, after the game's SYSTEM → CONFIG: 安排风格 as two slanted tiles, the 人格面具 question as
 * one three-way switch, then a flat list (塔尔塔罗斯 ›, 平安夜 ›, 手上至少留 ›, 其余社群也尽量提升,
 * 结缘网站的字条), the activities never to schedule (only when there are any), a folded 「更多选项」 (魅力 MAX, 目标余量,
 * 回答, 仔细重新安排) and the 从头重新安排 entry. Every change is saved at once and triggers a replan;
 * while one runs the page says so at the top. The page follows the saved profile
 * ({@link SystemList#follow}), so a rejected save puts the controls back.
 */
final class SettingsPage {
    /** Segment order of the 人格面具 choice. */
    static final Profile.PersonaMode[] PERSONA_MODES = {
        Profile.PersonaMode.ALL, Profile.PersonaMode.GOALS, Profile.PersonaMode.NONE};
    /** The 人格面具 segments on this page (in {@link #PERSONA_MODES} order). */
    static final String[] PERSONA_LABELS = {"都带", "只给目标", "不带"};
    static final String PERSONA_QUESTION = "见面时带同阿尔卡那的人格面具吗？";
    /** Under the segments while 只给目标 is chosen. */
    static final String PERSONA_GOALS_NOTE = "见面前会提醒你带哪个阿尔卡那";

    /** Choice order of the 安排风格 tiles. */
    static final Profile.Style[] STYLES = {Profile.Style.BREADTH, Profile.Style.MAX};
    /** Title and one line of each 安排风格 tile (in {@link #STYLES} order). */
    static final String[][] STYLE_TEXT = {
        {"尽量都体验", "每个社群都解锁、都往上提"},
        {"尽量多 MAX", "集中冲 MAX"},
    };

    static final String TOWER = "塔尔塔罗斯";
    static final String CHRISTMAS = "平安夜";
    static final String CASH = "手上至少留";
    static final String DATING_NOTES = "安排结缘网站的字条";
    /** Profile.otherLinks: free time after the goals goes to the other links. */
    static final String OTHER_LINKS = "其余社群也尽量提升";
    static final String EXCLUDED = "不再安排";
    static final String RESTORE = "恢复";
    static final String MORE = "更多选项";
    static final String CHARM = "魅力 MAX 后点数 ×1.51";
    static final String GOAL_BUFFER = "目标多留一次见面";
    static final String ANSWERS = "我会照提示选回答";
    /** Profile.deepChanges: a longer search for every automatic replan. */
    static final String DEEP = "仔细重新安排";
    static final String DEEP_NOTE = "每次更新多等十来秒";
    /** Top of the page while the plan is being made again. */
    static final String BUSY = "重新安排中…";

    private SettingsPage() {}

    static View build(HomeHost host) {
        return new Screen(host).root;
    }

    /** The page and its controls; {@link #bind} sets them from the saved profile. */
    private static final class Screen {
        final HomeHost host;
        final Context c;
        final LinearLayout root;
        final LinearLayout busy;
        final StyleTiles tiles;
        final SegmentedControl persona;
        final TextView personaNote;
        final SystemList.Row tower;
        final SystemList.Row christmas;
        final SystemList.Row cash;
        final ToggleRow others;
        final ToggleRow dating;
        final LinearLayout excludedBox;
        final LinearLayout excludedRows;
        final LinearLayout moreBox;
        final ImageView moreArrow;
        final View moreRow;
        final ToggleRow charm;
        final ToggleRow buffer;
        final ToggleRow answers;
        final ToggleRow deep;
        final LinearLayout replanHolder;
        String[] shownExcluded;

        Screen(HomeHost host) {
            this.host = host;
            this.c = host.activity();
            Rules rules = host.data().rules;
            root = Ui.column(c);

            busy = Ui.row(c);
            BusyStripe stripe = new BusyStripe(c);
            busy.addView(stripe, new LinearLayout.LayoutParams(0, Ui.dp(c, 4), 1));
            TextView busyText = Ui.text(c, BUSY, 13, Ui.col(c, R.color.p3r_cyan_300), true);
            LinearLayout.LayoutParams bt = Ui.wrap();
            bt.leftMargin = Ui.dp(c, 10);
            busy.addView(busyText, bt);
            busy.setVisibility(View.INVISIBLE);
            root.addView(busy, Ui.matchWrap());

            // STYLE
            root.addView(SystemList.groupHeader(c, "STYLE", "安排风格"), top(c, 4));
            tiles = new StyleTiles(c, i -> edit(host, q -> q.style = STYLES[i]));
            root.addView(tiles, top(c, 10));

            // PERSONA
            root.addView(SystemList.groupHeader(c, "PERSONA", "人格面具"), top(c, 26));
            LinearLayout question = Ui.row(c);
            question.addView(Ui.text(c, PERSONA_QUESTION, 16, Ui.col(c, R.color.p3r_white), true), Ui.weight(1));
            TextView bonus = Ui.tag(c, "×1.51", Ui.col(c, R.color.ui_cyan_soft), Ui.col(c, R.color.p3r_cyan_300));
            LinearLayout.LayoutParams bp = Ui.wrap();
            bp.leftMargin = Ui.dp(c, 10);
            question.addView(bonus, bp);
            root.addView(question, top(c, 10));
            personaNote = Ui.text(c, PERSONA_GOALS_NOTE, 13, Ui.col(c, R.color.p3r_cyan_300), false);
            persona = new SegmentedControl(c, Arrays.asList(PERSONA_LABELS));
            persona.setListener(i -> {
                Profile.PersonaMode chosen = PERSONA_MODES[i];
                personaNote.setVisibility(chosen == Profile.PersonaMode.GOALS ? View.VISIBLE : View.GONE);
                edit(host, q -> {
                    q.personaMode = chosen;
                    q.personaBonus = chosen == Profile.PersonaMode.ALL;
                });
            });
            root.addView(persona, top(c, 10));
            root.addView(personaNote, top(c, 8));

            // OPTIONS
            root.addView(SystemList.groupHeader(c, "OPTIONS", "其他设置"), top(c, 26));
            LinearLayout options = Ui.column(c);
            tower = SystemList.configRow(c, TOWER, null,
                () -> host.pushPage(TOWER, TowerSettingsPage.build(host)));
            options.addView(tower);
            christmas = SystemList.configRow(c, CHRISTMAS, null, this::pickChristmas);
            options.addView(christmas);
            cash = SystemList.configRow(c, CASH, null, this::pickCash);
            options.addView(cash);
            others = new ToggleRow(c, OTHER_LINKS, null, false).flat();
            others.setListener(on -> edit(host, q -> q.otherLinks = on));
            options.addView(others);
            if (rules != null && rules.datingNote != null) {
                dating = new ToggleRow(c, DATING_NOTES, datingNoteText(rules.datingNote), false).flat();
                dating.setListener(on -> edit(host, q -> q.datingNotes = on));
                options.addView(dating);
            } else {
                dating = null;
            }
            root.addView(options, top(c, 4));

            // 不再安排: only while there is something to bring back.
            excludedBox = Ui.column(c);
            excludedBox.addView(SystemList.groupHeader(c, null, EXCLUDED));
            excludedRows = Ui.column(c);
            excludedBox.addView(excludedRows, top(c, 4));
            root.addView(excludedBox, top(c, 26));

            // 更多选项, folded unless it was left open.
            LinearLayout more = Ui.row(c);
            more.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
            Ui.pad(more, 4, 4, 4, 4);
            TextView moreLabel = Ui.text(c, MORE, 15, Ui.col(c, R.color.p3r_cyan_300), true);
            more.addView(moreLabel, Ui.wrap());
            moreArrow = Ui.icon(c, R.drawable.ui_ic_down, Ui.col(c, R.color.p3r_cyan_300), 18);
            LinearLayout.LayoutParams ap = (LinearLayout.LayoutParams) moreArrow.getLayoutParams();
            ap.leftMargin = Ui.dp(c, 6);
            more.addView(moreArrow, ap);
            more.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
                .skew(Ui.dp(c, 8)));
            more.setClickable(true);
            more.setFocusable(true);
            moreRow = more;
            root.addView(more, top(c, 18));
            moreBox = Ui.column(c);
            charm = flatToggle(CHARM, null, (q, on) -> q.charmBonus = on);
            buffer = flatToggle(GOAL_BUFFER, null, (q, on) -> q.goalBuffer = on);
            answers = flatToggle(ANSWERS, null,
                (q, on) -> q.answers = on ? Profile.AnswerPolicy.BEST : Profile.AnswerPolicy.NONE);
            deep = flatToggle(DEEP, DEEP_NOTE, (q, on) -> q.deepChanges = on);
            moreBox.addView(charm);
            moreBox.addView(buffer);
            moreBox.addView(answers);
            moreBox.addView(deep);
            root.addView(moreBox, Ui.matchWrap());
            setMoreOpen(UiPrefs.settingsMoreOpen(c), false);
            more.setOnClickListener(v -> {
                boolean open = moreBox.getVisibility() != View.VISIBLE;
                setMoreOpen(open, true);
                UiPrefs.setSettingsMoreOpen(c, open);
            });

            replanHolder = Ui.column(c);
            root.addView(replanHolder, top(c, 18));

            bind(host.data());
            SystemList.follow(root, this::bind);
        }

        /** Shows what is saved in {@code d} (also after a save was turned down). */
        void bind(UiData d) {
            Profile p = d.profile();
            Rules rules = d.rules;
            busy.setVisibility(d.snap != null && d.snap.planning && !d.snap.fullReplanning
                ? View.VISIBLE : View.INVISIBLE);
            tiles.select(Math.max(0, Arrays.asList(STYLES).indexOf(p.style)));
            Profile.PersonaMode mode = p.effectivePersonaMode();
            persona.setSelected(Arrays.asList(PERSONA_MODES).indexOf(mode));
            personaNote.setVisibility(mode == Profile.PersonaMode.GOALS ? View.VISIBLE : View.GONE);
            tower.setValue(towerSummary(rules, p));
            christmas.setValue(ChristmasChoice.shortAnswer(rules, p.christmasLink));
            cash.setValue(Zh.yen(p.cashBuffer));
            others.setChecked(p.otherLinks);
            if (dating != null) {
                dating.setChecked(p.datingNotes);
            }
            charm.setChecked(p.charmBonus);
            buffer.setChecked(p.goalBuffer);
            answers.setChecked(p.answers == Profile.AnswerPolicy.BEST);
            deep.setChecked(p.deepChanges);
            if (shownExcluded == null || !Arrays.equals(shownExcluded, p.excludedActivities)) {
                shownExcluded = p.excludedActivities.clone();
                excludedRows.removeAllViews();
                for (String id : p.excludedActivities) {
                    excludedRows.addView(excludedRow(rules, id));
                }
                excludedBox.setVisibility(p.excludedActivities.length == 0 ? View.GONE : View.VISIBLE);
            }
            replanHolder.removeAllViews();
            View replan = MePage.replanRow(c, host, d);
            if (replan != null) {
                replanHolder.addView(replan);
            }
        }

        private ToggleRow flatToggle(String title, String description, BoolEdit change) {
            ToggleRow row = new ToggleRow(c, title, description, false).flat();
            row.setListener(on -> edit(host, q -> change.apply(q, on)));
            return row;
        }

        private void setMoreOpen(boolean open, boolean animate) {
            moreBox.setVisibility(open ? View.VISIBLE : View.GONE);
            moreRow.setContentDescription(MORE + (open ? "，已展开" : "，已收起"));
            float rotation = open ? 180f : 0f;
            if (animate && Ui.animationsEnabled()) {
                moreArrow.animate().rotation(rotation).setDuration(160).setInterpolator(Motion.SETTLE).start();
                if (open) {
                    Motion.cascade(moreBox);
                }
            } else {
                moreArrow.setRotation(rotation);
            }
        }

        /** 平安夜 → the choice sheet; a pick saves and closes it. */
        private void pickChristmas() {
            UiData d = host.data();
            if (d.rules == null) {
                return;
            }
            SimState obs = d.observed();
            ChristmasChoice.sheet(host, d.rules, i -> obs == null || i >= obs.rank.length ? 0 : obs.rank[i],
                d.profile().christmasLink, id -> edit(host, q -> q.christmasLink = id));
        }

        /** 手上至少留 → the cash pad. */
        private void pickCash() {
            CashPad.Options options = new CashPad.Options();
            options.title = "手上至少留多少钱？";
            options.initial = (long) host.data().profile().cashBuffer;
            options.allowUnknown = false;
            CashPad.show(c, options, amount -> {
                if (amount == null) {
                    return;
                }
                int value = (int) Math.min(Integer.MAX_VALUE, Math.max(0, amount));
                edit(host, q -> q.cashBuffer = value);
            });
        }

        /** 「定食屋「若津」吃大份套餐 … 恢复」: the row leaves at once, the plan takes it back. */
        private View excludedRow(Rules rules, String id) {
            Rules.Activity activity = rules == null ? null : rules.activity(id);
            String name = activity == null ? "已不存在的活动" : activity.zh;
            LinearLayout row = Ui.row(c);
            row.setMinimumHeight(Ui.dp(c, 52));
            row.setBackground(new SystemList.Cursor(c, true).noSweep());
            Ui.pad(row, 8, 2, 0, 3);
            row.addView(Ui.text(c, name, 15, Ui.col(c, R.color.p3r_white), false), Ui.weight(1));
            TextView restore = Ui.quiet(c, RESTORE);
            restore.setContentDescription(RESTORE + "：" + name);
            restore.setOnClickListener(v -> {
                if (!restore.isEnabled()) {
                    return;
                }
                restore.setEnabled(false);
                Runnable save = () -> edit(host, q -> q.excludedActivities = without(q.excludedActivities, id));
                if (Ui.animationsEnabled() && row.isAttachedToWindow()) {
                    row.animate().alpha(0f).translationX(Ui.dp(c, 32)).setDuration(160)
                        .setInterpolator(Motion.EASE).withEndAction(save).start();
                } else {
                    save.run();
                }
            });
            row.addView(restore, Ui.wrap());
            return row;
        }
    }

    /** 「每个区块 2 次」, or how many blocks have values of their own. */
    static String towerSummary(Rules rules, Profile p) {
        int own = 0;
        if (rules != null) {
            for (Rules.Block b : rules.blocks) {
                if (TowerSettingsPage.ownValues(p, b.id)) {
                    own++;
                }
            }
        }
        return own > 0 ? "单独设置了 " + own + " 个区块" : "每个区块 " + p.towerVisitsPerBlock + " 次";
    }

    /** 「11月11日起 · 每张 3,000 日元」 (when it can be bought and the price); null without either. */
    static String datingNoteText(Rules.DatingNote note) {
        List<String> parts = new ArrayList<>();
        if (note.fromDay > 0) {
            parts.add(Zh.date(note.fromDay) + "起");
        }
        if (note.cost > 0) {
            parts.add("每张 " + Zh.yen(note.cost));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    /**
     * The two 安排风格 tiles, like the game's difficulty select: the chosen one has the white face with
     * the cyan slab (the widget's 完成), which slides over when the other is picked.
     */
    private static final class StyleTiles extends LinearLayout {
        private final List<LinearLayout> tiles = new ArrayList<>();
        private final SlantedPanelDrawable slot;
        private final SystemList.Slide slide;
        private int selected = -1;

        StyleTiles(Context c, java.util.function.IntConsumer onPick) {
            super(c);
            setOrientation(HORIZONTAL);
            setBaselineAligned(false);
            int skew = Ui.dp(c, 12);
            slot = new SlantedPanelDrawable()
                .fill(Ui.alpha(Ui.col(c, R.color.p3r_navy_950), 120))
                .stroke(Ui.alpha(Ui.col(c, R.color.p3r_cyan_300), 110), Ui.dp(c, 1.2f))
                .skew(skew);
            slide = new SystemList.Slide(this, new SlantedPanelDrawable()
                .fill(Ui.col(c, R.color.p3r_white))
                .slab(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 4), Ui.dp(c, 4))
                .skew(skew));
            for (int i = 0; i < STYLES.length; i++) {
                final int index = i;
                LinearLayout tile = Ui.column(c);
                tile.setGravity(Gravity.CENTER_VERTICAL);
                tile.setMinimumHeight(Ui.dp(c, 84));
                Ui.pad(tile, 22, 10, 18, 14);
                tile.addView(Ui.mixed(c, STYLE_TEXT[i][0], 20, Ui.col(c, R.color.p3r_white), true));
                TextView line = Ui.text(c, STYLE_TEXT[i][1], 13, Ui.col(c, R.color.p3r_ink_muted), false);
                LinearLayout.LayoutParams lp = Ui.wrap();
                lp.topMargin = Ui.dp(c, 2);
                tile.addView(line, lp);
                tile.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_faint))
                    .skew(skew));
                tile.setClickable(true);
                tile.setFocusable(true);
                tile.setContentDescription(STYLE_TEXT[i][0] + "，" + STYLE_TEXT[i][1]);
                tile.setOnClickListener(v -> {
                    if (selected == index) {
                        return;
                    }
                    select(index);
                    onPick.accept(index);
                });
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
                if (i > 0) {
                    tp.leftMargin = Ui.dp(c, 10);
                }
                addView(tile, tp);
                tiles.add(tile);
            }
        }

        void select(int index) {
            selected = index;
            Context c = getContext();
            for (int i = 0; i < tiles.size(); i++) {
                boolean on = i == index;
                LinearLayout tile = tiles.get(i);
                tile.setSelected(on);
                ((TextView) tile.getChildAt(0)).setTextColor(Ui.col(c, on ? R.color.p3r_navy_950 : R.color.p3r_white));
                ((TextView) tile.getChildAt(1)).setTextColor(Ui.col(c, on ? R.color.p3r_navy_800 : R.color.p3r_ink_muted));
            }
            slide.select(index);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            for (LinearLayout tile : tiles) {
                if (tile.getWidth() == 0) {
                    continue;
                }
                slot.setBounds(tile.getLeft(), tile.getTop(), tile.getRight(), tile.getBottom());
                slot.draw(canvas);
            }
            slide.draw(canvas);
            super.dispatchDraw(canvas);
        }
    }

    /**
     * A tappable choice in a list (平安夜): slanted radio mark, bold title and an optional short line.
     * Children: the mark, then a column with the title and the line.
     */
    static LinearLayout choiceRow(Context c, String title, String description) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(c, 52));
        Ui.pad(row, 12, 8, 12, 8);
        View mark = new View(c);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16));
        mp.rightMargin = Ui.dp(c, 14);
        row.addView(mark, mp);
        LinearLayout texts = Ui.column(c);
        texts.addView(Ui.text(c, title, 16, Ui.col(c, R.color.p3r_white), true));
        if (description != null) {
            texts.addView(Ui.mixed(c, description, 13, Ui.col(c, R.color.p3r_ink_muted), false));
        }
        row.addView(texts, Ui.weight(1));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    /** Marks a {@link #choiceRow}: the chosen one gets a cyan blade on a faint cyan plate. */
    static void markChoice(Context c, LinearLayout row, String title, String description, boolean on) {
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 8))
            .pressedFill(Ui.col(c, R.color.ui_cyan_soft));
        if (on) {
            bg.fill(Ui.col(c, R.color.ui_cyan_faint)).accent(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 4));
        } else {
            bg.fill(0);
        }
        row.setBackground(bg);
        View mark = row.getChildAt(0);
        SlantedPanelDrawable dot = new SlantedPanelDrawable().skew(Ui.dp(c, 4));
        if (on) {
            dot.fill(Ui.col(c, R.color.p3r_cyan_400));
        } else {
            dot.stroke(Ui.col(c, R.color.p3r_cyan_300), Ui.dp(c, 1.5f));
        }
        mark.setBackground(dot);
        row.setSelected(on);
        row.setContentDescription(title + (description == null ? "" : "，" + description) + (on ? "，已选" : ""));
    }

    private static LinearLayout.LayoutParams top(Context c, int dp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, dp);
        return p;
    }

    /** Applies an edit to the latest profile and saves it. */
    static void edit(HomeHost host, Consumer<Profile> change) {
        Profile edited = host.data().profile().copy();
        change.accept(edited);
        host.saveProfile(edited);
    }

    private interface BoolEdit {
        void apply(Profile profile, boolean on);
    }

    static String[] without(String[] ids, String id) {
        List<String> out = new ArrayList<>();
        for (String s : ids) {
            if (!s.equals(id)) {
                out.add(s);
            }
        }
        return out.toArray(new String[0]);
    }

    static String[] with(String[] ids, String id) {
        List<String> out = new ArrayList<>(Arrays.asList(ids));
        if (!out.contains(id)) {
            out.add(id);
        }
        return out.toArray(new String[0]);
    }
}
