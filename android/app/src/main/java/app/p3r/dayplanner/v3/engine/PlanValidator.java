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
 * in the wrong slot kind, meetings outside the link's O days or while missing/lost, unconfirmed
 * holiday invitations in forecasts, unlock steps
 * out of order / too early / without prerequisites, side steps ({@link Plan.Step#side}: listed
 * non-consuming unlock steps, checked for order, slot kind, day rule, minGapDays, requirements and
 * fees, and counted in the unlock progress), excluded or unavailable activities, dating-site
 * notes (evening, from their first day, allowed, affordable, an established non-MAX link; never a
 * rank-up), Link Episodes (opted-in track, slot kind, the day's cell, in order), spending
 * below zero, Tartarus caps per block ({@link Profile#capFor}), a Tartarus step whose rescue claim
 * ({@code rescue_on_visit}) disagrees with the rescue windows (fromDay through toDay, the limit evening
 * included), the 12/24 slots planned as
 * anything but 「平安夜约会」 when the player accepted an invitation, ranks that skip, regress or exceed MAX, predicted ranks or cash that
 * disagree with the recount, and a summary whose end ranks disagree. Warnings: a FREE slot where a
 * productive action was verifiably legal. Messages are developer-facing English.
 */
final class PlanValidator {
    private PlanValidator() {}

    /** Errors after the first ones usually cascade from them; keep the report readable. */
    private static final int MAX_MESSAGES = 40;
    /** Assumption id of a Tartarus step that rescues a missing person (written out, not shared with Kernel). */
    static final String RESCUE_ASSUMPTION = "rescue_on_visit";

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
        /** Independent replay of forecast rescue groups, separate from Social Link observations. */
        long rescuedGroups;
        final int[] visits;
        final int[] shifts;
        final int[] used;
        /** Independent remaining calendar counts for choosing a finishable unlock project. */
        final int[][] dormRemaining;
        /** Actual future dated free training in this plan, counted once per evening. */
        int[][] plannedFreeStat;
        /** Link Episodes watched per track. */
        final int[] episodes;
        int notesUsed;
        /** Day of the last unlock fee paid in the plan (-1 none): optional purchases keep the reserve that day. */
        int feeDay = -1;

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
            dormRemaining = new int[nActs][];
            for (Rules.Activity activity : rules.activities) {
                if (!activity.dormActivity()) continue;
                int[] remaining = dormRemaining[activity.idx] = new int[rules.lastSlot() + 2];
                for (int at = rules.lastSlot(); at >= rules.firstSlot(); at--) {
                    remaining[at] = remaining[at + 1];
                    if (profile.lockOf(rules, at) == null && activityOnCalendar(activity, at)) remaining[at]++;
                }
            }
            episodes = new int[rules.episodes == null ? 0 : rules.episodes.length];
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
            copy(o.episodes, episodes);
            notesUsed = Math.max(0, o.notesUsed);
            rescued = o.rescued;
            failed = o.rescueFailed;
            rescuedGroups = o.plannedRescueGroups;
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
            buildPlannedFreeStats(plan);
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

        private void buildPlannedFreeStats(Plan plan) {
            int end = rules.lastSlot() + 1;
            plannedFreeStat = new int[3][end + 1];
            for (Plan.Step step : plan.steps) {
                int at = step.slot;
                if (at < 0 || at >= end || (at & 1) == 0 || lockAt(at) != null) continue;
                Action action = Action.parse(rules, step.action);
                if (action == null || action.type != Action.Type.ACTIVITY) continue;
                Rules.Activity activity = rules.activities[action.target];
                if (activity.kind != Rules.ActivityKind.STAT || activity.cost != 0
                        || (activity.fixedDays == null && !activity.dormActivity())
                        || profile.excluded(activity.id) || !activityOnCalendar(activity, at)
                        || (activity.requiresPlantedCrop && !profile.planDormGardening)) continue;
                for (int s = 0; s < Math.min(3, activity.gain.length); s++) {
                    plannedFreeStat[s][at] = Math.max(plannedFreeStat[s][at], activity.gain[s]);
                }
            }
            for (int at = end - 1; at >= 0; at--) {
                for (int s = 0; s < 3; s++) {
                    plannedFreeStat[s][at] += plannedFreeStat[s][at + 1];
                }
            }
        }

        /**
         * Lock of {@code s} under this profile, derived here on its own: the pack's lock, else
         * "christmas_date" on both slots of the day whose month-day is 12-24 when the player
         * accepted a Christmas Eve invitation.
         */
        private String lockAt(int s) {
            String lock = rules.lockOf(s);
            if (lock != null || profile.christmasLink == null || profile.christmasLink.trim().isEmpty()) return lock;
            Rules.Day d = rules.day(s >> 1);
            return d != null && "12-24".equals(d.md) ? "christmas_date" : null;
        }

        /** Tartarus cap of block {@code b}: its own entry in the profile, else the shared cap. */
        private int capOf(int b) {
            Integer own = profile.towerCapByBlock.get(rules.blocks[b].id);
            return Math.max(0, own != null ? own : profile.towerCapPerBlock);
        }

        private void replayStep(Plan.Step s) {
            String lock = lockAt(slot);
            if (lock != null) {
                if (!s.action.equals("locked:" + lock) || !"locked".equals(s.kind)) error(slot, "locked slot (" + lock + ") planned as " + s.action);
                if (!s.side.isEmpty()) error(slot, "side steps on a locked slot");
                Rules.Day day = rules.day(slot >> 1);
                for (int i = 0; i < Math.min(3, rules.stats.length); i++) {
                    int gain = (slot & 1) == 0 && day != null && i < day.automaticStatGain.length
                            ? Math.max(0, day.automaticStatGain[i]) : 0;
                    if (s.predicted.statDelta[i] != gain) error(slot, "predicted story stat gain "
                            + s.predicted.statDelta[i] + " but recount " + gain);
                    int after = 1;
                    for (int lv = 2; lv <= 6; lv++) {
                        if (stat[i] + gain >= rules.stats[i].thresholds[lv]) after = lv;
                    }
                    int changed = after != statLevel(i) ? after : -1;
                    if (s.predicted.statLvAfter[i] != changed) error(slot, "predicted story stat level "
                            + s.predicted.statLvAfter[i] + " but recount " + changed);
                }
                return;
            }
            Action a = Action.parse(rules, s.action);
            if (a == null) {
                error(slot, "unparseable action " + s.action);
                return;
            }
            long sideCost = side(s, a);
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
                case INVITE:
                    error(slot, "unconfirmed holiday invitation cannot be forecast");
                    break;
                case UNLOCK_STEP:
                    expectKind(s, "unlock");
                    unlock(s, a.target, a.step);
                    break;
                case ACTIVITY:
                    expectKind(s, rules.activities[a.target].dormActivity() ? "dorm"
                            : rules.activities[a.target].kind == Rules.ActivityKind.JOB ? "job" : "stat");
                    activity(a.target);
                    break;
                case TARTARUS:
                    expectKind(s, "tower");
                    tower(s, a.target, sideCost);
                    break;
                case NOTE:
                    expectKind(s, "note");
                    note(s, a.target);
                    break;
                case EPISODE:
                    expectKind(s, "episode");
                    episode(a.target);
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
            if (link.kind != Rules.LinkKind.MANUAL) error(slot, "meeting a story link " + link.id);
            if (!ordinaryCell) error(slot, link.id + " is not available in this slot");
            if (unavailable(l)) error(slot, link.id + " is missing or lost");
            int r = rank[l];
            if (r < 1 || r >= 10) {
                error(slot, "meeting " + link.id + " at rank " + r);
                return;
            }
            double m = multiplier(l);
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
                if (st.cost > 0) feeDay = d;
                if (j == establish && rank[l] == 0) {
                    rank[l] = 1;
                    pts[l] = answerPoints(l, 1, multiplier(l));
                    ready[l] = false;
                }
            }
            step[l] = Math.max(step[l], lastIdx + 1);
            lastStep[l] = d;
            checkRanks(s, from, rank[l]);
        }

        /**
         * Side steps listed on the step: each must be the link's next unlock step, non-consuming
         * (and not the chain's last step), of a link the plan wants, other than the main unlock's
         * link, at most one per link, in the right slot kind on an allowed day, after the earliest
         * day and minGapDays, with every prerequisite met and the fee affordable. Returns the fees.
         */
        private long side(Plan.Step s, Action main) {
            if (s.side.isEmpty()) return 0;
            if (s.sideZh.size() != s.side.size()) error(slot, "side and sideZh differ in length");
            int d = day();
            boolean[] seen = new boolean[nLinks];
            long spent = 0;
            for (String key : s.side) {
                Action a = Action.parse(rules, key);
                if (a == null || a.type != Action.Type.UNLOCK_STEP || a.target < 0 || a.target >= nLinks) {
                    error(slot, "side step " + key + " is not an unlock step");
                    continue;
                }
                int l = a.target;
                int k = a.step;
                Rules.Link link = rules.links[l];
                List<Rules.Step> steps = link.unlock.steps;
                if (seen[l]) error(slot, "two side steps of " + link.id + " in one slot");
                seen[l] = true;
                if (main.type == Action.Type.UNLOCK_STEP && main.target == l) error(slot, "side step of " + link.id + " beside its own unlock action");
                if (!isGoal(l) && !profile.otherLinks) error(slot, "side step for " + link.id + ", which the plan does not want");
                if (k != step[l] || k < 0 || k >= steps.size()) {
                    error(slot, "side step " + k + " of " + link.id + " out of order (next is " + step[l] + ")");
                    continue;
                }
                Rules.Step st = steps.get(k);
                if (st.consumesSlot || k == steps.size() - 1) error(slot, "side step " + k + " of " + link.id + " consumes a slot");
                if (link.kind != Rules.LinkKind.MANUAL || rank[l] != 0) error(slot, "side step for " + link.id + " at rank " + rank[l]);
                if (st.evening != evening()) error(slot, "side step " + k + " of " + link.id + " in the wrong slot kind");
                if (d < link.unlock.earliestDay) error(slot, "side step of " + link.id + " before its earliest day");
                if (!stepDayAllowed(link, st, d)) error(slot, "side step " + k + " of " + link.id + " not allowed on day " + d);
                if (unavailable(l)) error(slot, link.id + " is missing or lost");
                for (Map.Entry<Integer, Integer> e : link.unlock.statLv.entrySet()) {
                    if (statLevel(e.getKey()) < e.getValue()) error(slot, link.id + " side step needs stat " + e.getKey() + " Lv" + e.getValue());
                }
                for (Map.Entry<Integer, Integer> e : link.unlock.linkRank.entrySet()) {
                    if (rank[e.getKey()] < e.getValue()) error(slot, link.id + " side step needs link " + e.getKey() + " rank " + e.getValue());
                }
                if (lastStep[l] >= 0 && d - lastStep[l] < st.minGapDays) error(slot, "side step " + k + " of " + link.id + " too soon");
                int fee = Math.max(0, st.cost);
                if (cash < fee) error(slot, "side step " + k + " of " + link.id + " costs " + fee + " with cash " + cash);
                cash -= fee;
                spent += fee;
                if (fee > 0) feeDay = d;
                step[l] = k + 1;
                lastStep[l] = d;
                if (st.establishes && rank[l] == 0) {
                    rank[l] = 1;
                    pts[l] = answerPoints(l, 1, multiplier(l));
                    ready[l] = false;
                }
            }
            return spent;
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
            if (a.requiresPlantedCrop && !profile.planDormGardening) error(slot, "activity " + a.id + " needs planted-crop permission");
            if (a.dormActivity() && !dormTraitUseful(a)) {
                boolean useful = activityAffinityGain(a) > 0;
                for (int s = 0; s < Math.min(3, a.gain.length); s++) useful |= a.gain[s] > 0 && stat[s] < rules.stats[s].thresholds[6];
                if (!useful) error(slot, "dorm project " + a.id + " adds no useful stats, affinity or chosen characteristic progress");
            }
            if (a.once && used[idx] > 0) error(slot, "once-only activity " + a.id + " repeated");
            if (a.requiresActivity >= 0 && used[a.requiresActivity] <= 0) {
                error(slot, a.id + " needs prior activity " + rules.activities[a.requiresActivity].id);
            }
            for (Map.Entry<Integer, Integer> e : a.requiresLv.entrySet()) {
                if (statLevel(e.getKey()) < e.getValue()) error(slot, a.id + " needs stat " + e.getKey() + " Lv" + e.getValue());
            }
            int buffer = a.cost > 0 ? Math.max(0, profile.cashBuffer) : 0;
            if (a.cost > 0 && cash < a.cost + buffer) error(slot, a.id + " costs " + a.cost + " (+" + buffer + " buffer) with cash " + cash);
            cash -= Math.max(0, a.cost);
            if (a.cost > 0) checkFeeDayReserve(a.id);
            if (a.kind == Rules.ActivityKind.JOB) {
                int pay = 0;
                int shift = shifts[idx] + 1;
                for (int[] tier : a.pay) if (shift >= tier[0]) pay = tier[1];
                cash += pay;
                shifts[idx]++;
            }
            for (int s = 0; s < Math.min(3, a.gain.length); s++) stat[s] += a.gain[s];
            int affinity = activityAffinityGain(a);
            if (affinity > 0) pts[a.affinityLink] += affinity;
            used[idx]++;
        }

        /** Independent raw dorm affinity transition, without ordinary-visit multipliers. */
        private int activityAffinityGain(Rules.Activity activity) {
            int link = activity.affinityLink;
            if (activity.affinityPts <= 0 || link < 0 || link >= nLinks
                    || rank[link] < 1 || rank[link] >= 10 || ready[link]) return 0;
            int gap = rules.links[link].threshold[rank[link] + 1] - pts[link];
            return Math.min(activity.affinityPts, Math.max(0, gap));
        }

        /** Re-derive goal usefulness from stable teammate keys, independently of the kernel model. */
        private boolean dormTraitUseful(Rules.Activity activity) {
            String partnerId = activity.dormPartnerId();
            Profile.DormTarget target = profile.dormTarget(partnerId);
            if (partnerId == null || target == Profile.DormTarget.NONE || used[activity.idx] >= 3) return false;
            if (target == Profile.DormTarget.UNLOCK) {
                int bestProgress = -1;
                for (Rules.Activity track : rules.activities) {
                    if (!partnerId.equals(track.dormPartnerId())) continue;
                    if (used[track.idx] >= 3) return false;
                    int progress = Math.max(0, used[track.idx]);
                    if (!profile.excluded(track.id) && (!track.requiresPlantedCrop || profile.planDormGardening)
                            && dormRemaining[track.idx][slot] >= 3 - progress && progress > bestProgress) {
                        bestProgress = progress;
                    }
                }
                int progress = Math.max(0, used[activity.idx]);
                return progress == bestProgress && !profile.excluded(activity.id)
                        && (!activity.requiresPlantedCrop || profile.planDormGardening)
                        && dormRemaining[activity.idx][slot] >= 3 - progress;
            }
            return true;
        }

        /**
         * A paid optional purchase made on a day an unlock fee was paid leaves at least the fee
         * reserve (Kernel.FEE_RESERVE): the plan never spends a fee day's last yen on training or a note.
         */
        private void checkFeeDayReserve(String what) {
            if (feeDay == day() && cash < Kernel.FEE_RESERVE) {
                report.warnings.add("slot " + slot + ": " + what + " leaves " + cash + " on a day an unlock fee was paid");
            }
        }

        /**
         * Cash a note must leave for the FREE check, conservatively: the cash buffer, the fee
         * reserve on a fee day, and the next fee plus the reserve of every wanted link whose unlock
         * still has a paid step (the planner holds that much while the fee is due).
         */
        private long noteHold() {
            long hold = Math.max(0, profile.cashBuffer);
            if (feeDay == day()) hold = Math.max(hold, Kernel.FEE_RESERVE);
            long fees = 0;
            for (Rules.Link link : rules.links) {
                int l = link.idx;
                if (rank[l] != 0 || link.kind != Rules.LinkKind.MANUAL || (!isGoal(l) && !profile.otherLinks)) continue;
                List<Rules.Step> steps = link.unlock.steps;
                if (step[l] < 0 || step[l] >= steps.size()) continue;
                fees += Math.max(0, steps.get(step[l]).cost);
            }
            return fees > 0 ? Math.max(hold, fees + Kernel.FEE_RESERVE) : hold;
        }

        private boolean activityOnCalendar(Rules.Activity a) {
            return activityOnCalendar(a, slot);
        }

        private boolean activityOnCalendar(Rules.Activity a, int at) {
            Rules.Day d = rules.day(Rules.dayOf(at));
            if (d == null) return false;
            if (Rules.isEvening(at) ? !a.eve : !a.day) return false;
            if (d.d < a.fromDay || d.d > a.toDay || !a.allowsWeekday(d.wd)) return false;
            if (a.fixedDays != null && java.util.Arrays.binarySearch(a.fixedDays, d.d) < 0) return false;
            return a.dayTypes.isEmpty() || a.dayTypes.contains(d.type);
        }

        private void tower(Plan.Step s, int b, long sideCost) {
            Rules.Day d = rules.day(day());
            if (!evening() || d == null || !d.tartarus || d.block != b) error(slot, "Tartarus block " + b + " not enterable in this slot");
            if (b < 0 || b >= nBlocks) return;
            if (visits[b] >= capOf(b)) error(slot, "Tartarus cap " + capOf(b) + " exceeded in block " + b);
            visits[b]++;
            for (int i = 0; i < rules.reminders.rescueGroups.size(); i++) {
                Rules.RescueGroup g = rules.reminders.rescueGroups.get(i);
                if (day() >= g.fromDay && day() <= g.toDay) rescuedGroups |= 1L << i;
            }
            // The step's cash delta also carries the side steps' fees, which were paid already.
            long income = s.predicted.cashDelta + sideCost;
            if (income < 0) error(slot, "Tartarus visit loses money in the forecast");
            cash += Math.max(0, income);
            // A visit rescues everyone still missing whose window [fromDay, toDay] holds this evening (the limit
            // evening included; from toDay + 1 they are lost). The step must claim exactly that.
            int dd = day();
            String saved = null;
            for (int i = 0; i < rules.rescues.length; i++) {
                long bit = 1L << i;
                Rules.Rescue r = rules.rescues[i];
                if (dd >= r.fromDay && dd <= r.toDay && ((rescued | failed) & bit) == 0) {
                    rescued |= bit;
                    if (saved == null) saved = r.id;
                }
            }
            boolean claimed = s.assumptions != null && s.assumptions.contains(RESCUE_ASSUMPTION);
            if (claimed && saved == null) error(slot, "Tartarus visit claims a rescue outside every open rescue window");
            if (!claimed && saved != null) error(slot, "Tartarus visit rescues " + saved + " but does not claim it");
        }

        /**
         * Dating-site note: an open evening from the note's first day, notes allowed by the player,
         * the price (+ cash buffer) affordable, the use limit, and an established manual link at
         * RANK 1–9 that is not missing or lost. Adds the note's points (no multiplier; capped at the
         * threshold during a hold) and never changes the rank.
         */
        private void note(Plan.Step s, int l) {
            Rules.DatingNote note = rules.datingNote;
            Rules.Link link = rules.links[l];
            int r = rank[l];
            if (note == null) {
                error(slot, "dating-site note without a note in the pack");
                checkRanks(s, r, r);
                return;
            }
            if (!evening()) error(slot, "dating-site note in the daytime");
            if (!profile.datingNotes) error(slot, "dating-site note while the player turned notes off");
            if (day() < note.fromDay) error(slot, "dating-site note before it is sold");
            if (notesUsed >= Math.max(1, note.maxUses)) error(slot, "dating-site note use limit " + note.maxUses + " reached");
            for (Map.Entry<Integer, Integer> e : note.requiresLv.entrySet()) {
                if (statLevel(e.getKey()) < e.getValue()) error(slot, "dating-site note needs stat " + e.getKey() + " Lv" + e.getValue());
            }
            if (link.kind != Rules.LinkKind.MANUAL || r < 1 || r >= 10) error(slot, "dating-site note for " + link.id + " at rank " + r);
            if (unavailable(l)) error(slot, link.id + " is missing or lost");
            int cost = Math.max(0, note.cost);
            int buffer = cost > 0 ? Math.max(0, profile.cashBuffer) : 0;
            if (cash < cost + buffer) error(slot, "dating-site note costs " + cost + " (+" + buffer + " buffer) with cash " + cash);
            cash -= cost;
            if (cost > 0) checkFeeDayReserve("dating-site note");
            notesUsed++;
            if (r >= 1 && r < 10) {
                int thr = link.threshold[r + 1];
                int need = thr + (isGoal(l) && profile.goalBuffer && thr > 0 ? ordinaryGain() : 0);
                int next = pts[l] + Math.max(0, note.gain);
                pts[l] = heldNow(l, r + 1) ? Math.min(next, Math.max(pts[l], need)) : next;
            }
            checkRanks(s, r, rank[l]);
        }

        /**
         * Link Episode: an opted-in track, its slot kind, and a day whose cell allows the next
         * episode; adds the episode's stat points.
         */
        private void episode(int e) {
            if (e < 0 || e >= episodes.length) {
                error(slot, "unknown episode track " + e);
                return;
            }
            Rules.Episode ep = rules.episodes[e];
            int next = episodes[e] + 1;
            if (!profile.wantsEpisodes(ep.id)) error(slot, "episode of track " + ep.id + ", which the player did not choose");
            if (ep.evening != evening()) error(slot, "episode of " + ep.id + " in the wrong slot kind");
            if (next > ep.count) error(slot, "episode " + next + " of " + ep.id + " beyond its " + ep.count + " episodes");
            if (!episodeAllowed(ep, day(), next)) error(slot, "episode " + next + " of " + ep.id + " not available on day " + day());
            episodes[e]++;
            if (next >= 1 && next <= ep.gain.length && ep.gain[next - 1] != null) {
                for (int s = 0; s < Math.min(3, ep.gain[next - 1].length); s++) stat[s] += Math.max(0, ep.gain[next - 1][s]);
            }
        }

        private static boolean episodeAllowed(Rules.Episode ep, int d, int n) {
            return n >= 1 && n <= 31 && ep.dayMask != null && d >= 0 && d < ep.dayMask.length && (ep.dayMask[d] & (1 << (n - 1))) != 0;
        }

        // ------------------------------------------------------------------ model pieces

        private boolean productiveLegal() {
            int d = day();
            for (Rules.Link link : rules.links) {
                int l = link.idx;
                if (link.kind != Rules.LinkKind.MANUAL || rank[l] < 1 || rank[l] >= 10 || unavailable(l)) continue;
                boolean cell = link.avail.get(d) && link.evening == evening();
                if (cell) return true;
            }
            for (Rules.Activity a : rules.activities) {
                if (a.cost != 0 || profile.excluded(a.id) || (a.requiresPlantedCrop && !profile.planDormGardening)
                        || (a.once && used[a.idx] > 0) || !activityOnCalendar(a)) continue;
                if (a.requiresActivity >= 0 && used[a.requiresActivity] <= 0) continue;
                boolean reqs = true;
                for (Map.Entry<Integer, Integer> e : a.requiresLv.entrySet()) reqs &= statLevel(e.getKey()) >= e.getValue();
                if (!reqs) continue;
                // Work is always a manual option; a paid shift is not automatically a useful
                // schedule when its stats are capped and no near-term expense needs the cash.
                if (a.kind == Rules.ActivityKind.JOB && cashNeededSoon()) return true;
                if (dormTraitUseful(a)) return true;
                if (dormAffinityUseful(a)) return true;
                for (int s = 0; s < Math.min(3, a.gain.length); s++) {
                    if (usefulStatTraining(a, s)) return true;
                }
            }
            for (int e = 0; e < episodes.length; e++) {
                Rules.Episode ep = rules.episodes[e];
                if (profile.wantsEpisodes(ep.id) && ep.evening == evening() && episodes[e] < ep.count
                        && episodeAllowed(ep, d, episodes[e] + 1)) return true;
            }
            if (noteUseful(d)) return true;
            Rules.Day day = rules.day(d);
            if (!evening() || day == null || !day.tartarus || day.block < 0 || day.block >= nBlocks) return false;
            // Waiting for people still to appear can be the productive use of this allowance.
            // This check is derived from the windows/calendar here, without the planner's helper.
            if (canWaitForBatch(day.block)) return false;
            // A visit that would use the cap a pending rescue later in this block needs is not counted.
            return visits[day.block] + 1 + pendingRescues(day.block) <= capOf(day.block);
        }

        /**
         * Ordinary study need not pre-empt dated free sessions already assigned by this plan.
         * Count actual actions, not every available option, and finish before the first wanted
         * stat gate opens. A dated activity's own reward remains useful on its available night.
         */
        private boolean usefulStatTraining(Rules.Activity activity, int statIndex) {
            int gap = rules.stats[statIndex].thresholds[6] - stat[statIndex];
            if (activity.gain[statIndex] <= 0 || gap <= 0) return false;
            if (!evening() || activity.fixedDays != null || activity.dormActivity() || plannedFreeStat == null) return true;
            int deadline = rules.lastSlot() + 1;
            for (Rules.Link link : rules.links) {
                int l = link.idx;
                if (link.kind != Rules.LinkKind.MANUAL || (!isGoal(l) && !profile.otherLinks)) continue;
                // MAX Charm can improve existing links immediately, independently of unlock gates.
                if ("charm".equals(rules.stats[statIndex].id) && profile.charmBonus && rank[l] >= 1 && rank[l] < 10) {
                    deadline = slot + 1;
                }
                Integer required = link.unlock.statLv.get(statIndex);
                if (rank[l] != 0 || required == null || statLevel(statIndex) >= required) continue;
                deadline = Math.min(deadline, Math.max(slot + 1, Rules.slot(link.unlock.earliestDay, false)));
            }
            int future = plannedFreeStat[statIndex][slot + 1] - plannedFreeStat[statIndex][deadline];
            return future < gap;
        }

        /** Conservative cash purpose for the FREE warning, independently of the planner's prices. */
        private boolean cashNeededSoon() {
            long need = Math.max(0, profile.cashBuffer);
            long fees = 0;
            int last = Math.min(rules.lastDay, day() + 21);
            for (Rules.Link link : rules.links) {
                int l = link.idx;
                if (rank[l] != 0 || link.kind != Rules.LinkKind.MANUAL || unavailable(l)
                        || (!isGoal(l) && !profile.otherLinks) || link.unlock.earliestDay > last) continue;
                List<Rules.Step> steps = link.unlock.steps;
                int next = step[l];
                if (next < 0 || next >= steps.size()) continue;
                Rules.Step first = steps.get(next);
                boolean soon = false;
                for (int d = Math.max(day(), link.unlock.earliestDay); d <= last; d++) {
                    if (lockAt(Rules.slot(d, first.evening)) == null && stepDayAllowed(link, first, d)) {
                        soon = true;
                        break;
                    }
                }
                if (!soon) continue;
                for (int k = next; k < steps.size(); k++) fees += Math.max(0, steps.get(k).cost);
            }
            if (fees > 0) need = Math.max(need, fees + Kernel.FEE_RESERVE);
            return cash < need;
        }

        /** Affinity is productive only for a wanted rank with a real future ordinary meeting. */
        private boolean dormAffinityUseful(Rules.Activity activity) {
            if (activityAffinityGain(activity) <= 0) return false;
            int link = activity.affinityLink;
            if (!isGoal(link) && !profile.otherLinks) return false;
            if (!profile.otherLinks && rank[link] >= Math.max(1, Math.min(10, profile.target[link]))) return false;
            if (heldInto[link] == rank[link] + 1) return false;
            Rules.Link social = rules.links[link];
            for (int d = social.avail.nextSetBit(day()); d >= 0 && d <= rules.lastDay; d = social.avail.nextSetBit(d + 1)) {
                int meeting = Rules.slot(d, social.evening);
                if (meeting <= slot || lockAt(meeting) != null || unavailableOn(link, d)) continue;
                boolean held = false;
                for (Rules.Hold hold : social.holds) {
                    if (hold.rank == rank[link] + 1 && (hold.untilDay < 0 || d < hold.untilDay)) held = true;
                }
                if (!held) return true;
            }
            return false;
        }

        /** A dating-site note is affordable this evening and some established link the plan wants still needs points. */
        private boolean noteUseful(int d) {
            Rules.DatingNote note = rules.datingNote;
            if (note == null || note.gain <= 0 || !evening() || !profile.datingNotes || d < note.fromDay) return false;
            int cost = Math.max(0, note.cost);
            if (notesUsed >= Math.max(1, note.maxUses) || (cost > 0 && cash - cost < noteHold())) return false;
            for (Map.Entry<Integer, Integer> e : note.requiresLv.entrySet()) {
                if (statLevel(e.getKey()) < e.getValue()) return false;
            }
            for (Rules.Link link : rules.links) {
                int l = link.idx;
                int r = rank[l];
                if (link.kind != Rules.LinkKind.MANUAL || r < 1 || r >= 10 || ready[l] || unavailable(l)) continue;
                if (!isGoal(l) && !profile.otherLinks) continue;
                if (!profile.otherLinks && r >= Math.max(1, Math.min(10, profile.target[l]))) continue;
                int thr = link.threshold[r + 1];
                int need = thr + (isGoal(l) && profile.goalBuffer && thr > 0 ? ordinaryGain() : 0);
                if (thr > 0 && pts[l] < need) return true;
            }
            return false;
        }

        private boolean canWaitForBatch(int block) {
            if (visits[block] >= capOf(block)) return false;
            List<Rules.RescueGroup> groups = rules.reminders.rescueGroups;
            for (int i = 0; i < groups.size(); i++) {
                Rules.RescueGroup future = groups.get(i);
                if ((rescuedGroups & (1L << i)) != 0 || future.fromDay <= day()) continue;
                int last = Math.min(rules.lastSlot(), Rules.slot(future.toDay - 1, true));
                for (int night = Rules.slot(future.fromDay, true); night <= last; night += 2) {
                    Rules.Day date = rules.day(Rules.dayOf(night));
                    if (date == null || date.block != block || !date.tartarus || lockAt(night) != null) continue;
                    boolean shared = true;
                    for (int j = 0; j < groups.size(); j++) {
                        Rules.RescueGroup current = groups.get(j);
                        if ((rescuedGroups & (1L << j)) != 0 || current.fromDay > day() || current.toDay < day()) continue;
                        if (Rules.dayOf(night) >= current.toDay) { shared = false; break; }
                    }
                    if (shared) return true;
                }
            }
            return false;
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
                            && lockAt(s) == null) {
                        pending++;
                        break;
                    }
                }
            }
            return pending;
        }

        private void advance() {
            if ((slot & 1) == 0) {
                Rules.Day completedDay = rules.day(slot >> 1);
                if (completedDay != null) {
                    for (int i = 0; i < Math.min(stat.length, completedDay.automaticStatGain.length); i++) {
                        stat[i] += Math.max(0, completedDay.automaticStatGain[i]);
                    }
                }
            }
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
                    pts[l] = answerPoints(l, 1, multiplier(l));
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

        /** Affinity multiplier of link l: the Persona the player carries for it (Profile.personaFor) × Charm Lv6. */
        private double multiplier(int l) {
            double m = profile.personaFor(l) ? Math.max(1.0, rules.policy.personaMult) : 1.0;
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
