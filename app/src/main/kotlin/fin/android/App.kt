package fin.android

import android.app.Application
import fin.android.data.AppContainer

class App : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
