package it.salvaiciclisti.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

private const val TAG = "SiCDownload"
private const val ASSET_DIR = "routing"
private const val PREFS_NAME = "salvaiciclisti_mapdata"
private const val KEY_BUNDLE_INSTALLED = "bundle_version_installed"

/**
 * (2026-11-20) Mappa dentro l'APK. Alessandro non vuole per ora pubblicare i
 * dati su GitHub: la build (task copiaMappaNellApk in app/build.gradle.kts)
 * copia in assets/routing/ la mappa piu' recente della pipeline e scrive
 * bundle_version.txt.
 *
 * All'avvio ([installIfNeeded], chiamata da MapDataGate prima di controllare i
 * dati locali) i file dell'APK vengono copiati nella cartella privata dell'app
 * se la loro versione e' diversa dall'ultima installata da un APK, o se mancano
 * i dati locali. Quindi un APK nuovo porta sempre la sua mappa, anche sopra dati
 * scaricati in precedenza; un aggiornamento scaricato dopo (se il server tornera'
 * disponibile) resta finche' non si installa un APK con una mappa diversa.
 * graph.json e sources_manifest.json sono obbligatori e installati insieme (file
 * temporanei rinominati solo alla fine); indirizzi.json e layers.json solo se
 * presenti nell'APK; graph_heatmap.json e sources_manifest_heatmap.json
 * (2026-11-20) insieme, solo se presenti entrambi, altrimenti cancellati.
 */
object BundledMapData {
    private val REQUIRED = listOf("graph.json", "sources_manifest.json")
    private val OPTIONAL = listOf("indirizzi.json", "layers.json")
    // (2026-11-20) Grafo heat map: i due file insieme; se l'APK non li ha, si
    // cancellano quelli eventualmente installati da un APK precedente.
    private val HEATMAP = listOf("graph_heatmap.json", "sources_manifest_heatmap.json")

    /** Da chiamare su un thread di background (I/O su disco). Non lancia mai
     * eccezioni: un errore viene solo registrato nel log. */
    fun installIfNeeded(context: Context) {
        runCatching {
            val bundleVersion = readBundleVersion(context) ?: return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val installed = prefs.getString(KEY_BUNDLE_INSTALLED, null)
            if (installed == bundleVersion && AssetRepository.hasLocalData(context)) return

            val dir = File(context.filesDir, "routing").apply { mkdirs() }
            val tempToFinal = mutableListOf<Pair<File, File>>()
            for (name in REQUIRED) {
                val tmp = File(dir, "$name.tmp")
                copyAsset(context, name, tmp)
                tempToFinal.add(tmp to File(dir, name))
            }
            for ((tmp, dest) in tempToFinal) {
                if (!tmp.renameTo(dest)) throw IllegalStateException("impossibile installare ${dest.name}")
            }
            for (name in OPTIONAL) {
                runCatching {
                    val tmp = File(dir, "$name.tmp")
                    copyAsset(context, name, tmp)
                    if (!tmp.renameTo(File(dir, name))) throw IllegalStateException("impossibile installare $name")
                }.onFailure { e ->
                    if (e !is FileNotFoundException) Log.w(TAG, "mappa dell'APK: $name non installato: $e")
                }
            }
            runCatching {
                val pairs = HEATMAP.map { File(dir, "$it.tmp") to File(dir, it) }
                try {
                    for ((tmp, _) in pairs) copyAsset(context, tmp.name.removeSuffix(".tmp"), tmp)
                } catch (e: FileNotFoundException) {
                    pairs.forEach { (tmp, dest) -> tmp.delete(); dest.delete() }
                    return@runCatching
                }
                for ((tmp, dest) in pairs) {
                    if (!tmp.renameTo(dest)) throw IllegalStateException("impossibile installare ${dest.name}")
                }
            }.onFailure { e ->
                Log.w(TAG, "mappa dell'APK: grafo heat map non installato: $e")
                HEATMAP.forEach { File(dir, it).delete() }
            }
            prefs.edit().putString(KEY_BUNDLE_INSTALLED, bundleVersion).apply()
            RemoteMapDataRepository.setLocalVersion(context, bundleVersion)
            AssetRepository.invalidate()
            Log.d(TAG, "mappa dell'APK installata, versione $bundleVersion")
        }.onFailure { e ->
            Log.w(TAG, "installazione della mappa dell'APK fallita: $e")
        }
    }

    /** Null se l'APK non contiene una mappa. */
    private fun readBundleVersion(context: Context): String? = runCatching {
        context.assets.open("$ASSET_DIR/bundle_version.txt").bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun copyAsset(context: Context, name: String, dest: File) {
        context.assets.open("$ASSET_DIR/$name").use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
    }
}
