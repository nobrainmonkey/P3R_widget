package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The hero and the day sheet show an engine reason only when it changes what the player does
 * ({@link TodayPage#urgentReason}). The lines below are the engine's own wording (engine Labels):
 * every real deadline, last chance, scarce day, unlock requirement and story hold still reads as
 * urgent, word for word, and none of the reasons that only explain the plan does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class EngineCopyUrgencyTest {
    private static final String[] URGENT = {
        "今天是救出北村文吉的最后期限，最晚今晚救出",
        "北村文吉失踪了，期限 5月16日，救不出来教皇社群就没法继续了",
        "北村文吉失踪了，期限 5月16日",
        "今天是救出这批失踪者的最后期限，最晚今晚救出",
        "今晚顺便救出失踪的 3 人，期限 11月2日",
        "西胁结子只在周三、周六放学后出现，剩下的机会不多",
        "山岸风花剩下的见面机会不多，先攒点好感",
        "能去的晚上不多了，这个区块要去够 4 次",
        "解锁恋爱社群需要魅力达到 MAX",
        "要等剧情推进才能升级，先攒好感",
        "6月20日前升不了级（需要先听到学校里的传闻），先攒好感",
        "还升不了级（需要先听到学校里的传闻），先攒好感",
        "收到友近健二的短信后赴约",
        "这是能看第 1 话的最后机会",
        "能看第 3 话的日子不多了",
    };

    private static final String[] PLAN_ONLY = {
        "「想要」目标，预计这次见面能升到 RANK 6",
        "对方发来了休息日邀约，赴约也算一次见面",
        "有空就练练学力",
        "有空去塔里练练级",
        "这个区块要去 4 次",
        "晚上有空，给山岸风花攒点好感",
        "多赚点零花钱",
        "手上至少要留 5,000 日元，先打工攒着",
        "第 3 次，解锁荒垣真次郎的特性",
        "再做 2 次就能强化荒垣真次郎的特性",
        "特性已经到手，这次练能力值",
        "这天能看第 2 话",
        "完成后塔社群就解锁了",
        "这个时段想做什么都行",
    };

    @Test
    public void realDeadlinesAndHoldsAreShownWordForWord() {
        for (String why : URGENT) {
            assertEquals(why, why, TodayPage.urgentReason(why));
        }
    }

    @Test
    public void reasonsThatOnlyExplainThePlanAreNotShown() {
        for (String why : PLAN_ONLY) {
            assertNull(why, TodayPage.urgentReason(why));
        }
        assertNull(TodayPage.urgentReason(null));
        assertNull(TodayPage.urgentReason("  "));
    }
}
