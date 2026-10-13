package app.p3r.dayplanner.v3.ui;

import java.util.List;

import app.p3r.dayplanner.v3.model.Profile;

/** Goal tier and order edits shared by onboarding and the 社群 tab. */
final class Goals {
    private Goals() {}

    /** Next tier in the cycle 「—」→「想要」→「必须」→「—」. */
    static Profile.Tier next(Profile.Tier tier) {
        switch (tier) {
            case NONE:
                return Profile.Tier.WANT;
            case WANT:
                return Profile.Tier.MUST;
            default:
                return Profile.Tier.NONE;
        }
    }

    /** Cycles the tier of {@code link} in place and returns the new tier. */
    static Profile.Tier cycle(Profile profile, int link) {
        Profile.Tier tier = next(profile.tier[link]);
        setTier(profile, link, tier);
        return tier;
    }

    /** Sets a tier, placing the link last within it. */
    static void setTier(Profile profile, int link, Profile.Tier tier) {
        int maxOrder = -1;
        for (int i = 0; i < Profile.LINKS; i++) {
            if (i != link && profile.tier[i] == tier) {
                maxOrder = Math.max(maxOrder, profile.order[i]);
            }
        }
        profile.tier[link] = tier;
        profile.order[link] = tier == Profile.Tier.NONE ? 0 : maxOrder + 1;
    }

    /**
     * Moves a goal one place up (delta −1) or down (+1) within its tier; returns false when it is
     * already at that end. Orders are renumbered 0..n-1 so they stay compact.
     */
    static boolean move(Profile profile, int link, int delta) {
        Profile.Tier tier = profile.tier[link];
        if (tier == Profile.Tier.NONE) {
            return false;
        }
        List<Integer> ordered = LinkStatus.goalOrder(profile);
        ordered.removeIf(i -> profile.tier[i] != tier);
        int at = ordered.indexOf(link);
        int to = at + delta;
        if (at < 0 || to < 0 || to >= ordered.size()) {
            return false;
        }
        ordered.set(at, ordered.get(to));
        ordered.set(to, link);
        for (int i = 0; i < ordered.size(); i++) {
            profile.order[ordered.get(i)] = i;
        }
        return true;
    }

    /** Short label for a tier chip. */
    static String label(Profile.Tier tier) {
        switch (tier) {
            case MUST:
                return "必须";
            case WANT:
                return "想要";
            default:
                return "—";
        }
    }
}
