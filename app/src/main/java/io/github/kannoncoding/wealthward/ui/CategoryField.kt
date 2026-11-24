package io.github.kannoncoding.wealthward.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kannoncoding.wealthward.domain.LedgerCategory
import io.github.kannoncoding.wealthward.domain.categoryKey

@Composable
internal fun Fields.category(
 key: String,
 label: String,
 categories: List<LedgerCategory>,
 default: String = "",
) {
 text(key, label, default)
 var expanded by remember(key, draft.scope) { mutableStateOf(false) }
 Box {
  TextButton(onClick = { expanded = true }, enabled = !busy) {
   Text(localized("Elegir categoría para {0}", label))
  }
  DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
   categories.sortedBy { categoryKey(it.name) }.forEach { category ->
    DropdownMenuItem(text = { Text(category.name) }, onClick = {
     draft.set(key, category.name)
     expanded = false
    })
   }
   DropdownMenuItem(text = { Text(localized("Crear nueva categoría")) }, onClick = {
    draft.set(key, "")
    expanded = false
   })
  }
 }
}
