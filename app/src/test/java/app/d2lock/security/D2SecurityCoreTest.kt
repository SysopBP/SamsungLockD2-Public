package app.d2lock.security

import org.junit.Assert.*
import org.junit.Test

class D2SecurityCoreTest {
    @Test fun parsesVendorStatus() {
        val state = D2SecurityCore.parseStatus("Available(lock=0, maint=0, at=1)")
        assertNotNull(state)
        assertEquals(0, state!!.lock)
        assertEquals(0, state.maintenance)
        assertEquals(1, state.atInterface)
    }

    @Test fun rejectsIncompleteStatus() {
        assertNull(D2SecurityCore.parseStatus("lock=0, maint=0"))
    }

    @Test fun handlesWhitespaceAndSignedValues() {
        val state = D2SecurityCore.parseStatus("lock = -1; maint = 2; at = 0")
        assertNotNull(state)
        assertEquals(-1, state!!.lock)
    }
}
