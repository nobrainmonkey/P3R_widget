package app.p3r.dayplanner.v3.ui;

import app.p3r.dayplanner.R;
import app.p3r.dayplanner.v3.engine.Plan;

/**
 * Display/interaction category of a plan step. Decides the icon, the small tag shown on cards
 * and which result panel the quick sheet opens.
 */
public enum StepKind {
    /** Social link meeting predicted to rank up. */
    SOCIAL_RANK_UP("社群", R.drawable.ui_ic_social),
    /** Social link meeting without a predicted rank-up (points only). */
    SOCIAL_MEET("社群", R.drawable.ui_ic_social),
    /** A preparation step that opens a link. */
    UNLOCK("解锁社群", R.drawable.ui_ic_unlock),
    /** Training / meals / anything that raises 个人能力值. */
    STAT("个人能力值", R.drawable.ui_ic_stat),
    /** 打工. */
    JOB("打工", R.drawable.ui_ic_job),
    /** Exploring 塔尔塔罗斯. */
    TOWER("塔尔塔罗斯", R.drawable.ui_ic_tower),
    /** Story or calendar lock; nothing to choose. */
    LOCKED("剧情", R.drawable.ui_ic_locked),
    /** Nothing productive is possible. */
    FREE("自由活动", R.drawable.ui_ic_free);

    public final String tagZh;
    public final int iconRes;

    StepKind(String tagZh, int iconRes) {
        this.tagZh = tagZh;
        this.iconRes = iconRes;
    }

    /** Classifies a step by its coarse {@code kind} and prediction. */
    public static StepKind of(Plan.Step step) {
        if (step == null) {
            return FREE;
        }
        switch (step.kind == null ? "" : step.kind) {
            case "social":
                return step.predicted != null && step.predicted.rankUp ? SOCIAL_RANK_UP : SOCIAL_MEET;
            case "unlock":
                return UNLOCK;
            case "stat":
                return STAT;
            case "job":
                return JOB;
            case "tower":
                return TOWER;
            case "locked":
                return LOCKED;
            default:
                return FREE;
        }
    }

    /**
     * True when "完成" can be recorded with one tap and an undo bar (no question to ask):
     * training, jobs, story locks and free slots.
     */
    public boolean directCommit() {
        return this == STAT || this == JOB || this == LOCKED || this == FREE;
    }

    /** True for kinds where 做不了 / 改做别的 make sense. */
    public boolean deviatable() {
        return this != LOCKED;
    }
}
