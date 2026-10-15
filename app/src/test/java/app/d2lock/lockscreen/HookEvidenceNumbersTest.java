package app.d2lock.lockscreen;
import org.junit.Test;
import static org.junit.Assert.*;
public class HookEvidenceNumbersTest {
    @Test public void preservesMixedIntentCounterTypes() {
        assertEquals(2L, HookEvidenceNumbers.value(Integer.valueOf(2)));
        assertEquals(9L, HookEvidenceNumbers.value(Long.valueOf(9)));
        assertEquals(0L, HookEvidenceNumbers.value(Integer.valueOf(0)));
        assertEquals(-1L, HookEvidenceNumbers.value(null));
        assertEquals(-1L, HookEvidenceNumbers.value("installed"));
    }
}
