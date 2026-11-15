package fin.android.ui

internal enum class UnlockStatus { AVAILABLE, NOT_ENROLLED, NO_HARDWARE, UNKNOWN, ERROR }

internal enum class UnlockAction { AUTHENTICATE, DIRECT, RETRY }

/**
 * An unavailable authenticator does not establish that the device has no screen lock, so only a
 * confirmed unsecured device unlocks directly. There, whatever the biometric status says, no
 * credential exists to authenticate against: a prompt can only fail, and refusing would lock the
 * holder out of their own ledger for no protection.
 */
internal fun unlockAction(deviceSecure: Boolean?, status: UnlockStatus): UnlockAction =
    when {
        deviceSecure == false -> UnlockAction.DIRECT
        status == UnlockStatus.AVAILABLE || status == UnlockStatus.UNKNOWN -> UnlockAction.AUTHENTICATE
        else -> UnlockAction.RETRY
    }
