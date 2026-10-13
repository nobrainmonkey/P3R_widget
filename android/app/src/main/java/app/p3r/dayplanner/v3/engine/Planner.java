package app.p3r.dayplanner.v3.engine;

import app.p3r.dayplanner.v3.model.Profile;
import app.p3r.dayplanner.v3.model.Rules;

import java.util.Arrays;

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
 */
final class Planner {
    private Planner() {}

    private static final int POLICY = -1;
    private static final double EPS = 1e-6;

    static Plan plan(Rules rules, Profile profile, SimState observed, Plan previous, Engine.Options options) {
        long t0 = System.nanoTime();
        Model m = new Model(rules, profile, options);
        SimState start = Kernel.normalize(m, observed);
        Heuristics h = new Heuristics(m, start, previous);
        Search search = new Search(m, h, options, t0);
        int[] codes = search.run(start);
        Plan plan = assemble(m, h, start, codes);
        plan.budgetExhausted = search.exhausted;
        plan.elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;
        plan.createdMillis = System.currentTimeMillis();
        plan.planId = PlanHash.of(rules, profile, start, options, plan);
        if (options.progress != null) options.progress.onProgress(1f);
        return plan;
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

        Search(Model m, Heuristics h, Engine.Options options, long t0) {
            this.m = m;
            this.h = h;
            this.options = options;
            long budget = Math.max(0, options.timeBudgetMillis);
            this.deadline = t0 + budget * 1_000_000L;
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
                        code = buf[baseIdx];
                        if (improve && n >= 2) {
                            for (int c : diverse(n, baseIdx)) {
                                if (System.nanoTime() > deadline || cancelled()) {
                                    improve = false;
                                    exhausted = true;
                                    break;
                                }
                                double j = rollout(st, c, reached);
                                if (j > incumbent + EPS) {
                                    incumbent = j;
                                    code = c;
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
                        options.progress.onProgress(Math.min(0.99f, f));
                    }
                }
            }
            return chosen;
        }

        private boolean cancelled() {
            return options.cancelled != null && options.cancelled.get();
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
         * Up to {@code candidates} diverse options (best meeting, second meeting, best stat/job,
         * Tartarus), ordered by H, without the base choice itself.
         */
        private int[] diverse(int n, int baseIdx) {
            int social1 = -1;
            int social2 = -1;
            int act = -1;
            int tower = -1;
            for (int i = 0; i < n; i++) {
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
            int[] reps = {social1, social2, act, tower};
            Integer[] order = new Integer[4];
            int k = 0;
            for (int r : reps) if (r >= 0) order[k++] = r;
            Integer[] present = Arrays.copyOf(order, k);
            Arrays.sort(present, (a, b) -> scores[a] != scores[b] ? Double.compare(scores[b], scores[a]) : Integer.compare(a, b));
            int limit = Math.min(present.length, candidates);
            int[] out = new int[limit];
            int c = 0;
            for (int i = 0; i < limit; i++) {
                if (present[i] != baseIdx) out[c++] = buf[present[i]];
            }
            return Arrays.copyOf(out, c);
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
            Plan.Step step = new Plan.Step();
            step.slot = slot;
            if (!t.open(slot)) {
                String reason = t.lock[slot] != null ? t.lock[slot] : "out_of_range";
                step.action = Action.locked(reason).key(rules);
                labels.describeLocked(step, reason);
                step.predicted.cashAfter = st.cash;
                Kernel.apply(m, st, Kernel.FREE, null);
                sum.lockedSlots++;
            } else {
                SimState pre = st.copy();
                Kernel.effect(m, st, code, fx);
                Action action = Kernel.decode(code);
                step.action = action.key(rules);
                labels.describe(step, pre, code, fx);
                if (code != Kernel.FREE) {
                    step.predicted = Simulator.predicted(fx);
                    step.estimate = fx.estimate;
                    fx.addAssumptionIds(step.assumptions);
                    sum.usefulActions++;
                    sum.lastUsefulSlot = slot;
                } else {
                    step.predicted.cashAfter = st.cash;
                    sum.freeSlots++;
                }
                Kernel.advance(m, st);
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
