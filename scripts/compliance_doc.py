#!/usr/bin/env python3
"""Builds docs/HANDOFF_COMPLIANCE.md from the independent audit JSON files in docs/analysis/audit/.

audit.json   — first full audit of P3R_HANDOFF_PROMPT.md + owner directives (per section, per requirement)
reaudit.json — re-check of the items that were partial
final.json   — (optional) last verification of the items still partial after the re-audit
overrides.json — (optional) owner decisions and later releases that change a row after the audits,
               matched by section and requirement text (status 'superseded' when the owner replaced
               the requirement); each entry names its reason in `label`. An entry with `split` replaces
               the row with its parts, in place (a requirement with two halves the owner treated
               differently, e.g. [61]: the widget superseded, the progress kept safe)
Later files override earlier ones for the same requirement.
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AUD = os.path.join(ROOT, 'docs', 'analysis', 'audit')
SECTIONS = {
    'outcome_objective': '产品目标与规划目标（交接文档 5–20 行）',
    'forecasting_interaction': '保守预测与交互要求（22–56 行）',
    'visual_widget_sources': '视觉、小组件、数据来源与语言（57–67 行 + 所有者要求）',
    'defects_engineering': '已知缺陷、工程与交付（69–103 行）',
}
MARK = {'met': '✅ 已满足', 'partial': '🟡 部分满足', 'not_met': '❌ 未满足', 'superseded': '⚪ 已被取代'}


def key(text):
    return re.sub(r'\W+', '', text.lower())[:60]


def load(name):
    p = os.path.join(AUD, name)
    return json.load(open(p)) if os.path.exists(p) else []


def short(text, n=420):
    text = ' '.join(str(text).split())
    return text if len(text) <= n else text[:n].rstrip() + '…'


def version():
    gradle = open(os.path.join(ROOT, 'android', 'app', 'build.gradle')).read()
    return re.search(r"versionName '([^']+)'", gradle).group(1)


REVIEWS = (
    ('wiki_sources.json', '数据来源（Reload 专用）'),
    ('design_review_1.json', '外观（第 1 轮）'),
    ('design_review_2.json', '外观（第 2 轮）'),
    ('design_review_3.json', '外观（第 3 轮，3.1.0 新功能）'),
    ('owner_review.json', '所有者审阅（3.1.x 文案与界面）'),
)
VERDICT = {'pass': '✅ 通过', 'pass_with_nits': '✅ 通过（只有小问题）', 'pass_with_notes': '✅ 通过（附说明）', 'fail': '❌ 不通过'}


def apply_overrides(items, order):
    """Applies overrides.json: a new status and/or evidence/gap text for one row each, or its parts.
    Returns the requirement numbers of the rows split in parts."""
    split = []
    for sec in load('overrides.json'):
        for it in sec['items']:
            k = (sec['section'], key(it['requirement']))
            if k not in items:
                raise SystemExit(f"overrides.json: no row {it['requirement']!r} in {sec['section']}")
            if 'split' in it:
                parts = []
                for part in it['split']:
                    pk = (sec['section'], key(part['requirement']))
                    if pk in items or pk in parts:
                        raise SystemExit(f"overrides.json: part {part['requirement']!r} clashes with another row")
                    if part.get('status') not in MARK:
                        raise SystemExit(f"overrides.json: part {part['requirement']!r} needs a status")
                    items[pk] = dict(part, section=sec['section'], round=part['label'])
                    parts.append(pk)
                del items[k]
                at = order.index(k)
                order[at:at + 1] = parts
                split.append(it['requirement'].split(']')[0] + ']')
                continue
            row = dict(items[k])
            for field in ('status', 'evidence', 'gap'):
                if field in it:
                    row[field] = it[field]
            # A new status is the owner's call; an update of the text keeps the audit round it amends.
            row['round'] = it['label'] if 'status' in it else row['round'] + '；' + it['label']
            items[k] = row
    return split


def reviews():
    """The independent sign-off reviews (read-only agents), one row each."""
    out = ['## 独立复核', '',
           '除逐条审计外，另由互不相干的只读审查代理分别核对了数据来源和外观（原始记录在 `docs/analysis/audit/`）。', '',
           '| 复核 | 结论 | 摘要 | 记录 |', '|---|---|---|---|']
    for name, title in REVIEWS:
        path = os.path.join(AUD, name)
        if not os.path.exists(path):
            continue
        r = json.load(open(path))
        scope = f"（看了 {r['images_reviewed']} 张图）" if r.get('images_reviewed') else ''
        if r.get('fact_checks'):
            ok = sum(1 for f in r['fact_checks'] if f['result'].startswith('match'))
            fixed = sum(1 for f in r['fact_checks'] if 'fixed' in f['result'])
            scope = f"（核对 {len(r['fact_checks'])} 项事实：{ok} 项一致" + (f"，{fixed} 项已按更严格的说法修正" if fixed else '') + '）'
        out.append('| ' + ' | '.join([title, VERDICT.get(r['verdict'], r['verdict']) + scope,
                                       short(r['summary'], 600).replace('|', '／'), f'`{name}`']) + ' |')
    return out + ['']


def main():
    base = load('audit.json')
    order, items = [], {}
    for sec in base:
        for it in sec['items']:
            k = (sec['section'], key(it['requirement']))
            order.append(k)
            items[k] = dict(it, section=sec['section'], round='首次审计')
    # Re-audits check the items still partial, in the same order as they appear per section.
    for name, label in (('reaudit.json', '复查'), ('final.json', '最终复查')):
        for sec in load(name):
            pending = [k for k in order if k[0] == sec['section'] and items[k]['status'] != 'met']
            for k, it in zip(pending, sec['items']):
                items[k] = dict(it, requirement=items[k]['requirement'], section=sec['section'], round=label)
    split = apply_overrides(items, order)
    counts = {s: 0 for s in MARK}
    for v in items.values():
        counts[v['status']] += 1
    out = ['# 交接文档完成情况', '',
           f'本文件逐条对照 `P3R_HANDOFF_PROMPT.md`（交接/设计文档）以及所有者在开发过程中补充的要求，列出 {version()} 的完成情况和证据。',
           '每一条都由独立审查代理对照代码、测试、真机/模拟器截图核实（只读审查，证据含文件位置、测试名与运行结果、截图路径），'
           '未满足的地方如实写出。原始审查记录在 `docs/analysis/audit/`。', '',
           f"**统计**：已满足 {counts['met']} 条 · 部分满足 {counts['partial']} 条 · 未满足 {counts['not_met']} 条"
           + (f" · 已被所有者要求取代 {counts['superseded']} 条" if counts['superseded'] else '') + f"（共 {len(items)} 条" + (f"，其中 {'、'.join(split)} 按两半分成两条" if split else '') + "）。", '',
           '3.1.0 按所有者要求移除了 3.0.x 里附带的旧攻略和它的小组件（`docs/DESIGN_v3.md` §19）。交接文档第 61 行要求'
           '「旧的固定攻略小组件和进度都要保持安全」（Existing fixed-guide widget and progress must remain safe），'
           '这一条按两半分成两条：小组件那一半已被所有者要求取代；进度那一半已满足——打勾进度没有删除，仍留在手机上，'
           '3.1.0 之后的更新可以在「我的 → 备份与恢复」里导出（已发布的 3.1.0 安装包里还没有这一项）。'
           '引用已删除文件的证据已更新（`docs/analysis/audit/overrides.json`）；其余证据里提到旧攻略的，说的是当时审计的版本。', '',
           '相关文档：`docs/DESIGN_v3.md`（设计）、`docs/PLAN_METRICS.md`（各场景安排质量）、`rules/build/REPORT.md`（数据来源与 Reload 专用来源政策）、'
           '`docs/GLOSSARY_zh.md`（用语表）、`scripts/verify_selfcontained.sh`（证明构建只依赖项目目录）。', '']
    for sec, title in SECTIONS.items():
        rows = [items[k] for k in order if k[0] == sec]
        seen = set()
        out += [f'## {title}', '', '| 要求 | 结果 | 证据 | 仍未解决 / 说明 |', '|---|---|---|---|']
        for r in rows:
            rid = key(r['requirement'])
            if rid in seen:
                continue
            seen.add(rid)
            gap = short(r.get('gap', ''), 300) if r['status'] != 'met' or r.get('gap') else ''
            out.append('| ' + ' | '.join([
                short(r['requirement'], 160).replace('|', '／'),
                MARK[r['status']] + f"（{r['round']}）",
                short(r['evidence']).replace('|', '／'),
                gap.replace('|', '／'),
            ]) + ' |')
        out.append('')
    out += reviews()
    open(os.path.join(ROOT, 'docs', 'HANDOFF_COMPLIANCE.md'), 'w').write('\n'.join(out) + '\n')
    print(counts)


if __name__ == '__main__':
    sys.exit(main())
