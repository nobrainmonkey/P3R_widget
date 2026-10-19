# Persona 图鉴与合成数据

图鉴数据与合成规则整理来自 **[aqiu384](https://github.com/aqiu384)** 的 [Megami Tensei Fusion Tools](https://github.com/aqiu384/megaten-fusion-tool) 与 [Megami Tensei Database](https://github.com/aqiu384/megaten-database)。人格面具、技能和塔罗名称采用游戏导出表中的官方简体中文与英文。游戏名称与文本属于 ATLUS / SEGA。本应用未收录立绘或其他图像。

| 来源 | 固定版本 | 使用内容 |
| --- | --- | --- |
| Megami Tensei Fusion Tools | `0b207612f6cdcff092e62af16f5c355271a1d892` | `src/app/p3r/data/` 的Persona、技能、属性顺序、继承、合成表、特殊合成与解锁条件；`src/app/compendium/data/skill-effects.json` 的技能效果模板 |
| Megami Tensei Database | `8f87f212e206748b71298a52eb1eaec1efed6161` | `src/p3r/Content/Xrd777/Blueprints/common/Names/` 中的Persona、技能和塔罗中英名称；`UI/Tables/AddContentEntitlementNameDataAsset.tsv` 中的DLC名称 |

工具代码采用 [Unlicense](licenses/megaten-fusion-tool-Unlicense.txt)。Database 仓库在上述版本未单列许可证；本项目没有把工具代码的 Unlicense 扩大标注到游戏原始文本或图像。

## 范围和格式

离线资源为 `android/app/src/main/assets/p3r_compendium.json`，格式版本为 `schemaVersion: 1`。包含 **173个本体Persona、21个DLC Persona、333种初始/升级技能、7种神通法、22条特殊合成配方**。不含 Episode Aigis 的 `aeg-*` / `Content/Astrea` 数据。

- `persona_<gameId>`、`skill_<gameId>` 使用原游戏名称表的行ID；显示名称不作为主键。
- Persona 的 `key` 保留上游计算器键名，便于核对结果。上游 `Seiten Taisei A` 映射到原游戏 ID310（齐天大圣）；`Loki A` 显示为洛基，并通过DLC来源与本体区分。
- `skills[].level == 0` 表示初始技能，其他整数为习得等级。上游 `5217` 神通法哨兵被移入独立的 `theurgySkillIds`，不会作为等级显示。
- 三个DLC包为 `p5r_1`、`p5r_2`、`p4g`。上游将伊邪那岐标为 `Persona 4 Set 1`，其余P4 Persona标为 `Persona 4 Set`；导入时统一成同一个官方P4G组合。
- 耐性顺序为斩击、打击、贯穿、火炎、冰冻、电击、疾风、光明、黑暗、万能。编码为 `w` 弱、`s` 耐、`n` 无效、`r` 反射、`d` 吸收、`-` 普通。
- Persona/技能/塔罗/DLC**名称**采用原表 `zh-Hans` 与 `en`。`effectZh` 是根据上游结构化数值生成的简短中文技能说明，`unlock.textZh` 是解锁条件译文；这两项不标为官方原文。
- `fusion.chart` 保留上游下三角表，`specialRecipes` 使用稳定Persona ID。拥有DLC会改变可参与普通合成的Persona列表，应在合成前按用户选择重建。

## 重新生成与离线校验

在本机克隆并切换到上表两个固定版本，然后运行：

```sh
python3 scripts/import_persona_data.py \
  --tool-repo /path/to/megaten-fusion-tool \
  --database-repo /path/to/megaten-database
```

导入器只读取JSON和TSV，核对两个提交版本，不执行上游程序。加 `--check` 可比较生成结果，不写文件。

CI仅使用仓库中已提交的资源，无需网络或上游仓库：

```sh
python3 scripts/validate_persona_data.py
```

校验覆盖数量、唯一ID、中英名称、DLC分组、十项耐性、技能引用与学习等级、神通法分类、22项特殊合成和塔罗表完整性。

独立核对合成公式（需上述固定版本的工具仓库与 Java 17）：

```sh
python3 scripts/verify_fusion_reference.py --tool-repo /path/to/megaten-fusion-tool
```

该脚本读取上游原始表，在临时目录编译本项目两个纯 Java 类，再以独立 Python 实现核对
8种DLC配置的全部134,126组材料，并交换材料顺序复查。不执行上游代码、不联网，也不改资源。
3.5.0首次核对全部一致。
