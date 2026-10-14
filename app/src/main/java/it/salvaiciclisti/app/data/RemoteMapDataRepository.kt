package it.salvaiciclisti.app.data

import android.content.Context
import android.util.Log
import it.salvaiciclisti.app.json.SimpleJson
import it.salvaiciclisti.app.json.obj
import it.salvaiciclisti.app.json.str
import it.salvaiciclisti.app.json.strOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SiCDownload"

/** Repo pubblico scelto da Alessandro il 2026-09-21 - vedi upload_to_github()
 * in scripts/precompila.py, che pubblica su una release fissa (tag "dati")
 * di questo stesso repo. Nessuna credenziale qui: sono asset pubblici di una
 * release, scaricabili con una GET anonima come un browser qualunque. */
private const val GITHUB_OWNER_REPO = "alessandromura-droid/test-sic"
private const val RELEASE_TAG = "dati"
private const val RELEASE_BASE_URL =
    "https://github.com/$GITHUB_OWNER_REPO/releases/download/$RELEASE_TAG"

private const val PREFS_NAME = "salvaiciclisti_mapdata"
private const val KEY_LOCAL_VERSION = "local_version"

/**
 * Scarica graph.json/sources_manifest.json/indirizzi.json (+ layers.json,
 * vedi sotto) da GitHub Releases (pubblicati da scripts/precompila.py)
 * dentro lo storage privato dell'app - Alessandro (2026-09-21) non vuole
 * questi dati bundlati nell'APK. Nessuna libreria HTTP aggiunta:
 * HttpURLConnection di serie, stesso spirito "nessuna dipendenza esterna"
 * degli script Python che li generano.
 *
 * "Versione" e' l'hash sha256 (troncato a 16 caratteri) che precompila.py
 * calcola sui contenuti non compressi - confrontarla con quella scaricata
 * l'ultima volta dice se c'e' un aggiornamento senza dover riscaricare
 * 30+ MB solo per saperlo (si scarica prima il solo data_manifest.json,
 * poche centinaia di byte, mai gzippato apposta per questo).
 */
object RemoteMapDataRepository {

    /** I tre file senza cui l'app non puo' funzionare: tutti o nessuno (vedi
     * [downloadAndInstall]) - un download a meta' non deve mai lasciare un
     * grafo disallineato dall'indice indirizzi. */
    private val REQUIRED_ASSETS = mapOf(
        "graph.json.gz" to "graph.json",
        "sources_manifest.json.gz" to "sources_manifest.json",
        "indirizzi.json.gz" to "indirizzi.json",
    )

    /** Layer di dettaglio (bike box, rastrelliere, punti critici... - vedi
     * build_detail_layers() in build_routing_graph.py, 2026-10-02): a
     * differenza dei tre sopra, la loro ASSENZA non deve mai far fallire
     * l'intero aggiornamento - un Alessandro che non ha ancora ripubblicato
     * dopo questa modifica (data_manifest.json/asset vecchi, senza
     * layers.json.gz) deve continuare a scaricare grafo/indirizzi come
     * sempre, solo senza i layer di dettaglio finche' non ripubblica. */
    private val OPTIONAL_ASSETS = mapOf(
        "layers.json.gz" to "layers.json",
    )

    data class RemoteManifest(val version: String, val builtAt: String?)

    /** Null se non e' stato possibile contattare il server (offline, DNS,
     * timeout, 404 - es. precompila.py non ha ancora pubblicato nulla): i
     * chiamanti lo trattano sempre come "nessun controllo possibile ora",
     * mai come un errore fatale. */
    suspend fun fetchRemoteManifest(): RemoteManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val text = httpGetText("$RELEASE_BASE_URL/data_manifest.json")
            val obj = SimpleJson.parse(text).obj()
            RemoteManifest(
                version = obj.getValue("version").str(),
                builtAt = obj["built_at"]?.strOrNull(),
            )
        }.onFailure { e ->
            Log.d(TAG, "fetchRemoteManifest fallita (probabile assenza di rete): $e")
        }.getOrNull()
    }

    fun getLocalVersion(context: Context): String? =
        prefs(context).getString(KEY_LOCAL_VERSION, null)

    /**
     * Scarica ed installa i file richiesti ([REQUIRED_ASSETS]), poi tenta
     * (best-effort, vedi [OPTIONAL_ASSETS]) quelli facoltativi. Scrive prima
     * in file temporanei ("$nome.tmp") e li rinomina solo dopo che TUTTI i
     * download RICHIESTI sono riusciti: un download interrotto a meta' (es.
     * rete caduta) non deve mai lasciare l'app con un grafo corrotto o
     * disallineato dall'indice indirizzi - i file precedenti restano intatti
     * finche' i nuovi non sono tutti pronti. Un asset facoltativo che fallisce
     * (rete, o non ancora pubblicato) non tocca questo esito: si prova, e se
     * non va semplicemente non si aggiorna quel layer, loggato ma non fatale.
     */
    suspend fun downloadAndInstall(context: Context, version: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val routingDir = File(context.filesDir, "routing").apply { mkdirs() }
                val tempToFinal = mutableListOf<Pair<File, File>>()

                for ((assetName, localName) in REQUIRED_ASSETS) {
                    val tempFile = File(routingDir, "$localName.tmp")
                    downloadGzipTo("$RELEASE_BASE_URL/$assetName", tempFile)
                    tempToFinal.add(tempFile to File(routingDir, localName))
                }
                for ((temp, dest) in tempToFinal) {
                    if (!temp.renameTo(dest)) {
                        throw IllegalStateException("impossibile installare ${dest.name} (rename fallita)")
                    }
                }
                prefs(context).edit().putString(KEY_LOCAL_VERSION, version).apply()

                for ((assetName, localName) in OPTIONAL_ASSETS) {
                    runCatching {
                        val tempFile = File(routingDir, "$localName.tmp")
                        downloadGzipTo("$RELEASE_BASE_URL/$assetName", tempFile)
                        if (!tempFile.renameTo(File(routingDir, localName))) {
                            throw IllegalStateException("impossibile installare $localName (rename fallita)")
                        }
                    }.onFailure { e ->
                        Log.d(TAG, "asset facoltativo '$assetName' non installato (non bloccante): $e")
                    }
                }

                AssetRepository.invalidate()
            }.onFailure { e ->
                Log.w(TAG, "downloadAndInstall fallito: $e")
            }
        }

    private fun downloadGzipTo(url: String, dest: File) {
        val connection = openConnection(url, connectTimeoutMs = 20_000, readTimeoutMs = 30_000)
        try {
            // I file sono pubblicati come gzip "puro" (gzip.compress() lato
            // precompila.py, non Content-Encoding HTTP): si decomprime qui
            // il corpo della risposta cosi' com'e'.
            connection.inputStream.use { raw ->
                GZIPInputStream(raw).use { gzip ->
                    dest.outputStream().use { out -> gzip.copyTo(out) }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGetText(url: String): String {
        val connection = openConnection(url, connectTimeoutMs = 10_000, readTimeoutMs = 15_000)
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        return connection
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
