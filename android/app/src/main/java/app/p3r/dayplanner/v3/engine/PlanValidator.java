package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Independent replay validator (docs/DESIGN_v3.md §5.4 step 4). It re-derives the forecast with its
 * own accumulator written directly from the rule pack, deliberately sharing no code with
 * {@link Simulator}/{@code Kernel}, so a transition bug shows up as a mismatch instead of passing
 * silently.
 *
 * <p>Errors (plan must be rejected): wrong coverage or order of slots, actions on locked slots or
 * in the wrong slot kind, meetings outside the link's O/D days or while missing/lost, unlock steps
 * out of order / too early / without prerequisites, excluded or unavailable activities, spending
 * below zero, Tartarus caps, ranks that skip, regress or exceed MAX, predicted ranks or cash that
 * disagree with the recount, and a summary whose end ranks disagree. Warnings: a FREE slot where a
 * productive action was verifiably legal. Messages are developer-facing English.
 */
final class PlanValidator {
    private PlanValidator() {}

    /** Errors after the first ones usually cascade from them; keep the report readable. */
    private static final int MAX_MESSAGES = 40;

    /** Errors and warnings of one validation run. */
    static final class Report {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        boolean ok() { return errors.isEmpty(); }
    }

    static List<String> validate(Rules rules, Profile profile, SimState observed, Plan plan) {
        return check(rules, profile, observed, plan).errors;
    }

    static Report check(Rules rules, Profile profile, SimState observed, Plan plan) {
        Report report = new Report();
        new Replay(rules, profile, observed, report).run(plan);
        return report;
    }

    /** The independent accumulator. */
    private static final class Replay {
        final Rules rules;
        final Profile profile;
        final Report report;
        final int nLinks;
        final int nActs;
        final int nBlocks;

        int slot;
        long cash;
        final int[] stat = new int[3];
        final int[] rank = new int[22];
        final int[] pts = new int[22];
        final boolean[] ready = new boolean[22];
        final int[] step = new int[22];
        final int[] lastStep = new int[22];
        final int[] heldInto = new int[22];
        long rescued;
        long failed;
        final int[] visits;
        final int[] shifts;
        final int[] used;

        Replay(Rules rules, Profile profile, SimState o, Report report) {
            this.rules = rules;
            this.profile = profile;
            this.report = report;
            nLinks = rules.links.length;
            nActs = rules.activities.length;
            nBlocks = rules.blocks.length;
            visits = new int[nBlocks];
            shifts = new int[nActs];
            used = new int[nActs];
            slot = o.slot;
            cash = Math.max(0, o.cash);
            copy(o.statPts, stat);
            copy(o.rank, rank);
            copy(o.pts, pts);
            for (int i = 0; i < Math.min(22, o.ready.length); i++) ready[i] = o.ready[i];
            copy(o.unlockStep, step);
            java.util.Arrays.fill(lastStep, -1);
            copy(o.lastStepDay, lastStep);
            copy(o.heldRank, heldInto);
            copy(o.towerVisits, visits);
            copy(o.shifts, shifts);
            copy(o.used, used);
            rescued = o.rescued;
            failed = o.rescueFailed;
            for (int i = 0; i < 22; i++) rank[i] = Math.max(0, Math.min(10, rank[i]));
        }

        private static void copy(int[] from, int[] to) {
            if (from == null) return;
            System.arraycopy(from, 0, to, 0, Math.min(from.length, to.length));
        }

        private void error(int at, String msg) {
            if (report.errors.size() < MAX_MESSAGES) {
                report.errors.add("slot " + at + ": " + msg);
            } else if (report.errors.size() == MAX_MESSAGES) {
                report.errors.add("… further errors omitted");
            }
        }

