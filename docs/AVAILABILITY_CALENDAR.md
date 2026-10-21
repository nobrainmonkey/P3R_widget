# 活动日历的数据

「今天可做」和「活动日历」使用同一份 `AvailableActions` 数据。日期、星期、剧情占用、社群前置、属性要求和已完成活动均来自现有规则包；查询使用当前记录，不执行之后安排的行动。规划目标、属性已满或「不安排这个活动」不会隐藏实际营业与活动机会。现金不足仍显示营业与价格，种菜显示作物前提。

数据按天展开并缓存。创建界面快照只复制当前进度；查询一天时共用一份引擎日期表，避免切换页面时计算全年。输出集合不可修改。

## 电玩城白天营业

规则包为自动规划保留了保守的「仅晚上」集合（`rules/p3r/stats_actions.json` 的 `arcade_daytime_wiki_internal`）。这是规划数据取舍，不代表白天不营业。

活动日历另外显示相同游戏的白天时段，保持原来的星期、日期范围与剧情占用检查，不修改自动规划：

- 「青春高校」：周一、周四，魅力 +4。
- 「死亡之怖」：周二、周五，勇气 +4。
- 「你就是答案」：周三、周六，学力 +4。

依据和致谢：

- [aqiu384 / marendarade：Social Events](https://aqiu384.github.io/megaten-database/p3r/social-events)，本仓库快照 `rules/sources/aqiu_pages/social-events.html`，「Social Stat Regular Activities」表把这三个游戏标为 **B**（白天和晚上）。
- [marendarade：GameFAQs Social Stats](https://gamefaqs.gamespot.com/pc/409941-persona-3-reload/faqs/81170/social-stats)，现有证据文件中的 `gf81170`。
- [TenSquare3：GameFAQs Social Stat Activities Breakdown](https://gamefaqs.gamespot.com/xbox-series-x/409940-persona-3-reload/faqs/81159/social-stat-activities-breakdown)，现有证据文件中的 `gf81159`。

后二者在现有证据记录中均列明白天与晚上；规则包曾因 wiki469 的详细表仅列晚上而取交集。这部分补充的是上述三个三维游戏的白天时段，不扩展其他商店的营业时间。

## 人格面具训练

「今天可做」和活动日历的「电玩城」同时列出当日的面具训练，白天、晚上都能查看，三维满级后仍然显示。每次 **2,000 日元**，提升**当前装备的人格面具**的一项能力 **+3**：

| 提升能力 | 星期 | 攻略中的游戏名称 |
| --- | --- | --- |
| 力 +3 | 周一、周四、周日 | Punch Boxer |
| 魔 +3 | 周三、周六、周日 | Edgar's House of Fortunes |
| 速 +3 | 周二、周五、周日 | Real Weel Racer |

周日会同时列出三项。界面直接写「人格面具训练 · 力／魔／速 +3」，没有把未核对的游戏名称译作官方简中。价格不足只显示现金条件，不隐藏训练；剧情占用的时段不显示可外出的训练。

这些是 `AvailableActions` 的只读营业提示，键使用 `info:`，没有可记录的 `Action.Type`，不增加规划候选或假设玩家已经消耗现金、提升了面具能力。营业日期范围沿用已收录的电玩城活动。羁绊剧情也会出现在「今天可做」，继续排除普通社群拜访和解锁跑腿。

依据和致谢：

- [aqiu384 / marendarade：Combat — Raising Persona Stats](https://aqiu384.github.io/megaten-database/p3r/combat.html)：三项能力、每次 +3、星期表，以及需装备目标面具。
- [MegaTen Wiki：Paulownia Mall，Reload 表](https://megatenwiki.com/index.php?title=Paulownia_Mall&oldid=421725)：三种面具训练均为 2,000 日元；采用 Reload 行，未采用同页的 P3P 价格。
- [XboxAchievements：Persona 3 Reload — The best way to boost Social Stats](https://www.xboxachievements.com/game/persona-3-reload/guide/the-best-way-to-boost-social-stats)：电玩城及面具训练可在白天、晚上进行，周日同时有三种面具训练。这里只用于交叉核对营业时段，未导入其音符与点数的表述。
