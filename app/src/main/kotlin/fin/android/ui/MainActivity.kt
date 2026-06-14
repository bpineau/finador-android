package fin.android.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Single-activity host. Extends [FragmentActivity] so [androidx.biometric.BiometricPrompt] works
 * (it needs a FragmentActivity / fragment host).
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinadorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: AppViewModel = viewModel(factory = AppViewModel.Factory)
                    AppRoot(vm = vm, activity = this)
                }
            }
        }
    }
}
