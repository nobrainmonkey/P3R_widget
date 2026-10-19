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
 *
 * <p>安排风格 「尽量都体验」 (DESIGN §20): the 「尽量多 MAX」 planning of the same start runs alongside
 * on a second thread and every plan it makes joins the pool ({@link Pool#addMaxStylePlans}); the plan
 * kept never does worse than the 「尽量多 MAX」 plan on the goals or on what the style promises
 * ({@link Pool#keepsPromises}), and its score ({@link Candidate#score}) is at least that plan's
 * (less {@link #SWITCH_MARGIN} at most when it keeps the previous plan's next three days).
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
    /**
     * 「尽量都体验」 keeps a plan only if it wastes at most this many more daytime slots than the
     * 「尽量多 MAX」 plan of the same start ({@link Pool#keepsPromises}).
     */
    static final int BREADTH_WASTE_SLACK = 2;

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
    /** Manual deep search also looks beyond the ordinary four best action categories. */
    static final int DEEP_CANDIDATES = 8;

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
     * the previous plan's next three days. Under 「尽量都体验」 the 「尽量多 MAX」 planning of the same
     * start runs alongside and joins the pool, and only plans that keep the style's promises are
     * kept ({@link Pool#addMaxStylePlans}, {@link Pool#choose}). With a zero time budget only the
     * first tuning's base policy runs.
     */
    static Plan plan(Rules rules, Profile profile, SimState observed, Plan previous, Engine.Options options,
                     Heuristics.Tuning[] portfolio) {
        long t0 = System.nanoTime();
        long deadline = t0 + Math.max(0, options.timeBudgetMillis) * 1_000_000L;
        Plan ordinary = null;
        if (options.deepSearch && options.timeBudgetMillis > 0 && !cancelled(options)) {
            // Wider rollouts change their trajectories: they are not a superset of the ordinary
            // four-action search. Keep a real ordinary search inside the same overall budget.
            long available = Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
            if (available > 0) {
                Engine.Options baseline = quietCopy(options);
                baseline.deepSearch = false;
                baseline.candidates = 4;
                baseline.timeBudgetMillis = Math.min(Engine.DEFAULT_PLAN_BUDGET_MILLIS, available);
                baseline.trace = options.trace;
                Engine.Progress progress = options.progress;
                if (progress != null) baseline.progress = f -> progress.onProgress(DEEP_BASE_SHARE * f);
                ordinary = plan(rules, profile, observed, previous, baseline, portfolio);
            }
            Engine.Options expanded = quietCopy(options);
            expanded.incumbentPlan = options.incumbentPlan;
            expanded.trace = options.trace;
            Engine.Progress progress = options.progress;
            if (progress != null) expanded.progress = f -> progress.onProgress(DEEP_BASE_SHARE + (1 - DEEP_BASE_SHARE) * f);
            options = expanded;
        }
        Model m = new Model(rules, profile, options);
        SimState start = Kernel.normalize(m, observed);
        m.initializeBatches(start);
        boolean search = options.timeBudgetMillis > 0;
        Pool pool = new Pool(m, start, options.deepSearch ? null : previous, options, deadline, portfolio);
        Candidate incumbent = options.deepSearch ? pool.admitIncumbent(options.incumbentPlan) : null;
        Candidate remaining = pool.admitPrevious(previous);
        if (remaining != null && (incumbent == null || Pool.better(remaining, incumbent))) incumbent = remaining;
        Candidate ordinaryCandidate = pool.admitIncumbent(ordinary);
        if (ordinaryCandidate != null && (incumbent == null || Pool.better(ordinaryCandidate, incumbent))) incumbent = ordinaryCandidate;
        if (search) pool.startMaxStyleRun();
        boolean exhausted = pool.run(null, search, true);
        if (search) exhausted |= pool.prioritise();
        if (search) exhausted |= pool.addMaxStylePlans();
        Candidate chosen = pool.chooseWithFloor(incumbent);
        Plan plan = assemble(m, pool.labelHeuristics(chosen), start, chosen.codes, pool.tradedFor(chosen));
        Reminders.attach(m, start, chosen.codes, plan, options.lastTowerSlot);
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

    /** Deep search keeps the ordinary shortlist first, then explores every other screened tuning. */
    static Candidate[] fortifyOrder(Candidate[] order, Heuristics.Tuning[] portfolio, int goals, boolean deep) {
        Candidate[] normal = fortifyOrder(order, portfolio, goals);
        if (!deep) return normal;
        List<Candidate> out = new ArrayList<>(Arrays.asList(normal));
        for (Candidate c : order) if (!out.contains(c)) out.add(c);
        return out.toArray(new Candidate[0]);
    }

    /** Share of the progress bar used by the screening phase. */
    private static final float SCREEN_SHARE = 0.1f;
    /** Deep search first obtains the ordinary route, then broadens the search within the shared deadline. */
    private static final float DEEP_BASE_SHARE = 0.15f;

    /**
     * One complete plan of the portfolio. Plans are compared by their rescues left to the limit evening
     * first ({@link #late}: fewer is better, so a plan that depends on that evening when an earlier one
     * was open is never preferred), then by their goals ({@link Pool#compareGoals}), then by
     * {@link #score}: the objective J of the whole profile minus the waste margin for every daytime
     * slot that skips a wanted link which could be met there, so a plan that gives such a slot to
     * training or a job wins only by a clear gain (the same rule the rollout applies slot by slot, see
     * {@link #WASTE_MARGIN}).
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
        /** Wasted daytime slots ({@link #daytimeWaste}). */
        final int waste;
        /**
         * Rescues that matter done on the limit evening although the window had an earlier evening the
         * plan could use ({@link Heuristics#lateRescues}; DESIGN §10.3). 0 for every plan the planner keeps
         * whenever one of its plans manages that.
         */
        final int late;
        /** Missing-person groups that this plan could still rescue but leaves without a visit. */
        final int missed;
        /** Links at RANK 1 or above, links at RANK 5 or above and the sum of end ranks: what 「尽量都体验」 promises. */
        final int unlocked;
        final int rank5;
        final int rankSum;
        boolean exhausted;

        /** A plan that leaves no rescue to the limit evening ({@link #late} 0). */
        Candidate(Heuristics h, Heuristics.Tuning tuning, int[] goals, int[] codes, double j, double ownJ, double score, int variant,
                  boolean[] reached, int[] endRank, int[] linkRanks, int waste) {
            this(h, tuning, goals, codes, j, ownJ, score, variant, reached, endRank, linkRanks, waste, 0);
        }

        Candidate(Heuristics h, Heuristics.Tuning tuning, int[] goals, int[] codes, double j, double ownJ, double score, int variant,
                  boolean[] reached, int[] endRank, int[] linkRanks, int waste, int late) {
            this(h, tuning, goals, codes, j, ownJ, score, variant, reached, endRank, linkRanks, waste, late, 0);
        }

        Candidate(Heuristics h, Heuristics.Tuning tuning, int[] goals, int[] codes, double j, double ownJ, double score, int variant,
                  boolean[] reached, int[] endRank, int[] linkRanks, int waste, int late, int missed) {
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
            this.waste = waste;
            this.late = late;
            this.missed = missed;
            int open = 0;
            int high = 0;
            int sum = 0;
            for (int r : linkRanks) {
                if (r >= 1) open++;
                if (r >= 5) high++;
                sum += r;
            }
            this.unlocked = open;
            this.rank5 = high;
            this.rankSum = sum;
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
        /** 安排风格 of the plans this pool makes: {@code false} is 「尽量多 MAX」 (see {@link #addMaxStylePlans}). */
        final boolean breadth;
        /** The main runs of this pool report progress (not the 「尽量多 MAX」 pool run inside a 「尽量都体验」 one). */
        private final boolean reports;
        private final double margin;
        final List<Candidate> all = new ArrayList<>();
        /**
         * Under 「尽量都体验」: the plan 「尽量多 MAX」 makes from the same start (the choice of its
         * pool, measured with this pool's objective), once {@link #addMaxStylePlans} has run; else null.
         */
        Candidate maxPlan;
        private long fortifyNanos;
        private int fortified;

        Pool(Model m, SimState start, Plan previous, Engine.Options options, long deadline, Heuristics.Tuning[] portfolio) {
            this(m, start, previous, options, deadline, portfolio, m.profile.style == Profile.Style.BREADTH, true);
        }

        private Pool(Model m, SimState start, Plan previous, Engine.Options options, long deadline, Heuristics.Tuning[] portfolio,
                     boolean breadth, boolean reports) {
            this.m = m;
            this.start = start;
            this.previous = previous;
            this.options = options;
            this.deadline = deadline;
            this.portfolio = portfolio;
            this.breadth = breadth;
            this.reports = reports;
            this.goals = Heuristics.goalOrder(m);
            this.ref = heuristics(portfolio[0], null);
            this.margin = wasteMargin(ref);
        }

        /** Base policy and objective of this pool's style for {@code tuning} and {@code goalOrder} (null: the profile's). */
        Heuristics heuristics(Heuristics.Tuning tuning, int[] goalOrder) {
            return new Heuristics(m, start, previous, tuning, goalOrder, breadth);
        }

        /** Measures {@code codes}: objective of the whole profile, daytime waste, goal outcomes. */
        Candidate candidate(Heuristics h, Heuristics.Tuning tuning, int[] goalOrder, int[] codes, int variant) {
            Candidate c = measure(h, tuning, goalOrder, codes, variant);
            if (options.trace != null) options.trace.add(c.linkRanks.clone());
            return c;
        }

        private Candidate measure(Heuristics h, Heuristics.Tuning tuning, int[] goalOrder, int[] codes, int variant) {
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
            double own = goalOrder == null ? j : new Search(m, h, options, deadline, 0f, 0f).objectiveOf(start, codes);
            return new Candidate(h, tuning, goalOrder, codes, j, own, j - margin * waste, variant, reached, endRank, linkRanks, waste,
                    ref.lateRescues(reachedAt) + (m.batches == null ? 0 : m.batches.late(st)),
                    m.batches == null ? 0 : m.batches.missed(st));
        }

        /**
         * Admit only a complete, current-policy plan that can be replayed from this exact start.
         * The validator checks its stored forecast; the kernel then independently checks each
         * action's legality before we measure and later rebuild every label/prediction ourselves.
         */
        Candidate admitIncumbent(Plan plan) {
            if (!currentRoute(plan) || plan.fromSlot != start.slot) return null;
            try {
                if (!PlanValidator.validate(m.rules, m.profile, start, plan).isEmpty()) return null;
                return admitRoute(plan, 0);
            } catch (RuntimeException malformed) {
                return null;
            }
        }

        /**
         * Rebase a previous route onto the current observations. Its old predictions and summary
         * describe an earlier cursor and are deliberately ignored: every remaining action must
         * still be legal, and its rebuilt forecast must pass the independent replay validator.
         */
        Candidate admitPrevious(Plan plan) {
            if (!currentRoute(plan) || plan.fromSlot > start.slot) return null;
            return admitRoute(plan, start.slot - plan.fromSlot);
        }

        private boolean currentRoute(Plan plan) {
            return plan != null && plan.policyVersion == Plan.CURRENT_POLICY_VERSION
                    && m.rules.version.equals(plan.rulesVersion) && plan.fromSlot >= m.rules.firstSlot()
                    && plan.toSlot == m.t.lastSlot && plan.steps != null
                    && plan.steps.size() == Math.max(0, m.t.lastSlot - plan.fromSlot + 1);
        }

        private Candidate admitRoute(Plan plan, int offset) {
            try {
                int length = Math.max(0, m.t.lastSlot - start.slot + 1);
                if (offset < 0 || offset > plan.steps.size() || plan.steps.size() - offset != length) return null;
                int[] codes = new int[length];
                int[] legal = new int[Kernel.maxLegal(m)];
                SimState state = start.copy();
                for (int i = 0; i < codes.length; i++) {
                    Plan.Step step = plan.steps.get(offset + i);
                    if (step == null || step.slot != state.slot) return null;
                    Action action = Action.parse(m.rules, step.action);
                    if (action == null || action.type == Action.Type.INVITE) return null;
                    int code = Kernel.FREE;
                    if (!m.t.open(state.slot)) {
                        if (action.type != Action.Type.LOCKED
                                || !step.action.equals("locked:" + m.t.lock[state.slot])) return null;
                    } else if (action.type != Action.Type.FREE) {
                        if (action.type == Action.Type.LOCKED) return null;
                        code = Kernel.encode(action);
                        int n = Kernel.legal(m, state, legal);
                        boolean found = false;
                        for (int j = 0; j < n; j++) if (legal[j] == code) { found = true; break; }
                        if (!found) return null;
                    }
                    codes[i] = code;
                    Kernel.apply(m, state, code, null);
                }
                Plan rebuilt = assemble(m, ref, start, codes);
                if (!PlanValidator.validate(m.rules, m.profile, start, rebuilt).isEmpty()) return null;
                Candidate candidate = candidate(ref, portfolio[0], null, codes, -2);
                add(candidate);
                return candidate;
            } catch (RuntimeException malformed) {
                return null; // stale or malformed cached plans are never executed unchecked
            }
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
                Heuristics h = heuristics(portfolio[v], goalOrder);
                Search base = new Search(m, h, options, deadline, 0f, 0f);
                screened[v] = candidate(h, portfolio[v], goalOrder, base.runBase(start), v);
                if (main && reports && options.progress != null) options.progress.onProgress(SCREEN_SHARE * (v + 1) / variants);
            }
            Candidate[] order = Arrays.stream(screened).filter(c -> c != null)
                    .sorted((a, b) -> a.ownJ != b.ownJ ? Double.compare(b.ownJ, a.ownJ) : Integer.compare(a.variant, b.variant))
                    .toArray(Candidate[]::new);
            for (Candidate c : order) add(c);
            if (!search) return exhausted;
            int goalCount = goalOrder == null ? goals.length : goalOrder.length;
            // Prefix repair needs the same strategy coverage as a normal run: a reduced
            // shortlist can miss a feasible pair of higher goals when a third goal is added.
            Candidate[] fortifyOrder = fortifyOrder(order, portfolio, goalCount, options.deepSearch);
            int fortify = fortifyOrder.length;
            for (int k = 0; k < fortify; k++) {
                if ((k > 0 || !main) && !timeForAnotherFortify()) {
                    exhausted = true; // not enough time left for another full rollout
                    break;
                }
                Candidate base = fortifyOrder[k];
                float from = main && reports ? SCREEN_SHARE + (1 - SCREEN_SHARE) * k / fortify : 0.99f;
                float span = main && reports ? (1 - SCREEN_SHARE) / fortify : 0f;
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
            Heuristics h = heuristics(tuning, goalOrder);
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
         * missed one as goals ("top-k"; the later goals count as 「其余社群」), with the same
         * fortified strategy coverage as a normal run ({@link #run}). Each k runs at most once.
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
                if (best.reached[i] || anyReaches(i, best.missed, best.late)) continue;
                if (cancelled(options) || System.nanoTime() > deadline) return true;
                int[] promoted = new int[n];
                promoted[0] = goals[i];
                for (int a = 0, b = 1; a < n; a++) if (a != i) promoted[b++] = goals[a];
                Heuristics.Tuning tuning = Heuristics.Tuning.DEFAULT.goalFirst();
                Heuristics h = heuristics(tuning, promoted);
                add(candidate(h, tuning, promoted, new Search(m, h, options, deadline, 0f, 0f).runBase(start), -1));
            }
            return orderedNoteStrategy();
        }

        /**
         * Once every active goal is open, ordinary stat preparation no longer needs to compete
         * with their notes. Compare an additional route that funds affinity in goal order; this
         * can preserve an extra later-goal rank when the first goal needs several notes in a row.
         * The normal portfolio and prefix repairs always run first, unchanged.
         */
        private boolean orderedNoteStrategy() {
            if (goals.length < 2 || firstMissed(best()) < 0 || m.note == null
                    || m.note.gain <= 0 || start.notesUsed >= m.noteMaxUses
                    || Rules.dayOf(start.slot) < m.note.fromDay) return false;
            for (int goal : goals) if (start.rank[goal] < m.target(goal) && start.rank[goal] < 1) return false;
            for (java.util.Map.Entry<Integer, Integer> requirement : m.note.requiresLv.entrySet()) {
                if (Kernel.statLv(m, start, requirement.getKey()) < requirement.getValue()) return false;
            }
            if (cancelled(options) || System.nanoTime() > deadline) return true;
            Heuristics.Tuning tuning = Heuristics.Tuning.DEFAULT.goalFirst().orderedNotes();
            Heuristics h = heuristics(tuning, null);
            add(candidate(h, tuning, null, new Search(m, h, options, deadline, 0f, 0f).runBase(start), -3));
            if (!timeForAnotherFortify()) return true;
            Candidate fortified = fortify(tuning, null, -3, 0.99f, 0f);
            add(fortified);
            return fortified.exhausted;
        }

        /** Some plan with at most {@code late} rescues left to the limit evening reaches the goal. */
        private boolean anyReaches(int goal, int missed, int late) {
            for (Candidate c : all) if (c.reached[goal] && c.missed <= missed && c.late <= late) return true;
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

        /**
         * {@code a} before {@code b}: fewer rescues left to the limit evening ({@link Candidate#late}), then
         * strict goal priority ({@link #compareGoals}), then the score.
         */
        private static boolean better(Candidate a, Candidate b) {
            if (a.missed != b.missed) return a.missed < b.missed;
            if (a.late != b.late) return a.late < b.late;
            int k = compareGoals(a, b);
            if (k != 0) return k > 0;
            return a.score > b.score + EPS;
        }

        /** The best plan so far by rescues left to the limit evening, goals, then score (the first one on ties). */
        Candidate best() {
            Candidate best = null;
            for (Candidate c : all) if (best == null || better(c, best)) best = c;
            return best;
        }

        /**
         * The plan to keep, among the plans whose goals tie with the best ({@link #compareGoals}) and
         * that leave no more rescues to the limit evening than it ({@link Candidate#late}):
         * the one with the best score, or the best one that keeps the previous plan's next three
         * days when it scores within {@link #SWITCH_MARGIN}. Under 「尽量都体验」 only the plans that
         * keep its promises ({@link #keepsPromises}) take part, whenever one of them ties on the
         * goals: always when the 「尽量多 MAX」 plan does, since it keeps them itself.
         */
        Candidate choose() {
            Candidate top = best();
            boolean bounded = false;
            if (maxPlan != null) {
                for (Candidate c : all) {
                    if (c.missed == top.missed && c.late == top.late && compareGoals(c, top) == 0 && keepsPromises(c)) {
                        bounded = true;
                        break;
                    }
                }
            }
            Candidate best = null;
            Candidate stable = null;
            for (Candidate c : all) {
                if (c.missed != top.missed || c.late != top.late || compareGoals(c, top) != 0 || bounded && !keepsPromises(c)) continue;
                if (best == null || c.score > best.score + EPS) best = c;
                if (keepsPreviousWindow(ref, start.slot, c.codes) && (stable == null || c.score > stable.score + EPS)) stable = c;
            }
            return stable != null && best.score <= stable.score + SWITCH_MARGIN ? stable : best;
        }

        /**
         * Preserve a legal comparison baseline without undoing the current style's promises.
         * Rescue and goal priority already apply to every candidate in {@link #choose}; a higher
         * score alone must not restore an old MAX-style route after the player chooses breadth.
         */
        Candidate chooseWithFloor(Candidate incumbent) {
            Candidate chosen = choose();
            if (incumbent != null && better(incumbent, chosen)
                    && (maxPlan == null || keepsPromises(incumbent) || !keepsPromises(chosen))) return incumbent;
            return chosen;
        }

        /**
         * 「尽量都体验」 is never a worse plan than 「尽量多 MAX」 on what it promises (DESIGN §20): the
         * 「尽量多 MAX」 plan of the same start ({@link #maxPlan}) does not beat {@code c} on links
         * unlocked, links at RANK 5 or above and the rank sum all at once (at least one strictly);
         * {@code c} also keeps at least as many links at RANK 5 or above, so spreading meetings over
         * more unlocks does not sacrifice the middle of their stories;
         * {@code c} unlocks fewer links than it only when the 「尽量都体验」 objective itself (J, before
         * the waste margin) scores {@code c} at least as high, since the style unlocks every link first
         * (「先把每个社群都解锁」); and {@code c} wastes at most {@link #BREADTH_WASTE_SLACK} more daytimes
         * than it. True for every plan when there is no such plan (「尽量多 MAX」 itself, or a zero time
         * budget).
         *
         * <p>The unlock rule (3.1.0 re-check): the waste margin charges a goal profile's wasted
         * daytime at 2.5 slots of the goals' earlier MAX ({@link #wasteMargin}) although 「尽量都体验」
         * values that earlier MAX at a quarter, so the score could keep a plan that leaves 恶魔 locked
         * where 「尽量多 MAX」 takes it to MAX (10/22, 想要 恋爱, 必须 永劫、倒悬者: 19 links unlocked
         * against 20, J 3,724 lower), the daytimes that pay its fees and raise 魅力 counted as waste.
         */
        boolean keepsPromises(Candidate c) {
            Candidate x = maxPlan;
            if (x == null || c == x) return true;
            // Breadth must include the links that a MAX-style route can complete, rather than
            // replacing a difficult unlock with another already-open link of the same count.
            for (int l = 0; l < c.linkRanks.length; l++) {
                if (x.linkRanks[l] >= 10 && c.linkRanks[l] == 0) return false;
            }
            if (c.rank5 < x.rank5) return false;
            boolean dominated = x.unlocked >= c.unlocked && x.rank5 >= c.rank5 && x.rankSum >= c.rankSum
                    && (x.unlocked > c.unlocked || x.rank5 > c.rank5 || x.rankSum > c.rankSum);
            boolean fewerUnlocked = c.unlocked < x.unlocked && c.j < x.j - EPS;
            return !dominated && !fewerUnlocked && c.waste <= x.waste + BREADTH_WASTE_SLACK;
        }

        /**
         * Heuristics that label the plan: the plan's own, with the whole profile's goals and style (a
         * top-k plan, or one of the 「尽量多 MAX」 run of {@link #addMaxStylePlans}, is labelled like
         * any other plan of this profile).
         */
        Heuristics labelHeuristics(Candidate c) {
            return c.goals == null && c.h.breadth == breadth ? c.h : heuristics(c.tuning, null);
        }

        /** The 「尽量多 MAX」 run of {@link #addMaxStylePlans} on its own thread ({@link #startMaxStyleRun}), or null. */
        private java.util.concurrent.FutureTask<Pool> maxRun;
        /** Of a 「尽量多 MAX」 pool run by {@link #runMaxStyle}: its time budget ran out before it was complete. */
        private boolean maxStyleExhausted;

        /**
         * Under 「尽量都体验」, starts the planning of {@link #addMaxStylePlans} on a second thread, so
         * that it runs while this pool plans with its own style (the same deadline and cancel flag;
         * neither run reads the other's state). Without a thread it runs in
         * {@link #addMaxStylePlans}, after this pool.
         */
        void startMaxStyleRun() {
            if (!breadth || maxRun != null) return;
            Pool max = new Pool(m, start, previous, quietCopy(options), deadline, portfolio, false, false);
            java.util.concurrent.FutureTask<Pool> task = new java.util.concurrent.FutureTask<>(() -> runMaxStyle(max));
            Thread thread = new Thread(task, "p3r-plan-max-style");
            thread.setDaemon(true);
            try {
                thread.start();
            } catch (OutOfMemoryError noThread) {
                return; // no thread to be had: addMaxStylePlans runs it after this pool
            }
            maxRun = task;
        }

        /** The whole 「尽量多 MAX」 planning on {@code max}: screening, fortified searches, strict goal priority. */
        private static Pool runMaxStyle(Pool max) {
            boolean exhausted = max.run(null, true, true);
            exhausted |= max.prioritise();
            max.maxStyleExhausted = exhausted;
            return max;
        }

        /**
         * 「尽量都体验」 must never be a worse plan than 「尽量多 MAX」 on what it promises, and it
         * keeps every goal that 「尽量多 MAX」 reaches (spec §8.1: strict goal priority exactly as in
         * 3.0). Its own search can miss both: from 10/22 with 必须 ①星 ②正义 ③命运 its best plan
         * unlocked 18 links where 「尽量多 MAX」 unlocks 19, with fewer links at RANK 5 or above and a
         * lower rank sum, although its own objective scores the 「尽量多 MAX」 plan higher (3.1.0
         * re-check); with 必须 ①女教皇 ②永劫 ③恋爱 it left 恋爱 at RANK 9. So under 「尽量都体验」
         * the whole 「尽量多 MAX」 planning of the same start (the same screening, fortified searches
         * and strict-priority runs as a profile with that style, started alongside by
         * {@link #startMaxStyleRun}) joins the pool, every plan measured with this profile's objective,
         * and {@link #maxPlan} is the plan that style keeps. {@link #compareGoals} then sees every plan
         * the 3.0 engine makes, and among plans whose goals tie {@link #choose} keeps the best score
         * among those that {@link #keepsPromises keep the promises}, the 「尽量多 MAX」 plan included.
         * The screened 「尽量多 MAX」 tunings alone do not tell which of them would be worth a fortified
         * search (their base plans score thousands below the plan their search makes), so the whole
         * run is kept. Nothing runs under 「尽量多 MAX」. Returns true when the time budget ran out
         * before that run was complete.
         */
        boolean addMaxStylePlans() {
            if (!breadth) return false;
            Pool max;
            if (maxRun != null) {
                max = awaitMaxRun();
            } else {
                max = runMaxStyle(new Pool(m, start, previous, quietCopy(options), deadline, portfolio, false, false));
            }
            Candidate kept = max.choose();
            for (Candidate c : max.all) {
                Candidate mine = measure(c.h, c.tuning, c.goals, c.codes, c.variant);
                mine.exhausted = c.exhausted;
                if (options.trace != null) options.trace.add(mine.linkRanks.clone());
                if (c == kept) maxPlan = mine;
                add(mine);
            }
            return max.maxStyleExhausted;
        }

        /** The pool of {@link #maxRun} once it is done (it ends by the deadline, or on cancel). */
        private Pool awaitMaxRun() {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        return maxRun.get();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (java.util.concurrent.ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        if (cause instanceof Error) throw (Error) cause;
                        throw new IllegalStateException(cause);
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        /**
         * For every goal {@code chosen} misses, the goal it gave way to: among the plans of the pool
         * that reach that goal (and leave no more rescues to the limit evening than {@code chosen}),
         * the goal on which {@code chosen} beats them ({@link #decidingGoal}), provided {@code chosen}
         * reaches it; the latest such goal (the closest trade). -1 when no such plan reached the goal
         * (nothing was traded for it) and for every other link.
         */
        int[] tradedFor(Candidate chosen) {
            int[] out = new int[m.t.nLinks];
            Arrays.fill(out, -1);
            for (int i = 0; i < goals.length; i++) {
                if (chosen.reached[i]) continue;
                int traded = -1;
                for (Candidate c : all) {
                    if (!c.reached[i] || c.missed > chosen.missed || c.late > chosen.late) continue;
                    int d = decidingGoal(chosen, c);
                    if (d >= 0 && d < i && chosen.reached[d]) traded = Math.max(traded, d);
                }
                out[goals[i]] = traded < 0 ? -1 : goals[traded];
            }
            return out;
        }
    }

    /**
     * The options of the 「尽量多 MAX」 run inside a 「尽量都体验」 plan: the same budget, candidates and
     * cancel flag, no progress callback (it runs on another thread) and no trace (its plans are traced
     * when they join the 「尽量都体验」 pool).
     */
    static Engine.Options quietCopy(Engine.Options o) {
        Engine.Options q = new Engine.Options();
        q.timeBudgetMillis = o.timeBudgetMillis;
        q.candidates = o.candidates;
        q.deepSearch = o.deepSearch;
        q.cancelled = o.cancelled;
        q.towerIncomePerVisit = o.towerIncomePerVisit;
        q.followUp = o.followUp;
        q.lastTowerSlot = o.lastTowerSlot;
        return q;
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
            this.candidates = Math.max(options.deepSearch ? DEEP_CANDIDATES : 1, options.candidates);
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
                            for (int c : diverse(n, baseIdx)) {
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
         * returned: first one per category (best two meetings, best admitted/gate unlock or gateway
         * meeting outside the top two, best stat/job, best dorm project, Tartarus, best dating-site
         * note, Link Episode), ordered by H; when K is larger than that, the next best remaining
         * actions by H.
         */
        private int[] diverse(int n, int baseIdx) {
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
            int dorm = -1;
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
                    if (m.rules.activities[Kernel.target(code)].dormActivity()) {
                        if (dorm < 0 || scores[i] > scores[dorm]) dorm = i;
                    } else if (act < 0 || scores[i] > scores[act]) act = i;
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
            List<Integer> picked = new ArrayList<>(8);
            for (int r : new int[]{social1, social2, unlock, act, dorm, tower, note, episode}) {
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
            int dorm = -1;
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
                    if (m.rules.activities[Kernel.target(buf[i])].dormActivity()) {
                        if (dorm < 0 || scores[i] > scores[dorm]) dorm = i;
                    } else if (act < 0 || scores[i] > scores[act]) act = i;
                } else if (type == Kernel.T_TOWER) {
                    if (tower < 0 || scores[i] > scores[tower]) tower = i;
                } else if (type == Kernel.T_NOTE) {
                    if (note < 0 || scores[i] > scores[note]) note = i;
                } else if (type == Kernel.T_EPISODE) {
                    if (episode < 0 || scores[i] > scores[episode]) episode = i;
                }
            }
            List<Integer> picked = new ArrayList<>(7);
            for (int r : new int[]{social1, social2, act, dorm, tower, note, episode}) if (r >= 0) picked.add(r);
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
        return alternatives(rules, profile, observed, new Engine.Options());
    }

    static List<Plan.Step> alternatives(Rules rules, Profile profile, SimState observed, Engine.Options options) {
        if (options == null) options = new Engine.Options();
        Model m = new Model(rules, profile, options);
        SimState st = Kernel.normalize(m, observed);
        m.initializeBatches(st);
        Heuristics h = new Heuristics(m, st, null);
        Labels labels = new Labels(m, h);
        int[] codes = new int[Kernel.maxLegal(m)];
        int n = Kernel.legal(m, st, codes);
        double[] scores = new double[n];
        double[] value = new double[n];
        h.prepare(st);
        for (int i = 0; i < n; i++) scores[i] = h.score(st, codes[i]);
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
        // A D cell is only a possible SMS sender. Keep these manual-record choices separate
        // from scored/rolled-out actions so future plans never depend on an unreceived message.
        for (int l = 0; l < m.t.nLinks; l++) {
            if (Kernel.inviteCandidate(m, st, l)) {
                out.add(describe(m, labels, st, Kernel.code(Kernel.T_INVITE, l, 0), fx));
            }
        }
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
                Kernel.apply(m, st, Kernel.FREE, fx);
                step.predicted = Simulator.predicted(fx);
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