        void run(Plan plan) {
            int last = rules.lastSlot();
            if (plan.fromSlot != slot) report.errors.add("plan starts at " + plan.fromSlot + ", observed slot is " + slot);
            if (plan.toSlot != last) report.errors.add("plan ends at " + plan.toSlot + ", calendar ends at " + last);
            int expected = Math.max(0, last - slot + 1);
            if (plan.steps.size() != expected) report.errors.add("plan has " + plan.steps.size() + " steps, expected " + expected);
            for (Plan.Step s : plan.steps) {
                if (s.slot != slot) {
                    error(s.slot, "out of order, expected slot " + slot);
                    return;
                }
                if (s.titleZh == null || s.titleZh.isEmpty()) error(slot, "empty title");
                replayStep(s);
                if (cash < 0) error(slot, "cash below zero: " + cash);
                advance();
            }
            for (int l = 0; l < Math.min(nLinks, plan.summary.endRanks.length); l++) {
                if (plan.summary.endRanks[l] != rank[l]) {
                    report.errors.add("summary end rank of " + rules.links[l].id + " is " + plan.summary.endRanks[l] + ", recount " + rank[l]);
                }
            }
        }

        // ------------------------------------------------------------------ one step

        private void replayStep(Plan.Step s) {
            String lock = rules.lockOf(slot);
            if (lock != null) {
                if (!s.action.equals("locked:" + lock) || !"locked".equals(s.kind)) error(slot, "locked slot (" + lock + ") planned as " + s.action);
                return;
            }
            Action a = Action.parse(rules, s.action);
            if (a == null) {
                error(slot, "unparseable action " + s.action);
                return;
            }
            switch (a.type) {
                case LOCKED:
                    error(slot, "free slot marked locked");
                    return;
                case FREE:
                    expectKind(s, "free");
                    if (productiveLegal()) report.warnings.add("slot " + slot + ": FREE while a productive action was legal");
                    return;
                case MEET:
                    expectKind(s, "social");
                    meet(s, a.target);
                    break;
                case UNLOCK_STEP:
                    expectKind(s, "unlock");
                    unlock(s, a.target, a.step);
                    break;
                case ACTIVITY:
                    expectKind(s, rules.activities[a.target].kind == Rules.ActivityKind.JOB ? "job" : "stat");
                    activity(a.target);
                    break;
                case TARTARUS:
                    expectKind(s, "tower");
                    tower(s, a.target);
                    break;
                default:
                    break;
            }
            if (s.predicted.cashAfter != cash) error(slot, "predicted cash " + s.predicted.cashAfter + " but recount " + cash);
        }

        private void expectKind(Plan.Step s, String kind) {
            if (!kind.equals(s.kind)) error(slot, "kind " + s.kind + " for " + s.action);
        }

        private int day() { return slot >> 1; }

        private boolean evening() { return (slot & 1) == 1; }

        private void meet(Plan.Step s, int l) {
            Rules.Link link = rules.links[l];
            int d = day();
            boolean ordinaryCell = link.avail.get(d) && link.evening == evening();
            boolean inviteCell = link.invite.get(d) && !evening();
            if (link.kind != Rules.LinkKind.MANUAL) error(slot, "meeting a story link " + link.id);
            if (!ordinaryCell && !inviteCell) error(slot, link.id + " is not available in this slot");
            if (unavailable(l)) error(slot, link.id + " is missing or lost");
            int r = rank[l];
            if (r < 1 || r >= 10) {
                error(slot, "meeting " + link.id + " at rank " + r);
                return;
            }
            double m = multiplier();
            int thr = link.threshold[r + 1];
            int need = thr + (isGoal(l) && profile.goalBuffer && thr > 0 ? ordinaryGain() : 0);
            boolean wasReady = ready[l];
            boolean held = !wasReady && heldNow(l, r + 1);
            boolean up = !held && (wasReady || pts[l] >= need);
            if (up) {
                rank[l] = r + 1;
                pts[l] = answerPoints(l, r + 1, m);
                ready[l] = false;
                heldInto[l] = 0;
            } else {
                int next = pts[l] + Math.max(1, floor(ordinaryGain() * m));
                pts[l] = held ? Math.min(next, Math.max(pts[l], need)) : next;
            }
            checkRanks(s, r, rank[l]);
        }

        private void checkRanks(Plan.Step s, int from, int to) {
            if (s.predicted.rankFrom != from || s.predicted.rankTo != to) {
                error(slot, "predicted RANK " + s.predicted.rankFrom + "→" + s.predicted.rankTo + " but recount " + from + "→" + to);
            }
            if (to < from || to > from + 1 || to > 10) error(slot, "rank jump " + from + "→" + to);
        }

