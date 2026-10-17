package app.d2lock.security;

import org.junit.Test;
import static org.junit.Assert.*;

public class WatchdogRecoveryBudgetTest {
    @Test public void capsRapidAndRepeatedRepairsThenAllowsANewWindow() {
        WatchdogRecoveryBudget budget = new WatchdogRecoveryBudget();
        assertTrue(budget.allow(0));
        assertFalse(budget.allow(4999));
        assertTrue(budget.allow(5000));
        assertTrue(budget.allow(10000));
        assertFalse(budget.allow(15000));
        assertFalse(budget.allow(59999));
        assertTrue(budget.allow(60000));
        assertEquals(1, budget.attempts());
    }
    @Test public void clockResetDoesNotPermanentlyBlockRecovery() {
        WatchdogRecoveryBudget budget = new WatchdogRecoveryBudget();
        assertFalse(budget.allow(-1));
        assertTrue(budget.allow(120000));
        assertTrue(budget.allow(10));
        assertFalse(budget.allow(11));
    }
}
