package app.d2lock

import app.d2lock.update.ReleaseVersion
import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun preventsDowngradesAndUnknownTags() {
        assertFalse(ReleaseVersion.newer("0.6.2-boot-ui-v22", "0.6.3-audit-repairs-v23"))
        assertFalse(ReleaseVersion.newer("latest", "0.6.3"))
        assertFalse(ReleaseVersion.newer("v0.6.3-preview", "0.6.3"))
        assertTrue(ReleaseVersion.newer("v0.6.4", "0.6.3"))
        assertTrue(ReleaseVersion.newer("0.6.3", "0.6.3-preview"))
        assertTrue(ReleaseVersion.newer("0.6.3-audit-v23", "0.6.2-ui-v22"))
    }
}
