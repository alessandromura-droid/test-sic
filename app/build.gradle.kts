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
