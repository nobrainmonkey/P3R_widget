package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Full-horizon planner (docs/DESIGN_v3.md §5.3–5.4): a greedy base policy over {@link Heuristics}
 * improved by a fortified rollout.
 *
 * <p>Walking the slots in order, the planner keeps an incumbent trajectory whose objective J is
 * known. At each open slot it takes up to {@code options.candidates} diverse candidates (best
 * meeting, second meeting, best stat/job, Tartarus), rolls each one out with the base policy to the
 * last slot and switches only when a candidate strictly beats the incumbent, so the result is never
 * worse than the base policy. Because every incumbent is "one deliberate choice, then the base
 * policy", its tail is reproduced by simply running the base policy, and no trajectory needs to be
 * stored. When the time budget runs out or the caller cancels, the remaining slots follow the
 * incumbent; the plan always covers every slot up to {@link Rules#lastSlot()}.
 *
 * <p>Stability: for the first three days the previous plan's action (when still legal) becomes the
 * incumbent unless it is worse by more than {@link #STABILITY_MARGIN}, and a challenger must beat
 * it by that margin; the base policy also gives it a 2% bonus.
 *
 * <p>Portfolio: the whole search runs for up to three base-policy tunings ({@link #PORTFOLIO})
 * while the budget allows, and the plan with the best objective is kept (preferring, within
 * {@link #SWITCH_MARGIN}, one that keeps the previous plan's next three days).
 */
final class Planner {
    private Planner() {}

    private static final int POLICY = -1;
    private static final double EPS = 1e-6;
    /**
     * Objective difference (about 1/6 of a RANK of a non-goal link) below which a replan keeps the
     * previous plan's choice for the next three days, so a result that matched the forecast does not
     * reshuffle the near schedule.
     */
    static final double STABILITY_MARGIN = 50;
    /**
     * A tuning that changes the next three days of the previous plan is adopted only when it beats
     * the best tuning that keeps them by more than one extra MAX of a non-goal link (smaller gains
     * are picked up by later replans, once those days are more than three days away).
     */
    static final double SWITCH_MARGIN = Heuristics.MAX_BONUS_RANKS * Heuristics.W_OTHER;

    /**
     * Base-policy tunings tried in order while the budget allows; the plan with the best objective
     * wins (ties keep the earlier tuning), so the result is never worse than the default tuning.
     */
    static final Heuristics.Tuning[] PORTFOLIO = {
            Heuristics.Tuning.DEFAULT,
            new Heuristics.Tuning(1.6, 2.0, 0.3),
            new Heuristics.Tuning(1.0, 1.0, 0.5)};

    static Plan plan(Rules rules, Profile profile, SimState observed, Plan previous, Engine.Options options) {
        long t0 = System.nanoTime();
        Model m = new Model(rules, profile, options);
        SimState start = Kernel.normalize(m, observed);
        long deadline = t0 + Math.max(0, options.timeBudgetMillis) * 1_000_000L;
        Candidate best = null;
        Candidate bestStable = null;
        boolean exhausted = false;
        int variants = options.timeBudgetMillis > 0 ? PORTFOLIO.length : 1;
        for (int v = 0; v < variants; v++) {
            long now = System.nanoTime();
            if (v > 0 && (cancelled(options) || now + (now - t0) / v > deadline)) {
                exhausted = true; // not enough time left for another full tuning
                break;
            }
            Heuristics h = new Heuristics(m, start, previous, PORTFOLIO[v]);
            Search search = new Search(m, h, options, deadline, v / (float) variants, 1f / variants);
            int[] codes = search.run(start);
            exhausted |= search.exhausted;
            Candidate c = new Candidate(h, codes, search.objectiveOf(start, codes));
            if (best == null || c.j > best.j + EPS) best = c;
            if (keepsPreviousWindow(h, start.slot, codes) && (bestStable == null || c.j > bestStable.j + EPS)) bestStable = c;
        }
        Candidate chosen = bestStable != null && best.j <= bestStable.j + SWITCH_MARGIN ? bestStable : best;
        Plan plan = assemble(m, chosen.h, start, chosen.codes);
        plan.budgetExhausted = exhausted;
        plan.elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;
        plan.createdMillis = System.currentTimeMillis();
        plan.planId = PlanHash.of(rules, profile, start, options, plan);
        if (options.progress != null) options.progress.onProgress(1f);
        return plan;
    }

    /** One tuning's complete result. */
    private static final class Candidate {
        final Heuristics h;
        final int[] codes;
        final double j;

        Candidate(Heuristics h, int[] codes, double j) {
            this.h = h;
            this.codes = codes;
            this.j = j;
        }
    }

    /** True when the plan repeats every productive action the previous plan had for the next three days. */
    private static boolean keepsPreviousWindow(Heuristics h, int from, int[] codes) {
        for (int i = 0; i < codes.length; i++) {
            int previous = h.previousCode(from + i);
            if (previous >= 0 && codes[i] != previous) return false;
        }
        return true;
    }

    private static boolean cancelled(Engine.Options options) {
        return options.cancelled != null && options.cancelled.get();
    }

    /** The search loop; single use. */
    static final class Search {
        final Model m;
        final Heuristics h;
        final Engine.Options options;
        final long deadline;
        final int candidates;
        boolean exhausted;
        long rollouts;
        long rolloutSteps;
        private final int[] buf;
        private final int[] rolloutBuf;
        private final double[] scores;
        private int[] reachedScratch;

        /** Progress reported for this search maps to [progressFrom, progressFrom + progressSpan]. */
        private final float progressFrom;
        private final float progressSpan;

        Search(Model m, Heuristics h, Engine.Options options, long deadline, float progressFrom, float progressSpan) {
            this.m = m;
            this.h = h;
            this.options = options;
            this.deadline = deadline;
            this.progressFrom = progressFrom;
            this.progressSpan = progressSpan;
            this.candidates = Math.max(1, options.candidates);
            this.buf = new int[Kernel.maxLegal(m)];
            this.rolloutBuf = new int[Kernel.maxLegal(m)];
            this.scores = new double[buf.length];
        }

        /** Chosen action code per slot from {@code start.slot} to the last slot. */
        int[] run(SimState start) {
            Tables t = m.t;
            int from = start.slot;
            int to = t.lastSlot;
            int len = Math.max(0, to - from + 1);
            int[] chosen = new int[len];
            SimState st = start.copy();
            int[] reached = h.initialReached(start);
            reachedScratch = new int[reached.length];
            boolean improve = options.timeBudgetMillis > 0 && candidates >= 2;
            double incumbent = improve ? rollout(st, POLICY, reached) : 0;
            float lastReported = 0;
            for (int slot = from; slot <= to; slot++) {
                if (improve && (cancelled() || System.nanoTime() > deadline)) {
                    improve = false;
                    exhausted = true;
                }
                int code = Kernel.FREE;
                if (t.open(slot)) {
                    int n = Kernel.legal(m, st, buf);
                    if (n > 0) {
                        int baseIdx = scoreAll(st, n);
                        code = Heuristics.vetoed(scores[baseIdx]) ? Kernel.FREE : buf[baseIdx];
                        double margin = EPS;
                        int previous = h.previousCode(slot);
                        if (improve && previous >= 0 && previous != code && isLegal(previous, n)) {
                            // Stability: keep what the previous plan said for the next days unless clearly worse.
                            double j = rollout(st, previous, reached);
                            if (j >= incumbent - STABILITY_MARGIN) {
                                incumbent = j;
                                code = previous;
                            }
                        }
                        if (code == previous) margin = STABILITY_MARGIN;
                        if (improve && n >= 2) {
                            for (int c : diverse(n, baseIdx)) {
                                if (c == code) continue;
                                if (System.nanoTime() > deadline || cancelled()) {
                                    improve = false;
                                    exhausted = true;
                                    break;
                                }
                                double j = rollout(st, c, reached);
                                if (j > incumbent + margin) {
                                    incumbent = j;
                                    code = c;
                                    margin = EPS;
                                }
                            }
                        }
                    }
                }
                chosen[slot - from] = code;
                Kernel.apply(m, st, code, null);
                h.track(st, slot, reached);
                if (options.progress != null && len > 0) {
                    float f = (slot - from + 1) / (float) len;
                    if (f - lastReported >= 0.02f) {
                        lastReported = f;
                        options.progress.onProgress(Math.min(0.99f, progressFrom + progressSpan * f));
                    }
                }
            }
            return chosen;
        }

        private boolean isLegal(int code, int n) {
            for (int i = 0; i < n; i++) if (buf[i] == code && !Heuristics.vetoed(scores[i])) return true;
            return false;
        }

        private boolean cancelled() {
            return Planner.cancelled(options);
        }

        /** Objective J of following {@code codes} from {@code start}. */
        double objectiveOf(SimState start, int[] codes) {
            SimState st = start.copy();
            int[] reached = h.initialReached(start);
            for (int code : codes) {
                int slot = st.slot;
                Kernel.apply(m, st, code, null);
                h.track(st, slot, reached);
            }
            return h.objective(st, reached);
        }

        /** Scores every legal code into {@link #scores}; returns the index of the base policy's choice. */
        private int scoreAll(SimState st, int n) {
            h.prepare(st);
            int best = 0;
            for (int i = 0; i < n; i++) {
                scores[i] = h.score(st, buf[i]);
                if (scores[i] > scores[best]) best = i;
            }
            return best;
        }

        /**
         * Up to {@code candidates} options to roll out, the base choice included in the count but not
         * returned: first one per category (best meeting, second meeting, best stat/job, Tartarus),
         * ordered by H; when K is larger than that, the next best remaining actions by H.
         */
        private int[] diverse(int n, int baseIdx) {
            int social1 = -1;
            int social2 = -1;
            int act = -1;
            int tower = -1;
            for (int i = 0; i < n; i++) {
                if (Heuristics.vetoed(scores[i])) continue;
                int type = Kernel.type(buf[i]);
                if (type == Kernel.T_MEET || type == Kernel.T_UNLOCK) {
                    if (social1 < 0 || scores[i] > scores[social1]) {
                        social2 = social1;
                        social1 = i;
                    } else if (social2 < 0 || scores[i] > scores[social2]) {
                        social2 = i;
                    }
                } else if (type == Kernel.T_ACT) {
                    if (act < 0 || scores[i] > scores[act]) act = i;
                } else if (type == Kernel.T_TOWER) {
                    if (tower < 0 || scores[i] > scores[tower]) tower = i;
                }
            }
            List<Integer> picked = new ArrayList<>(4);
            for (int r : new int[]{social1, social2, act, tower}) if (r >= 0) picked.add(r);
            picked.sort(this::byScore);
            if (candidates > picked.size()) {
                List<Integer> rest = new ArrayList<>();
                for (int i = 0; i < n; i++) if (!picked.contains(i) && !Heuristics.vetoed(scores[i])) rest.add(i);
                rest.sort(this::byScore);
                for (int i = 0; i < rest.size() && picked.size() < candidates; i++) picked.add(rest.get(i));
            }
            int limit = Math.min(picked.size(), candidates);
            boolean baseVetoed = Heuristics.vetoed(scores[baseIdx]);
            int[] out = new int[limit];
            int c = 0;
            for (int i = 0; i < limit; i++) {
                int idx = picked.get(i);
                if (idx != baseIdx || baseVetoed) out[c++] = buf[idx];
            }
            return Arrays.copyOf(out, c);
        }

        /** Higher H first, then the kernel's deterministic order. */
        private int byScore(int a, int b) {
            return scores[a] != scores[b] ? Double.compare(scores[b], scores[a]) : Integer.compare(a, b);
        }

        /** Objective of: {@code first} now (or the base policy when POLICY), then the base policy to the end. */
        double rollout(SimState from, int first, int[] reached) {
            rollouts++;
            SimState st = from.copy();
            int[] r = reachedScratch;
            System.arraycopy(reached, 0, r, 0, r.length);
            Tables t = m.t;
            int last = t.lastSlot;
            if (first != POLICY && st.slot <= last) {
                int slot = st.slot;
                Kernel.apply(m, st, first, null);
                h.track(st, slot, r);
            }
            while (st.slot <= last) {
                int slot = st.slot;
                int code = Kernel.FREE;
                if (t.open(slot)) code = h.choose(st, rolloutBuf, Kernel.legal(m, st, rolloutBuf));
                Kernel.apply(m, st, code, null);
                h.track(st, slot, r);
                rolloutSteps++;
            }
            return h.objective(st, r);
        }
    }

    // ------------------------------------------------------------------ alternatives (改做别的)

    /**
     * Every legal productive action for {@code observed.slot} as a labeled step (best first by the
     * base policy), for the "改做别的" list. Steps carry the same fields as plan steps.
     */
    static List<Plan.Step> alternatives(Rules rules, Profile profile, SimState observed) {
        Model m = new Model(rules, profile, null);
        SimState st = Kernel.normalize(m, observed);
        Heuristics h = new Heuristics(m, st, null);
        Labels labels = new Labels(m, h);
        int[] codes = new int[Kernel.maxLegal(m)];
        int n = Kernel.legal(m, st, codes);
        double[] scores = new double[n];
        h.prepare(st);
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            scores[i] = h.score(st, codes[i]);
            order.add(i);
        }
        order.sort((a, b) -> scores[a] != scores[b] ? Double.compare(scores[b], scores[a]) : Integer.compare(a, b));
        List<Plan.Step> out = new ArrayList<>(n);
        Kernel.Effect fx = new Kernel.Effect();
        for (int i : order) out.add(describe(m, labels, st, codes[i], fx));
        return out;
    }

    /** A labeled step for {@code code} taken in {@code st} (which is not modified). */
    private static Plan.Step describe(Model m, Labels labels, SimState st, int code, Kernel.Effect fx) {
        Plan.Step step = new Plan.Step();
        step.slot = st.slot;
        SimState after = st.copy();
        Kernel.effect(m, after, code, fx);
        step.action = Kernel.decode(code).key(m.rules);
        labels.describe(step, st, code, fx);
        if (code != Kernel.FREE) {
            step.predicted = Simulator.predicted(fx);
            step.estimate = fx.estimate;
            fx.addAssumptionIds(step.assumptions);
        } else {
            step.predicted.cashAfter = st.cash;
        }
        return step;
    }

    // ------------------------------------------------------------------ plan assembly

    static Plan assemble(Model m, Heuristics h, SimState start, int[] codes) {
        Rules rules = m.rules;
        Tables t = m.t;
        Labels labels = new Labels(m, h);
        Plan plan = new Plan();
        plan.rulesVersion = rules.version;
        plan.fromSlot = start.slot;
        plan.toSlot = t.lastSlot;
        Plan.Summary sum = plan.summary;
        Arrays.fill(sum.maxSlot, -1);
        for (int l = 0; l < t.nLinks && l < sum.maxSlot.length; l++) {
            if (start.rank[l] >= h.target[l]) sum.maxSlot[l] = start.slot;
        }
        SimState st = start.copy();
        Kernel.Effect fx = new Kernel.Effect();
        for (int i = 0; i < codes.length; i++) {
            int slot = st.slot;
            int code = codes[i];
            Plan.Step step;
            if (!t.open(slot)) {
                step = new Plan.Step();
                step.slot = slot;
                String reason = t.lock[slot] != null ? t.lock[slot] : "out_of_range";
                step.action = Action.locked(reason).key(rules);
                labels.describeLocked(step, reason);
                step.predicted.cashAfter = st.cash;
                Kernel.apply(m, st, Kernel.FREE, null);
                sum.lockedSlots++;
            } else {
                step = describe(m, labels, st, code, fx);
                if (code != Kernel.FREE) {
                    sum.usefulActions++;
                    sum.lastUsefulSlot = slot;
                } else {
                    sum.freeSlots++;
                }
                Kernel.apply(m, st, code, null);
            }
            for (int l = 0; l < t.nLinks && l < sum.maxSlot.length; l++) {
                if (sum.maxSlot[l] < 0 && st.rank[l] >= h.target[l]) sum.maxSlot[l] = slot;
            }
            plan.steps.add(step);
        }
        for (int l = 0; l < t.nLinks && l < sum.endRanks.length; l++) sum.endRanks[l] = st.rank[l];
        sum.endCash = st.cash;
        for (int s = 0; s < Math.min(3, t.nStats); s++) sum.endStatLv[s] = Kernel.statLv(m, st, s);
        labels.notes(plan, start, st);
        return plan;
    }
}