        private void unlock(Plan.Step s, int l, int lastIdx) {
            Rules.Link link = rules.links[l];
            List<Rules.Step> steps = link.unlock.steps;
            int d = day();
            int k = step[l];
            if (rank[l] != 0) error(slot, "unlock step for established link " + link.id);
            if (lastIdx < k || lastIdx >= steps.size()) {
                error(slot, "unlock step " + lastIdx + " out of order for " + link.id + " (next is " + k + ")");
                return;
            }
            for (int j = k; j < lastIdx; j++) {
                if (steps.get(j).consumesSlot) error(slot, "unlock step " + j + " of " + link.id + " skipped");
            }
            if (steps.get(lastIdx).evening != evening()) error(slot, "unlock step in the wrong slot kind");
            if (d < link.unlock.earliestDay) error(slot, "unlock of " + link.id + " before its earliest day");
            if (unavailable(l)) error(slot, link.id + " is missing or lost");
            for (Map.Entry<Integer, Integer> e : link.unlock.statLv.entrySet()) {
                if (statLevel(e.getKey()) < e.getValue()) error(slot, link.id + " needs stat " + e.getKey() + " Lv" + e.getValue());
            }
            for (Map.Entry<Integer, Integer> e : link.unlock.linkRank.entrySet()) {
                if (rank[e.getKey()] < e.getValue()) error(slot, link.id + " needs link " + e.getKey() + " rank " + e.getValue());
            }
            int establish = -1;
            for (int j = 0; j < steps.size(); j++) {
                if (steps.get(j).establishes) {
                    establish = j;
                    break;
                }
            }
            if (establish < 0) establish = steps.size() - 1;
            int from = rank[l];
            for (int j = k; j <= lastIdx; j++) {
                Rules.Step st = steps.get(j);
                if (!stepDayAllowed(link, st, d)) error(slot, "unlock step " + j + " of " + link.id + " not allowed on day " + d);
                int gap = j == k ? (lastStep[l] < 0 ? 0 : d - lastStep[l]) : 0;
                if (j == k && lastStep[l] >= 0 && gap < st.minGapDays) error(slot, "unlock step " + j + " of " + link.id + " too soon");
                if (j > k && st.minGapDays > 0) error(slot, "folded unlock step " + j + " needs its own day");
                cash -= Math.max(0, st.cost);
                if (j == establish && rank[l] == 0) {
                    rank[l] = 1;
                    pts[l] = answerPoints(l, 1, multiplier());
                    ready[l] = false;
                }
            }
            step[l] = Math.max(step[l], lastIdx + 1);
            lastStep[l] = d;
            checkRanks(s, from, rank[l]);
        }

        private boolean stepDayAllowed(Rules.Link link, Rules.Step st, int d) {
            if (d < st.fromDay) return false;
            Rules.Day day = rules.day(d);
            switch (st.dayRule) {
                case LINK_AVAIL: return link.meetsOn(d);
                case WEEKDAYS: return day != null && Tables.contains(st.weekdays, day.wd);
                case FIXED: return Tables.contains(st.fixedDays, d);
                default: return true;
            }
        }

        private void activity(int idx) {
            Rules.Activity a = rules.activities[idx];
            if (!activityOnCalendar(a)) error(slot, "activity " + a.id + " not available in this slot");
            if (profile.excluded(a.id)) error(slot, "activity " + a.id + " is excluded by the player");
            if (a.once && used[idx] > 0) error(slot, "once-only activity " + a.id + " repeated");
            for (Map.Entry<Integer, Integer> e : a.requiresLv.entrySet()) {
                if (statLevel(e.getKey()) < e.getValue()) error(slot, a.id + " needs stat " + e.getKey() + " Lv" + e.getValue());
            }
            int buffer = a.cost > 0 ? Math.max(0, profile.cashBuffer) : 0;
            if (a.cost > 0 && cash < a.cost + buffer) error(slot, a.id + " costs " + a.cost + " (+" + buffer + " buffer) with cash " + cash);
            cash -= Math.max(0, a.cost);
            if (a.kind == Rules.ActivityKind.JOB) {
                int pay = 0;
                int shift = shifts[idx] + 1;
                for (int[] tier : a.pay) if (shift >= tier[0]) pay = tier[1];
                cash += pay;
                shifts[idx]++;
            }
            for (int s = 0; s < Math.min(3, a.gain.length); s++) stat[s] += a.gain[s];
            used[idx]++;
        }

