package io.github.kannoncoding.wealthward

import android.app.Application
import io.github.kannoncoding.wealthward.data.AppContainer

class WealthWardApplication : Application() {
 val container by lazy { AppContainer(this) }
}
