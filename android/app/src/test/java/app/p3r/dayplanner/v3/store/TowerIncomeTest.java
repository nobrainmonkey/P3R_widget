package app.p3r.dayplanner.v3.store;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Median of the player's recorded tower gains in the current and previous block (plain JVM). */
public class TowerIncomeTest {

    @Test
    public void needsAtLeastTwoRecords() {
        assertEquals(0, TowerIncome.estimate(gains(2, 5_000), 2));
        assertEquals(0, TowerIncome.estimate(new ArrayList<>(), 2));
    }

    @Test
    public void oddCountTakesTheMiddleValue() {
        assertEquals(4_000, TowerIncome.estimate(gains(2, 9_000, 1_000, 4_000), 2));
    }

    @Test
    public void evenCountAveragesTheMiddlePairRoundingDown() {
        assertEquals(3_500, TowerIncome.estimate(gains(2, 3_000, 4_000), 2));
        assertEquals(3_000, TowerIncome.estimate(gains(2, 3_000, 3_001), 2));
    }

    @Test
    public void usesCurrentAndPreviousBlockOnly() {
        List<Replayer.TowerGain> g = new ArrayList<>();
        g.add(new Replayer.TowerGain(1, 0, 90_000));
        g.add(new Replayer.TowerGain(2, 0, 90_000));
        g.add(new Replayer.TowerGain(3, 1, 2_000));
        g.add(new Replayer.TowerGain(4, 2, 6_000));
        assertEquals("blocks 1 and 2 only", 4_000, TowerIncome.estimate(g, 2));
        assertEquals("one record in block 2 alone is not enough", 0, TowerIncome.estimate(g.subList(3, 4), 2));
    }

    @Test
    public void lossesAndMissingBlockGiveZero() {
        assertEquals(0, TowerIncome.estimate(gains(1, -2_000, -500), 1));
        assertEquals(0, TowerIncome.estimate(gains(1, 2_000, 3_000), -1));
    }

    private static List<Replayer.TowerGain> gains(int block, long... nets) {
        List<Replayer.TowerGain> out = new ArrayList<>();
        for (int i = 0; i < nets.length; i++) {
            out.add(new Replayer.TowerGain(i + 1, block, nets[i]));
        }
        return out;
    }
}