        private boolean activityOnCalendar(Rules.Activity a) {
            Rules.Day d = rules.day(day());
            if (d == null) return false;
            if (evening() ? !a.eve : !a.day) return false;
            if (d.d < a.fromDay || d.d > a.toDay || !a.allowsWeekday(d.wd)) return false;
            return a.dayTypes.isEmpty() || a.dayTypes.contains(d.type);
        }

        private void tower(Plan.Step s, int b) {
            Rules.Day d = rules.day(day());
            if (!evening() || d == null || !d.tartarus || d.block != b) error(slot, "Tartarus block " + b + " not enterable in this slot");
            if (b < 0 || b >= nBlocks) return;
            if (visits[b] >= profile.towerCapPerBlock) error(slot, "Tartarus cap " + profile.towerCapPerBlock + " exceeded in block " + b);
            visits[b]++;
            if (s.predicted.cashDelta < 0) error(slot, "Tartarus visit loses money in the forecast");
            cash += Math.max(0, s.predicted.cashDelta);
            int dd = day();
            for (int i = 0; i < rules.rescues.length; i++) {
                long bit = 1L << i;
                Rules.Rescue r = rules.rescues[i];
                if (dd >= r.fromDay && dd <= r.toDay && ((rescued | failed) & bit) == 0) rescued |= bit;
            }
        }

        // ------------------------------------------------------------------ model pieces

        private boolean productiveLegal() {
            int d = day();
            for (Rules.Link link : rules.links) {
                int l = link.idx;
                if (link.kind != Rules.LinkKind.MANUAL || rank[l] < 1 || rank[l] >= 10 || unavailable(l)) continue;
                boolean cell = (link.avail.get(d) && link.evening == evening()) || (link.invite.get(d) && !evening());
                if (cell) return true;
            }
            for (Rules.Activity a : rules.activities) {
                if (a.cost != 0 || profile.excluded(a.id) || (a.once && used[a.idx] > 0) || !activityOnCalendar(a)) continue;
                boolean reqs = true;
                for (Map.Entry<Integer, Integer> e : a.requiresLv.entrySet()) reqs &= statLevel(e.getKey()) >= e.getValue();
                if (!reqs) continue;
                if (a.kind == Rules.ActivityKind.JOB) return true;
                for (int s = 0; s < Math.min(3, a.gain.length); s++) {
                    if (a.gain[s] > 0 && stat[s] < rules.stats[s].thresholds[6]) return true;
                }
            }
            Rules.Day day = rules.day(d);
            if (!evening() || day == null || !day.tartarus || day.block < 0 || day.block >= nBlocks) return false;
            // A visit that would use the cap a pending rescue later in this block needs is not counted.
            return visits[day.block] + 1 + pendingRescues(day.block) <= profile.towerCapPerBlock;
        }

        /** Rescues of links that still matter whose window has an enterable evening later in this block. */
        private int pendingRescues(int block) {
            int pending = 0;
            for (int i = 0; i < rules.rescues.length; i++) {
                Rules.Rescue r = rules.rescues[i];
                if (((rescued | failed) & (1L << i)) != 0 || r.link < 0 || r.link >= nLinks || rank[r.link] >= 10) continue;
                if (!isGoal(r.link) && !profile.otherLinks) continue;
                for (int s = slot + 1; s <= Math.min(rules.lastSlot(), r.toDay * 2 + 1); s++) {
                    Rules.Day day = rules.day(s >> 1);
                    if ((s & 1) == 1 && s >> 1 >= r.fromDay && day != null && day.tartarus && day.block == block
                            && rules.lockOf(s) == null) {
                        pending++;
                        break;
                    }
                }
            }
            return pending;
        }

