#!/usr/bin/env python3
"""Builds docs/HANDOFF_COMPLIANCE.md from the independent audit JSON files in docs/analysis/audit/.

audit.json   — first full audit of P3R_HANDOFF_PROMPT.md + owner directives (per section, per requirement)
reaudit.json — re-check of the items that were partial
final.json   — (optional) last verification of the items still partial after the re-audit
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
MARK = {'met': '✅ 已满足', 'partial': '🟡 部分满足', 'not_met': '❌ 未满足'}


def key(text):
    return re.sub(r'\W+', '', text.lower())[:60]


def load(name):
    p = os.path.join(AUD, name)
    return json.load(open(p)) if os.path.exists(p) else []


def short(text, n=420):
    text = ' '.join(str(text).split())
    return text if len(text) <= n else text[:n].rstrip() + '…'


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
    counts = {s: 0 for s in MARK}
    for v in items.values():
        counts[v['status']] += 1
    out = ['# 交接文档完成情况', '',
           '本文件逐条对照 `P3R_HANDOFF_PROMPT.md`（交接/设计文档）以及所有者在开发过程中补充的要求，列出 3.0.1 的完成情况和证据。',
           '每一条都由独立审查代理对照代码、测试、真机/模拟器截图核实（只读审查，证据含文件位置、测试名与运行结果、截图路径），'
           '未满足的地方如实写出。原始审查记录在 `docs/analysis/audit/`。', '',
           f"**统计**：已满足 {counts['met']} 条 · 部分满足 {counts['partial']} 条 · 未满足 {counts['not_met']} 条（共 {len(items)} 条）。", '',
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
    open(os.path.join(ROOT, 'docs', 'HANDOFF_COMPLIANCE.md'), 'w').write('\n'.join(out) + '\n')
    print(counts)


if __name__ == '__main__':
    sys.exit(main())
