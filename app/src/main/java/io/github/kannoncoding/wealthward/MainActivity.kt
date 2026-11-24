package io.github.kannoncoding.wealthward

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.kannoncoding.wealthward.ui.AppLanguage
import io.github.kannoncoding.wealthward.ui.WealthWardApp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  AppLanguage.load(this)
  val container = (application as WealthWardApplication).container
  setContent {
   var launchStage by rememberSaveable { mutableIntStateOf(0) }
   if (launchStage == 2) WealthWardApp(container) else {
    BrandLaunch(launchStage == 0)
    LaunchedEffect(launchStage) {
     withFrameNanos { }
     delay(2_000)
     launchStage += 1
    }
   }
  }
 }
}

@Composable
private fun BrandLaunch(showCat: Boolean) {
 val cat = painterResource(R.drawable.cat_launch_art)
 val wealthWard = painterResource(R.drawable.wealthward_logo)
 BoxWithConstraints(
  Modifier.fillMaxSize().background(if (showCat) Color(0xFF000904) else Color.Black),
  contentAlignment = Alignment.Center,
 ) {
  if (showCat) Image(cat, contentDescription = "CAT Coding Adaptations Technologies",
   modifier = Modifier.fillMaxSize(),
   contentScale = if (maxWidth > maxHeight || maxWidth / maxHeight < .40f) ContentScale.Fit else ContentScale.Crop)
  else Image(wealthWard, contentDescription = "WealthWard",
   modifier = Modifier.size(minOf(maxWidth, maxHeight, 342.dp)).padding(16.dp), contentScale = ContentScale.Fit)
 }
}
