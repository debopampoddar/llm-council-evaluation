package com.debopam.llmcouncil.evaluation.execution;

/** Conservative reservation prevents a multi-call council run from crossing the configured cap. */
public class CallBudget {
    private final int maximum;
    private int consumed;

    public CallBudget(int maximum, int alreadyConsumed) {
        this.maximum = maximum;
        this.consumed = alreadyConsumed;
    }

    public synchronized Reservation reserve(int upperBound, String unit) {
        if (upperBound < 0) throw new IllegalArgumentException("upperBound must be non-negative");
        if ((long) consumed + upperBound > maximum) {
            throw new BudgetExceededException("Call budget would be exceeded by " + unit
                    + ": " + consumed + " used, " + upperBound + " reserved, maximum " + maximum);
        }
        consumed += upperBound;
        return new Reservation(upperBound);
    }

    public synchronized void reconcile(Reservation reservation, int actualCalls) {
        if (actualCalls < 0) throw new IllegalArgumentException("actualCalls must be non-negative");
        if (reservation.closed) throw new IllegalStateException("Reservation was already reconciled");
        consumed -= reservation.reserved;
        consumed += actualCalls;
        reservation.closed = true;
        if (consumed > maximum) {
            throw new BudgetExceededException("Recorded calls exceeded the configured maximum: "
                    + consumed + " used, maximum " + maximum);
        }
    }

    public synchronized int consumed() { return consumed; }
    public int maximum() { return maximum; }

    public static final class Reservation {
        private final int reserved;
        private boolean closed;
        private Reservation(int reserved) { this.reserved = reserved; }
        public int reserved() { return reserved; }
        public boolean closed() { return closed; }
    }

    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) { super(message); }
    }
}
