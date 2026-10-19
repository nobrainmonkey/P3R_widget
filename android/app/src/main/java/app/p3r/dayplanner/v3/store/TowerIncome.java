package app.p3r.dayplanner.v3.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Forecast cash per Tartarus visit, estimated from the player's own records
 * (docs/DESIGN_v3.md §10.4, labelled "按你的记录估计" in the UI).
 */
public final class TowerIncome {
    /** Records needed before the estimate is used. */
    public static final int MIN_RECORDS = 2;

    private TowerIncome() {}

    /**
     * Median net gain of the recorded visits in {@code currentBlock} and the block before it, when
     * at least {@link #MIN_RECORDS} such visits exist; otherwise 0. Negative medians (visits that
     * cost money on balance) also give 0: the planner never forecasts tower spending.
     *
     * @param gains visits with an exactly known net change
     * @param currentBlock block index at the observed slot, or -1 when no block is active
     */
    public static int estimate(List<Replayer.TowerGain> gains, int currentBlock) {
        if (currentBlock < 0) {
            return 0;
        }
        List<Long> nets = new ArrayList<>();
        for (Replayer.TowerGain gain : gains) {
            if (gain.block == currentBlock || gain.block == currentBlock - 1) {
                nets.add(gain.net);
            }
        }
        if (nets.size() < MIN_RECORDS) {
            return 0;
        }
        Collections.sort(nets);
        int mid = nets.size() / 2;
        long median = nets.size() % 2 == 1
                ? nets.get(mid)
                : Math.floorDiv(nets.get(mid - 1) + nets.get(mid), 2L);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, median));
    }
}
