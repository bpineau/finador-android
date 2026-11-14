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

    @Test fun confirmedUnsecuredDeviceCanUnlockWithoutEnrollment() {
        assertEquals(UnlockAction.DIRECT, unlockAction(false, UnlockStatus.NOT_ENROLLED))
        assertEquals(UnlockAction.DIRECT, unlockAction(false, UnlockStatus.NO_HARDWARE))
    }

    @Test fun unknownAndErrorStatusesNeverBypassAuthentication() {
        assertEquals(UnlockAction.AUTHENTICATE, unlockAction(false, UnlockStatus.UNKNOWN))
        assertEquals(UnlockAction.RETRY, unlockAction(false, UnlockStatus.ERROR))
    }

    @Test fun aFreshSuccessfulCheckCanRetryAfterAnError() {
        assertEquals(UnlockAction.RETRY, unlockAction(true, UnlockStatus.ERROR))
        assertEquals(UnlockAction.AUTHENTICATE, unlockAction(true, UnlockStatus.AVAILABLE))
    }
}
