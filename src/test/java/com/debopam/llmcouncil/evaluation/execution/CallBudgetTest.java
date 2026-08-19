package com.debopam.llmcouncil.evaluation.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CallBudgetTest {
    @Test
    void reservesWorstCaseAndReturnsUnusedCapacity() {
        CallBudget budget = new CallBudget(10, 2);
        CallBudget.Reservation reservation = budget.reserve(6, "unit");
        budget.reconcile(reservation, 3);
        assertEquals(5, budget.consumed());
        assertThrows(CallBudget.BudgetExceededException.class, () -> budget.reserve(6, "next"));
    }

    @Test
    void failsClosedWhenRecordedUsageExceedsTheReservationAndCap() {
        CallBudget budget = new CallBudget(5, 0);
        CallBudget.Reservation reservation = budget.reserve(5, "underestimated-unit");
        assertThrows(CallBudget.BudgetExceededException.class,
                () -> budget.reconcile(reservation, 6));
        assertEquals(6, budget.consumed());
        assertThrows(IllegalStateException.class, () -> budget.reconcile(reservation, 1));
    }
}
