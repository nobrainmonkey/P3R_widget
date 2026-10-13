# P3R规则包0.1.0

真实社区资料交叉核对的有限规则包，不是synthetic数据，也未声称游戏实测认证或全年完整。原版攻略、元数据和APK没有修改。

## 接入入口

- playable_actions.json：可映射的太阳/恶魔会面及有限进塔动作
- calendar_january_slice.json：1/2—1/30，两社群58个明确日期格；其他日期/实体未知
- tower_stages.json：11个阶段记录；只有stage10启用。冲突日期保留null，不擅自选边
- social_links.json：22社群目录、19个周表候选、前置和冲突。周表不是可直接排程的逐日日历
- stats_actions.json：三维完整门槛和5个确定基础数值的夜间活动，需额外确认空闲时段、地点、钱和加成状态。不是在所有日期均可执行
- sources_calendar_tower.json、各文件sources：来源与版本证据
- integration_contract.json：日期、未知值、回填和幂等访问语义
- coverage.json：支持范围与下一步具体数据缺口
- test_fixtures.json、validate_rules.py、validation.json：16项数据检查与跨接口测试样例

日期ordinal以游戏4/1为0，January位于下一游戏年，不能用现实年份或系统时间。calendar的ISOweekday仅用于核对游戏日历。原数据的白天/放学后应映射到同一个有限日间槽，不得凭文字额外造槽。

会面+1只在已解锁、Rank1..9、正常社群、明确合法日期、实际完成正常会面的前提下成立；无需隐藏好感。但玩家点开任务或旧版勾选不等于完成游戏会面。

进塔只有01-20/01-28是当前证据支持的安全候选子集；没收录的夜晚不是非法，搜索没找到不能据此证明无法进塔。探索无预计现金或楼层效果，回填只接受净现金变化/绝对余额二选一与实际最高层，并以同一访问ID幂等记一次。本阶段已用次数来自玩家新基线，不由历史勾选推导。

证据分类：corroborated=社区来源一致；contested=来源冲突；incomplete/unknown=不完整。没有规则被标为游戏实测confirmed。Wiki标题不足以判版本：gorillawiki103被发现含旧版214F/VisionQuest并排除。

运行：python validate_rules.py。检查只证明数据一致性与有限数学样例，不替代引擎适配测试、Android测试或实际游戏存档回放。

## 0.2.0 扩展（2026-10-13）

- calendar_full_candidate.json：286 日、19 社群、5,434 个来源日历格。O、D、负号和空白分别保存，不将邀请当普通会面，不将负号推广为全局不能行动。逐日来源已收齐，独立实测未完成。
- reported_rank_points.json：17 个好感度社群、175 条等级场景数值。场景 Rank n 的门槛用于下一次 n→n+1，不能错用到进入 n 的会面。精确数值模拟仍未启用。
- observation_gated_rank_actions.json：17 个已解锁社群的观察式升阶动作。玩家明确确认当前等级已可升阶、日期可正常会面、特殊条件满足后，可预测一次普通会面；会面后 readiness 变回未知，不能连锁假定永远能升级。
- romance_rank_rules_candidate.json：六个恋爱对象，65 场景、59 数值门槛、121 选项序号记录、10 前置恋爱标志及友情分支。保留来源序号，未编造中文选项。
- reported_regular_activities.json：22 项活动原始数值候选；未核实的耗时、特殊条件和工资模型保持未知。

这是通用目标规划产品的数据准备，不依赖任何用户既有存档或预先选定的社群。下一步先完成魔术师纵向模型，再扩展战车、恋爱对象和全社群。
