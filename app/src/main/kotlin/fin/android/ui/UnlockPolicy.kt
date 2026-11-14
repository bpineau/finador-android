package fin.android.ui

internal enum class UnlockStatus { AVAILABLE, NOT_ENROLLED, NO_HARDWARE, UNKNOWN, ERROR }

internal enum class UnlockAction { AUTHENTICATE, DIRECT, RETRY }

/** An unavailable authenticator does not establish that the device has no screen lock. */
internal fun unlockAction(deviceSecure: Boolean?, status: UnlockStatus): UnlockAction =
    when {
        status == UnlockStatus.AVAILABLE || status == UnlockStatus.UNKNOWN -> UnlockAction.AUTHENTICATE
        deviceSecure == false && (status == UnlockStatus.NOT_ENROLLED || status == UnlockStatus.NO_HARDWARE) ->
            UnlockAction.DIRECT
        else -> UnlockAction.RETRY
    }
