/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.widget;
import org.junit.Test;
import static org.junit.Assert.*;
public class SuppressionWidgetPolicyTest {
    @Test public void rebootAndMissingSnapshotsCannotStartTimers() {
        assertTrue(SuppressionWidgetPolicy.current(2, 664, 664, 100, 200));
        assertFalse(SuppressionWidgetPolicy.current(2, 663, 664, 100, 200));
        assertFalse(SuppressionWidgetPolicy.current(2, -1, -1, 100, 200));
        assertFalse(SuppressionWidgetPolicy.current(2, 664, 664, 300, 200));
        assertFalse(SuppressionWidgetPolicy.current(1, 664, 664, 100, 200));
        assertFalse(SuppressionWidgetPolicy.timer(0, 100, 200));
        assertFalse(SuppressionWidgetPolicy.timer(1, -1, 200));
        assertFalse(SuppressionWidgetPolicy.timer(1, 300, 200));
        assertTrue(SuppressionWidgetPolicy.timer(1, 100, 200));
    }
}
