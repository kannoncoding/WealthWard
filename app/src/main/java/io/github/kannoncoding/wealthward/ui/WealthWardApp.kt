package io.github.kannoncoding.wealthward.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kannoncoding.wealthward.BuildConfig
import io.github.kannoncoding.wealthward.data.AppContainer
import io.github.kannoncoding.wealthward.data.DashboardSnapshot
import java.math.BigDecimal

private val Pastels = listOf(Color(0xFFE2DCFC), Color(0xFFE7DFFC), Color(0xFFDFE9FC), Color(0xFFD9EDF8), Color(0xFFE2E5F8))
private val Scheme = lightColorScheme(primary = Color(0xFF54427D), onPrimary = Color.White, background = Color(0xFFFAF9FF), surface = Color(0xFFFAF9FF), onSurface = Color(0xFF272238), secondaryContainer = Color(0xFFE2DCFC))
private val Tabs = listOf("Plan", "Movimientos", "Cuentas", "Metas", "Config")

@Composable fun WealthWardApp(container: AppContainer) {
 MaterialTheme(colorScheme = Scheme) {
  var selected by rememberSaveable { mutableIntStateOf(0) }
  var ready by remember { mutableStateOf(false) }
  var failure by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(container) {
   try { container.initializeDemo(); ready = true } catch (_: Exception) { failure = "No se pudieron abrir los datos de demostración." }
  }
  val snapshot by container.dashboard.collectAsStateWithLifecycle(initialValue = DashboardSnapshot(emptyList()))
  Scaffold(bottomBar = {
   NavigationBar { Tabs.forEachIndexed { index, title ->
    NavigationBarItem(modifier = Modifier.semantics { contentDescription = title }, selected = selected == index, onClick = { selected = index }, icon = { Text(listOf("◫", "⇄", "▤", "◎", "⚙")[index]) }, label = { Text(if (index == 1) "Movs." else title, maxLines = 1, style = MaterialTheme.typography.labelSmall) })
   } }
  }) { padding ->
   Column(Modifier.fillMaxSize().background(Scheme.background).padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text("WealthWard", style = MaterialTheme.typography.labelLarge)
    Text(Tabs[selected], style = MaterialTheme.typography.headlineLarge)
    Text("Base de desarrollo · datos ficticios", style = MaterialTheme.typography.bodyMedium)
    if (failure != null) Text(failure!!, color = MaterialTheme.colorScheme.error)
    else if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth())
    when (selected) {
     0 -> {
      InfoCard("Tu plan, por periodo", "Los periodos, versiones y líneas del plan se conservan en la base local. El editor de presupuesto llegará en la siguiente fase.", 0)
      InfoCard("Monedas separadas", "CRC y USD se muestran por cuenta. No se suman monedas ni se aplican conversiones implícitas.", 3)
      AccountCards(snapshot)
     }
     1 -> InfoCard("Movimientos", "La contabilización y las transferencias se validan como operaciones atómicas. La captura de movimientos se implementará en una fase posterior.", 2)
     2 -> AccountCards(snapshot)
     3 -> InfoCard("Metas", "Este espacio está preparado para las metas de ahorro. Su creación y seguimiento se implementarán en una fase posterior.", 1)
     4 -> {
      InfoCard("Diagnóstico", "Versión ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nEntorno ${BuildConfig.BUILD_TYPE}\nAndroid ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}\n${BuildConfig.APPLICATION_ID}", 4)
      InfoCard("Almacenamiento local", "Demostración sin conexión bancaria. La app conserva sus datos entre aperturas. No contiene saldos personales ni credenciales.", 2)
     }
    }
   }
  }
 }
}

@Composable private fun AccountCards(snapshot: DashboardSnapshot) {
 if (snapshot.accounts.isEmpty()) Text("Todavía no hay cuentas cargadas.")
 snapshot.accounts.forEach { account ->
  val amount = BigDecimal.valueOf(account.balance.minor, 2).toPlainString()
  InfoCard(account.name, "${account.currency} $amount\n${account.kind}", 2)
 }
}
@Composable private fun InfoCard(title: String, detail: String, tint: Int) {
 Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Pastels[tint])) {
  Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text(title, style = MaterialTheme.typography.titleMedium)
   Text(detail, style = MaterialTheme.typography.bodyLarge)
  }
 }
}


