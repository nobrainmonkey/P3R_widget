# P3R 规划 · 《女神异闻录3 Reload》社群日程安排

离线运行的 Android 应用 + 桌面小组件。根据你**现在的存档状态**和想 MAX 的社群，把从今天到 1 月 31 日的**每一个白天和晚上**都排好，并且整份安排前后自洽：每一步对现金、个人能力值（学力/魅力/勇气）、社群 RANK、解锁进度和塔尔塔罗斯次数的影响都会推演下去，后面的安排建立在前面的结果之上。

> 不追求“全局最优”，追求“相对优秀、靠得住”：先满足你选的目标，剩下的时间尽量把其他社群也拉满。

## 截图

<p>
<img src="docs/screenshots/widget_home.png" width="420" alt="桌面小组件">
</p>
<p>
<img src="docs/screenshots/today.png" width="230" alt="今天">
<img src="docs/screenshots/result_sheet.png" width="230" alt="记录结果">
<img src="docs/screenshots/step_detail.png" width="230" alt="步骤详情">
</p>
<p>
<img src="docs/screenshots/schedule.png" width="230" alt="日程">
<img src="docs/screenshots/links.png" width="230" alt="社群">
<img src="docs/screenshots/rank_pad.png" width="230" alt="RANK 小键盘">
</p>
<p>
<img src="docs/screenshots/result_tower.png" width="230" alt="塔尔塔罗斯结果">
<img src="docs/screenshots/settings.png" width="230" alt="规划设置">
</p>

## 下载