        private void advance() {
            slot++;
            if ((slot & 1) != 0) return;
            int d = slot >> 1;
            for (int i = 0; i < rules.rescues.length; i++) {
                long bit = 1L << i;
                if (d > rules.rescues[i].toDay && ((rescued | failed) & bit) == 0) failed |= bit;
            }
            if (d > rules.lastDay) return;
            for (Rules.Link link : rules.links) {
                int l = link.idx;
                if (link.kind == Rules.LinkKind.STORY) {
                    if (link.storyRankDay == null) continue;
                    for (int r = 1; r < Math.min(11, link.storyRankDay.length); r++) {
                        int sd = link.storyRankDay[r];
                        if (sd >= 0 && sd <= d && rank[l] < r) rank[l] = r;
                    }
                } else if (rank[l] == 0 && link.unlock.steps.isEmpty() && d >= link.unlock.earliestDay
                        && !unavailableOn(l, d) && prereqs(link)) {
                    rank[l] = 1;
                    pts[l] = answerPoints(l, 1, multiplier());
                    ready[l] = false;
                }
            }
        }

        private boolean prereqs(Rules.Link link) {
            for (Map.Entry<Integer, Integer> e : link.unlock.statLv.entrySet()) {
                if (statLevel(e.getKey()) < e.getValue()) return false;
            }
            for (Map.Entry<Integer, Integer> e : link.unlock.linkRank.entrySet()) {
                if (rank[e.getKey()] < e.getValue()) return false;
            }
            return true;
        }

        private boolean unavailable(int l) {
            return unavailableOn(l, day());
        }

        /** Missing (window active, not rescued) or lost (deadline passed without rescue). */
        private boolean unavailableOn(int l, int d) {
            Rules.Link link = rules.links[l];
            int from = -1;
            int to = -1;
            int rescue = -1;
            if (link.missing != null) {
                from = link.missing.fromDay;
                to = link.missing.toDay;
                for (int i = 0; i < rules.rescues.length; i++) {
                    if (rules.rescues[i].id.equals(link.missing.rescueId)) rescue = i;
                }
            } else {
                for (int i = 0; i < rules.rescues.length; i++) {
                    if (rules.rescues[i].link == l) {
                        from = rules.rescues[i].fromDay;
                        to = rules.rescues[i].toDay;
                        rescue = i;
                    }
                }
            }
            if (from < 0) return false;
            if (rescue < 0) return d >= from && d <= to;
            long bit = 1L << rescue;
            if ((failed & bit) != 0) return true;
            if ((rescued & bit) != 0) return false;
            return d >= from;
        }

        private boolean heldNow(int l, int into) {
            if (heldInto[l] == into) return true;
            for (Rules.Hold h : rules.links[l].holds) {
                if (h.rank == into && (h.untilDay < 0 || day() < h.untilDay)) return true;
            }
            return false;
        }

        private boolean isGoal(int l) {
            return l < profile.tier.length && profile.tier[l] != Profile.Tier.NONE;
        }

        private int ordinaryGain() {
            return Math.max(1, rules.policy.ordinaryGain);
        }

        private int statLevel(int s) {
            if (s < 0 || s >= rules.stats.length) return 6;
            int[] thr = rules.stats[s].thresholds;
            int lv = 1;
            for (int i = 2; i <= 6; i++) if (stat[s] >= thr[i]) lv = i;
            return lv;
        }

        private double multiplier() {
            double m = profile.personaBonus ? rules.policy.personaMult : 1.0;
            Rules.Stat charm = rules.stat("charm");
            if (profile.charmBonus && charm != null && statLevel(charm.idx) >= 6) m *= rules.policy.charmMult;
            return m;
        }

        private int answerPoints(int l, int r, double m) {
            if (profile.answers != Profile.AnswerPolicy.BEST) return 0;
            Rules.Link link = rules.links[l];
            boolean romance = l < profile.route.length && profile.route[l] == Profile.Route.ROMANCE && link.eventGainRomance != null;
            int[] eg = romance ? link.eventGainRomance : link.eventGain;
            return r < eg.length ? floor(eg[r] * m) : 0;
        }

        private static int floor(double x) {
            return (int) Math.floor(x + 1e-6);
        }
    }
}
