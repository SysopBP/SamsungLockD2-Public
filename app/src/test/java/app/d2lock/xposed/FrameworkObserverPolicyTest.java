package app.d2lock.xposed;
import org.junit.Test;
import static org.junit.Assert.*;
public class FrameworkObserverPolicyTest {
    @Test public void diagnosticsAndDirectBootNeverRelock() {
        assertFalse(FrameworkObserverPolicy.shouldRelock(true,true,true,true,"KEYGUARD"));
        assertFalse(FrameworkObserverPolicy.shouldRelock(false,true,false,true,"KEYGUARD"));
    }
    @Test public void unrelatedEventsAndDisabledControlsNeverRelock() {
        for (String event : new String[]{"SLEEP","NATIVE_RESUMED","BOOT_READY",null})
            assertFalse(FrameworkObserverPolicy.shouldRelock(true,true,false,true,event));
        assertFalse(FrameworkObserverPolicy.shouldRelock(true,false,false,true,"KEYGUARD"));
        assertFalse(FrameworkObserverPolicy.shouldRelock(true,true,false,false,"KEYGUARD"));
        assertTrue(FrameworkObserverPolicy.shouldRelock(true,true,false,true,"KEYGUARD"));
    }
}
