package fin.android.ui

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
    // canAuthenticate decides whether to auto-prompt biometrics. On a device/emulator without any
    // enrolled biometric or device credential, we fall back to a plain "Unlock" button that calls
    // vm.unlock() directly (the secrets are still encrypted at rest via the Keystore master key).
    val available = remember {
        BiometricManager.from(activity).canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS
    }
    var prompted by remember { mutableStateOf(false) }
    val error by vm.message.collectAsStateWithLifecycle()

    fun prompt() {
        prompted = true
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                vm.unlock()
            }
        }
        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock finador")
            .setSubtitle("Authenticate to decrypt your ledger")
            .setAllowedAuthenticators(ALLOWED)
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(available) {
        if (available && !prompted) prompt()
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
        if (available) {
            Button(onClick = { prompt() }, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock with biometrics")
            }
        } else {
            Text(
                "No biometric or device lock is set up. Unlock directly.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.unlock() }, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock")
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { vm.forget() }) {
            Text("Reconfigure (repo or token)")
        }
    }
}
