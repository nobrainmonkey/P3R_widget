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

后二者在现有证据记录中均列明白天与晚上；规则包曾因 wiki469 的详细表仅列晚上而取交集。活动日历的白天补充限于上述三个已收录游戏，不扩展其他商店的营业时间。
