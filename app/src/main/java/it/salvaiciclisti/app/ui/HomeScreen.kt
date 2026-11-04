package it.salvaiciclisti.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app's home: exactly the two buttons asked for, "Mappa" and
 * "Impostazioni" - nothing else. Everything else (route search, source
 * selection) lives one tap away, on its own screen.
 */
@Composable
fun HomeScreen(onOpenMap: () -> Unit, onOpenSettings: () -> Unit) {
    Scaffold { padding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Salvaiciclisti",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 48.dp),
            )
            Button(
                onClick = onOpenMap,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text("Mappa")
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 16.dp),
            ) {
                Text("Impostazioni")
            }
        }
    }
}
