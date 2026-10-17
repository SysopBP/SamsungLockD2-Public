/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed;
import org.junit.Test;
import static org.junit.Assert.*;
public class PeriodicCredentialPolicyTest {
    @Test public void legacySettingsNeverSuppressAuthentication() {
        for (boolean enabled : new boolean[]{false, true})
            for (int user : new int[]{-1, 0, 10})
                for (boolean owner : new boolean[]{false, true})
                    for (long timeout : new long[]{0, 3600000, PeriodicCredentialPolicy.DEFAULT_TIMEOUT_MS, Long.MAX_VALUE})
                        assertFalse(PeriodicCredentialPolicy.allowed(enabled, user, owner, owner, timeout));
    }
}
