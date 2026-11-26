package dev.vantage.core;

import dev.vantage.core.chunk.Budget;
import dev.vantage.core.chunk.Chunks;
import dev.vantage.module.client.FrameBudget;

/**
 * The finders' view of the chunk service's per-tick time budget (B-176: it used to run only with the beta module Frame Budget on, so
 * normal users had no limit at all). It is on for everyone now. Every scanner loop spends what each scan took and asks {@link #go}
 * before the next one; once the tick's work has used more than the budget the rest of the queue simply waits for the next tick.
 * Nothing is dropped, so the results are the same, they only arrive later. Each scanner still gets at least one scan a tick, so
 * nobody starves. The account is the service's own ({@link Chunks#budget()}), so jobs queued with {@code Chunks.submit} and these
 * loops draw on the same milliseconds. The number is {@link Budget#DEFAULT_NS} (2.0 ms), or the Frame Budget module's setting while
 * that module is on.
 */
public final class ScanBudget {
    private ScanBudget() {}

    /** Rig: how many times a scan loop stopped early because the budget was used up. */
    public static long deferred;
    /** Rig: the budget in ms to use instead of the module's setting (NaN = none). */
    public static double testBudgetMs = Double.NaN;

    public static void newTick() {
        Chunks.budget().newTick();
    }

    /** Always true: the budget is not a beta feature any more. Kept because the in-game checks ask. */
    public static boolean active() {
        return true;
    }

    /** The budget in nanoseconds: the rig's number, else Frame Budget's setting while that module is on, else the default. */
    public static long limitNs() {
        if (!Double.isNaN(testBudgetMs)) return (long) (testBudgetMs * 1e6);
        FrameBudget m = Modules.get(FrameBudget.class);
        return m != null && m.on() ? (long) (m.budget.get() * 1e6) : Budget.DEFAULT_NS;
    }

    public static void spend(long ns) {
        Chunks.budget().spend(ns);
    }

    public static boolean over() {
        if (Chunks.budget().used() <= limitNs()) return false;
        deferred++;
        return true;
    }

    /** True when the n-th scan (0 based) of a loop may run: the first always, the others while the budget lasts. */
    public static boolean go(int n) {
        return n == 0 || !over();
    }
}
