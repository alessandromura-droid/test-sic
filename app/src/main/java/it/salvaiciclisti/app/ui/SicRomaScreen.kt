package it.salvaiciclisti.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Terza voce della barra in basso (icona bicicletta, etichetta "SiC-Roma").
 * Segnaposto: Alessandro non ha ancora specificato i contenuti di questa
 * sezione, quindi per ora mostra solo un avviso esplicito - meglio una
 * schermata vuota ma dichiarata tale, che una sezione silenziosamente
 * mancante o riempita a caso con contenuti indovinati.
 */
@Composable
fun SicRomaScreen() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "SiC-Roma\n\nContenuto di questa sezione ancora da definire.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
