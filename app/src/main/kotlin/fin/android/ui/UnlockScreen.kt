package fin.android.ui

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

@Composable
fun UnlockScreen(vm: AppViewModel, activity: FragmentActivity) {
    var assessment by remember(activity) { mutableStateOf(assessUnlock(activity)) }
    var prompted by rememberSaveable { mutableStateOf(false) }
    var authenticationError by remember { mutableStateOf<String?>(null) }
    val error by vm.onboardError.collectAsStateWithLifecycle()

    // Recreate the callback on activity recreation, but do not restart an existing prompt.
    val prompt = remember(activity, vm) {
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticationError = null
                    vm.unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticationError = "$errString. Tap Unlock to retry."
                }

                override fun onAuthenticationFailed() {
                    authenticationError = "Authentication was not recognized. Try again or use your device lock."
                }
            })
    }

    fun authenticate(authenticators: Int) {
        prompted = true
        authenticationError = null
        try {
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock finador")
                .setSubtitle("Authenticate to decrypt your ledger")
                .setAllowedAuthenticators(authenticators)
                .build())
        } catch (_: Exception) {
            authenticationError = "Authentication could not start. Unlock your device and tap Unlock to retry."
        }
    }

    fun unlock() {
        // A screen lock or sensor's availability may have changed since the screen opened.
        assessment = assessUnlock(activity)
        authenticationError = null
        when (assessment.action) {
            UnlockAction.DIRECT -> vm.unlock()
            UnlockAction.AUTHENTICATE -> authenticate(assessment.authenticators)
            UnlockAction.RETRY -> authenticationError = assessment.message
        }
    }

    LaunchedEffect(activity) {
        if (!prompted && assessment.action == UnlockAction.AUTHENTICATE) {
            authenticate(assessment.authenticators)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "finador",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))
        if (assessment.action == UnlockAction.DIRECT) {
            Text(
                "No biometric or device lock is set up. Unlock directly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = { unlock() }, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }

        (authenticationError ?: error ?: assessment.message)?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { vm.forget() }, modifier = Modifier.fillMaxWidth()) {
            Text("Reconfigure (repo or token)")
        }
    }
}

private data class UnlockAssessment(
    val action: UnlockAction,
    val authenticators: Int = ALLOWED,
    val message: String? = null,
)

private fun assessUnlock(activity: FragmentActivity): UnlockAssessment {
    val secure = runCatching { activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure }.getOrNull()
    val manager = BiometricManager.from(activity)
    val result = runCatching { manager.canAuthenticate(ALLOWED) }.getOrNull()
    // API 30 supports a credentials-only prompt when a sensor is temporarily unavailable.
    if (secure != false && result != BiometricManager.BIOMETRIC_SUCCESS && Build.VERSION.SDK_INT >= 30 &&
        runCatching { manager.canAuthenticate(DEVICE_CREDENTIAL) }.getOrNull() == BiometricManager.BIOMETRIC_SUCCESS) {
        return UnlockAssessment(UnlockAction.AUTHENTICATE, DEVICE_CREDENTIAL)
    }
    val status = when (result) {
        BiometricManager.BIOMETRIC_SUCCESS -> UnlockStatus.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> UnlockStatus.NOT_ENROLLED
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> UnlockStatus.NO_HARDWARE
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> UnlockStatus.UNKNOWN
        else -> UnlockStatus.ERROR
    }
    val action = unlockAction(secure, status)
    val message = if (action == UnlockAction.RETRY)
        "Authentication is unavailable. Check your device lock settings, then tap Unlock to retry." else null
    return UnlockAssessment(action, message = message)
}
