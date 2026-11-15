package fin.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UnlockPolicyTest {
    @Test fun securedDevicesNeverOfferDirectUnlock() {
        UnlockStatus.entries.forEach { status ->
            assertNotEquals(status.name, UnlockAction.DIRECT, unlockAction(true, status))
        }
    }

    @Test fun unknownScreenLockStateNeverOffersDirectUnlock() {
        UnlockStatus.entries.forEach { status ->
            assertNotEquals(status.name, UnlockAction.DIRECT, unlockAction(null, status))
        }
    }

    // No screen lock means no credential: a prompt could only fail, whatever the sensor reports.
    @Test fun confirmedUnsecuredDeviceAlwaysUnlocksDirectly() {
        UnlockStatus.entries.forEach { status ->
            assertEquals(status.name, UnlockAction.DIRECT, unlockAction(false, status))
        }
    }

    @Test fun unknownAndErrorStatusesNeverBypassAuthenticationOnASecuredDevice() {
        assertEquals(UnlockAction.AUTHENTICATE, unlockAction(true, UnlockStatus.UNKNOWN))
        assertEquals(UnlockAction.RETRY, unlockAction(true, UnlockStatus.NOT_ENROLLED))
        assertEquals(UnlockAction.RETRY, unlockAction(null, UnlockStatus.ERROR))
    }

    @Test fun aFreshSuccessfulCheckCanRetryAfterAnError() {
        assertEquals(UnlockAction.RETRY, unlockAction(true, UnlockStatus.ERROR))
        assertEquals(UnlockAction.AUTHENTICATE, unlockAction(true, UnlockStatus.AVAILABLE))
    }
}
