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
 * <p>Portfolio: every base-policy tuning of {@link #PORTFOLIO} is screened (its base policy alone,
 * about one rollout each); the best {@link #FORTIFY} get the full fortified search while the budget
 * allows, and the plan with the best objective is kept (preferring, within {@link #SWITCH_MARGIN},
 * one that keeps the previous plan's next three days).
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
     * Objective gain (one RANK of a non-goal link) a rollout must show before a daytime slot in
     * which a wanted link could be met is given to training, a job or nothing: such gains are made
     * of estimates (stat points, yen) while the meeting is certain progress. Plans of the portfolio
     * are compared with the same margin per wasted slot.
     */
    static final double WASTE_MARGIN = Heuristics.W_OTHER;
    /** For goals: the waste margin is this share of the goal's weight (2.5 slots earlier MAX). */
    static final double WASTE_GOAL_SHARE = 2.5 * Heuristics.EARLY_PER_SLOT;

    enum CandidateMode {
        NEW_DIVERSE,
        OLD_DIVERSE,
        NORMAL_TOP_K
    }

    static CandidateMode candidateMode = CandidateMode.NEW_DIVERSE;

    static boolean social(int code) {
        int type = Kernel.type(code);
        return type == Kernel.T_MEET || type == Kernel.T_UNLOCK;
    }

    /**
     * Base-policy tunings screened on every plan (the base policy alone costs about one rollout).
     * The first one is the default. They differ in how eagerly stats are trained, money is earned
     * and saved for fees, how early gated links must open, how much of the evenings training may
     * take and how much daytime demand is admitted (Heuristics.Tuning); which mix fits best depends
     * on the state (new game, persona bonus, a 「必须」 goal, a late start), so the planner tries them
     * all and fortifies the best few.
     */
    static final Heuristics.Tuning[] PORTFOLIO = {
            Heuristics.Tuning.DEFAULT,
            new Heuristics.Tuning(1.6, 2.0, 0.3, 4.0, 1.0, 4.0, 1.15),
            new Heuristics.Tuning(0.7, 2.0, 0.3, 1.5, 1.0, 4.0, 1.15),
            new Heuristics.Tuning(1.6, 2.0, 0.3, 1.5, 1.0, 1.0, 1.15),
            new Heuristics.Tuning(0.7, 2.0, 0.5, 4.0, 1.0, 4.0, 1.15),
            new Heuristics.Tuning(1.0, 2.0, 0.3, 1.5, 0.6, 1.0, 1.15),
            new Heuristics.Tuning(0.7, 1.0, 0.5, 1.5, 1.0, 4.0, 0.9),
            new Heuristics.Tuning(0.7, 2.0, 0.5, 4.0, 0.8, 4.0, 1.0),
            new Heuristics.Tuning(1.6, 2.0, 0.5, 1.5, 0.6, 1.0, 0.9),
            new Heuristics.Tuning(1.0, 2.0, 0.3, 2.5, 0.8, 4.0, 1.15),
            new Heuristics.Tuning(1.6, 2.0, 0.3, 4.0, 0.6, 4.0, 1.15),
            new Heuristics.Tuning(0.7, 1.0, 0.3, 4.0, 1.0, 1.0, 0.9),
            new Heuristics.Tuning(0.7, 2.0, 0.5, 1.5, 0.6, 4.0, 0.9),
            new Heuristics.Tuning(0.7, 2.0, 0.3, 4.0, 1.0, 1.0, 1.15),
            new Heuristics.Tuning(1.0, 1.0, 0.3, 2.5, 0.8, 1.0, 1.0),
            new Heuristics.Tuning(1.0, 1.0, 0.5, 4.0, 0.6, 4.0, 100)};

    /**
     * Extra tunings screened only when the profile has goals: goals in strict priority order
     * ({@link Heuristics.Tuning#goalFirst}). When goals compete for the same days the balanced
     * tunings may share the days out so that no goal makes it; these give every chance to the
     * first goal, then the next. The objective (goal weights spaced by priority) picks the winner.
     */
    static final Heuristics.Tuning[] GOAL_FIRST = {
            Heuristics.Tuning.DEFAULT.goalFirst(),
            new Heuristics.Tuning(1.6, 2.0, 0.3, 4.0, 1.0, 4.0, 1.15, true)};

    /** The tunings screened for this profile: {@link #PORTFOLIO}, plus {@link #GOAL_FIRST} when there are goals. */
    static Heuristics.Tuning[] portfolioFor(Profile profile) {
        if (!profile.hasAnyGoal()) return PORTFOLIO;
        Heuristics.Tuning[] all = Arrays.copyOf(PORTFOLIO, PORTFOLIO.length + GOAL_FIRST.length);
        System.arraycopy(GOAL_FIRST, 0, all, PORTFOLIO.length, GOAL_FIRST.length);
        return all;
    }

    /** Screened tunings whose fortified rollout runs (best screened objective first) while the budget allows. */
    static final int FORTIFY = 3;

    static Plan plan(Rules rules, Profile profile, SimState observed, Plan previous, Engine.Options options) {
        return plan(rules, profile, observed, previous, options, portfolioFor(profile));
    }

    /**
     * Screens every tuning of {@code portfolio} with its base policy, then fortifies the best
     * {@link #FORTIFY} (in screened order, while the budget allows). With two or more goals the
     * goals are then honoured in strict priority order ({@link Pool#prioritise}): when the best plan
     * misses a goal while a later one exists, the planner also plans with only the goals up to the
     * missed one as goals (the rest count as 「其余社群」). The plan kept is the best by
     * {@link Pool#compareGoals} (goals in priority order: reached beats missed, a missed goal's
     * higher end rank beats a lower one), then by objective (less the waste margin per wasted
     * daytime slot, see {@link Candidate}), preferring within {@link #SWITCH_MARGIN} one that keeps
     * the previous plan's next three days. With a zero time budget only the first tuning's base
     * policy runs.
     */
    static Plan plan(Rules rules, Profile profile, SimState observed, Plan previous, Engine.Options options,
                     Heuristics.Tuning[] portfolio) {
        long t0 = System.nanoTime();
        Model m = new Model(rules, profile, options);
        SimState start = Kernel.normalize(m, observed);
        long deadline = t0 + Math.max(0, options.timeBudgetMillis) * 1_000_000L;
        boolean search = options.timeBudgetMillis > 0;
        Pool pool = new Pool(m, start, previous, options, deadline, portfolio);
        boolean exhausted = pool.run(null, search, true);
        if (search) exhausted |= pool.prioritise();
        Candidate chosen = pool.choose();
        Plan plan = assemble(m, pool.labelHeuristics(chosen), start, chosen.codes, pool.tradedFor(chosen));
        plan.budgetExhausted = exhausted;
        plan.followUp = options.followUp;
        if (exhausted) plan.summary.notesZh.add(0, plan.budgetNoteZh());
        plan.elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;
        plan.createdMillis = System.currentTimeMillis();
        plan.planId = PlanHash.of(rules, profile, start, options, plan);
        if (options.progress != null) options.progress.onProgress(1f);
        return plan;
    }

    /**
     * The screened tunings to fortify, in order: the best {@link #FORTIFY} by screened objective,
     * with the {@link Heuristics.Tuning#goalFirst} ones that are not among them right after the
     * best (a goal-first base policy often screens below a balanced one that sacrifices the first
     * goal, while its fortified plan beats it; one goal-first tuning for a single goal, all of them
     * when goals can compete for the same days).
     */
    static Candidate[] fortifyOrder(Candidate[] order, Heuristics.Tuning[] portfolio, int goals) {
        List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < Math.min(FORTIFY, order.length); i++) out.add(order[i]);
        int extra = goals == 0 ? 0 : goals == 1 ? 1 : Integer.MAX_VALUE;
        int at = Math.min(1, out.size());
        for (Candidate c : order) {
            if (extra <= 0) break;
            if (!portfolio[c.variant].goalFirst) continue;
            extra--;
            if (out.contains(c)) continue;
            out.add(at++, c);
        }
        return out.toArray(new Candidate[0]);
    }

    /**
     * The screened tunings fortified in a strict-priority run ({@link Pool#prioritise}): the best
     * by that goal order's objective and the best {@link Heuristics.Tuning#goalFirst} one (two
     * fortified searches, so the extra runs stay within the time budget).
     */
    static Candidate[] priorityFortifyOrder(Candidate[] order, Heuristics.Tuning[] portfolio) {
        List<Candidate> out = new ArrayList<>();
        if (order.length > 0) out.add(order[0]);
        for (Candidate c : order) {
            if (!portfolio[c.variant].goalFirst) continue;
            if (!out.contains(c)) out.add(c);
            break;
        }
        return out.toArray(new Candidate[0]);
    }

    /** Share of the progress bar used by the screening phase. */
    private static final float SCREEN_SHARE = 0.1f;

    /**
     * One complete plan of the portfolio. Plans are compared by their goals first
     * ({@link Pool#compareGoals}), then by {@link #score}: the objective J of the whole profile minus
     * the waste margin for every daytime slot that skips a wanted link which could be met there, so
     * a plan that gives such a slot to training or a job wins only by a clear gain (the same rule
     * the rollout applies slot by slot, see {@link #WASTE_MARGIN}).
     */
    static final class Candidate {
        final Heuristics h;
        final Heuristics.Tuning tuning;
        /** Goal order the plan was made with (null: the profile's own). */
        final int[] goals;
        final int[] codes;
        /** Objective of the whole profile, and of the heuristics that made the plan (the same for the profile's own goal order). */
        final double j;
        final double ownJ;
        final double score;
        /** Index of the tuning in the portfolio (-1 for a screened "goal first" probe). */
        final int variant;
        /** Per goal, in the profile's priority order: reached its target, and its end rank. */
        final boolean[] reached;
        final int[] endRank;
        /** End rank of every link. */
        final int[] linkRanks;
        boolean exhausted;

        Candidate(Heuristics h, Heuristics.Tuning tuning, int[] goals, int[] codes, double j, double ownJ, double score, int variant,
                  boolean[] reached, int[] endRank, int[] linkRanks) {
            this.h = h;
            this.tuning = tuning;
            this.goals = goals;
            this.codes = codes;
            this.j = j;
            this.ownJ = ownJ;
            this.score = score;
            this.variant = variant;
            this.reached = reached;
            this.endRank = endRank;
            this.linkRanks = linkRanks;
        }
    }

    /**
     * Every complete plan of one planning run, all measured with the objective of the whole profile,
     * and the strict goal priority over them.
     */
    static final class Pool {
        final Model m;
        final SimState start;
        final Plan previous;
        final Engine.Options options;
        final long deadline;
        /** Base-policy tunings screened for every goal order. */
        final Heuristics.Tuning[] portfolio;
        /** The whole profile's goals in priority order. */
        final int[] goals;
        /** Objective of the whole profile (the same for every tuning). */
        final Heuristics ref;
        private final double margin;
        final List<Candidate> all = new ArrayList<>();
        private long fortifyNanos;
        private int fortified;

        Pool(Model m, SimState start, Plan previous, Engine.Options options, long deadline, Heuristics.Tuning[] portfolio) {
            this.m = m;
            this.start = start;
            this.previous = previous;
            this.options = options;
            this.deadline = deadline;
            this.portfolio = portfolio;
            this.goals = Heuristics.goalOrder(m);
            this.ref = new Heuristics(m, start, previous, portfolio[0]);
            this.margin = wasteMargin(ref);
        }

        /** Measures {@code codes}: objective of the whole profile, daytime waste, goal outcomes. */
        Candidate candidate(Heuristics h, Heuristics.Tuning tuning, int[] goalOrder, int[] codes, int variant) {
            SimState st = start.copy();
            int[] reachedAt = ref.initialReached(start);
            int waste = 0;
            for (int code : codes) {
                int slot = st.slot;
                boolean open = m.t.open(slot);
                int type = Kernel.type(code);
                if (open && (slot & 1) == 0 && type != Kernel.T_MEET && type != Kernel.T_UNLOCK && type != Kernel.T_EPISODE
                        && meetableWanted(m, st, slot)) {
                    waste++;
                }
                Kernel.apply(m, st, open ? code : Kernel.FREE, null);
                ref.track(st, slot, reachedAt);
            }
            double j = ref.objective(st, reachedAt) - Heuristics.FRAGILE_MAX_PENALTY * ref.fragileMaxes(st, reachedAt);
            boolean[] reached = new boolean[goals.length];
            int[] endRank = new int[goals.length];
            for (int i = 0; i < goals.length; i++) {
                endRank[i] = st.rank[goals[i]];
                reached[i] = endRank[i] >= m.target(goals[i]);
            }
            int[] linkRanks = Arrays.copyOf(st.rank, m.t.nLinks);
            if (options.trace != null) options.trace.add(linkRanks.clone());
            double own = goalOrder == null ? j : new Search(m, h, options, deadline, 0f, 0f).objectiveOf(start, codes);
            return new Candidate(h, tuning, goalOrder, codes, j, own, j - margin * waste, variant, reached, endRank, linkRanks);
        }

        /**
         * One portfolio run for {@code goalOrder} (null: the profile's own): every tuning's base
         * policy is screened (only the first one without {@code search}), the best are fortified
         * ({@link #fortifyOrder}, by the objective of that goal order) while the budget allows, and
         * every plan joins the pool. {@code main} reports progress. Returns true when the time
         * budget ran out before the run was complete.
         */
        boolean run(int[] goalOrder, boolean search, boolean main) {
            boolean exhausted = false;
            int variants = search ? portfolio.length : 1;
            Candidate[] screened = new Candidate[variants];
            for (int v = 0; v < variants; v++) {
                if (v > 0 && (cancelled(options) || System.nanoTime() > deadline)) {
                    exhausted = true;
                    break;
                }
                Heuristics h = new Heuristics(m, start, previous, portfolio[v], goalOrder);
                Search base = new Search(m, h, options, deadline, 0f, 0f);
                screened[v] = candidate(h, portfolio[v], goalOrder, base.runBase(start), v);
                if (main && options.progress != null) options.progress.onProgress(SCREEN_SHARE * (v + 1) / variants);
            }
            Candidate[] order = Arrays.stream(screened).filter(c -> c != null)
                    .sorted((a, b) -> a.ownJ != b.ownJ ? Double.compare(b.ownJ, a.ownJ) : Integer.compare(a.variant, b.variant))
                    .toArray(Candidate[]::new);
            for (Candidate c : order) add(c);
            if (!search) return exhausted;
            int goalCount = goalOrder == null ? goals.length : goalOrder.length;
            Candidate[] fortifyOrder = main ? fortifyOrder(order, portfolio, goalCount) : priorityFortifyOrder(order, portfolio);
            int fortify = fortifyOrder.length;
            for (int k = 0; k < fortify; k++) {
                if ((k > 0 || !main) && !timeForAnotherFortify()) {
                    exhausted = true; // not enough time left for another full rollout
                    break;
                }
                Candidate base = fortifyOrder[k];
                float from = main ? SCREEN_SHARE + (1 - SCREEN_SHARE) * k / fortify : 0.99f;
                float span = main ? (1 - SCREEN_SHARE) / fortify : 0f;
                Candidate c = fortify(portfolio[base.variant], goalOrder, base.variant, from, span);
                exhausted |= c.exhausted;
                add(c);
            }
            return exhausted;
        }

        void add(Candidate c) {
            all.add(c);
        }

        /** Fortified search of {@code tuning} with goal order {@code goalOrder} (null: the profile's). */
        Candidate fortify(Heuristics.Tuning tuning, int[] goalOrder, int variant, float progressFrom, float progressSpan) {
            long t0 = System.nanoTime();
            Heuristics h = new Heuristics(m, start, previous, tuning, goalOrder);
            Search search = new Search(m, h, options, deadline, progressFrom, progressSpan);
            int[] codes = search.run(start);
            Candidate c = candidate(h, tuning, goalOrder, codes, variant);
            c.exhausted = search.exhausted;
            fortifyNanos += System.nanoTime() - t0;
            fortified++;
            return c;
        }

        /** Another fortified search is expected to finish before the deadline (the average one so far). */
        boolean timeForAnotherFortify() {
            if (cancelled(options)) return false;
            long now = System.nanoTime();
            long average = fortified == 0 ? 0 : fortifyNanos / fortified;
            return now + average <= deadline;
        }

        /**
         * Strict goal priority. While the best plan misses a goal (its first missed goal, in
         * priority order) and a later goal exists, plan once more with only the goals up to the
         * missed one as goals ("top-k"; the later goals count as 「其余社群」), a whole portfolio run
         * ({@link #run}). Each k runs at most once.
         * Then, for every goal the best plan still misses, a base-policy plan with that goal first
         * is screened, so the notes can say which goal it gave way to. Returns true when the time
         * budget ran out before a needed run.
         */
        boolean prioritise() {
            int n = goals.length;
            if (n < 2) return false;
            boolean[] tried = new boolean[n + 1];
            while (true) {
                Candidate best = best();
                int f = firstMissed(best);
                if (f < 0 || f >= n - 1 || tried[f + 1]) break;
                tried[f + 1] = true;
                if (!timeForAnotherFortify()) return true;
                if (run(Arrays.copyOf(goals, f + 1), true, false)) return true;
            }
            Candidate best = best();
            for (int i = 1; i < n; i++) {
                if (best.reached[i] || anyReaches(i)) continue;
                if (cancelled(options) || System.nanoTime() > deadline) return true;
                int[] promoted = new int[n];
                promoted[0] = goals[i];
                for (int a = 0, b = 1; a < n; a++) if (a != i) promoted[b++] = goals[a];
                Heuristics.Tuning tuning = Heuristics.Tuning.DEFAULT.goalFirst();
                Heuristics h = new Heuristics(m, start, previous, tuning, promoted);
                add(candidate(h, tuning, promoted, new Search(m, h, options, deadline, 0f, 0f).runBase(start), -1));
            }
            return false;
        }

        private boolean anyReaches(int goal) {
            for (Candidate c : all) if (c.reached[goal]) return true;
            return false;
        }

        /** First goal (place in the priority order) the plan misses, or -1. */
        static int firstMissed(Candidate c) {
            for (int i = 0; i < c.reached.length; i++) if (!c.reached[i]) return i;
            return -1;
        }

        /**
         * Strict goal priority between two plans: the first goal (in priority order) on which they
         * differ decides; reaching the target beats missing it, and between two misses the higher
         * end rank wins. Positive when {@code a} is better, 0 when the goals tie.
         */
        static int compareGoals(Candidate a, Candidate b) {
            int d = decidingGoal(a, b);
            if (d < 0) return 0;
            if (a.reached[d] != b.reached[d]) return a.reached[d] ? 1 : -1;
            return Integer.compare(a.endRank[d], b.endRank[d]);
        }

        /** Place of the first goal on which {@code a} and {@code b} differ ({@link #compareGoals}), or -1. */
        static int decidingGoal(Candidate a, Candidate b) {
            for (int i = 0; i < a.reached.length; i++) {
                if (a.reached[i] != b.reached[i]) return i;
                if (!a.reached[i] && a.endRank[i] != b.endRank[i]) return i;
            }
            return -1;
        }

        private static boolean better(Candidate a, Candidate b) {
            int k = compareGoals(a, b);
            if (k != 0) return k > 0;
            return a.score > b.score + EPS;
        }

        /** The best plan so far by goals, then score (the first one on ties). */
        Candidate best() {
            Candidate best = null;
            for (Candidate c : all) if (best == null || better(c, best)) best = c;
            return best;
        }

        /**
         * The plan to keep: the best ({@link #best}), or, among the plans whose goals tie with it,
         * the best one that keeps the previous plan's next three days when it scores within
         * {@link #SWITCH_MARGIN}.
         */
        Candidate choose() {
            Candidate best = best();
            Candidate stable = null;
            for (Candidate c : all) {
                if (compareGoals(c, best) != 0 || !keepsPreviousWindow(ref, start.slot, c.codes)) continue;
                if (stable == null || c.score > stable.score + EPS) stable = c;
            }
            return stable != null && best.score <= stable.score + SWITCH_MARGIN ? stable : best;
        }

        /**
         * Heuristics that label the plan: the plan's own, with the whole profile's goals (a top-k
         * plan is labelled like any other plan of this profile).
         */
        Heuristics labelHeuristics(Candidate c) {
            return c.goals == null ? c.h : new Heuristics(m, start, previous, c.tuning);
        }

        /**
         * For every goal {@code chosen} misses, the goal it gave way to: among the plans of the pool
         * that reach that goal, the goal on which {@code chosen} beats them ({@link #decidingGoal}),
         * provided {@code chosen} reaches it; the latest such goal (the closest trade). -1 when no
         * plan of the pool reached the goal (nothing was traded for it) and for every other link.
         */
        int[] tradedFor(Candidate chosen) {
            int[] out = new int[m.t.nLinks];
            Arrays.fill(out, -1);
            for (int i = 0; i < goals.length; i++) {
                if (chosen.reached[i]) continue;
                int traded = -1;
                for (Candidate c : all) {
                    if (!c.reached[i]) continue;
                    int d = decidingGoal(chosen, c);
                    if (d >= 0 && d < i && chosen.reached[d]) traded = Math.max(traded, d);
                }
                out[goals[i]] = traded < 0 ? -1 : goals[traded];
            }
            return out;
        }
    }

    /** {@link #WASTE_MARGIN}, raised for goals whose earlier MAX is worth more per slot. */
    static double wasteMargin(Heuristics h) {
        double goalWeight = 0;
        for (double w : h.wGoal) goalWeight = Math.max(goalWeight, w);
        return Math.max(WASTE_MARGIN, WASTE_GOAL_SHARE * goalWeight);
    }

    /**
     * Daytime slots of {@code codes} that do something other than a meeting, an unlock step or an
     * opted-in Link Episode while a wanted, established, unfinished link could be met there.
     */
    static int daytimeWaste(Model m, SimState start, int[] codes) {
        SimState st = start.copy();
        int waste = 0;
        for (int code : codes) {
            int slot = st.slot;
            boolean open = m.t.open(slot);
            int type = Kernel.type(code);
            if (open && (slot & 1) == 0 && type != Kernel.T_MEET && type != Kernel.T_UNLOCK && type != Kernel.T_EPISODE
                    && meetableWanted(m, st, slot)) {
                waste++;
            }
            Kernel.apply(m, st, open ? code : Kernel.FREE, null);
        }
        return waste;
    }

    /** A wanted (goal, or any link with 其余社群), established, unfinished link can be met in this slot. */
    static boolean meetableWanted(Model m, SimState st, int slot) {
        int day = slot >> 1;
        int mask = m.t.meetMask[slot];
        for (int l = 0; mask != 0; l++, mask >>>= 1) {
            if ((mask & 1) == 0 || !(m.goal[l] || m.profile.otherLinks)) continue;
            int goalRank = m.goal[l] && !m.profile.otherLinks ? m.target(l) : 10;
            if (st.rank[l] >= goalRank) continue;
            if (Kernel.meetLegal(m, st, l, day)) return true;
        }
        return false;
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
        /** {@link #WASTE_MARGIN}, raised for goals whose earlier MAX is worth more per slot. */
        private final double wasteMargin;
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
            this.wasteMargin = Planner.wasteMargin(h);
            this.buf = new int[Kernel.maxLegal(m)];
            this.rolloutBuf = new int[Kernel.maxLegal(m)];
            this.scores = new double[buf.length];
        }

        /** Sizes the scratch used by {@link #rollout} for a {@code reached} array of that length (done by {@link #run}). */
        void prepareRollouts(int length) {
            reachedScratch = new int[length];
        }

        /** The base policy's action code per slot from {@code start.slot} to the last slot (no rollouts). */
        int[] runBase(SimState start) {
            Tables t = m.t;
            int len = Math.max(0, t.lastSlot - start.slot + 1);
            int[] chosen = new int[len];
            SimState st = start.copy();
            for (int i = 0; i < len; i++) {
                int code = Kernel.FREE;
                if (t.open(st.slot)) code = h.choose(st, rolloutBuf, Kernel.legal(m, st, rolloutBuf));
                chosen[i] = code;
                Kernel.apply(m, st, code, null);
            }
            return chosen;
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
                            boolean meetable = (slot & 1) == 0 && socialLegal(n);
                            for (int c : diverse(slot, n, baseIdx)) {
                                if (c == code) continue;
                                if (System.nanoTime() > deadline || cancelled()) {
                                    improve = false;
                                    exhausted = true;
                                    break;
                                }
                                double j = rollout(st, c, reached);
                                // A daytime slot spent on anything but a link while one could be met must pay off clearly.
                                double need = meetable && !social(c) ? Math.max(margin, wasteMargin) : margin;
                                if (j > incumbent + need) {
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

        /** A wanted link can be met or opened in this slot (a legal meeting/unlock with a real score). */
        private boolean socialLegal(int n) {
            for (int i = 0; i < n; i++) {
                if (social(buf[i]) && scores[i] > 1.0) return true;
            }
            return false;
        }

        private boolean isLegal(int code, int n) {
            for (int i = 0; i < n; i++) if (buf[i] == code && !Heuristics.vetoed(scores[i])) return true;
            return false;
        }

        private boolean cancelled() {
            return Planner.cancelled(options);
        }

        /**
         * Objective J of following {@code codes} from {@code start}, less the robustness penalty for
         * 「其余社群」 links that reach MAX on their last meeting opportunity
         * ({@link Heuristics#FRAGILE_MAX_PENALTY}): the value complete plans are compared by.
         */
        double objectiveOf(SimState start, int[] codes) {
            SimState st = start.copy();
            int[] reached = h.initialReached(start);
            for (int code : codes) {
                int slot = st.slot;
                Kernel.apply(m, st, code, null);
                h.track(st, slot, reached);
            }
            return h.objective(st, reached) - Heuristics.FRAGILE_MAX_PENALTY * h.fragileMaxes(st, reached);
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
         * returned: first one per category (best two social actions, best admitted/gate unlock or
         * gateway meeting outside the top two, best stat/job, Tartarus, best dating-site note, Link
         * Episode), ordered by H; when K is larger than that, the next best remaining actions by H.
         */
        private int[] diverse(int slot, int n, int baseIdx) {
            if (candidateMode == CandidateMode.NORMAL_TOP_K) {
                return topKNormal(n, baseIdx);
            }
            if (candidateMode == CandidateMode.OLD_DIVERSE) {
                return oldDiverse(n, baseIdx);
            }
            int social1 = -1;
            int social2 = -1;
            int unlock = -1;
            int anyUnlock = -1;
            int act = -1;
            int tower = -1;
            int note = -1;
            int episode = -1;
            for (int i = 0; i < n; i++) {
                if (Heuristics.vetoed(scores[i])) continue;
                int code = buf[i];
                int type = Kernel.type(code);
                if (type == Kernel.T_MEET) {
                    if (social1 < 0 || scores[i] > scores[social1]) {
                        social2 = social1;
                        social1 = i;
                    } else if (social2 < 0 || scores[i] > scores[social2]) {
                        social2 = i;
                    }
                } else if (type == Kernel.T_UNLOCK) {
                    if (anyUnlock < 0 || scores[i] > scores[anyUnlock]) anyUnlock = i;
                    if (h.isAdmittedOrGate(Kernel.target(code)) && (unlock < 0 || scores[i] > scores[unlock])) {
                        unlock = i;
                    }
                } else if (type == Kernel.T_ACT) {
                    if (act < 0 || scores[i] > scores[act]) act = i;
                } else if (type == Kernel.T_TOWER) {
                    if (tower < 0 || scores[i] > scores[tower]) tower = i;
                } else if (type == Kernel.T_NOTE) {
                    if (note < 0 || scores[i] > scores[note]) note = i;
                } else if (type == Kernel.T_EPISODE) {
                    if (episode < 0 || scores[i] > scores[episode]) episode = i;
                }
            }
            if (unlock < 0) {
                for (int i = 0; i < n; i++) {
                    if (i == social1 || i == social2 || Heuristics.vetoed(scores[i])) continue;
                    int code = buf[i];
                    if (Kernel.type(code) == Kernel.T_MEET && h.opensGate(Kernel.target(code))) {
                        if (unlock < 0 || scores[i] > scores[unlock]) unlock = i;
                    }
                }
            }
            if (unlock < 0 && anyUnlock >= 0 && (social2 < 0 || scores[anyUnlock] > scores[social2])) {
                unlock = anyUnlock;
            }
            List<Integer> picked = new ArrayList<>(7);
            for (int r : new int[]{social1, social2, unlock, act, tower, note, episode}) {
                if (r >= 0 && !picked.contains(r)) picked.add(r);
            }
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

        private int[] oldDiverse(int n, int baseIdx) {
            int social1 = -1;
            int social2 = -1;
            int act = -1;
            int tower = -1;
            int note = -1;
            int episode = -1;
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
                } else if (type == Kernel.T_NOTE) {
                    if (note < 0 || scores[i] > scores[note]) note = i;
                } else if (type == Kernel.T_EPISODE) {
                    if (episode < 0 || scores[i] > scores[episode]) episode = i;
                }
            }
            List<Integer> picked = new ArrayList<>(6);
            for (int r : new int[]{social1, social2, act, tower, note, episode}) if (r >= 0) picked.add(r);
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

        private int[] topKNormal(int n, int baseIdx) {
            List<Integer> picked = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (!Heuristics.vetoed(scores[i])) picked.add(i);
            }
            picked.sort(this::byScore);
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
     * Every legal productive action for {@code observed.slot} as a labeled step, most useful first,
     * for the "改做别的" list. Usefulness is the objective J of taking the action now and following
     * the base policy to the end of the calendar (what the rest of the year looks like after this
     * choice); ties go to the base policy's H, then the kernel's order. Steps carry the same fields
     * as plan steps; the caller marks the planned one.
     */
    static List<Plan.Step> alternatives(Rules rules, Profile profile, SimState observed) {
        Model m = new Model(rules, profile, null);
        SimState st = Kernel.normalize(m, observed);
        Heuristics h = new Heuristics(m, st, null);
        Labels labels = new Labels(m, h);
        int[] codes = new int[Kernel.maxLegal(m)];
        int n = Kernel.legal(m, st, codes);
        double[] scores = new double[n];
        double[] value = new double[n];
        h.prepare(st);
        for (int i = 0; i < n; i++) scores[i] = h.score(st, codes[i]);
        Engine.Options options = new Engine.Options();
        Search search = new Search(m, h, options, Long.MAX_VALUE, 0f, 0f);
        int[] reached = h.initialReached(st);
        search.prepareRollouts(reached.length);
        for (int i = 0; i < n; i++) value[i] = search.rollout(st, codes[i], reached);
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) order.add(i);
        order.sort((a, b) -> {
            if (value[a] != value[b]) return Double.compare(value[b], value[a]);
            if (scores[a] != scores[b]) return Double.compare(scores[b], scores[a]);
            return Integer.compare(a, b);
        });
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
        labels.describeSide(step, fx);
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

    /** Slot at which each link goes from RANK 0 to an unlocked link when following {@code codes} (-1 never or already). */
    static int[] openSlots(Model m, SimState start, int[] codes) {
        int[] open = new int[m.t.nLinks];
        Arrays.fill(open, -1);
        SimState st = start.copy();
        for (int code : codes) {
            int slot = st.slot;
            int[] before = st.rank.clone();
            Kernel.apply(m, st, m.t.open(slot) ? code : Kernel.FREE, null);
            for (int l = 0; l < open.length; l++) {
                if (open[l] < 0 && before[l] == 0 && st.rank[l] > 0) open[l] = slot;
            }
        }
        return open;
    }

    static Plan assemble(Model m, Heuristics h, SimState start, int[] codes) {
        return assemble(m, h, start, codes, null);
    }

    /**
     * The labelled plan for {@code codes}; {@code tradedFor} names, per goal the plan misses, the
     * goal it gave way to (see Pool#tradedFor; null when unknown).
     */
    static Plan assemble(Model m, Heuristics h, SimState start, int[] codes, int[] tradedFor) {
        Rules rules = m.rules;
        Tables t = m.t;
        Labels labels = new Labels(m, h);
        labels.setPlannedOpen(openSlots(m, start, codes));
        labels.setTradedFor(tradedFor);
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
        if (tradedFor != null) {
            for (int l = 0; l < t.nLinks && l < sum.tradedFor.length; l++) sum.tradedFor[l] = tradedFor[l];
        }
        labels.notes(plan, start, st);
        return plan;
    }
}
