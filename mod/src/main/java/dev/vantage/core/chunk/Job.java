package dev.vantage.core.chunk;

/** One piece of chunk work for the {@link WorkQueue}. */
@FunctionalInterface
public interface Job {
    /**
     * Runs the job on the client thread. The queue counts the time the call takes; the job only asks {@link Budget#hasTimeLeft()} (which includes the time it has used so far) to know whether to carry on, and never spends time itself.
     *
     * @return true when the job is finished; false for "not finished": it is run again on the next tick, before any new job
     */
    boolean run(Budget budget);
}
