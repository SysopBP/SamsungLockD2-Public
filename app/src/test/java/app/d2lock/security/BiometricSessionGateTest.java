package app.d2lock.security;
import org.junit.Test;
import static org.junit.Assert.*;
public class BiometricSessionGateTest {
    @Test public void successIsSingleUse() {
        BiometricSessionGate gate = new BiometricSessionGate();
        long token = gate.begin();
        assertTrue(gate.consume(token));
        assertFalse(gate.consume(token));
    }
    @Test public void backgroundCancellationRejectsLateSuccess() {
        BiometricSessionGate gate = new BiometricSessionGate();
        long token = gate.begin();
        gate.cancel();
        assertFalse(gate.consume(token));
    }
    @Test public void oldCallbackCannotConsumeNewAttempt() {
        BiometricSessionGate gate = new BiometricSessionGate();
        long old = gate.begin();
        long current = gate.begin();
        assertFalse(gate.consume(old));
        assertTrue(gate.consume(current));
    }
}
