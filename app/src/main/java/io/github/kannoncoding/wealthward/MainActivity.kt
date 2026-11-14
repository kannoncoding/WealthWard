package io.github.kannoncoding.wealthward

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.kannoncoding.wealthward.ui.WealthWardApp

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val container = (application as WealthWardApplication).container
  setContent { WealthWardApp(container) }
 }
}
