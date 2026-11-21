import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "it.salvaiciclisti.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "it.salvaiciclisti.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.osmdroid.android)
    implementation(libs.jgrapht.core)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Flag da riga di comando (./gradlew testDebugUnitTest installDebug -Pforce)
// per NON bloccare l'installazione quando RouteCoherenceTest trova percorsi
// incoerenti: il test stampa comunque la percentuale di percorsi incoerenti
// (cosi' ci si regola), ma non fa fallire la build. Di default (senza
// -Pforce) il test resta bloccante come sempre - questo e' solo un modo
// rapido per installare comunque sul telefono dopo aver gia' letto il
// report ed essersi convinti che i falliti sono falsi allarmi.
tasks.withType<Test>().configureEach {
    systemProperty("routeCoherence.force", project.hasProperty("force").toString())
    // Numero di coppie di prova di RouteCoherenceTest: ./gradlew testDebugUnitTest -PnumPercorsi=30
    systemProperty("routeCoherence.pairs", (project.findProperty("numPercorsi") ?: "10").toString())
    // Seme delle coppie di prova (default: nuovo a ogni esecuzione, stampato dal test):
    // ./gradlew testDebugUnitTest --rerun -PseedPercorsi=123456 rifa' le stesse coppie
    systemProperty("routeCoherence.seed", (project.findProperty("seedPercorsi") ?: "").toString())
    // Distanza massima (km, in linea d'aria) fra partenza e arrivo delle coppie di prova:
    // ./gradlew testDebugUnitTest --rerun -PmaxDist=5   (default: nessun limite)
    systemProperty("routeCoherence.maxDistKm", (project.findProperty("maxDist") ?: "").toString())
}

// Report HTML dei test (build/reports/tests/...): il messaggio di errore di
// RouteCoherenceTest contiene per ogni percorso incoerente il suffisso
// "(GPX: nome.gpx)", ma Gradle lo scrive come semplice testo. Questo task
// (Alessandro, 2026-10-13) lo riscrive in un hyperlink relativo al file in
// build/test-outputs/route-coherence/, cosi' basta cliccare nel report.
// E' un finalizer dei task di test, quindi gira anche quando il test FALLISCE
// (il caso in cui il report serve di piu'; un doLast sul test non girerebbe).
// Idempotente: dopo la prima riscrittura il testo "(GPX: " non c'e' piu'.
val linkTestReportGpx by tasks.registering {
    val buildDirProvider = layout.buildDirectory.asFile
    doLast {
        val buildDir = buildDirProvider.get()
        val reportsRoot = File(buildDir, "reports/tests")
        val gpxRoot = File(buildDir, "test-outputs/route-coherence")
        if (!reportsRoot.exists()) return@doLast
        val gpxPattern = Regex("""\(GPX: ([^)<>]+\.gpx)\)""")
        reportsRoot.walkTopDown()
            .filter { it.isFile && it.extension == "html" }
            .forEach { html ->
                val text = html.readText()
                if (!text.contains("(GPX: ")) return@forEach
                val updated = gpxPattern.replace(text) { match ->
                    val name = match.groupValues[1]
                    val rel = html.parentFile.toPath()
                        .relativize(File(gpxRoot, name).toPath())
                        .toString()
                        .replace(File.separatorChar, '/')
                    "(<a href=\"$rel\">GPX: $name</a>)"
                }
                if (updated != text) html.writeText(updated)
            }
    }
}

tasks.withType<Test>().configureEach {
    finalizedBy(linkTestReportGpx)
}

// (2026-11-20) Mappa piu' recente dentro l'APK: per ora i dati non si
// pubblicano su GitHub (Alessandro). Prima di ogni build copia in
// src/main/assets/routing/ (esclusa dal repository, vedi .gitignore):
// graph.json e sources_manifest.json dalla prima cartella della pipeline che li
// ha entrambi (routing_v4, poi routing_v3, poi routing), indirizzi.json e
// layers.json dalla prima che li ha; toglie da li' ogni altro file (le mappe
// vecchie incluse a suo tempo) e scrive bundle_version.txt (sha256 dei
// contenuti, 16 caratteri). L'app (BundledMapData.kt) installa questi dati al
// primo avvio di ogni APK con una mappa diversa. Riscrive un file solo se il
// contenuto cambia, cosi' le build successive non rifanno il merge degli asset.
val copiaMappaNellApk by tasks.registering {
    val percorsiBuild = rootProject.file("percorsi/build")
    val dest = file("src/main/assets/routing")
    outputs.dir(dest)
    outputs.upToDateWhen { false }
    doLast {
        val ordine = listOf("routing_v4", "routing_v3", "routing").map { File(percorsiBuild, it) }
        val grafoDir = ordine.firstOrNull {
            File(it, "graph.json").isFile && File(it, "sources_manifest.json").isFile
        } ?: throw GradleException(
            "mappa nell'APK: nessuna cartella con graph.json e sources_manifest.json in $percorsiBuild " +
                "(routing_v4, routing_v3, routing): eseguire prima la pipeline",
        )
        val scelti = linkedMapOf(
            "graph.json" to File(grafoDir, "graph.json"),
            "sources_manifest.json" to File(grafoDir, "sources_manifest.json"),
        )
        // (2026-11-20) grafo heat map: dalla stessa cartella del grafo, solo se
        // ci sono entrambi i file (altrimenti l'app usa solo i raccordi)
        val hmGrafo = File(grafoDir, "graph_heatmap.json")
        val hmManifest = File(grafoDir, "sources_manifest_heatmap.json")
        if (hmGrafo.isFile && hmManifest.isFile) {
            scelti["graph_heatmap.json"] = hmGrafo
            scelti["sources_manifest_heatmap.json"] = hmManifest
        } else {
            logger.warn("mappa nell'APK: graph_heatmap.json/sources_manifest_heatmap.json non trovati in ${grafoDir.name}, grafo heat map non incluso")
        }
        for (nome in listOf("indirizzi.json", "layers.json")) {
            val f = ordine.map { File(it, nome) }.firstOrNull { it.isFile }
            if (f != null) scelti[nome] = f else logger.warn("mappa nell'APK: $nome non trovato, non incluso")
        }
        dest.mkdirs()
        dest.listFiles()
            ?.filter { it.isFile && it.name !in scelti.keys && it.name != "bundle_version.txt" }
            ?.forEach { it.delete() }
        val md = MessageDigest.getInstance("SHA-256")
        for ((nome, src) in scelti) {
            val bytes = src.readBytes()
            md.update(nome.toByteArray())
            md.update(bytes)
            val out = File(dest, nome)
            if (!out.isFile || !out.readBytes().contentEquals(bytes)) out.writeBytes(bytes)
            logger.lifecycle("mappa nell'APK: $nome <- ${src.parentFile.name}/${src.name}")
        }
        val versione = md.digest().joinToString("") { "%02x".format(it) }.take(16)
        val vf = File(dest, "bundle_version.txt")
        if (!vf.isFile || vf.readText() != versione) vf.writeText(versione)
        logger.lifecycle("mappa nell'APK: versione $versione")
    }
}

tasks.named("preBuild") {
    dependsOn(copiaMappaNellApk)
}
