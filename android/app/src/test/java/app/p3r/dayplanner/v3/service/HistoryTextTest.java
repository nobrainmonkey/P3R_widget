package app.p3r.dayplanner.v3.service;

import static org.junit.Assert.assertEquals;

import app.p3r.dayplanner.v3.engine.Action;
import app.p3r.dayplanner.v3.model.Rules;
import app.p3r.dayplanner.v3.store.TestRules;
import app.p3r.dayplanner.v3.store.TowerRecord;

import org.junit.Test;

/**
 * 记录历史 lines say what happened in plain words: the cash as the result sheet shows it
 * (「现金 +2,000 日元」, never 「多了」 or a note about bookkeeping), the side steps as 「顺便的事」,
 * and the note by whom it was for.
 */
public class HistoryTextTest {
    private final Rules rules = TestRules.create();

    private static TowerRecord visit(boolean balance, long amount) {
        TowerRecord rec = new TowerRecord();
        rec.floor = 27;
        rec.balanceMode = balance;
        rec.amount = amount;
        return rec;
    }

    @Test
    public void towerCashReadsLikeTheResultSheet() {
        assertEquals("到达 27F · 出塔后余额 14,750 日元", HistoryText.towerDetails(rules, visit(true, 14_750)));
        assertEquals("到达 27F · 现金 +2,000 日元", HistoryText.towerDetails(rules, visit(false, 2_000)));
        assertEquals("到达 27F · 现金 −1,200 日元", HistoryText.towerDetails(rules, visit(false, -1_200)));
        assertEquals("到达 27F · 现金没变", HistoryText.towerDetails(rules, visit(false, 0)));
        TowerRecord forgot = visit(false, 0);
        forgot.cashUnknown = true;
        assertEquals("到达 27F · 现金没记", HistoryText.towerDetails(rules, forgot));
        TowerRecord skipped = visit(true, 9_000);
        skipped.sideDone = false;
        assertEquals("到达 27F · 出塔后余额 9,000 日元 · 顺便的事没做", HistoryText.towerDetails(rules, skipped));
    }

    @Test
    public void aNoteIsNamedByWhomItWasFor() {
        assertEquals("结缘网站的字条 · 小田桐秀利",
                HistoryText.title(rules, Action.note(TestRules.EMPEROR), null));
    }
}
