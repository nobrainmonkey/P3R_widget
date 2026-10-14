package app.p3r.dayplanner.v3.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.p3r.dayplanner.v3.Intents;

/** 我的 lists the planner's own entries in order; 3.1.0 removed the 2.x guide and its entry. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MePageTest extends UiTestBase {

    @Test
    public void meListsOnlyThePlannersOwnEntries() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), HomeActivity.class);
        intent.putExtra(Intents.EXTRA_TAB, "me");
        HomeActivity a = home(intent);
        String all = Shots.allText(a.getWindow().getDecorView());
        assertFalse(all, all.contains("攻略"));
        int at = -1;
        for (String title : new String[]{"同步现状", "修改进度", "记录历史", "规划设置", "规划说明", "备份与恢复", "添加桌面小组件", "关于"}) {
            int found = all.indexOf(title + "\n", at + 1);
            assertTrue("「" + title + "」 after the entry before it:\n" + all, found > at);
            at = found;
        }
    }
}