到 [Releases](https://github.com/nobrainmonkey/P3R_widget/releases) 下载最新的 `P3R-Planner-<版本>.apk`，在手机上打开安装即可（需要允许“安装未知应用”）。

**关于旧版本（2.0.0 “P3R”）**：新版的包名是 `app.p3r.planner`，会作为一个**新应用**安装，不会覆盖、也不会删除旧版的数据（旧版用的签名证书不在这里，无法原地升级）。

- 旧版「固定攻略」的打勾进度：在旧版里打开固定攻略 → 导出进度，然后在新版「我的 → 逐日攻略」里导入。
- 存档进度：新版第一次打开时，用设置向导把当前存档状态录一遍（日期、现金、三项能力、各社群 RANK），大约一两分钟。

## 怎么用

1. **第一次打开**：选目标社群（「必须」/「想要」），回答「见面时通常带着同阿尔卡那的人格面具吗？」（基本都带 / 只给目标社群带 / 不带·不一定），再照着游戏画面录入日期、现金、个人能力值和社群 RANK。中途存档还会问失踪者是否已经救出。
2. **每天玩的时候**：看「今天」页或桌面小组件的下一步，做完点「完成」。
   - 训练、打工：点一下就记好。
   - 和社群的人见面：弹出小面板，「升到 RANK 7 了」/「没升级」/「没见到」，点一个就行。需要带人格面具时会提醒你带哪个阿尔卡那。
   - 塔尔塔罗斯：填到达的最高层，现金填「出塔后余额」或「这次多了多少」其中一个。
   - 顺便要做的事（比如给田中社长付款）会写在同一项里，不占时段。
3. **没照安排走**：点「做不了 / 改做别的」选原因或实际做的事，后面的安排会自动重新安排。
4. **很久没记录**：「我的 → 同步现状」，填今天的游戏日期，只改和预计不一样的数值。
5. **可选**：在「社群」页勾选想看的羁绊剧情（顺平、真田、虎狼丸、天田、荒垣），会在能看的日子留出时段；11 月后会用「结缘网站的字条」把多出来的晚上换成社群好感。

所有记录都能撤销（「我的 → 记录历史」），之后改目标或人格面具设置也不会改写已经确认过的记录。

## 安排是怎么算的

- 按时间顺序完整推演整个日历，每个时段只做一件事；每一步的结果（钱、个人能力值、RANK、解锁进度、塔尔塔罗斯次数）都会带到后面。
- 需要付钱解锁的社群（比如恶魔社群的三次付款）：交完钱手上至少还留 2,500 日元；快到交钱的时候，花钱提升能力和买结缘网站的字条都不会动用要交的钱。只有晚交会赶不上升级目标时，才会留得比这少。
- 搜索方法：贪心基础策略 + 前瞻“滚动推演”（rollout）。每个时段比较几个候选，各自往后模拟到 1 月 31 日，选最终结果最好的那个；白天优先留给社群，能在晚上练的个人能力值不占白天，打工只在需要用钱时安排。手机上一般 1 秒左右算完，算不完也会给出覆盖到 1 月 31 日的完整安排。
- 实测（新游戏开始）：见面时基本都带同阿尔卡那的人格面具，预计 22 个社群全部 MAX；不计人格面具加成时 21 个；「必须：皇帝」时皇帝 6月29日 MAX，共 21 个 MAX。目标严格按顺序优先：排在前面的目标不会为了后面的目标让步。各场景的完整结果见 [`docs/PLAN_METRICS.md`](docs/PLAN_METRICS.md)。
- 每份安排生成后都由独立的校验器检查：不重复占用时段、不在剧情日行动、不超支、不超过塔尔塔罗斯次数上限、RANK 推演一致。
- 看不见的数值（好感点数、能力值点数）只用保守估计，并且和你确认过的进度分开保存：预计永远不会被当成已发生的事实。普通见面 +10 点是估计值（游戏里约显示 ♪♪），在「规划说明」里写明。

## 数据来源

优先使用从 P3R 游戏文件中提取的数据表（社群点数阈值、加成系数、能力值阈值、休息日邀约日期、塔尔塔罗斯区块开放日、官方简体中文名称），其次是 Reload 专用的社群日历和攻略站，并排除 P3/FES/P3P 的旧数据。每条规则都记录了来源和可信度，详见 `rules/build/REPORT.md` 与 `rules/sources/SOURCES.json`。

## 从源码构建

所有构建依赖（JDK 17、Android SDK、Gradle 缓存）都放在项目目录内的 `.toolchain/`，不会安装到系统里。

```bash
scripts/setup_toolchain.sh
```

```bash
scripts/build_release.sh
```

产物在 `dist/`。

验证构建不依赖项目目录以外的任何东西（空环境、空 HOME、Gradle 离线模式，并检查 ~/.gradle、~/.m2、~/.android 没有新文件）：

```bash
scripts/verify_selfcontained.sh
```
发布签名密钥放在 `.signing/`（已被 git 忽略，不会上传）；没有密钥时会退回调试签名。

## 目录

| 路径 | 内容 |
|---|---|
| `android/` | Android 工程（`v3/` 为新版的安排功能；`MainActivity`/`PlannerWidget` 为保留的逐日攻略） |
| `rules/build/` | 规则包编译脚本，生成 `android/app/src/main/assets/p3r/p3r_rules.json` |
| `rules/p3r/`, `rules/sources/` | 规则证据与来源快照（不打包进 APK） |
| `docs/DESIGN_v3.md` | v3 设计文档 |
| `docs/HANDOFF_COMPLIANCE.md` | 交接文档每条要求的完成情况与证据 |
| `docs/PLAN_METRICS.md` | 各场景的安排质量（真实规则包） |
| `docs/analysis/` | 重写前对旧代码、数据、来源和交互的分析报告 |
| `P3R_HANDOFF_PROMPT.md`, `P3R_Planner_Handoff_20261013.zip` | 交接文档与原始交接包 |

## 说明

非官方粉丝工具，与 ATLUS / SEGA 无关。《女神异闻录3 Reload》及相关名称、标志归其各自权利人所有。数字字体 Barlow Condensed 采用 SIL Open Font License（`docs/licenses/`），未作修改。
