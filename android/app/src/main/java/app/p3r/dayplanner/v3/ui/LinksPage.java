package app.p3r.dayplanner.v3.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

/**
 * 社群 tab: the goal order (↑↓ within 必须 / 想要), the "其余社群也尽量提升" switch, the 22
 * arcana cards (罗马数字 + 阿尔卡那 + 人物 · 性别, confirmed RANK → forecast end RANK, a tier chip
 * cycling 未选/想要/必须, and when MAX is reached or why not) and the opt-in 羁绊剧情 tracks.
 */
final class LinksPage {
    private final HomeHost host;
    private final Context c;
    private final ScrollView scroll;
    private final LinearLayout column;

    LinksPage(HomeHost host) {
        this.host = host;
        this.c = host.activity();
        scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        column = Ui.column(c);
        Ui.pad(column, Ui.GUTTER, 8, Ui.GUTTER, 24);
        scroll.addView(column, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    View view() {
        return scroll;
    }

    void setBottomInset(int px) {
        scroll.setPadding(0, 0, 0, px);
    }

    void bind(UiData d) {
        int y = scroll.getScrollY();
        column.removeAllViews();
        Profile profile = d.profile();
        TextView title = Ui.text(c, "社群", 28, Ui.col(c, R.color.p3r_white), true);
        Ui.pad(title, 0, 8, 0, 0);
        column.addView(title);
        TextView hint = Ui.muted(c, "点卡片右上角的标签切换：未选 → 想要 → 必须 → 未选。目标按下面的顺序优先安排。");
        column.addView(hint, spaced(4));

        List<Integer> goals = LinkStatus.goalOrder(profile);
        if (!goals.isEmpty()) {
            column.addView(Ui.sectionHeader(c, "目标顺序", goals.size() + " 个"), spaced(16));
            for (int i = 0; i < goals.size(); i++) {
                column.addView(goalRow(d, profile, goals, i), spaced(i == 0 ? 4 : 8));
            }
        }
        ToggleRow others = new ToggleRow(c, "其余社群也尽量提升", "排完目标后，空出来的时段用来提升其他社群。",
            profile.otherLinks);
        others.setListener(on -> {
            Profile edited = profile.copy();
            edited.otherLinks = on;
            host.saveProfile(edited);
        });
        column.addView(others, spaced(16));

        column.addView(Ui.sectionHeader(c, "全部社群", "22 个"), spaced(20));
        LinearLayout row = null;
        for (Rules.Link link : d.rules.links) {
            if (link.idx % 2 == 0) {
                row = Ui.row(c);
                row.setGravity(Gravity.TOP);
                row.setBaselineAligned(false);
                column.addView(row, spaced(link.idx == 0 ? 4 : 10));
            }
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (link.idx % 2 == 1) {
                cardParams.leftMargin = Ui.dp(c, 10);
            }
            row.addView(card(d, profile, link), cardParams);
        }
        if (d.rules.episodes.length > 0) {
            column.addView(Ui.sectionHeader(c, "羁绊剧情", null), spaced(24));
            column.addView(Ui.muted(c, "不加社群点数，但能拿到道具/特性；勾选后会在可看的日子留出时段。"), spaced(2));
            for (Rules.Episode track : d.rules.episodes) {
                column.addView(episodeRow(d, profile, track), spaced(8));
            }
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private LinearLayout.LayoutParams spaced(int topDp) {
        LinearLayout.LayoutParams p = Ui.matchWrap();
        p.topMargin = Ui.dp(c, topDp);
        return p;
    }

    private View goalRow(UiData d, Profile profile, List<Integer> goals, int position) {
        int link = goals.get(position);
        Rules.Link l = d.rules.links[link];
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.card(c));
        Ui.pad(row, 14, 2, 4, 2);
        TextView index = Ui.numeral(c, String.valueOf(position + 1), 22, Ui.col(c, R.color.p3r_cyan_400));
        index.setMinWidth(Ui.dp(c, 26));
        row.addView(index, Ui.wrap());
        TextView name = Ui.text(c, l.labelZh, 15, Ui.col(c, R.color.p3r_white), true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np = Ui.weight(1);
        np.leftMargin = Ui.dp(c, 6);
        row.addView(name, np);
        row.addView(tierBadge(profile.tier[link]), Ui.wrap());
        boolean canUp = position > 0 && profile.tier[goals.get(position - 1)] == profile.tier[link];
        boolean canDown = position + 1 < goals.size() && profile.tier[goals.get(position + 1)] == profile.tier[link];
        row.addView(arrow(R.drawable.ui_ic_up, "上移 " + l.arcanaZh, canUp, () -> move(profile, link, -1)));
        row.addView(arrow(R.drawable.ui_ic_down, "下移 " + l.arcanaZh, canDown, () -> move(profile, link, +1)));
        return row;
    }

    private void move(Profile profile, int link, int delta) {
        Profile edited = profile.copy();
        if (Goals.move(edited, link, delta)) {
            host.saveProfile(edited);
        }
    }

    private View arrow(int icon, String description, boolean enabled, Runnable action) {
        ImageView view = new ImageView(c);
        view.setImageDrawable(Ui.tinted(c, icon, Ui.col(c, R.color.p3r_white)));
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setContentDescription(description);
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.25f);
        view.setBackground(new SlantedPanelDrawable().fill(0).pressedFill(Ui.col(c, R.color.ui_cyan_soft)));
        view.setOnClickListener(v -> action.run());
        int size = Ui.dp(c, 48);
        view.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return view;
    }

    private TextView tierBadge(Profile.Tier tier) {
        TextView badge = Ui.text(c, Goals.label(tier), 12, Ui.col(c, R.color.p3r_white), true);
        badge.setGravity(Gravity.CENTER);
        SlantedPanelDrawable bg = new SlantedPanelDrawable().skew(Ui.dp(c, 5));
        if (tier == Profile.Tier.MUST) {
            bg.fill(Ui.col(c, R.color.p3r_white));
            badge.setTextColor(Ui.col(c, R.color.p3r_navy_950));
        } else if (tier == Profile.Tier.WANT) {
            bg.stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 1.5f));
            badge.setTextColor(Ui.col(c, R.color.p3r_cyan_400));
        } else {
            bg.stroke(Ui.col(c, R.color.p3r_line), Ui.dp(c, 1));
            badge.setTextColor(Ui.col(c, R.color.p3r_ink_muted));
        }
        badge.setBackground(bg);
        Ui.pad(badge, 10, 3, 10, 3);
        badge.setMinWidth(Ui.dp(c, 48));
        return badge;
    }

    private View card(UiData d, Profile profile, Rules.Link link) {
        LinkStatus status = LinkStatus.of(d.rules, profile, d.observed(), d.plan(), link.idx);
        Profile.Tier tier = profile.tier[link.idx];
        LinearLayout card = Ui.column(c);
        SlantedPanelDrawable bg = Ui.card(c);
        if (tier == Profile.Tier.MUST) {
            bg.stroke(Ui.col(c, R.color.p3r_white), Ui.dp(c, 2)).fill(Ui.col(c, R.color.ui_card_raised));
        } else if (tier == Profile.Tier.WANT) {
            bg.stroke(Ui.col(c, R.color.p3r_cyan_400), Ui.dp(c, 1.5f));
        }
        card.setBackground(bg);
        Ui.pad(card, 12, 4, 4, 12);

        LinearLayout top = Ui.row(c);
        TextView roman = Ui.numeral(c, Zh.roman(link.arcanaNo), 22, Ui.col(c, R.color.p3r_cyan_400));
        top.addView(roman, Ui.weight(1));
        TextView chip = tierBadge(tier);
        LinearLayout tap = Ui.row(c);
        tap.setGravity(Gravity.CENTER);
        tap.setMinimumHeight(Ui.dp(c, Ui.TOUCH));
        tap.setMinimumWidth(Ui.dp(c, 64));
        tap.addView(chip);
        tap.setClickable(true);
        tap.setFocusable(true);
        tap.setContentDescription(link.arcanaZh + " 目标：" + Goals.label(tier) + "，点按切换");
        tap.setOnClickListener(v -> {
            Profile edited = profile.copy();
            Goals.cycle(edited, link.idx);
            host.saveProfile(edited);
        });
        top.addView(tap, Ui.wrap());
        card.addView(top);

        TextView arcana = Ui.text(c, link.arcanaZh, 19, Ui.col(c, R.color.p3r_white), true);
        card.addView(arcana);
        TextView person = Ui.text(c, Zh.person(link), 12, Ui.col(c, R.color.p3r_ink_muted), false);
        person.setMaxLines(2);
        person.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(person);

        String rankText = status.projected > status.observed
            ? Zh.rankStep(status.observed, status.projected)
            : "RANK " + Zh.rankValue(status.observed);
        if (status.observed == 0 && status.projected == 0) {
            rankText = "未解锁";
        }
        TextView rank = Ui.mixed(c, rankText, 15, Ui.col(c, R.color.p3r_white), true);
        LinearLayout.LayoutParams rp = Ui.matchWrap();
        rp.topMargin = Ui.dp(c, 8);
        card.addView(rank, rp);
        RankPips pips = new RankPips(c);
        pips.set(status.observed, status.projected);
        LinearLayout.LayoutParams pp = Ui.matchWrap();
        pp.topMargin = Ui.dp(c, 6);
        pp.rightMargin = Ui.dp(c, 8);
        card.addView(pips, pp);
        TextView foot = Ui.mixed(c, status.footZh, 12,
            status.lost ? Ui.col(c, R.color.p3r_danger) : Ui.col(c, R.color.p3r_cyan_200), false);
        LinearLayout.LayoutParams fp = Ui.matchWrap();
        fp.topMargin = Ui.dp(c, 6);
        card.addView(foot, fp);
        card.setContentDescription(link.labelZh + "，" + (status.observed == 0 ? "未解锁" : Zh.rank(status.observed))
            + "，" + status.footZh);
        return card;
    }

    /** One 羁绊剧情 track: person, episode count, window summary and an opt-in switch. */
    private View episodeRow(UiData d, Profile profile, Rules.Episode track) {
        int watched = d.observed() != null && track.idx < d.observed().episodes.length
            ? d.observed().episodes[track.idx] : 0;
        String title = track.personZh + " · 共 " + track.count + " 话";
        ToggleRow row = new ToggleRow(c, title, episodeSummary(track, watched), profile.wantsEpisodes(track.id));
        row.setListener(on -> {
            Profile edited = profile.copy();
            edited.episodeTracks = on ? SettingsPage.with(edited.episodeTracks, track.id)
                : SettingsPage.without(edited.episodeTracks, track.id);
            host.saveProfile(edited);
        });
        return row;
    }

    /** "放学后 · 5月12日–1月29日能看 · 已看 1 话 · 看完能拿到…". */
    static String episodeSummary(Rules.Episode track, int watched) {
        int first = -1;
        int last = -1;
        for (int day = 0; day < track.dayMask.length; day++) {
            if (track.dayMask[day] != 0) {
                if (first < 0) {
                    first = day;
                }
                last = day;
            }
        }
        StringBuilder sb = new StringBuilder(track.evening ? "晚上" : "白天");
        if (first >= 0) {
            sb.append(" · ").append(Zh.dateRange(first, last)).append("能看");
        }
        if (watched >= track.count && track.count > 0) {
            sb.append(" · 已经看完");
        } else if (watched > 0) {
            sb.append(" · 已看 ").append(watched).append(" 话");
        }
        if (track.rewardZh != null && !track.rewardZh.isEmpty()) {
            sb.append(" · 看完能拿到").append(track.rewardZh);
        }
        return sb.toString();
    }
}
