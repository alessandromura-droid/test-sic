// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register<Exec>("precompila") {
    group = "data"
    description = "Scarica/aggiorna in percorsi/raw le sorgenti dati per l'app. " +
            "Usa -Pforce=true per riscaricare anche le sorgenti gia' presenti."

    val force = project.hasProperty("force")
    val args = mutableListOf("python3", "scripts/precompila.py")
    if (force) args.add("--force")

    commandLine(args)
}

tasks.register<Exec>("precompilaV2") {
    group = "data"
    description = "Come 'precompila', ma con la pipeline sperimentale v2 " +
            "(collapse_same_source_duplicates + node_lines_globally, vedi " +
            "scripts/build_routing_graph_v2.py). Scrive in percorsi/build/routing_v2/ " +
            "(non tocca l'output di v1) e non pubblica mai su GitHub. Richiede " +
            "'pip3 install -r scripts/requirements.txt' una tantum (Shapely). " +
            "Usa -Pforce=true per riscaricare anche le sorgenti gia' presenti."

    val force = project.hasProperty("force")
    val args = mutableListOf("python3", "scripts/precompila_v2.py")
    if (force) args.add("--force")

    commandLine(args)
}
