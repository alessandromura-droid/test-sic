#!/usr/bin/env python3
"""
build_routing_graph_v3.py - VARIANTE SPERIMENTALE di build_routing_graph_v2.py
(2026-10-03, "vai" di Alessandro dopo la discussione su neatnet). v2 resta
INTOCCATA (come v1 lo e' rimasta per v2): questo file aggiunge UN solo
cambiamento architetturale, un interruttore a tre modalita' `use_neatnet`
(esteso il 2026-10-03 da booleano a tre valori, su richiesta di Alessandro:
"inseriamo una terza opzione, che neatnet sia prima e poi il nostro sw"):

  - use_neatnet = disable (default; alias retrocompatibile: false): la
    passata ISOLATA per sorgente non gira piu' una volta sola
    (A->B->B2->C->D), ma IN LOOP, ripetendo l'intera sequenza finche' il
    risultato non converge (stesso arco-set, non solo stesso conteggio) o
    si raggiunge `source_pass_max_iterations` - pensato apposta per il bug
    noto di D (gira in un solo passaggio: un taglio fatto da un nodo
    diverso nella stessa chiamata non viene mai riconsiderato) senza dover
    riscrivere D stesso. Alessandro, 2026-10-03: "e' rischioso, lo so,
    voglio vedere che succede" - sperimentale, nessuna garanzia di
    convergenza, da qui la stampa esaustiva passo per passo (vedi piu'
    sotto) per osservarla (o la sua assenza) direttamente.

  - use_neatnet = enable (alias retrocompatibile: true): la passata isolata
    per sorgente usa prima la libreria neatnet (github.com/uscuni/neatnet,
    vedi scripts/requirements_v3.txt) per ripulire le linee grezze di
    QUESTA sorgente - MAI su piu' sorgenti insieme: neatnet non ha alcun
    concetto di "non fondere mai archi di sorgenti diverse", quindi usarla
    sul grafo globale romperebbe quella garanzia in silenzio (valutato e
    scartato il 2026-10-03). Quello che neatnet produce passa comunque,
    SEMPRE, per la nostra pipeline A->B->B2->C->D normale (UNA sola volta,
    non in loop) - "usala ma aggiungi quello che manca" (Alessandro,
    2026-10-03): non e' verificato se neatnet nodifica gia' da sola i
    semplici incroci a X fuori dai suoi "artefatti" rilevati, quindi la
    nostra C (e le altre) girano comunque sopra come rete di sicurezza, i
    contatori stampati mostrano quanto lavoro le resta davvero da fare.

  - use_neatnet = both (nuovo, 2026-10-03): neatnet PRIMA, come in
    "enable", ma poi NON una singola passata - il LOOP completo di
    "disable" sul risultato di neatnet, fino a convergenza o al tetto di
    sicurezza. Rete di sicurezza piu' a fondo di una singola passata,
    pensata per i casi (vedi analisi del 2026-10-03 sul residuo di
    estremi agganciati che non convergeva mai in "disable" puro, dovuto al
    disallineamento fra dangle_tolerance_m e node_cluster_max_diameter_m)
    dove serve sia la pulizia di neatnet sia piu' di un giro del nostro
    codice per stabilizzarsi.

La passata GLOBALE (multi-sorgente) resta ESATTAMENTE quella di v2, senza
eccezioni: solo B, C e D (mai B2, mai neatnet, mai in loop) - Alessandro,
2026-10-03: "il passaggio multimappa rimane invariato, niente neatnet,
niente loop".

Stesso identico precompila_parametri.txt di v2 (funziona retroattivamente:
un parametro nuovo assente nel file resta al suo default, come sempre) -
Alessandro, 2026-10-03: "il file parametri puo' rimanere cosi'".

Schema A/B/B2/C/D invariato rispetto a v2 (vedi li' per i dettagli di
ciascun passo); qui solo le novita' sopra.

Ogni sorgente viene elaborata PRIMA DA SOLA (con use_neatnet o col loop),
poi tutte le sorgenti gia' pulite vengono messe insieme e si ripetono SOLO
B, C e D (mai B2) - ESATTAMENTE come in v2:

  A) build_nodes_from_endpoints(): un nodo e' SOLO E SOLTANTO l'inizio o la
     fine di un arco - nessun vertice interno, nessun incrocio ancora
     (quello e' il passo C, deliberatamente separato).

  B) cluster_merge_nodes(): fonde i nodi vicini con un clustering
     gerarchico a diametro limitato (NODE_CLUSTER_MAX_DIAMETER_M) -
     simmetrico, indipendente dall'ordine di elaborazione (sostituisce la
     vecchia NodeGrid ad "ancora fissa", che dipendeva da quale sorgente
     veniva elaborata per prima).

  B2) collapse_degree2_nodes(): un nodo con ESATTAMENTE due archi incidenti
      scompare, fondendo i due archi in uno solo (nome = concatenazione dei
      due nomi) - mai un vero punto di decisione per chi pedala. SOLO entro
      la stessa sorgente: mai ripetuta nella passata globale, altrimenti
      fonderebbe archi di sorgenti diverse in uno solo "senza sorgente".

  C) create_crossing_nodes(): crea un nodo vero (e taglia subito gli archi)
     dove un arco incrocia geometricamente un altro, fino a
     MAX_CROSSINGS_PER_PAIR volte - oltre, quasi certo doppione/
     parallelismo digitalizzato, la coppia e' scartata per intero.

  D) create_dangle_nodes(): ultimo passo, aggiunto il 2026-10-03 dopo uno
     screenshot reale di Alessandro ("vedi che c'e' un nodo vicino ad un
     arco e non e' unito"). Collega un'estremita' "a vuoto" di un arco al
     tracciato di un altro arco quando cade a meno di DANGLE_TOLERANCE_M
     da esso senza toccarlo esattamente (il caso C lo avrebbe gia' preso) -
     tipicamente due sorgenti che digitalizzano lo stesso incrocio con un
     piccolo scarto (precedente storico "Porta San Giovanni"). Si ripete
     anche nella passata globale, dove questo caso e' il piu' frequente.

Niente piu' deduplica di linee intere per copertura geometrica (la vecchia
collapse_same_source_duplicates): Alessandro, 2026-10-02, e' restio a
cancellare tracce sovrapposte ("potrebbero divergere dopo un certo punto") -
due archi che corrono uno sopra l'altro restano semplicemente due archi
paralleli fra le stesse coppie di nodi, non vengono piu' cancellati.

Richiede Shapely e Pillow (vedi scripts/requirements.txt, invariato da v2)
sempre, in ogni caso - l'unica dipendenza esterna della passata in loop
(use_neatnet=false) e di tutta la passata globale. Con use_neatnet=true
servono IN PIU' geopandas, neatnet e pyproj (scripts/requirements_v3.txt) -
importate solo al bisogno dentro _neatnet_preprocess(), cosi' chi usa solo
il loop non deve installarle. Scrive in una cartella SEPARATA (ASSETS_DIR
qui sotto, routing_v3/) apposta per non toccare l'output di v1 ne' di v2
finche' Alessandro non promuove questa versione a mano.

Uso:
    pip3 install -r scripts/requirements.txt      # una tantum (sempre)
    pip3 install -r scripts/requirements_v3.txt   # una tantum (solo se usi use_neatnet=true)
    python3 scripts/build_routing_graph_v3.py
"""
import io
import json
import math
import re
import time
import urllib.error
import urllib.request
from collections import defaultdict
from pathlib import Path

try:
    from shapely.geometry import LineString, Point
    from shapely.ops import substring
    from shapely.strtree import STRtree
except ImportError as _e:
    raise SystemExit(
        "build_routing_graph_v3.py richiede Shapely, non installato in questo "
        "ambiente Python. Esegui prima:\n"
        "    pip3 install -r scripts/requirements.txt\n"
        f"(errore originale: {_e})"
    )

try:
    from PIL import Image, ImageDraw
except ImportError as _e:
    raise SystemExit(
        "build_routing_graph_v3.py richiede Pillow (per le immagini di debug "
        "per sorgente), non installato in questo ambiente Python. Esegui prima:\n"
        "    pip3 install -r scripts/requirements.txt\n"
        f"(errore originale: {_e})"
    )

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "percorsi" / "raw"
# v3 scrive in una cartella SEPARATA sia da build_routing_graph.py (v1,
# routing/) sia da build_routing_graph_v2.py (v2, routing_v2/): finche'
# Alessandro non promuove questa versione a mano, v1 e v2 restano del
# tutto intoccate da questo file sperimentale.
ASSETS_DIR = ROOT / "percorsi" / "build" / "routing_v3"

# Cartelle sotto percorsi/raw/ che contengono geometrie effettivamente
# instradabili. I segnaposto (piano_150km, biciplan_pdf_ufficiale) non hanno
# file .geojson e vengono quindi ignorati automaticamente.
SOURCE_LABELS = {
    "rsm_piste_ciclabili": "RSM - piste ciclabili ufficiali",
    "osm_extract": "OpenStreetMap - infrastruttura ciclabile",
    "g3w_ciclovie_metropolitane": "Citta' Metropolitana - progetti ciclovie",
    "salvaiciclisti_sicrm": "SIC",
    "salvaiciclisti_ciclopolitana": "Salvaiciclisti - Ciclopolitana (rete concettuale, non e' un tracciato reale)",
    # Mappa supplementare (Alessandro, 2026-10-13): tratti di strada OSM che
    # collegano le componenti di SIC, eliminano i giri lunghi e scavalcano le
    # barriere per partenza/arrivo. NON scaricata da precompila: la genera
    # scripts/raccordi.py in percorsi/raw/raccordi/ (vedi il suo docstring);
    # precompila_v3.py ne verifica l'impronta prima di costruire il grafo.
    "raccordi": "Raccordi - tratti di strada che collegano la rete SIC",
    # Sorgente LOCALE di debug (Alessandro, 2026-10-03: "la sorgente 'debug'
    # deve entrare come tutte le altre [...] il precompila non deve 'sapere'
    # che e' un test. deve pensare che sia una mappa, semplicemente molto
    # semplice, di percorsi ciclabili"): un sottoinsieme minimo di archi
    # REALI (tipicamente 2: l'arco su cui un nodo dangling si aggancia o
    # dovrebbe agganciarsi, e l'arco che crea il nodo dangling stesso),
    # estratto a mano con scripts/extract_debug_subset.m per riprodurre un
    # bug isolandolo dal rumore delle migliaia di altri archi di SIC. La sua
    # fetch (fetch_debug() in precompila_v3.py) non tocca la rete - legge
    # scripts/debug_fixtures/debug_input.json e lo riscrive come .geojson
    # sotto percorsi/raw/debug/ - ma per il resto della pipeline (qui,
    # iter_line_sources(), load_lines(), process_debug in
    # precompila_parametri.txt) e' indistinguibile da una sorgente vera.
    "debug": "Debug - sottoinsieme minimo estratto a mano per isolare un bug (non e' una sorgente reale)",
}

# Layer dentro percorsi/raw/salvaiciclisti_sicrm/ che non sono piste rilevate
# ma la mappa-concept "Ciclopolitana di Roma": linee schematiche in stile
# mappa della metro (nomi "L1", "L2 -L4", "TAN1"...) tra capolinea lontani,
# digitalizzate con pochi vertici su tratte di 10-20 km (uno dei due file si
# autoetichetta pure "Tipologia": "Ciclopolitane" nei dati). Trattate come
# pista vera, il router le sceglie sempre come scorciatoia "gratis" tra un
# capo all'altro della citta', producendo i salti assurdi che
# RouteCoherenceTest cattura. Non le si esclude dal grafo: qui sotto vengono
# solo ritaggiate su una sorgente propria (vedi load_lines), cosi' e' scelta
# dell'utente dalle Impostazioni se usarle o no, non una decisione presa qui
# in silenzio.
CICLOPOLITANA_FILENAMES = {
    "Ciclopolitana_di_Roma_2021.geojson",
    "Ciclopolitane_Ciclonvallazioni.geojson",
}

# File da NON trattare come layer di dati (dump grezzi intermedi, non feature
# collection pulite da usare per il grafo).
#
# igm_010109_tr_str_tratto_stradale.geojson (dentro g3w_ciclovie_metropolitane)
# e' la rete stradale generica IGM ("Circonvallazione Tiburtina", "strada
# urbana di scorrimento", "rampa/svincolo"...), non un progetto di ciclovia:
# il portale G3W della Citta' Metropolitana la espone insieme ai 124 layer
# di piste vere e propri di quella fonte, ma non e' instradabile in bici e va
# contro l'intento dichiarato sopra ("nessuna rete stradale generica di
# supporto"). E' anche enorme (137MB / 144.232 feature, contro i ~13MB di
# tutti gli altri layer g3w messi insieme) - da sola gonfiava il grafo
# dell'88% in piu' di archi, causando OutOfMemoryError sul telefono.
SKIP_FILENAMES = {
    "overpass_raw.json", "igm_010109_tr_str_tratto_stradale.geojson",
    # SIC: layer OBSOLETO, non piu' aggiornato (Irene, 2026-10-14: "il layer da
    # utilizzare e' solo CiclabiliEsist_Municipi, l'altro e' vecchio"). Duplicava
    # le stesse piste di CiclabiliEsist_Municipi (stesso ID_pista) e una parte
    # era spostata in blocco di ~80 m: archi doppi paralleli e cluster ABCD.
    "Ciclabili_Esistenti.geojson",
}

# Layer con geometrie LineString che pero' NON sono piste ciclabili
# (ferrovie, confini amministrativi, ecc.) - scoperti scansionando
# iter_line_sources() il 2026-10-02 (G3W scarica TUTTI i layer del suo
# progetto, non solo le piste - vedi fetch_g3w in precompila.py). Decisione
# di Alessandro: niente deduzione dal tipo di geometria, ogni file va
# dichiarato esplicitamente qui - altrimenti resta instradabile come oggi
# (comportamento invariato per tutto cio' che non e' elencato). Esclusi da
# load_lines() (mai nel grafo di routing), riesposti da build_detail_layers()
# SOLO come riferimento visivo nel pannello "layers" dell'app, con un
# tratteggio (mai "solid", riservato ai percorsi veri) per non confonderli
# con una pista vera della stessa sorgente/colore - vedi le note di progetto.
NON_CICLABILE_LAYERS = {
    # Davvero non ciclabili (infrastruttura di tutt'altro genere).
    ("g3w_ciclovie_metropolitane", "ferrovie.geojson"): {
        "key": "g3w_ferrovie", "label": "Ferrovie", "dash": "dash",
    },
    ("g3w_ciclovie_metropolitane", "confine_cmrc.geojson"): {
        "key": "g3w_confine_cmrc", "label": "Confine Citta' Metropolitana di Roma", "dash": "dashdot",
    },
    # Ciclabili per davvero, ma escluse dal grafo per un motivo diverso -
    # stesso trattamento (fuori dal routing, visibili solo come riferimento
    # tratteggiato), motivo diverso: qui e' doppione/risoluzione del dato,
    # non "non e' una pista". Decisione 2026-10-02 in risposta a "FALLO":
    # un giudizio di merito preso per non lasciare la lookup table
    # a meta', correggibile in seguito se Alessandro la vuole diversa.
    ("g3w_ciclovie_metropolitane", "piste_ciclabili_OpenStreetMap.geojson"): {
        # Copia delle ciclabili OSM fatta dal portale G3W: quasi certamente
        # duplica (con un proprio snapshot, magari disallineato) la nostra
        # fetch diretta da Overpass (osm_extract) - tenerla instradabile
        # vorrebbe dire due set di archi per le stesse piste.
        "key": "g3w_piste_osm_doppione", "label": "Piste OSM (copia G3W, doppione)", "dash": "dot",
    },
    ("g3w_ciclovie_metropolitane", "programma_bicitalia.geojson"): {
        # Tracciato Bicitalia: percorso cicloturistico reale ma digitalizzato
        # in modo schematico (pochi vertici su tratte lunghe) - stesso
        # rischio di "scorciatoia assurda" gia' noto per Ciclopolitana.
        "key": "g3w_bicitalia", "label": "Programma Bicitalia (schematico)", "dash": "dot",
    },
    ("g3w_ciclovie_metropolitane", "programma_eurovelo.geojson"): {
        "key": "g3w_eurovelo", "label": "EuroVelo (schematico)", "dash": "dot",
    },
}

GRID_CELL_DEG = 0.0003  # ~30m: usata dall'indice spaziale a griglia di
                        # cluster_merge_nodes() per potare le coppie da
                        # considerare, senza scansionare tutto il grafo

# ---------------------------------------------------------------------------
# Parametri della pipeline A/B/B2/C (vedi il docstring del modulo).
# Pensati per finire in Impostazioni come gli altri RuleParams, se
# Alessandro vuole poterli tarare senza toccare il codice.

NODE_CLUSTER_MAX_DIAMETER_M = 30.0
# Diametro massimo (metri) di un gruppo di nodi fusi insieme in
# cluster_merge_nodes() - passo B. Sostituisce le vecchie SNAP_TOLERANCE_M/
# NODE_MERGE_TOLERANCE_M ad "ancora fissa": quelle garantivano "nessuna
# coppia di nodi finali piu' vicina della soglia" ma lasciavano un incrocio
# reale piu' largo della soglia frammentato in piu' nodi in modo arbitrario,
# dipendente dall'ordine di elaborazione (verificato sui dati reali: 357
# cluster di 3+ nodi entro 40m mai fusi, nessuno pero' con una coppia piu'
# vicina di 25m - la vecchia garanzia "nessuna coppia troppo vicina" c'era
# gia', mancava quella opposta "nessun gruppo troppo largo"). Il clustering
# a diametro limitato garantisce ENTRAMBE le cose. Alessandro, 2026-10-02:
# "<=30".

MAX_CROSSINGS_PER_PAIR = 2
# Numero massimo di punti di incrocio fra una coppia di archi (passo C)
# oltre il quale NON si creano piu' nodi: quasi certo doppione/parallelismo
# digitalizzato due volte, non un incrocio vero (una strada ne incrocia
# un'altra una volta sola, al massimo due in casi reali tipo scavalco e
# sottopasso vicini). Alessandro, 2026-10-02: "massimo due (settabile)".

DANGLE_TOLERANCE_M = 15.0
# Distanza massima (metri) fra l'estremita' "a vuoto" di un arco e il
# tracciato di un altro arco perche' il passo D (create_dangle_nodes) li
# colleghi. Caso tipico: due sorgenti digitalizzano lo stesso incrocio
# reale con un piccolo scarto (precedente storico "Porta San Giovanni":
# OSM/SICRM scostati di 17-24m sullo stesso incrocio). Alessandro,
# 2026-10-03, dopo uno screenshot reale di un nodo non unito all'arco
# vicino: "vedi che c'e' un nodo vicino ad un arco e non e' unito!" ->
# passo D aggiunto apposta, replicato anche nella passata globale.

NEATNET_MODE = "disable"
# Interruttore v3 a tre modalita' (Alessandro, 2026-10-03; esteso da
# booleano a enum lo stesso giorno: "inseriamo una terza opzione"). Valori
# validi: "disable" (default: la passata isolata per sorgente gira in loop
# col nostro A/B/B2/C/D, vedi build_source_graph_looped), "enable" (usa
# prima neatnet.neatify() per ripulire la sorgente, poi UNA sola passata
# di A->B->B2->C->D sopra il risultato, vedi _neatnet_preprocess), "both"
# (neatnet prima, poi il LOOP completo invece di una singola passata) -
# MAI nella passata globale, che resta sempre e solo quella di v2.

SOURCE_PASS_MAX_ITERATIONS = 10
# Tetto di sicurezza per il loop A/B/B2/C/D per-sorgente (quando
# NEATNET_MODE e' "disable" o "both"): se il grafo non si stabilizza entro
# questo numero di passaggi, ci si ferma comunque stampando un avviso,
# invece di girare all'infinito - sperimentale, non c'e' garanzia teorica
# di convergenza (Alessandro, 2026-10-03: "e' rischioso, lo so, voglio
# vedere che succede"), quindi un limite esplicito e' prudente anche se in
# pratica dovesse convergere sempre in 2-3 passaggi.

RESIDUAL_CLUSTER_RADIUS_M = 100.0
# Raggio (metri) di collegamento per la clusterizzazione (single-linkage) dei
# punti "residui" di un loop per-sorgente che NON converge entro
# SOURCE_PASS_MAX_ITERATIONS (Alessandro, 2026-10-13: "identifichi le zone
# dove persistono gli errori dopo le (tipicamente 10) iterazioni... fai una
# clusterizzazione"): due punti a meno di questa distanza finiscono nello
# stesso cluster (anche a catena). Vedi _write_residual_report().

_LAST_DANGLE_POINTS = []
# Posizioni (lat, lon) degli estremi "a vuoto" che l'ULTIMA chiamata di
# create_dangle_nodes() ha agganciato (posizione PRIMA dello snap). Canale
# laterale per non cambiare la firma/il ritorno di D, usato da
# build_source_graph_looped() per sapere dove il loop non si chiude.

RESIDUAL_BY_SOURCE = {}
# source_key -> dict con l'esito del loop di quella sorgente (convergenza,
# iterazioni, punti residui dell'ultima passata). Azzerato a ogni build().

RESIDUAL_PASSES = {}
# source_key -> lista (una voce per passata del loop, in ordine) di dict
# {"dangle_points", "changed_points", "converged"}: residui di OGNI passata,
# scritti in debug_images/residual_errors_passo_NN.txt (Alessandro,
# 2026-10-14). Azzerato a ogni build().

# Le linee di questa sorgente non partecipano MAI a create_crossing_nodes()
# (ne' come primo arco ne' come controparte): sono tracciati concettuali
# ("mappa della metro"), non piste reali - un "incrocio" fra una pista vera
# e una linea Ciclopolitana non e' un punto dove un ciclista puo' davvero
# cambiare percorso. Restano comunque nel grafo finale (filtrabili da sole
# in Impostazioni).
NODING_EXCLUDE_SOURCES = {"salvaiciclisti_ciclopolitana"}

# Angolo (gradi) tra il vettore di entrata e quello di uscita in un vertice
# oltre il quale si considera un'inversione "avanti e indietro" sulla
# stessa traccia (vedi remove_uturn_spikes piu' sotto), non una svolta
# vera. A Roma non ci sono tornanti stradali veri (al piu' qualche svolta
# stretta a Monte Mario, mai una vera inversione a 180 gradi), quindi la
# soglia puo' restare alta/aggressiva senza rischio di falsi positivi su
# incroci o curve strette reali (Alessandro, 2026-10-02).
UTURN_ANGLE_THRESHOLD_DEG = 160.0

# Chiavi di proprieta' da cui provare a estrarre un nome leggibile per
# l'arco, in ordine di preferenza. Volutamente NON include campi come
# "denominazi"/"oggetto" del G3W (sono descrizioni di progetto lunghe una
# frase, non un nome di via) ne' "Tragitto__da___a_" (descrive due punti,
# non un nome unico) - quegli archi restano senza nome piuttosto che avere
# un'etichetta fuorviante.
NAME_KEYS = ["name", "NOME", "Name", "Dati__Name"]
MAX_NAME_LEN = 60


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlambda / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def polyline_length_m(coords):
    return sum(haversine_m(coords[i][0], coords[i][1], coords[i + 1][0], coords[i + 1][1])
               for i in range(len(coords) - 1))


def utm33n_to_wgs84(easting, northing):
    """UTM zona 33N (EPSG:32633) -> WGS84 lat/lon, formula inversa di Redfearn,
    pura Python (nessuna dipendenza tipo pyproj). Serve solo per la sorgente
    g3w_ciclovie_metropolitane: l'API G3W-Suite di Citta' Metropolitana
    restituisce quel layer in coordinate piane (metri), non in gradi come le
    altre tre sorgenti - senza questa conversione i suoi nodi finiscono con
    "lat/lon" dell'ordine di milioni di gradi. math.radians() li accetta
    comunque (e' periodica ogni 360 gradi), quindi per puro caso di
    avvolgimento modulare un nodo-spazzatura puo' ricadere entro tolleranza
    di un nodo vero da qualche parte su Roma, innestando nel grafo un arco
    completamente inventato con lunghezza altrettanto inventata - trovato
    analizzando gli archi piu' lunghi del grafo consolidato (fino a decine di
    miliardi di metri), verificato riproiettando un campione di punti (che
    ricadono correttamente nel territorio della Citta' Metropolitana di Roma
    solo con zona 33N, non 32N) e confermato su tutte le 1220 polilinee della
    sorgente (100% in coordinate piane, nessun mix con gradi gia' corretti)."""
    a = 6378137.0
    f = 1 / 298.257223563
    e2 = 2 * f - f * f
    e2p = e2 / (1 - e2)
    k0 = 0.9996
    zone = 33
    x = easting - 500000.0
    y = northing

    e1 = (1 - math.sqrt(1 - e2)) / (1 + math.sqrt(1 - e2))
    M = y / k0
    mu = M / (a * (1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256))

    phi1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu)
            + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu))

    N1 = a / math.sqrt(1 - e2 * math.sin(phi1) ** 2)
    T1 = math.tan(phi1) ** 2
    C1 = e2p * math.cos(phi1) ** 2
    R1 = a * (1 - e2) / (1 - e2 * math.sin(phi1) ** 2) ** 1.5
    D = x / (N1 * k0)

    lat = phi1 - (N1 * math.tan(phi1) / R1) * (
        D ** 2 / 2
        - (5 + 3 * T1 + 10 * C1 - 4 * C1 ** 2 - 9 * e2p) * D ** 4 / 24
        + (61 + 90 * T1 + 298 * C1 + 45 * T1 ** 2 - 252 * e2p - 3 * C1 ** 2) * D ** 6 / 720
    )
    lon = (D - (1 + 2 * T1 + C1) * D ** 3 / 6
           + (5 - 2 * C1 + 28 * T1 - 3 * C1 ** 2 + 8 * e2p + 24 * T1 ** 2) * D ** 5 / 120) / math.cos(phi1)
    lon0 = math.radians((zone - 1) * 6 - 180 + 3)
    lon = lon0 + lon
    return math.degrees(lat), math.degrees(lon)


def web_mercator_to_wgs84(x, y):
    """Web Mercator (EPSG:3857) -> WGS84 lat/lon, formula sferica pura Python
    (nessuna dipendenza tipo pyproj). Serve solo per rsm_piste_ciclabili:
    quella sorgente (RSM Open Data, ArcGIS) arriva anche lei in coordinate
    piane (metri), non in gradi - stesso identico problema di
    g3w_ciclovie_metropolitane (vedi utm33n_to_wgs84), ma con una proiezione
    diversa: qui e' la sfera di Web Mercator, la stessa usata dai tile di
    Google/OSM. Verificato riproiettando un campione di punti: cadono
    correttamente su Roma solo con questa formula (con UTM33N no)."""
    r = 6378137.0
    lon = math.degrees(x / r)
    lat = math.degrees(2 * math.atan(math.exp(y / r)) - math.pi / 2)
    return lat, lon


def extract_name(props):
    for key in NAME_KEYS:
        val = props.get(key)
        if isinstance(val, str):
            val = val.strip()
            if val and len(val) <= MAX_NAME_LEN:
                return val
    return None


def iter_line_sources():
    """Genera (source_key, geojson_path) per ogni file di dati vettoriali
    sotto percorsi/raw/, incluse le sottocartelle (es. layer_correlati/ di
    salvaiciclisti_sicrm, che restano taggate con la sorgente principale)."""
    for source_key in SOURCE_LABELS:
        source_dir = RAW_DIR / source_key
        if not source_dir.is_dir():
            continue
        for path in sorted(source_dir.rglob("*.geojson")):
            if path.name in SKIP_FILENAMES:
                continue
            yield source_key, path


def load_lines():
    """Estrae ogni LineString/MultiLineString trovata come una lista di
    liste di (lat, lon) (GeoJSON e' lon,lat: qui si inverte subito, il resto
    dello script lavora sempre in (lat, lon))."""
    lines = []
    stats = defaultdict(int)
    for source_key, path in iter_line_sources():
        _mappa = ("salvaiciclisti_ciclopolitana" if path.name in CICLOPOLITANA_FILENAMES
                  else source_key)
        if not _layer_enabled(_mappa, path):
            # Mappa o layer spento in precompila_parametri.txt
            # (process_<mappa> / layer_<...> = false).
            continue
        if (source_key, path.name) in NON_CICLABILE_LAYERS:
            # Non instradabile (vedi NON_CICLABILE_LAYERS sopra) -
            # mai nel grafo, anche se la geometria e' LineString.
            continue
        try:
            data = json.loads(path.read_text())
        except Exception as e:
            print(f"  [build_routing_graph] impossibile leggere {path}: {e}")
            continue
        for feature in data.get("features", []):
            geom = feature.get("geometry") or {}
            gtype = geom.get("type")
            if gtype == "LineString":
                coord_sets = [geom.get("coordinates", [])]
            elif gtype == "MultiLineString":
                coord_sets = geom.get("coordinates", [])
            else:
                continue  # niente Point/Polygon: non sono instradabili
            name = extract_name(feature.get("properties") or {})
            for coords in coord_sets:
                if len(coords) < 2:
                    continue
                if source_key == "g3w_ciclovie_metropolitane":
                    # Questa sorgente arriva in UTM33N (metri), non in gradi:
                    # vedi utm33n_to_wgs84 per il perche'.
                    latlon = [utm33n_to_wgs84(c[0], c[1]) for c in coords]
                elif source_key == "rsm_piste_ciclabili":
                    # Idem, ma in Web Mercator: vedi web_mercator_to_wgs84.
                    latlon = [web_mercator_to_wgs84(c[0], c[1]) for c in coords]
                else:
                    latlon = [(c[1], c[0]) for c in coords]
                effective_source = (
                    "salvaiciclisti_ciclopolitana" if path.name in CICLOPOLITANA_FILENAMES else source_key
                )
                lines.append({"coords": latlon, "source": effective_source, "name": name})
                stats[effective_source] += 1
    return lines, stats


def _local_projection(lat0):
    """Proiezione equirettangolare locale (gradi -> metri), centrata su
    lat0: approssimazione adeguata per l'estensione di Roma (poche decine di
    km), stesso principio di haversine_m ma qui serve una proiezione (x,y)
    vera e propria per darla in pasto a Shapely, non solo una funzione-
    distanza. Condivisa da collapse_same_source_duplicates() e
    node_lines_globally(): stesso ancoraggio per entrambe, cosi' le
    coordinate che l'una inserisce restano coerenti per l'altra."""
    mlat = 111320.0
    mlon = 111320.0 * math.cos(math.radians(lat0))

    def to_m(latlon):
        lat, lon = latlon
        return (lon * mlon, lat * mlat)

    def from_m(xy):
        x, y = xy
        return (y / mlat, x / mlon)

    return to_m, from_m


def _line_geoms(lines, to_m, exclude_sources=frozenset()):
    """LineString Shapely per ogni linea (None per quelle escluse o troppo
    corte dopo aver tolto i punti consecutivi duplicati - Shapely rifiuta
    una LineString con un solo punto)."""
    geoms = []
    for line in lines:
        if line["source"] in exclude_sources:
            geoms.append(None)
            continue
        pts = [to_m(c) for c in line["coords"]]
        dedup = [pts[0]]
        for pt in pts[1:]:
            if pt != dedup[-1]:
                dedup.append(pt)
        geoms.append(LineString(dedup) if len(dedup) >= 2 else None)
    return geoms


def remove_uturn_spikes(lines):
    """Rimuove, dentro ogni singola linea presa da sola, i vertici che
    fanno "avanti e indietro" sulla stessa traccia: un punto in cui la
    direzione di marcia si inverte quasi del tutto (angolo fra il vettore
    di entrata e quello di uscita >= UTURN_ANGLE_THRESHOLD_DEG) e' quasi
    sempre un artefatto di digitalizzazione o una traccia GPS che torna sui
    propri passi, non una svolta vera (Alessandro, 2026-10-02).

    Algoritmo a pila: si scorrono i vertici della linea in ordine; prima di
    tenere un nuovo vertice si controlla l'angolo formato dagli ultimi due
    vertici gia' tenuti insieme al nuovo arrivato - se e' un'inversione, si
    scarta l'ultimo vertice tenuto (non il nuovo) e si ripete il confronto
    con quello ancora prima in pila. Gestisce da solo anche le inversioni
    su piu' vertici ("il nodo successivo fa anch'esso 180 per tornare
    nella direzione iniziale"): ogni vertice-spike viene scartato in
    cascata finche' la direzione torna coerente. Il primo e l'ultimo
    vertice della linea non vengono mai scartati."""
    stats = {"lines_modified": 0, "vertices_removed": 0}
    new_lines = []
    for line in lines:
        coords = line["coords"]
        if len(coords) < 3:
            new_lines.append(line)
            continue
        lat0 = coords[len(coords) // 2][0]
        to_m, _ = _local_projection(lat0)
        pts_m = [to_m(c) for c in coords]

        stack = [0]
        for i in range(1, len(pts_m)):
            while len(stack) >= 2:
                ax, ay = pts_m[stack[-2]]
                bx, by = pts_m[stack[-1]]
                cx, cy = pts_m[i]
                v_in = (bx - ax, by - ay)
                v_out = (cx - bx, cy - by)
                norm_in = math.hypot(*v_in)
                norm_out = math.hypot(*v_out)
                if norm_in < 1e-6 or norm_out < 1e-6:
                    break
                cos_angle = (v_in[0] * v_out[0] + v_in[1] * v_out[1]) / (norm_in * norm_out)
                cos_angle = max(-1.0, min(1.0, cos_angle))
                angle_deg = math.degrees(math.acos(cos_angle))
                if angle_deg >= UTURN_ANGLE_THRESHOLD_DEG:
                    stack.pop()
                    stats["vertices_removed"] += 1
                else:
                    break
            stack.append(i)

        if len(stack) == len(coords):
            new_lines.append(line)
            continue
        stats["lines_modified"] += 1
        new_line = dict(line)
        new_line["coords"] = [coords[idx] for idx in stack]
        new_lines.append(new_line)
    return new_lines, stats


def _edge_geoms(edges, to_m, exclude_sources=frozenset()):
    """Shapely LineString per ogni arco (coordinate proiettate in metri via
    to_m) - None per gli archi esclusi o troppo corti dopo aver tolto punti
    consecutivi duplicati. Usata da create_crossing_nodes() per il noding
    geometrico esatto (Shapely/GEOS)."""
    geoms = []
    for e in edges:
        if e["src"] in exclude_sources:
            geoms.append(None)
            continue
        pts = [to_m(c) for c in e["geom"]]
        dedup = [pts[0]]
        for pt in pts[1:]:
            if pt != dedup[-1]:
                dedup.append(pt)
        geoms.append(LineString(dedup) if len(dedup) >= 2 else None)
    return geoms


def build_nodes_from_endpoints(lines):
    """Passo A: un nodo e' SOLO E SOLTANTO l'inizio o la fine di un arco
    (Alessandro, 2026-10-02: "un nodo e' solo e soltanto la fine di un
    arco, e l'inizio") - nessun vertice interno, nessun incrocio ancora:
    quello e' il passo C, piu' sotto, deliberatamente separato. Un nodo
    grezzo per OGNI estremo di OGNI linea, senza fondere nulla (lo fa
    cluster_merge_nodes() subito dopo) - quindi due nodi grezzi per ogni
    linea, anche se molti finiranno per essere lo stesso punto fisico."""
    raw_nodes = []
    edges = []
    for line in lines:
        a_idx = len(raw_nodes)
        raw_nodes.append(line["coords"][0])
        b_idx = len(raw_nodes)
        raw_nodes.append(line["coords"][-1])
        edges.append({
            "a": a_idx, "b": b_idx,
            "src": line["source"], "name": line["name"],
            "len": round(polyline_length_m(line["coords"]), 1),
            "geom": [[round(lat, 6), round(lon, 6)] for lat, lon in line["coords"]],
        })
    print(f"    [A: build_nodes_from_endpoints] {len(lines)} linee -> "
          f"{len(raw_nodes)} nodi grezzi, {len(edges)} archi")
    return raw_nodes, edges


def cluster_merge_nodes(raw_nodes, edges, max_diameter_m):
    """Passo B: fonde i nodi vicini con un clustering GERARCHICO a legame
    completo e diametro limitato (Alessandro, 2026-10-02: "fai il merge dei
    nodi con clustering") - sostituisce la vecchia NodeGrid ad "ancora
    fissa", che fondeva un punto nel primo gruppo abbastanza vicino
    all'ANCORA (il primo punto arrivato), non a tutti i membri: il
    risultato dipendeva dall'ordine di elaborazione, e un incrocio reale
    piu' largo della soglia finiva frammentato in piu' nodi in modo
    arbitrario (verificato sui dati reali: 357 cluster di 3+ nodi entro 40m
    mai fusi - la vecchia garanzia "nessuna coppia troppo vicina" c'era
    gia', mancava quella opposta "nessun gruppo troppo largo").

    Qui un punto entra in un gruppo solo se il gruppo risultante resta con
    un diametro (la piu' grande fra tutte le distanze a coppie fra i suoi
    membri) entro max_diameter_m - simmetrico, indipendente dall'ordine di
    arrivo. Unione golosa in ordine di distanza crescente, fra candidati
    trovati con un indice a griglia (stesso principio della vecchia
    NodeGrid, qui solo per potare le coppie da considerare, non per
    decidere la fusione). La posizione finale di un gruppo e' il baricentro
    dei suoi membri (mai la posizione di un singolo punto "vincitore" -
    niente piu' dipendenza dalla sorgente che arriva per prima)."""
    n = len(raw_nodes)
    if n == 0:
        return [], list(edges)

    grid = defaultdict(list)
    for i, (lat, lon) in enumerate(raw_nodes):
        grid[(int(lat / GRID_CELL_DEG), int(lon / GRID_CELL_DEG))].append(i)

    candidate_pairs = []
    seen_pairs = set()
    for i, (lat, lon) in enumerate(raw_nodes):
        cx, cy = int(lat / GRID_CELL_DEG), int(lon / GRID_CELL_DEG)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for j in grid.get((cx + dx, cy + dy), ()):
                    if j <= i:
                        continue
                    key = (i, j)
                    if key in seen_pairs:
                        continue
                    seen_pairs.add(key)
                    d = haversine_m(lat, lon, *raw_nodes[j])
                    if d <= max_diameter_m:
                        candidate_pairs.append((d, i, j))
    candidate_pairs.sort(key=lambda t: t[0])

    cluster_of = list(range(n))
    members = {i: [i] for i in range(n)}

    def cluster_diameter(pts_idx):
        maxd = 0.0
        for a in range(len(pts_idx)):
            la, lo = raw_nodes[pts_idx[a]]
            for b in range(a + 1, len(pts_idx)):
                d = haversine_m(la, lo, *raw_nodes[pts_idx[b]])
                if d > maxd:
                    maxd = d
        return maxd

    for _, i, j in candidate_pairs:
        ci, cj = cluster_of[i], cluster_of[j]
        if ci == cj:
            continue
        merged = members[ci] + members[cj]
        if cluster_diameter(merged) <= max_diameter_m:
            for p in members[cj]:
                cluster_of[p] = ci
            members[ci] = merged
            del members[cj]

    roots = sorted(members.keys())
    root_to_final = {root: idx for idx, root in enumerate(roots)}
    final_nodes = []
    for root in roots:
        pts_idx = members[root]
        lat_c = sum(raw_nodes[p][0] for p in pts_idx) / len(pts_idx)
        lon_c = sum(raw_nodes[p][1] for p in pts_idx) / len(pts_idx)
        final_nodes.append((round(lat_c, 6), round(lon_c, 6)))

    remap = [root_to_final[cluster_of[i]] for i in range(n)]
    new_edges = []
    dropped_self_loops = 0
    for e in edges:
        a2, b2 = remap[e["a"]], remap[e["b"]]
        if a2 == b2:
            dropped_self_loops += 1
            continue
        new_edges.append({**e, "a": a2, "b": b2})
    extra = (f", {dropped_self_loops} archi scartati (auto-arco dopo la fusione)"
             if dropped_self_loops else "")
    print(f"    [B: cluster_merge_nodes] {n} nodi grezzi -> {len(final_nodes)} nodi "
          f"(diametro max {max_diameter_m}m){extra}")

    return final_nodes, new_edges


def collapse_degree2_nodes(nodes, edges):
    """Passo B2: un nodo con ESATTAMENTE due archi incidenti scompare,
    fondendo i due archi in uno solo (Alessandro, 2026-10-02: "un nodo che
    ha solo due archi scompare, unendo i due archi in uno solo") - non e'
    mai un vero punto di decisione per chi pedala, quasi sempre solo il
    punto dove finisce un file GeoJSON e riprende un altro (stessa via,
    stessa sorgente). SOLO entro la stessa sorgente (va chiamata prima di
    mettere insieme le sorgenti, mai dopo - altrimenti fonderebbe archi di
    sorgenti diverse in uno solo "senza sorgente", rompendo il filtro di
    Impostazioni): per questo nella pipeline compare fra B e C nella
    passata per-sorgente, e NON viene mai ripetuta nella passata globale
    (Alessandro, 2026-10-02: "secondo run BC e basta, non si eliminano nodi
    tra sorgenti diverse").

    Ripetuta fino a un punto fisso: una catena di piu' nodi-grado-2
    consecutivi collassa fino in fondo in un solo arco, non solo un pezzo
    alla volta. Il nome del nuovo arco e' la concatenazione dei due nomi
    (Alessandro, 2026-10-02: "come nome metterai la concatenazione dei due
    nomi") - se identici resta uno solo (il caso comune: stessa via
    spezzata in piu' file), se uno dei due e' assente resta l'altro. Un
    nodo viene saltato (mai fuso) se la fusione produrrebbe un auto-arco (i
    due estremi esterni dei due archi coincidono gia') - tipicamente un
    anello chiuso tutto a grado 2: fonderlo del tutto lo farebbe sparire,
    quindi si lascia sempre almeno un nodo ad ancorarlo."""

    def merge_names(name_a, name_b):
        if name_a and name_b:
            return name_a if name_a == name_b else f"{name_a} / {name_b}"
        return name_a or name_b

    edges = list(edges)
    merges_done = 0
    changed = True
    while changed:
        changed = False
        incident = defaultdict(list)
        for idx, e in enumerate(edges):
            if e is None:
                continue
            incident[e["a"]].append(idx)
            incident[e["b"]].append(idx)

        for node_id, edge_idxs in list(incident.items()):
            if len(edge_idxs) != 2:
                continue
            i1, i2 = edge_idxs
            if i1 == i2:
                continue
            e1, e2 = edges[i1], edges[i2]
            if e1 is None or e2 is None:
                continue
            if e1["src"] != e2["src"]:
                continue  # non dovrebbe succedere qui (passata per-sorgente), prudenza
            other1 = e1["b"] if e1["a"] == node_id else e1["a"]
            other2 = e2["b"] if e2["a"] == node_id else e2["a"]
            if other1 == other2:
                continue  # fonderebbe in un auto-arco (anello) - lascia stare

            seg1 = e1["geom"] if e1["b"] == node_id else list(reversed(e1["geom"]))
            seg2 = e2["geom"] if e2["a"] == node_id else list(reversed(e2["geom"]))
            merged_geom = seg1[:-1] + seg2
            merged_edge = {
                "a": other1, "b": other2,
                "src": e1["src"], "name": merge_names(e1.get("name"), e2.get("name")),
                "len": round(e1["len"] + e2["len"], 1),
                "geom": merged_geom,
            }
            edges[i1] = merged_edge
            edges[i2] = None
            changed = True
            merges_done += 1

    final_edges = [e for e in edges if e is not None]
    used_node_ids = set()
    for e in final_edges:
        used_node_ids.add(e["a"])
        used_node_ids.add(e["b"])
    sorted_used = sorted(used_node_ids)
    remap = {old: new for new, old in enumerate(sorted_used)}
    new_nodes = [nodes[old] for old in sorted_used]
    for e in final_edges:
        e["a"] = remap[e["a"]]
        e["b"] = remap[e["b"]]
    print(f"    [B2: collapse_degree2_nodes] {merges_done} nodi di grado 2 collassati -> "
          f"{len(new_nodes)} nodi, {len(final_edges)} archi")
    return new_nodes, final_edges


def create_crossing_nodes(nodes, edges, max_crossings_per_pair):
    """Passo C: crea un nodo vero dove un arco incrocia geometricamente un
    altro (Alessandro, 2026-10-02: "crei nodi laddove un arco ne incontra
    un altro una volta sola, massimo due") - noding esatto (Shapely/GEOS),
    non piu' un inserimento di vertice che sperava in un riconoscimento
    successivo (il vecchio node_lines_globally + build_graph: verificato
    con un caso minimo che un incrocio vero, se il punto non coincideva
    gia' con l'estremita' di una terza linea, restava semplicemente
    scollegato). Qui il nodo si crea e l'arco si taglia (via
    shapely.ops.substring) nello stesso momento in cui l'incrocio viene
    trovato, niente passaggi successivi da cui dipendere.

    Se una coppia di archi si incrocia piu' di max_crossings_per_pair
    volte, e' quasi certamente la stessa pista/strada mappata due volte o
    due tracciati che corrono paralleli per un tratto (rumore di
    digitalizzazione, non un incrocio vero) - la coppia viene scartata per
    intero, nessuno dei suoi punti diventa un nodo. Le linee di
    NODING_EXCLUDE_SOURCES (Ciclopolitana) non partecipano mai, ne' come
    primo arco ne' come controparte."""
    if not edges:
        return list(nodes), list(edges)

    nodes = list(nodes)
    lat0 = sum(lat for lat, lon in nodes) / len(nodes)
    to_m, from_m = _local_projection(lat0)
    geoms = _edge_geoms(edges, to_m, exclude_sources=NODING_EXCLUDE_SOURCES)

    valid_idx = [i for i, g in enumerate(geoms) if g is not None]
    valid_geoms = [geoms[i] for i in valid_idx]
    if not valid_geoms:
        return nodes, list(edges)
    tree = STRtree(valid_geoms)

    cuts = defaultdict(set)
    seen_pairs = set()
    crossings_found = 0
    skipped_as_probable_duplicates = 0

    for a, ga in enumerate(valid_geoms):
        i = valid_idx[a]
        nearby = tree.query(ga, predicate="intersects")
        for b in nearby:
            j = valid_idx[b]
            if j <= i:
                continue
            key = (i, j)
            if key in seen_pairs:
                continue
            seen_pairs.add(key)
            ei, ej = edges[i], edges[j]
            if {ei["a"], ei["b"]} & {ej["a"], ej["b"]}:
                continue  # gia' connessi da un nodo condiviso, niente da creare qui
            gb = geoms[j]
            inter = ga.intersection(gb)
            if inter.is_empty:
                continue
            if inter.geom_type == "Point":
                points = [inter]
            elif inter.geom_type == "MultiPoint":
                points = list(inter.geoms)
            else:
                # sovrapposizione per un tratto (LineString/MultiLineString/
                # GeometryCollection mista): non un incrocio puntuale, quasi
                # certo doppione/parallelismo - nessun nodo da qui.
                continue
            if len(points) > max_crossings_per_pair:
                skipped_as_probable_duplicates += 1
                continue
            for pt in points:
                xy = (pt.x, pt.y)
                cuts[i].add(xy)
                cuts[j].add(xy)
                crossings_found += 1

    print(f"    [C: create_crossing_nodes] {crossings_found} incroci veri, "
          f"{skipped_as_probable_duplicates} coppie scartate per troppi incroci")
    if not cuts:
        return nodes, list(edges)

    cut_point_to_node = {}

    def node_for_point(x, y):
        for (ox, oy), nid in cut_point_to_node.items():
            if abs(ox - x) < 0.01 and abs(oy - y) < 0.01:  # 1cm, solo float noise
                return nid
        nid = len(nodes)
        lat, lon = from_m((x, y))
        nodes.append((round(lat, 6), round(lon, 6)))
        cut_point_to_node[(x, y)] = nid
        return nid

    new_edges = []
    for idx, e in enumerate(edges):
        pts_xy = cuts.get(idx)
        if not pts_xy:
            new_edges.append(e)
            continue
        g = geoms[idx]
        cut_list = [(0.0, e["a"])]
        for x, y in pts_xy:
            d = g.project(Point(x, y))
            cut_list.append((d, node_for_point(x, y)))
        cut_list.append((g.length, e["b"]))
        cut_list.sort(key=lambda t: t[0])
        dedup_list = [cut_list[0]]
        for d, nid in cut_list[1:]:
            if d - dedup_list[-1][0] < 0.5:  # troppo vicino: stesso punto
                continue
            dedup_list.append((d, nid))
        if len(dedup_list) < 2:
            new_edges.append(e)
            continue
        for (d0, n0), (d1, n1) in zip(dedup_list, dedup_list[1:]):
            sub = substring(g, d0, d1)
            if sub.is_empty or sub.length <= 0:
                continue
            coords_latlon = [from_m(pt) for pt in sub.coords]
            if len(coords_latlon) < 2:
                continue
            new_edges.append({
                "a": n0, "b": n1,
                "src": e["src"], "name": e.get("name"),
                "len": round(polyline_length_m(coords_latlon), 1),
                "geom": [[round(lat, 6), round(lon, 6)] for lat, lon in coords_latlon],
            })
    return nodes, new_edges


def create_dangle_nodes(nodes, edges, dangle_tolerance_m):
    """Passo D, l'ultimo: collega un'estremita' "a vuoto" di un arco al
    tracciato di un altro arco quando cade a meno di dangle_tolerance_m da
    esso, ma non esattamente sopra (altrimenti l'avrebbe gia' trovato il
    passo C) - il caso di due sorgenti che mappano lo stesso incrocio con
    un piccolo scarto di digitalizzazione (Alessandro, 2026-10-03: "vedi
    che c'e' un nodo vicino ad un arco e non e' unito" - screenshot reale,
    un nodo accanto a un arco che prosegue senza fondersi). Va DOPO C
    (prima gli incroci esatti, poi solo i quasi-incroci residui) e si
    ripete nella passata globale esattamente come B e C (il caso piu'
    comune e' proprio fra sorgenti diverse - lo stesso scenario di Porta
    San Giovanni della vecchia pipeline: OSM/SICRM della stessa
    intersezione scostati di 17-24m).

    A differenza di create_crossing_nodes(), qui NON si crea un nodo nuovo:
    l'estremita' "a vuoto" e' gia' un nodo esistente, si taglia solo
    l'ALTRO arco in quel punto e si riusa l'ID del nodo gia' esistente - ma
    (Alessandro, 2026-10-03, dopo aver trovato con un caso reale un vuoto
    geometrico di 36.8m fra un nodo "agganciato" e il punto di taglio:
    "prima strada" fra le due proposte) il nodo viene anche SPOSTATO
    esattamente sul punto di taglio, e l'arco a cui apparteneva
    l'estremita' viene aggiornato di conseguenza (geometria e lunghezza) -
    altrimenti il grafo risulterebbe connesso per id-nodo ma con un vuoto
    geometrico vero fino a dangle_tolerance_m, invisibile a chi guarda solo
    gli id (un router che segue id-nodo "teletrasporterebbe" oltre quel
    vuoto, e la lunghezza dell'arco non lo conterebbe).

    Effetto collaterale dichiarato (2026-10-13): lascia in
    _LAST_DANGLE_POINTS le posizioni (lat, lon, pre-snap) degli estremi
    agganciati in QUESTA chiamata, per l'analisi dei residui del loop."""
    global _LAST_DANGLE_POINTS
    _LAST_DANGLE_POINTS = []
    dangle_points = []
    if not edges:
        return list(nodes), list(edges)

    nodes = list(nodes)
    lat0 = sum(lat for lat, lon in nodes) / len(nodes)
    to_m, from_m = _local_projection(lat0)
    geoms = _edge_geoms(edges, to_m, exclude_sources=NODING_EXCLUDE_SOURCES)

    valid_idx = [i for i, g in enumerate(geoms) if g is not None]
    valid_geoms = [geoms[i] for i in valid_idx]
    if not valid_geoms:
        return nodes, list(edges)
    tree = STRtree(valid_geoms)

    cuts = defaultdict(list)  # edge_idx -> [(distanza lungo l'arco, node_id)]
    dangles_found = 0

    # Margine (metri) entro al quale il punto piu' vicino su un arco
    # candidato NON conta come "interno all'arco" ma come "coincide con un
    # suo estremo" - trovato con un test sintetico (chiusura a T subito
    # dopo un incrocio appena creato dal passo C): senza questo margine,
    # un'estremita' "a vuoto" che cade vicino al nodo di incrocio di
    # un'altra coppia di archi veniva ri-agganciata A UNO di quegli archi
    # proprio li', in un punto che il successivo deduplica (sotto, soglia
    # 0.5m) confondeva con l'estremo gia' corretto dell'arco - perdendo
    # silenziosamente la connessione vera al nodo di incrocio. Il passo D
    # deve agganciarsi solo all'INTERNO di un arco (il caso T-junction
    # dello screenshot di Alessandro), mai a un suo estremo: quello e'
    # un caso di nodi vicini, di competenza del passo B, non D.
    DANGLE_INTERIOR_MARGIN_M = 2.0

    for a, ga in enumerate(valid_geoms):
        i = valid_idx[a]
        ei = edges[i]
        for node_id in (ei["a"], ei["b"]):
            lat_n, lon_n = nodes[node_id]
            end_pt = Point(to_m((lat_n, lon_n)))
            nearby = tree.query(end_pt, predicate="dwithin", distance=dangle_tolerance_m)
            best = None
            for b in nearby:
                j = valid_idx[b]
                if j == i:
                    continue
                ej = edges[j]
                if node_id in (ej["a"], ej["b"]):
                    continue  # gia' connesso li', niente da fare
                gb = geoms[j]
                d = gb.distance(end_pt)
                if d >= dangle_tolerance_m:
                    continue
                dist_along = gb.project(end_pt)
                if dist_along < DANGLE_INTERIOR_MARGIN_M or dist_along > gb.length - DANGLE_INTERIOR_MARGIN_M:
                    continue  # troppo vicino a un estremo di ej: non e' un vero caso D
                if best is None or d < best[0]:
                    best = (d, j, gb, dist_along)
            if best is not None:
                _, j, gb, dist_along = best
                cuts[j].append((dist_along, node_id))
                dangles_found += 1
                dangle_points.append((lat_n, lon_n))

    _LAST_DANGLE_POINTS = dangle_points
    print(f"    [D: create_dangle_nodes] {dangles_found} estremi agganciati al tracciato di un altro arco")
    if not cuts:
        return nodes, list(edges)

    # node_id -> nuova posizione (lat, lon), presa PARI PARI dalla geometria
    # gia' arrotondata del sotto-arco che tocca quel punto - mai ricalcolata
    # indipendentemente, cosi' nodo e arco restano combacianti all'ultima
    # cifra, non solo "quasi" (vedi docstring sopra).
    node_new_pos = {}

    new_edges = []
    for idx, e in enumerate(edges):
        extra_cuts = cuts.get(idx)
        if not extra_cuts:
            new_edges.append(e)
            continue
        extra_cut_node_ids = {nid for _, nid in extra_cuts}
        g = geoms[idx]
        cut_list = [(0.0, e["a"])] + extra_cuts + [(g.length, e["b"])]
        cut_list.sort(key=lambda t: t[0])
        dedup_list = [cut_list[0]]
        for d, nid in cut_list[1:]:
            if d - dedup_list[-1][0] < 0.5:  # troppo vicino: stesso punto
                continue
            dedup_list.append((d, nid))
        if len(dedup_list) < 2:
            new_edges.append(e)
            continue
        for (d0, n0), (d1, n1) in zip(dedup_list, dedup_list[1:]):
            sub = substring(g, d0, d1)
            if sub.is_empty or sub.length <= 0:
                continue
            coords_latlon = [from_m(pt) for pt in sub.coords]
            if len(coords_latlon) < 2:
                continue
            geom_rounded = [[round(lat, 6), round(lon, 6)] for lat, lon in coords_latlon]
            new_edges.append({
                "a": n0, "b": n1,
                "src": e["src"], "name": e.get("name"),
                "len": round(polyline_length_m(coords_latlon), 1),
                "geom": geom_rounded,
            })
            # n0/n1 che vengono da un aggancio D (non dagli estremi
            # originali e["a"]/e["b"] dell'arco appena tagliato) ottengono
            # qui la loro posizione DEFINITIVA.
            if n0 in extra_cut_node_ids:
                node_new_pos[n0] = tuple(geom_rounded[0])
            if n1 in extra_cut_node_ids:
                node_new_pos[n1] = tuple(geom_rounded[-1])

    if node_new_pos:
        for nid, pos in node_new_pos.items():
            nodes[nid] = pos
        # Qualunque altro arco che referenzia uno di questi nodi (tipico:
        # l'arco a cui apparteneva l'estremita' dangling, mai toccato fin
        # qui) va aggiornato sull'estremo e sulla lunghezza. Copie nuove,
        # non mutazione in-place: gli edge "passati invariati" sopra sono
        # ancora gli stessi dict dell'input, e non vogliamo effetti
        # collaterali su eventuali altri riferimenti a quei dict altrove
        # nella pipeline (es. la passata globale).
        fixed_edges = []
        for e in new_edges:
            if e["a"] in node_new_pos or e["b"] in node_new_pos:
                geom = [list(pt) for pt in e["geom"]]
                if e["a"] in node_new_pos:
                    geom[0] = list(node_new_pos[e["a"]])
                if e["b"] in node_new_pos:
                    geom[-1] = list(node_new_pos[e["b"]])
                e = {**e, "geom": geom,
                     "len": round(polyline_length_m([tuple(pt) for pt in geom]), 1)}
            fixed_edges.append(e)
        new_edges = fixed_edges

    return nodes, new_edges


def build_source_graph(lines_for_source, max_crossings_per_pair, max_cluster_diameter_m,
                        dangle_tolerance_m):
    """Pipeline completa A->B->B2->C->D per UNA sola sorgente - la passata
    "isolata" di {source}_auto.jpg, e anche il primo stadio reale della
    build globale qui sotto."""
    raw_nodes, edges = build_nodes_from_endpoints(lines_for_source)
    nodes, edges = cluster_merge_nodes(raw_nodes, edges, max_cluster_diameter_m)
    nodes, edges = collapse_degree2_nodes(nodes, edges)
    nodes, edges = create_crossing_nodes(nodes, edges, max_crossings_per_pair)
    nodes, edges = create_dangle_nodes(nodes, edges, dangle_tolerance_m)
    return nodes, edges


def build_global_graph(per_source_graphs, max_crossings_per_pair, max_cluster_diameter_m,
                        dangle_tolerance_m):
    """Seconda passata: mette insieme i grafi gia' puliti di tutte le
    sorgenti e ripete B, C e D (mai B2 - vedi il docstring di
    collapse_degree2_nodes sul perche'). Le mappe restano separate: ogni
    arco mantiene sempre il suo "src" originale, non viene mai fuso con un
    arco di un'altra sorgente (solo i NODI possono essere condivisi fra
    sorgenti diverse, mai gli archi). D qui e' particolarmente importante:
    il caso piu' comune di "estremo a vuoto" e' proprio fra sorgenti
    diverse, non dentro la stessa (Alessandro, 2026-10-03: "in questo caso,
    D va replicato nel passaggio 'a molte mappe', corretto?" - si')."""
    all_nodes = []
    all_edges = []
    for src_nodes, src_edges in per_source_graphs.values():
        offset = len(all_nodes)
        all_nodes.extend(src_nodes)
        for e in src_edges:
            all_edges.append({**e, "a": e["a"] + offset, "b": e["b"] + offset})
    nodes, edges = cluster_merge_nodes(all_nodes, all_edges, max_cluster_diameter_m)
    nodes, edges = create_crossing_nodes(nodes, edges, max_crossings_per_pair)
    nodes, edges = create_dangle_nodes(nodes, edges, dangle_tolerance_m)
    return nodes, edges


def _edges_signature(nodes, edges):
    """Firma geometrica del grafo, usata da build_source_graph_looped() per
    rilevare la convergenza del loop A/B/B2/C/D (v3): un insieme di tuple
    (sorgente, nome, i due estremi come lat/lon arrotondati - in ordine fisso
    cosi' da essere indipendenti dal verso dell'arco, lunghezza arrotondata).
    Deliberatamente NON usa gli indici "a"/"b" dei nodi: vengono ricalcolati
    da zero a ogni iterazione (nuova build_nodes_from_endpoints su linee
    rialimentate), quindi due iterazioni strutturalmente identiche
    avrebbero comunque indici diversi - la firma guarda solo alla geometria
    e agli attributi, mai alla numerazione interna. Due iterazioni con la
    stessa firma non hanno piu' cambiato nulla: il loop ha raggiunto un
    punto fisso."""
    sig = set()
    for e in edges:
        pa = tuple(nodes[e["a"]])
        pb = tuple(nodes[e["b"]])
        endpoints = tuple(sorted((pa, pb)))
        sig.add((e["src"], e.get("name"), endpoints, e["len"]))
    return frozenset(sig)


def _cluster_points(points, radius_m):
    """Clusterizzazione single-linkage di punti (lat, lon): due punti a
    distanza <= radius_m stanno nello stesso cluster (anche a catena).
    Ritorna una lista di (lat_centro, lon_centro, n_punti, estensione_m),
    dove il centro e' la media delle coordinate e l'estensione la massima
    distanza di un punto dal centro (un valore molto piu' grande del raggio
    segnala un cluster "a catena" lungo una strada). Ordinata per n_punti
    decrescente. Solo libreria standard (griglia di celle di lato radius_m
    + union-find), nessuna dipendenza da shapely."""
    n = len(points)
    if n == 0:
        return []
    lat0 = sum(p[0] for p in points) / n
    kx = 111320.0 * math.cos(math.radians(lat0))
    ky = 110540.0
    xy = [(p[1] * kx, p[0] * ky) for p in points]
    cell = max(radius_m, 1e-6)
    grid = defaultdict(list)
    for i, (x, y) in enumerate(xy):
        grid[(int(x // cell), int(y // cell))].append(i)
    parent = list(range(n))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i, (x, y) in enumerate(xy):
        cx, cy = int(x // cell), int(y // cell)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for j in grid.get((cx + dx, cy + dy), ()):
                    if j <= i:
                        continue
                    if math.hypot(xy[j][0] - x, xy[j][1] - y) <= radius_m:
                        ri, rj = find(i), find(j)
                        if ri != rj:
                            parent[rj] = ri
    groups = defaultdict(list)
    for i in range(n):
        groups[find(i)].append(i)
    clusters = []
    for idxs in groups.values():
        clat = sum(points[i][0] for i in idxs) / len(idxs)
        clon = sum(points[i][1] for i in idxs) / len(idxs)
        extent = max(
            math.hypot((points[i][1] - clon) * kx, (points[i][0] - clat) * ky)
            for i in idxs)
        clusters.append((clat, clon, len(idxs), extent))
    clusters.sort(key=lambda c: -c[2])
    return clusters


def _write_residual_report(path):
    """Scrive in `path` (txt) i residui dei loop per-sorgente: per ogni
    sorgente che NON ha convergito, un cluster per riga con lat, lon, numero
    di "bachi" ed estensione del cluster (Alessandro, 2026-10-13). Il file
    viene riscritto a ogni build, anche quando tutte le sorgenti convergono
    (in quel caso contiene solo l'intestazione e l'esito per sorgente)."""
    out = [
        "# Residui del loop A/B/B2/C/D per-sorgente (build_routing_graph_v3.py)",
        f"# generato il {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"# raggio di clusterizzazione (single-linkage): {RESIDUAL_CLUSTER_RADIUS_M:g} m "
        "(parametro residual_cluster_radius_m)",
        "# \"bachi\" = estremi \"a vuoto\" che il passo D ha ancora agganciato nell'ULTIMA",
        "# passata del loop (posizione prima dello snap); se l'ultima passata non ne ha",
        "# ma il grafo cambia ancora, = punto medio degli archi che differiscono dalla",
        "# passata precedente (metodo: diff).",
        "# colonne (separate da TAB): lat  lon  bachi  estensione_m  sorgente  metodo",
        "",
    ]
    if not RESIDUAL_BY_SOURCE:
        out.append("# nessuna sorgente con loop elaborata (use_neatnet = enable, oppure "
                   "tutte disabilitate)")
    for source_key, info in RESIDUAL_BY_SOURCE.items():
        if info["converged"]:
            out.append(f"# {source_key}: convergenza al passaggio {info['iterations']} - "
                       f"nessun residuo")
            continue
        method = "D" if info["dangle_points"] else "diff"
        points = info["dangle_points"] or info["changed_points"]
        clusters = _cluster_points(points, RESIDUAL_CLUSTER_RADIUS_M)
        out.append(f"# {source_key}: NON convergente dopo {info['iterations']} passaggi - "
                   f"{len(points)} punti in {len(clusters)} cluster (metodo {method})")
        for clat, clon, n, extent in clusters:
            out.append(f"{clat:.6f}\t{clon:.6f}\t{n}\t{extent:.0f}\t{source_key}\t{method}")
        out.append("")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


def _write_residual_pass_reports(out_dir):
    """Un file per passata del loop per-sorgente, residual_errors_passo_NN.txt
    in out_dir, stesso formato di residual_errors.txt: per ogni sorgente i
    cluster dei residui di QUELLA passata (estremi agganciati da D; se non ce
    ne sono, punto medio degli archi cambiati rispetto alla passata prima).
    I file di build precedenti vengono cancellati prima, cosi' non restano
    passate vecchie in piu' (Alessandro, 2026-10-14). Ritorna i file scritti."""
    out_dir.mkdir(parents=True, exist_ok=True)
    for vecchio in out_dir.glob("residual_errors_passo_*.txt"):
        try:
            vecchio.unlink()
        except Exception:
            pass
    n_passi = max((len(v) for v in RESIDUAL_PASSES.values()), default=0)
    scritti = []
    for k in range(n_passi):
        out = [
            f"# Residui della passata {k + 1} del loop A/B/B2/C/D per-sorgente "
            "(build_routing_graph_v3.py)",
            f"# generato il {time.strftime('%Y-%m-%d %H:%M:%S')}",
            f"# raggio di clusterizzazione (single-linkage): {RESIDUAL_CLUSTER_RADIUS_M:g} m "
            "(parametro residual_cluster_radius_m)",
            "# \"bachi\" = estremi \"a vuoto\" agganciati dal passo D in QUESTA passata",
            "# (posizione prima dello snap); se non ce ne sono, punto medio degli archi",
            "# che differiscono dalla passata precedente (metodo: diff).",
            "# colonne (separate da TAB): lat  lon  bachi  estensione_m  sorgente  metodo",
            "",
        ]
        for source_key, passes in RESIDUAL_PASSES.items():
            if k >= len(passes):
                out.append(f"# {source_key}: loop gia' terminato alla passata {len(passes)}")
                continue
            info = passes[k]
            if info["dangle_points"]:
                method, points = "D", info["dangle_points"]
            elif info["changed_points"]:
                method, points = "diff", info["changed_points"]
            else:
                stato = " (convergenza)" if info["converged"] else ""
                out.append(f"# {source_key}: nessun residuo in questa passata{stato}")
                continue
            clusters = _cluster_points(points, RESIDUAL_CLUSTER_RADIUS_M)
            out.append(f"# {source_key}: passata {k + 1} - {len(points)} punti in "
                       f"{len(clusters)} cluster (metodo {method})")
            for clat, clon, n, extent in clusters:
                out.append(f"{clat:.6f}\t{clon:.6f}\t{n}\t{extent:.0f}\t{source_key}\t{method}")
            out.append("")
        path = out_dir / f"residual_errors_passo_{k + 1:02d}.txt"
        path.write_text("\n".join(out) + "\n", encoding="utf-8")
        scritti.append(path)
    return scritti


def build_source_graph_looped(lines_for_source, max_crossings_per_pair, max_cluster_diameter_m,
                               dangle_tolerance_m, max_iterations, source_key):
    """Variante v3 del passo per-sorgente quando NEATNET_MODE e' "disable"
    (o la seconda meta' di "both", dopo neatnet)
    (Alessandro, 2026-10-03: "fai A B B2 C D a loop finche' non converge (e'
    rischioso, lo so, voglio vedere che succede)"): invece di una singola
    passata A->B->B2->C->D, la ripete rialimentando ad ogni iterazione gli
    archi appena prodotti come "linee" di partenza della successiva, finche'
    la struttura del grafo (vedi _edges_signature) smette di cambiare o si
    raggiunge max_iterations - un tetto di sicurezza esplicito, non un loop
    senza fine, proprio perche' la convergenza non e' garantita.

    Motivazione (dal caso reale del nodo 709, sessione del 2026-10-03): una
    singola passata di create_dangle_nodes() (passo D) decide tutti i suoi
    agganci sulla geometria degli archi COSI' COM'ERA all'inizio della
    chiamata; se l'aggancio di un nodo dangling taglia un arco proprio nel
    punto che servirebbe a un ALTRO nodo dangling, quel secondo nodo non
    viene mai riconsiderato nella stessa chiamata - serve una chiamata
    successiva, sulla geometria gia' aggiornata, perche' abbia una nuova
    occasione. Ripetere l'intera sequenza A/B/B2/C/D da capo (non solo D)
    tiene il grafo sempre internamente coerente a ogni iterazione."""
    lines = lines_for_source
    nodes, edges = [], []
    prev_sig = None
    last_dangles = []
    last_changed = []
    for iteration in range(1, max_iterations + 1):
        print(f"    {'=' * 60}")
        print(f"    [{source_key}] PASSAGGIO {iteration}/{max_iterations}")
        print(f"    {'=' * 60}")
        nodes, edges = build_source_graph(
            lines, max_crossings_per_pair, max_cluster_diameter_m, dangle_tolerance_m)
        sig = _edges_signature(nodes, edges)
        # Residui di QUESTA passata (servono solo se e' l'ultima senza
        # convergenza): estremi agganciati da D e, in subordine, punto medio
        # degli archi che differiscono dalla passata precedente.
        last_dangles = list(_LAST_DANGLE_POINTS)
        last_changed = []
        if prev_sig is not None:
            for _src, _name, (pa, pb), _len in (sig ^ prev_sig):
                last_changed.append(((pa[0] + pb[0]) / 2.0, (pa[1] + pb[1]) / 2.0))
        RESIDUAL_PASSES.setdefault(source_key, []).append({
            "dangle_points": list(last_dangles), "changed_points": list(last_changed),
            "converged": sig == prev_sig})
        if sig == prev_sig:
            print(f"    [{source_key}] convergenza raggiunta al passaggio {iteration}: "
                  f"nessun cambiamento strutturale rispetto al passaggio precedente")
            RESIDUAL_BY_SOURCE[source_key] = {
                "converged": True, "iterations": iteration,
                "dangle_points": [], "changed_points": []}
            break
        prev_sig = sig
        lines = [
            {"coords": e["geom"], "source": e["src"], "name": e.get("name")}
            for e in edges
        ]
    else:
        print(f"    [{source_key}] ATTENZIONE: raggiunto il tetto di sicurezza di "
              f"{max_iterations} iterazioni senza convergenza - uso comunque il risultato "
              f"dell'ultima iterazione, ma potrebbe non essere strutturalmente stabile")
        RESIDUAL_BY_SOURCE[source_key] = {
            "converged": False, "iterations": max_iterations,
            "dangle_points": last_dangles, "changed_points": last_changed}
        _n_res = len(last_dangles) or len(last_changed)
        print(f"    [{source_key}] residuo dell'ultima passata: {_n_res} punti in "
              f"{len(_cluster_points(last_dangles or last_changed, RESIDUAL_CLUSTER_RADIUS_M))} "
              f"cluster (elenco in debug_images/residual_errors.txt)")
    return nodes, edges


def _nan_to_none(value):
    """None resta None; un NaN float (tipico di una colonna pandas/geopandas
    senza valore per quella riga) diventa None; qualunque altro valore
    passa invariato. Usata da _neatnet_preprocess() per non riportare 'nan'
    come se fosse un nome o una sorgente veri."""
    if value is None:
        return None
    if isinstance(value, float) and math.isnan(value):
        return None
    return value


def _neatnet_preprocess(lines_for_source, max_cluster_diameter_m, dangle_tolerance_m, source_key):
    """Passata di pulizia v3 con la libreria neatnet, usata quando
    NEATNET_MODE e' "enable" o "both", PRIMA di dare il risultato in pasto alla
    build_source_graph() invariata - che qui funziona da rete di sicurezza
    per cio' che neatify() non risolve da sola (Alessandro, 2026-10-03: "se
    lo usa, fai come hai detto, ossia usala ma aggiungi quello che manca").

    geopandas e neatnet sono importati SOLO qui (lazy): chi non usa
    NEATNET_MODE="disable" (il default) non deve averli installati (vedi
    requirements_v3.txt,
    separato da requirements.txt).

    Riproiezione in UTM 33N (EPSG:32633, la zona corretta per l'area di
    Roma) fatta con GeoSeries.to_crs() di geopandas, non con pyproj.Transformer
    a mano: shapely/geopandas si aspettano sempre punti (lon, lat) = (x, y),
    mentre questo script usa ovunque (lat, lon) - lasciare che sia geopandas
    a invertire gli assi durante la riproiezione evita un bug di inversione
    lat/lon silenzioso che sarebbe facile introdurre scrivendolo a mano.

    NON passiamo dangle_tolerance_m al parametro min_dangle_length di
    neatify(): dalla documentazione di neatnet, min_dangle_length e' una
    soglia di POTATURA (elimina/accorcia i dangle piu' corti della soglia),
    non una soglia di CONNESSIONE come il nostro DANGLE_TOLERANCE_M -
    Alessandro ha detto piu' volte di essere restio a cancellare tracce
    reali che potrebbero divergere solo dopo un certo punto, quindi qui si
    lascia il default di neatnet invece di riusare un nostro parametro per
    un significato diverso da quello per cui e' stato pensato."""
    try:
        import geopandas as gpd
        import neatnet
    except ImportError as _e:
        raise RuntimeError(
            "geopandas/neatnet non installati in questo ambiente Python - "
            "esegui prima: pip3 install -r scripts/requirements_v3.txt "
            f"(errore originale: {_e})"
        ) from _e

    print(f"    [{source_key}] neatnet.neatify(): {len(lines_for_source)} linee grezze, "
          f"consolidation_tolerance={max_cluster_diameter_m}m, min_dangle_length=default di "
          f"neatnet (deliberatamente NON {dangle_tolerance_m}m - vedi commento nel codice)")

    names = [line.get("name") for line in lines_for_source]
    srcs = [line["source"] for line in lines_for_source]
    geoms = [LineString([(lon, lat) for lat, lon in line["coords"]]) for line in lines_for_source]
    gdf = gpd.GeoDataFrame({"name": names, "source": srcs}, geometry=geoms, crs="EPSG:4326")
    gdf_utm = gdf.to_crs("EPSG:32633")

    cleaned = neatnet.neatify(gdf_utm, consolidation_tolerance=max_cluster_diameter_m)
    cleaned_wgs84 = cleaned.to_crs("EPSG:4326")

    has_name = "name" in cleaned_wgs84.columns
    has_source = "source" in cleaned_wgs84.columns
    out_lines = []
    dropped = 0
    for _, row in cleaned_wgs84.iterrows():
        geom = row.geometry
        if geom is None or geom.is_empty:
            dropped += 1
            continue
        coords = [(lat, lon) for lon, lat in geom.coords]
        if len(coords) < 2:
            dropped += 1
            continue
        name = _nan_to_none(row["name"]) if has_name else None
        src = _nan_to_none(row["source"]) if has_source else None
        if src is None:
            src = source_key
        out_lines.append({"coords": coords, "source": src, "name": name})

    extra = f", {dropped} geometrie vuote/degeneri scartate" if dropped else ""
    print(f"    [{source_key}] neatnet.neatify() completata: {len(lines_for_source)} linee -> "
          f"{len(out_lines)} linee pulite{extra} (passate ora a build_source_graph() invariata "
          f"come rete di sicurezza)")
    return out_lines


def _source_enabled(source_key):
    """True se source_key va processata in questa build (v3, Alessandro,
    2026-10-03: "metti anche le sorgenti da usare in parametri, cosi' nel
    debug commento quello che non serve"): legge il parametro dinamico
    process_<source_key> generato piu' sotto in _PARAM_DEFAULTS, uno per
    ogni chiave di SOURCE_LABELS. Assente, commentato o non impostato ->
    abilitata di default (nessuna sorgente esclusa a sorpresa)."""
    return globals().get(f"PROCESS_{source_key.upper()}", True)


# ---------------------------------------------------------------------------
# Immagini di debug per sorgente: archi colorati, nodi come pallini, sopra
# tile OSM standard - SENZA overlay ciclabile (Alessandro, 2026-10-02: "la
# mappa sottostante ha gia' un percorso ciclabile disegnato dentro - le
# tiles dovrebbero avere solo le strade". Confermato: lo stile CyclOSM usato
# prima disegna lui stesso l'infrastruttura ciclabile reale dentro ai tile,
# in blu ("blue is for bikes" - wiki.openstreetmap.org/wiki/CyclOSM),
# confondibile con i nostri archi che sono anch'essi blu/vicino al blu).
# Una JPEG per sorgente di routing (chiave SOURCE_LABELS come nome file),
# in ASSETS_DIR/debug_images/.
#
# Area FISSA (centro e raggio in km, vedi DEBUG_IMAGE_CENTER_LAT/LON e
# DEBUG_IMAGE_RADIUS_KM piu' sotto), identica per tutte e 5 le immagini -
# non piu' calcolata dal bounding box di ciascuna sorgente: una sorgente
# con percorsi lontani (es. "piano_150km" dentro SIC, o gli itinerari
# regionali di Ciclopolitana) faceva coprire tutto il Lazio alla figura, a
# bassa risoluzione effettiva, invece della sola area urbana (Alessandro,
# 2026-10-02: "hai messo almeno tutta la provincia... non va bene"). Zoom
# nativo dei tile scelto automaticamente (_pick_fixed_area_zoom_and_factor)
# per essere il piu' leggero possibile senza mai essere piu' grezzo della
# risoluzione target (si restringe leggermente in disegno, non si allarga
# mai un tile scarso - niente sfocatura). Con i default (raggio 20km,
# canvas 8000px = 5 m/px target) servono ~44x44 tile a zoom 15, scaricati
# UNA SOLA VOLTA (stessa area/zoom per tutte le sorgenti: le prime 4 delle
# 5 immagini pescano dalla cache) - entro l'uso personale/non bulk della
# policy OSM (operations.osmfoundation.org/policies/tiles/: niente
# pre-seeding di aree grandi o piu' zoom, cache locale permanente, User-
# Agent dichiarato - tutte cose che questo script gia' rispetta).
TARGET_SIZE_PX = 8000
TILE_SIZE_PX = 256
MAX_TILE_ZOOM = 19  # zoom massimo utile dei tile OSM standard

EDGE_LINE_WIDTH_PX = 1
# Linee sottili per vedere le sovrapposizioni fra sorgenti diverse sullo
# stesso tratto, che a 4px si fondevano in un unico tratto indistinguibile
# (Alessandro, 2026-10-02: "voglio vedere dove si sovrappongono, cosi' non
# le vedo"). Il pallino dei nodi resta una misura fissa indipendente
# (non piu' derivata dallo spessore della linea, ora troppo sottile per
# restare un pallino visibile) - stessa dimensione di prima.
NODE_DOT_RADIUS_PX = 2.0

# Colori di archi e nodi, anch'essi editabili da precompila_parametri.txt
# (Alessandro, 2026-10-02: "rilancio mettendo le righe verdi, se continua
# il problema e vedo righe verdi grosse e sfumate non ci sono dubbi" - per
# distinguere un difetto nel disegno degli archi da un artefatto della
# mappa CyclOSM sottostante, che non ha mai linee verdi).
EDGE_LINE_COLOR = (0, 70, 220)
NODE_DOT_COLOR = (220, 20, 20)

# Centro e raggio dell'area fissa inquadrata in ogni JPEG di debug
# (Alessandro, 2026-10-02: "considererai come raggio circa quello del
# raccordo, diciamo 20 km intorno al centro di roma"). Centro di default:
# Piazza Venezia / Altare della Patria, il punto "Roma km 0" di
# riferimento (41.896435, 12.482531 - latitude.to). Raggio = meta' del
# lato del quadrato inquadrato (quindi 20km di raggio = area 40x40km,
# coerente con il calcolo 8000px/40km = 5 m/px fatto da Alessandro).
DEBUG_IMAGE_CENTER_LAT = 41.896435
DEBUG_IMAGE_CENTER_LON = 12.482531
DEBUG_IMAGE_RADIUS_KM = 20.0

# ---------------------------------------------------------------------------
# Parametri esterni, editabili senza toccare il codice (Alessandro,
# 2026-10-02: "voglio che i parametri siano in un precompila_parametri.txt
# che sta nella cartella principale, non in una sottocartella"). Il file
# vive nella cartella principale del progetto (salvaiciclisti/, NON
# scripts/) e sovrascrive, quando presente e valido, i default appena
# assegnati qui sopra. Un parametro assente, commentato (#) o con un
# valore non valido (testo al posto di un numero, ecc.) resta semplicemente
# al suo default - non blocca mai la build, al massimo stampa un avviso.
# Se il file non esiste ancora viene creato ora, precompilato con i
# default correnti e i relativi commenti, cosi' serve gia' da modello
# pronto all'uso. Solo per build_routing_graph_v2.py: build_routing_graph.py
# (v1) resta intoccato, come deciso.
PARAMS_FILE = ROOT / "precompila_parametri.txt"


def _parse_color(value_str):
    """'r,g,b' (ciascuno 0-255) -> tupla (r, g, b). Stesso trattamento di
    ogni altro parametro: un formato sbagliato solleva ValueError, che
    _load_external_params() intercetta gia' per tenere il default con un
    avviso, senza bloccare la build."""
    parts = value_str.split(",")
    if len(parts) != 3:
        raise ValueError(f"serve 'r,g,b', non {value_str!r}")
    r, g, b = (int(p.strip()) for p in parts)
    if not all(0 <= c <= 255 for c in (r, g, b)):
        raise ValueError(f"componenti colore fuori range 0-255: {value_str!r}")
    return (r, g, b)


def _parse_bool(value_str):
    """Caster per i parametri booleani di precompila_parametri.txt (v3):
    accetta, senza distinguere maiuscole/minuscole, true/si/yes/on/1 per
    vero e false/no/off/0 per falso; qualunque altro valore solleva
    ValueError, che _load_external_params() intercetta gia' per tenere il
    default con un avviso, invece di un parsing ambiguo silenzioso."""
    v = value_str.strip().lower()
    if v in ("true", "si", "yes", "on", "1"):
        return True
    if v in ("false", "no", "off", "0"):
        return False
    raise ValueError(f"valore booleano non riconosciuto: {value_str!r}")


def _parse_neatnet_mode(value_str):
    """Caster per use_neatnet (v3, esteso il 2026-10-03 da booleano a tre
    modalita' su richiesta di Alessandro: "inseriamo una terza opzione, che
    neatnet sia prima e poi il nostro sw"). Valori validi (senza distinguere
    maiuscole/minuscole): 'disable' (default - solo il nostro loop
    A/B/B2/C/D), 'enable' (solo neatnet + UNA passata del nostro codice
    come rete di sicurezza), 'both' (neatnet prima, poi il LOOP completo
    invece di una singola passata). true/si/yes/on/1 restano alias di
    'enable' e false/no/off/0 di 'disable', per compatibilita' con un file
    parametri scritto con la versione booleana precedente di questo
    switch. Qualunque altro valore solleva ValueError, che
    _load_external_params() intercetta gia' per tenere il default con un
    avviso, invece di un parsing ambiguo silenzioso."""
    v = value_str.strip().lower()
    if v in ("enable", "true", "si", "yes", "on", "1"):
        return "enable"
    if v in ("disable", "false", "no", "off", "0"):
        return "disable"
    if v == "both":
        return "both"
    raise ValueError(f"valore non riconosciuto per use_neatnet: {value_str!r} "
                      f"(valori validi: disable, enable, both)")


def _format_param_value(value):
    """Formatta un valore di default per precompila_parametri.txt - una
    tupla colore come 'r,g,b' (lo stesso formato che _parse_color legge),
    un booleano come 'true'/'false' minuscolo (lo stesso formato che
    _parse_bool si aspetta in lettura, invece di 'True'/'False' con la
    maiuscola che darebbe str() su un bool Python), tutto il resto con la
    sua rappresentazione normale."""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, tuple):
        return ",".join(str(c) for c in value)
    return str(value)


# chiave nel file -> (nome della costante globale, funzione di conversione,
# commento esplicativo scritto nel file la prima volta che viene generato)
_PARAM_DEFAULTS = {
    "node_cluster_max_diameter_m": ("NODE_CLUSTER_MAX_DIAMETER_M", float,
        "Diametro massimo (metri) di un gruppo di nodi fusi insieme (passo\n"
        "# B del clustering) - un incrocio reale piu' largo di cosi' resta\n"
        "# su piu' nodi distinti invece di diventarne uno solo."),
    "max_crossings_per_pair": ("MAX_CROSSINGS_PER_PAIR", int,
        "Oltre questo numero di incroci (passo C), una coppia di archi e'\n"
        "# considerata doppione/parallelismo spurio e scartata per intero."),
    "dangle_tolerance_m": ("DANGLE_TOLERANCE_M", float,
        "Distanza massima (metri) fra l'estremita' \"a vuoto\" di un arco e\n"
        "# il tracciato di un altro arco perche' il passo D li colleghi\n"
        "# (caso tipico: stesso incrocio reale digitalizzato da due fonti\n"
        "# con un piccolo scarto)."),
    "uturn_angle_threshold_deg": ("UTURN_ANGLE_THRESHOLD_DEG", float,
        "Soglia (gradi) oltre la quale un vertice e' un'inversione\n"
        "# \"avanti e indietro\" da rimuovere (angolo fra vettore di entrata\n"
        "# e di uscita)."),
    "debug_image_size_px": ("TARGET_SIZE_PX", int,
        "Dimensione (pixel) del lato del canvas quadrato di ogni JPEG di\n"
        "# debug per sorgente."),
    "debug_image_edge_width_px": ("EDGE_LINE_WIDTH_PX", int,
        "Spessore (pixel) delle linee degli archi nelle JPEG di debug."),
    "debug_image_node_radius_px": ("NODE_DOT_RADIUS_PX", float,
        "Raggio (pixel) dei pallini rossi dei nodi nelle JPEG di debug."),
    "debug_image_center_lat": ("DEBUG_IMAGE_CENTER_LAT", float,
        "Latitudine del centro dell'area fissa inquadrata nelle JPEG di\n"
        "# debug (default: Piazza Venezia, \"Roma km 0\")."),
    "debug_image_center_lon": ("DEBUG_IMAGE_CENTER_LON", float,
        "Longitudine del centro dell'area fissa inquadrata nelle JPEG di\n"
        "# debug."),
    "debug_image_radius_km": ("DEBUG_IMAGE_RADIUS_KM", float,
        "Raggio (km) dell'area inquadrata nelle JPEG di debug - meta' del\n"
        "# lato del quadrato (20 = area 40x40km). La risoluzione (m/px) si\n"
        "# ricalcola da sola da questo raggio e da debug_image_size_px."),
    "debug_image_edge_color": ("EDGE_LINE_COLOR", _parse_color,
        "Colore delle linee degli archi nelle JPEG di debug, 'r,g,b'\n"
        "# (0-255 ciascuno) - libero, per distinguerli bene dallo sfondo OSM."),
    "debug_image_node_color": ("NODE_DOT_COLOR", _parse_color,
        "Colore dei pallini dei nodi nelle JPEG di debug, 'r,g,b'."),
}

# Parametri v3 aggiunti dinamicamente (Alessandro, 2026-10-03: "metti anche
# le sorgenti da usare in parametri, cosi' nel debug commento quello che
# non serve") - un process_<source_key> per ogni chiave di SOURCE_LABELS
# (default True: nessuna sorgente esclusa a sorpresa se la riga manca dal
# file), piu' use_neatnet e source_pass_max_iterations. Il default in
# globals()[global_name] deve gia' esistere a questo punto perche'
# _write_default_params_file() lo legga: per use_neatnet/
# source_pass_max_iterations e' gia' impostato piu' in alto
# (NEATNET_MODE/SOURCE_PASS_MAX_ITERATIONS); per ogni process_<source_key>
# lo impostiamo qui stesso, subito prima di aggiungerlo a _PARAM_DEFAULTS.
_PARAM_DEFAULTS["use_neatnet"] = ("NEATNET_MODE", _parse_neatnet_mode,
    "Tre modalita': 'disable' (default) solo il nostro loop A/B/B2/C/D;\n"
    "# 'enable' solo neatnet + una passata del nostro codice come rete di\n"
    "# sicurezza; 'both' neatnet PRIMA, poi il loop completo (non una sola\n"
    "# passata) - vedi il docstring del modulo per i dettagli.")
_PARAM_DEFAULTS["source_pass_max_iterations"] = ("SOURCE_PASS_MAX_ITERATIONS", int,
    "Tetto di sicurezza al numero di iterazioni del loop A/B/B2/C/D per-\n"
    "# sorgente quando use_neatnet e' false - la convergenza non e'\n"
    "# garantita, quindi il loop si ferma comunque qui se non si stabilizza.")
_PARAM_DEFAULTS["residual_cluster_radius_m"] = ("RESIDUAL_CLUSTER_RADIUS_M", float,
    "Raggio (metri) per raggruppare in zone i punti dove il loop per-sorgente\n"
    "# non converge entro source_pass_max_iterations: due punti piu' vicini di\n"
    "# cosi' stanno nello stesso cluster (anche a catena). L'elenco finisce in\n"
    "# percorsi/build/routing_v3/debug_images/residual_errors.txt.")
for _source_key in SOURCE_LABELS:
    _global_name = f"PROCESS_{_source_key.upper()}"
    globals()[_global_name] = True
    _PARAM_DEFAULTS[f"process_{_source_key}"] = (_global_name, _parse_bool,
        f"Se processare la sorgente '{_source_key}' in questa build (true,\n"
        f"# default) oppure saltarla per intero (false) - comodo in debug per\n"
        f"# isolare una singola sorgente senza commentare codice.")

# Interruttori per MAPPA e per LAYER (Alessandro, 2026-10-14). Per ogni
# mappa (chiave di SOURCE_LABELS) c'e' process_<mappa> e, subito sotto, un
# layer_<prefisso>_<file> = true/false per ogni suo .geojson in percorsi/raw/
# (sottocartelle comprese, esclusi SKIP_FILENAMES). La Ciclopolitana ha come
# layer i suoi due file dentro la cartella di SIC. process_<mappa> = false
# spegne la mappa e TUTTI i suoi layer, qualunque sia il loro valore. Un layer
# spento non entra ne' nel grafo (load_lines) ne' nei layer di dettaglio
# (build_detail_layers). Tutti gli interruttori stanno in UN blocco ordinato in
# fondo a precompila_parametri.txt, riscritto a ogni lancio da
# _riscrivi_blocco_interruttori() conservando i valori gia' impostati.
# Default di un layer nuovo: acceso, tranne per SIC, dove nasce acceso solo se
# e' in SIC_LAYER_DEFAULT_ON (gli unici da usare secondo Alessandro).
SIC_LAYER_DEFAULT_ON = {
    "CiclabiliEsist_Municipi", "Passaggi_Segreti", "Punti_Riparazione_Bici",
    "Strade_Scolastiche", "Autorimessebikefriendly", "Infrastrutture_Ostacoli_Interruzioni",
    "Rastrelliere", "NegoziBikeFriendly", "BikeBoxes_Comune_di_Roma",
}
LAYER_PREFIX = {
    "salvaiciclisti_sicrm": "sic",
    "salvaiciclisti_ciclopolitana": "ciclopolitana",
    "g3w_ciclovie_metropolitane": "g3w",
    "rsm_piste_ciclabili": "rsm",
    "osm_extract": "osm",
    "raccordi": "raccordi",
    "debug": "debug",
}


def _layer_prefix(source_key):
    return LAYER_PREFIX.get(source_key, re.sub(r"[^0-9a-z]+", "_", source_key.lower()))


def _layer_param_key(source_key, path):
    return ("layer_" + _layer_prefix(source_key) + "_"
            + re.sub(r"[^0-9a-z]+", "_", path.stem.lower()).strip("_"))


def _layer_default(source_key, path):
    if source_key == "salvaiciclisti_sicrm":
        return path.stem in SIC_LAYER_DEFAULT_ON
    return True


def _layer_files(source_key):
    """I .geojson (Path) che formano i layer della mappa source_key."""
    if source_key == "salvaiciclisti_ciclopolitana":
        d = RAW_DIR / "salvaiciclisti_sicrm"
        return [p for p in sorted(d.rglob("*.geojson")) if p.name in CICLOPOLITANA_FILENAMES] \
            if d.is_dir() else []
    d = RAW_DIR / source_key
    if not d.is_dir():
        return []
    return [p for p in sorted(d.rglob("*.geojson"))
            if p.name not in SKIP_FILENAMES
            and not (source_key == "salvaiciclisti_sicrm" and p.name in CICLOPOLITANA_FILENAMES)]


def _layer_enabled(source_key, path):
    """False se la mappa (process_<mappa>) o il layer (layer_<...>) e' spento.
    source_key e' la mappa EFFETTIVA (per i file della Ciclopolitana dentro la
    cartella di SIC passare 'salvaiciclisti_ciclopolitana')."""
    if source_key in SOURCE_LABELS and not _source_enabled(source_key):
        return False
    key = _layer_param_key(source_key, path)
    return globals().get(key.upper(), _layer_default(source_key, path))


_LAYER_KEYS_BY_SOURCE = {}
for _source_key in SOURCE_LABELS:
    _LAYER_KEYS_BY_SOURCE[_source_key] = []
    for _path in _layer_files(_source_key):
        _key = _layer_param_key(_source_key, _path)
        globals()[_key.upper()] = _layer_default(_source_key, _path)
        _PARAM_DEFAULTS[_key] = (_key.upper(), _parse_bool, "")
        _LAYER_KEYS_BY_SOURCE[_source_key].append((_key, _path))

_FLAG_LINE_RE = re.compile(r"^[ \t]*#?[ \t]*(process_|layer_)[0-9a-z_]+[ \t]*=", re.IGNORECASE)
_BLOCK_START = "# =================== INTERRUTTORI MAPPE E LAYER ==================="
_BLOCK_END = "# ================= FINE INTERRUTTORI MAPPE E LAYER ================="


def _riscrivi_blocco_interruttori(orfani):
    """Toglie da precompila_parametri.txt ogni riga process_*/layer_* (e il
    commento subito sopra, fino alla riga vuota) e il vecchio blocco, poi
    riscrive in fondo UN blocco ordinato per mappa con i valori attuali.
    orfani: {chiave layer_: valore} letti dal file ma senza file su disco
    (conservati in coda al blocco, cosi' il valore non si perde)."""
    try:
        testo = PARAMS_FILE.read_text(encoding="utf-8")
    except Exception as e:
        print(f"  [parametri] impossibile leggere {PARAMS_FILE}: {e}")
        return
    righe = testo.splitlines()
    tenute = []
    dentro = False
    for r in righe:
        if r.strip() == _BLOCK_START:
            dentro = True
            continue
        if r.strip() == _BLOCK_END:
            dentro = False
            continue
        if dentro:
            continue
        if _FLAG_LINE_RE.match(r):
            # via anche il commento contiguo subito sopra
            while tenute and tenute[-1].lstrip().startswith("#"):
                tenute.pop()
            continue
        tenute.append(r)
    # intestazioni "--- parametri aggiunti automaticamente ---" rimaste senza
    # parametri sotto (il prossimo contenuto e' un'altra intestazione o la
    # fine) e righe vuote ripetute
    def _intestazione(r):
        return ("parametri aggiunti automaticamente" in r
                or "recente dello script (non toccano" in r)
    pulite = []
    for i, r in enumerate(tenute):
        if _intestazione(r):
            dopo = [x for x in tenute[i + 1:] if x.strip() and not _intestazione(x)]
            j = next((k for k in range(i + 1, len(tenute))
                      if tenute[k].strip() and not _intestazione(tenute[k])), None)
            prossima_intestazione = next((k for k in range(i + 1, len(tenute))
                                          if _intestazione(tenute[k])
                                          and "parametri aggiunti" in tenute[k]), None)
            if not dopo or (prossima_intestazione is not None and j is not None
                            and prossima_intestazione < j):
                continue
        if not r.strip() and pulite and not pulite[-1].strip():
            continue
        pulite.append(r)
    tenute = pulite
    while tenute and not tenute[-1].strip():
        tenute.pop()
    blocco = [
        "", _BLOCK_START,
        "# Blocco riscritto automaticamente a ogni lancio di build_routing_graph_v3",
        "# (i valori impostati qui vengono conservati). process_<mappa> = false spegne",
        "# la mappa e TUTTI i suoi layer, qualunque sia il valore dei layer_ sotto.",
        "# I layer nuovi nascono accesi (per SIC solo quelli di SIC_LAYER_DEFAULT_ON).",
    ]
    for source_key, label in SOURCE_LABELS.items():
        blocco += ["", "# " + "=" * 66, f"# {source_key} - {label}",
                   f"process_{source_key} = {_format_param_value(_source_enabled(source_key))}"]
        for key, path in _LAYER_KEYS_BY_SOURCE.get(source_key, []):
            blocco.append(f"{key} = {_format_param_value(globals()[key.upper()])}")
    if orfani:
        blocco += ["", "# " + "=" * 66,
                   "# layer senza file su disco (valore conservato finche' il file non torna)"]
        for key, val in sorted(orfani.items()):
            blocco.append(f"{key} = {_format_param_value(val)}")
    blocco += ["", _BLOCK_END, ""]
    nuovo = "\n".join(tenute) + "\n" + "\n".join(blocco)
    if nuovo != testo:
        try:
            PARAMS_FILE.write_text(nuovo, encoding="utf-8")
            print(f"  [parametri] riscritto il blocco interruttori mappe/layer in {PARAMS_FILE.name}")
        except Exception as e:
            print(f"  [parametri] impossibile riscrivere {PARAMS_FILE}: {e}")


def _write_default_params_file():
    lines = [
        "# Parametri di precompila_v2 (build_routing_graph_v2.py).",
        "# Modifica un valore qui sotto e rilancia precompila_v2: non serve",
        "# toccare il codice. Un parametro mancante, commentato (con #) o con",
        "# un valore non valido (testo al posto di un numero, ecc.) resta",
        "# semplicemente al suo default - non blocca mai la build.",
        "",
    ]
    for key, (global_name, _caster, comment) in _PARAM_DEFAULTS.items():
        if key.startswith(("process_", "layer_")):
            continue  # stanno nel blocco interruttori (_riscrivi_blocco_interruttori)
        lines.append(f"# {comment}")
        lines.append(f"{key} = {_format_param_value(globals()[global_name])}")
        lines.append("")
    PARAMS_FILE.write_text("\n".join(lines), encoding="utf-8")


def _load_external_params():
    if not PARAMS_FILE.exists():
        try:
            _write_default_params_file()
        except Exception as e:
            print(f"  [parametri] impossibile creare {PARAMS_FILE}: {e}")
        _riscrivi_blocco_interruttori({})
        return

    try:
        raw_text = PARAMS_FILE.read_text(encoding="utf-8")
    except Exception as e:
        print(f"  [parametri] impossibile leggere {PARAMS_FILE}: {e} - uso i default")
        return

    _orfani = {}
    raw_lines = raw_text.splitlines()
    for line_no, raw_line in enumerate(raw_lines, start=1):
        line = raw_line.split("#", 1)[0].strip()
        if not line or "=" not in line:
            continue
        key, _, value_str = line.partition("=")
        key = key.strip().lower()
        value_str = value_str.strip()
        if key not in _PARAM_DEFAULTS:
            if key.startswith("layer_"):
                try:
                    _orfani[key] = _parse_bool(value_str)
                except (TypeError, ValueError):
                    pass
                continue
            print(f"  [parametri] riga {line_no}: parametro '{key}' sconosciuto, ignorato")
            continue
        global_name, caster, _comment = _PARAM_DEFAULTS[key]
        try:
            value = caster(value_str)
        except (TypeError, ValueError):
            print(f"  [parametri] riga {line_no}: valore non valido per '{key}' "
                  f"({value_str!r}) - resto al default {globals()[global_name]}")
            continue
        globals()[global_name] = value
        print(f"  [parametri] {key} = {value} (da {PARAMS_FILE.name})")

    # Parametri introdotti da una versione piu' recente dello script (qui:
    # i process_<sorgente>/use_neatnet/source_pass_max_iterations di v3) ma
    # MAI apparsi nel file, nemmeno commentati: li aggiunge in fondo ora,
    # col loro default corrente, cosi' restano visibili ed editabili -
    # altrimenti, visto che il file esiste gia' (creato a suo tempo da v2),
    # _write_default_params_file() qui sopra non viene mai richiamata e
    # questi parametri resterebbero invisibili per sempre pur funzionando
    # di default (bug segnalato da Alessandro, 2026-10-03: "mancano le
    # sorgenti abilitate e lo switch neatnet"). Una riga gia' presente ma
    # COMMENTATA (#) conta come "gia' apparsa" e non viene toccata, per non
    # riattivare un parametro disabilitato di proposito.
    missing = []
    for key in _PARAM_DEFAULTS:
        if key.startswith(("process_", "layer_")):
            continue  # gestiti da _riscrivi_blocco_interruttori qui sotto
        pattern = re.compile(rf"(?m)^[ \t]*#?[ \t]*{re.escape(key)}[ \t]*=")
        if not pattern.search(raw_text):
            missing.append(key)
    if missing:
        addition = ["", "# --- parametri aggiunti automaticamente da una versione piu'",
                    "# recente dello script (non toccano le righe sopra) ---", ""]
        for key in missing:
            global_name, _caster, comment = _PARAM_DEFAULTS[key]
            addition.append(f"# {comment}")
            addition.append(f"{key} = {_format_param_value(globals()[global_name])}")
            addition.append("")
        try:
            PARAMS_FILE.write_text(raw_text.rstrip("\n") + "\n" + "\n".join(addition),
                                    encoding="utf-8")
            print(f"  [parametri] aggiunti in fondo a {PARAMS_FILE.name} i nuovi parametri: "
                  f"{', '.join(missing)} (coi valori di default correnti)")
        except Exception as e:
            print(f"  [parametri] impossibile aggiungere i nuovi parametri a "
                  f"{PARAMS_FILE}: {e} - uso comunque i default in memoria")

    _riscrivi_blocco_interruttori(_orfani)


_load_external_params()

# OSM standard (niente overlay ciclabile) invece di CyclOSM - Alessandro,
# 2026-10-02: "cancello tutte queste tile e le ricarichiamo da OSM, non da
# cyclOSM, senza piste". Un solo dominio: la policy OSM attuale
# (operations.osmfoundation.org/policies/tiles/) dichiara esplicitamente
# deprecato il round-robin a/b/c ("other subdomains... may be withdrawn
# without notice").
TILE_URL_TEMPLATES = (
    "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
)

# Cache locale PERMANENTE su disco, indipendente dalla run/sessione
# (Alessandro, 2026-10-02: "crea una cache locale, permanente,
# indipendentemente dalla sessione") - stessa cartella di RAW_DIR
# (percorsi/raw/), stesso spirito: una volta scaricato un tile non si
# riscarica mai piu', nemmeno in un lancio futuro di precompila_v2.py.
# Condivisa fra le 5 immagini di una stessa run (gia' elimina la maggior
# parte dei doppioni, vedi la cache in memoria qui sotto) E fra run diverse
# nel tempo. Dimensione attesa con i dati di oggi: qualche migliaio di tile,
# poche decine di MB in tutto - nessuna pulizia/scadenza automatica, non
# serve per questo volume.
TILE_CACHE_DIR = RAW_DIR / "tile_cache_osm"  # rinominata da tile_cache_cyclosm
# il 2026-10-02 passando a tile OSM standard - la vecchia cartella (tile
# CyclOSM, con overlay ciclabile) non viene piu' letta da questo script.


def _lon_to_x(lon, zoom):
    n = 2 ** zoom
    return (lon + 180.0) / 360.0 * n * TILE_SIZE_PX


def _lat_to_y(lat, zoom):
    n = 2 ** zoom
    lat_rad = math.radians(max(min(lat, 85.05112878), -85.05112878))
    return (1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n * TILE_SIZE_PX


def _resolution_m_per_px(zoom, lat):
    """Risoluzione a terra (metri per pixel) di un tile Web Mercator
    standard (256px) a un certo zoom e una certa latitudine - formula
    standard: 156543.03392 m/px all'equatore a zoom 0, dimezzata a ogni
    zoom successivo, scalata per cos(latitudine)."""
    return 156543.03392 * math.cos(math.radians(lat)) / (2 ** zoom)


def _pick_fixed_area_zoom_and_factor(center_lat, radius_km, target_px):
    """Sceglie lo zoom nativo dei tile - il PIU' BASSO (quindi il piu'
    leggero da scaricare) che non sia pero' piu' grezzo della risoluzione
    target, cosi' da non dover mai ingrandire/sfocare un tile troppo poco
    dettagliato - e il fattore di scala da applicare in disegno per
    arrivare esattamente alla risoluzione target su un'area quadrata di
    lato 2*radius_km centrata su center_lat. Esempio con i default (raggio
    20km, canvas 8000px): target 5 m/px, zoom nativo 15 (~3.56 m/px a
    Roma, l'immediato successivo a 14 che da solo non basterebbe, ~7.11
    m/px) scalato con un fattore ~0.71 (si restringe leggermente, mai il
    contrario)."""
    span_m = radius_km * 2 * 1000.0
    target_res = span_m / target_px
    zoom = MAX_TILE_ZOOM
    for z in range(0, MAX_TILE_ZOOM + 1):
        if _resolution_m_per_px(z, center_lat) <= target_res:
            zoom = z
            break
    native_res = _resolution_m_per_px(zoom, center_lat)
    factor = target_px * native_res / span_m
    return zoom, factor


# Cache in memoria per-run (oltre a quella su disco sotto): dentro la
# stessa chiamata a render_source_debug_images() piu' sorgenti allo stesso
# zoom condividono spesso lo stesso tile (es. RSM/SIC/Ciclopolitana, tutte a
# zoom 14 sui dati di oggi) - evita anche la sola lettura/decodifica da
# disco ripetuta inutilmente. Svuotata a ogni processo nuovo (non e' quella
# "permanente" - quella e' TILE_CACHE_DIR su disco).
_TILE_MEMORY_CACHE = {}


def _tile_cache_path(z, x, y):
    return TILE_CACHE_DIR / str(z) / str(x) / f"{y}.png"


def _fetch_tile(z, x, y, retries=3):
    """Un tile PNG (256x256) da CyclOSM, con cache permanente su disco
    (TILE_CACHE_DIR) oltre a quella in memoria per-run, e retry/backoff solo
    sui download veri - None se non disponibile (404, o fallito dopo tutti
    i tentativi): un tile mancante lascia solo un buco grigio nello sfondo,
    non deve mai far fallire l'intera immagine."""
    key = (z, x, y)
    if key in _TILE_MEMORY_CACHE:
        return _TILE_MEMORY_CACHE[key]

    n = 2 ** z
    if not (0 <= x < n and 0 <= y < n):
        _TILE_MEMORY_CACHE[key] = None
        return None

    cache_path = _tile_cache_path(z, x, y)
    if cache_path.exists():
        try:
            img = Image.open(cache_path).convert("RGB")
            _TILE_MEMORY_CACHE[key] = img
            return img
        except Exception:
            # file di cache corrotto (es. scrittura interrotta in una run
            # precedente) - lo ignora e lo riscarica sotto come se non ci
            # fosse, poi lo sovrascrive.
            pass

    url = TILE_URL_TEMPLATES[(x + y) % len(TILE_URL_TEMPLATES)].format(z=z, x=x, y=y)
    backoff = 1.0
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": "salvaiciclisti-precompila_v2/1.0 "
                              "(script locale, uso personale - "
                              "github.com/alessandromura-droid/test-sic)",
            })
            with urllib.request.urlopen(req, timeout=20) as resp:
                data = resp.read()
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            cache_path.write_bytes(data)
            img = Image.open(io.BytesIO(data)).convert("RGB")
            _TILE_MEMORY_CACHE[key] = img
            return img
        except urllib.error.HTTPError as e:
            if e.code == 404:
                _TILE_MEMORY_CACHE[key] = None
                return None
            if attempt == retries:
                print(f"    [debug_images] tile {z}/{x}/{y} fallito ({e}), lascio vuoto")
                _TILE_MEMORY_CACHE[key] = None
                return None
        except Exception as e:
            if attempt == retries:
                print(f"    [debug_images] tile {z}/{x}/{y} fallito ({e}), lascio vuoto")
                _TILE_MEMORY_CACHE[key] = None
                return None
        time.sleep(backoff)
        backoff *= 2
    _TILE_MEMORY_CACHE[key] = None
    return None


def _edges_and_nodes_to_json(nodes_list, edges_list, to_canvas_px):
    """Converte (nodes, edges) - stessa identica selezione di nodi
    effettivamente referenziati da almeno un arco gia' usata per disegnare
    i pallini nelle JPEG di debug, rimappati a indici compatti 0..N-1 - in
    due dict JSON-serializzabili: uno in coordinate PIXEL canvas (stesso
    spazio della JPEG corrispondente - Alessandro, 2026-10-03: "salva
    direttamente i nodi in coordinate pixel, a me non cambia nulla"), uno
    in lat/lon (per completezza - "dovessero chiedermelo dei
    collaboratori... su un file diverso cosi' in caso lo cancello")."""
    node_ids = sorted({nid for e in edges_list for nid in (e["a"], e["b"])})
    remap = {old: new for new, old in enumerate(node_ids)}
    nodes_latlon = [nodes_list[nid] for nid in node_ids]
    nodes_px = [to_canvas_px(lat, lon) for lat, lon in nodes_latlon]

    def edge_record(e, geom_key, geom_points):
        return {
            "a": remap[e["a"]], "b": remap[e["b"]],
            "src": e["src"], "name": e.get("name"), "len": e["len"],
            geom_key: geom_points,
        }

    edges_px = [edge_record(e, "geom_px",
                             [[round(x, 1), round(y, 1)] for x, y in
                              (to_canvas_px(lat, lon) for lat, lon in e["geom"])])
                for e in edges_list]
    edges_latlon = [edge_record(e, "geom", e["geom"]) for e in edges_list]

    json_px = {
        "nodes_px": [[round(x, 1), round(y, 1)] for x, y in nodes_px],
        "edges": edges_px,
    }
    json_latlon = {
        "nodes": [[lat, lon] for lat, lon in nodes_latlon],
        "edges": edges_latlon,
    }
    return json_px, json_latlon


def _write_stage_json(nodes_list, edges_list, to_canvas_px, out_dir, stem):
    """Scrive {stem}.json (coordinate pixel, il formato che Alessandro
    plotta in MATLAB) e {stem}_latlon.json (stesso grafo in lat/lon, file
    separato apposta cosi' si puo' cancellare senza perdere il primo) -
    Alessandro, 2026-10-03: "salviamo i risultati intermedi non solo come
    jpeg ma anche come file [...] per _auto e _final"."""
    json_px, json_latlon = _edges_and_nodes_to_json(nodes_list, edges_list, to_canvas_px)
    (out_dir / f"{stem}.json").write_text(
        json.dumps(json_px, ensure_ascii=False), encoding="utf-8")
    (out_dir / f"{stem}_latlon.json").write_text(
        json.dumps(json_latlon, ensure_ascii=False), encoding="utf-8")
    print(f"  [debug_images] scritti {stem}.json (pixel) e {stem}_latlon.json (lat/lon)")


def _raw_lines_to_json(raw_lines, to_canvas_px):
    """Variante di _edges_and_nodes_to_json() per lo stadio _orig (Alessandro,
    2026-10-03: "i pallini non puoi salvarli per _orig, perche' non ci sono
    [...] ma non ho detto che non volevo il json [...] json anche per orig,
    solo archi ovviamente"): a questo stadio non esiste ancora alcun
    concetto di nodo (nasce solo al passo A di build_source_graph()), quindi
    ogni voce di raw_lines e' gia' di per se' un arco - "a"/"b"/"len" non si
    applicano e vengono omessi. "nodes_px"/"nodes" restano liste VUOTE, non
    assenti: plot_debug_graph.m accede sempre a data.nodes_px, e un array
    vuoto gli permette di continuare a funzionare invariato anche qui (un
    plot di coordinate vuote non produce errori, semplicemente nessun
    pallino - coerente con l'JPEG di questo stesso stadio, che non ne
    disegna nessuno)."""
    def edge_record(line, geom_key, geom_points):
        return {
            "src": line["source"], "name": line.get("name"),
            geom_key: geom_points,
        }

    edges_px = [edge_record(line, "geom_px",
                             [[round(x, 1), round(y, 1)] for x, y in
                              (to_canvas_px(lat, lon) for lat, lon in line["coords"])])
                for line in raw_lines]
    edges_latlon = [edge_record(line, "geom", line["coords"]) for line in raw_lines]

    json_px = {"nodes_px": [], "edges": edges_px}
    json_latlon = {"nodes": [], "edges": edges_latlon}
    return json_px, json_latlon


def _write_raw_lines_json(raw_lines, to_canvas_px, out_dir, stem):
    """Come _write_stage_json(), ma per lo stadio _orig: solo linee grezze,
    nessun nodo (vedi _raw_lines_to_json())."""
    json_px, json_latlon = _raw_lines_to_json(raw_lines, to_canvas_px)
    (out_dir / f"{stem}.json").write_text(
        json.dumps(json_px, ensure_ascii=False), encoding="utf-8")
    (out_dir / f"{stem}_latlon.json").write_text(
        json.dumps(json_latlon, ensure_ascii=False), encoding="utf-8")
    print(f"  [debug_images] scritti {stem}.json (pixel) e {stem}_latlon.json (lat/lon)")


def render_source_debug_images(nodes, edges, out_dir, raw_lines_by_source, auto_by_source):
    """Tre JPEG per sorgente invece di una sola (Alessandro, 2026-10-02:
    "per capire meglio cosa sta succedendo, devi triplicare il numero di
    jpeg"), per separare visivamente l'effetto di ciascuna fase della
    pipeline di noding sulla stessa sorgente:

      - {source}_orig_<pxkm>pxkm.jpg: il dato grezzo cosi' come arriva da
        load_lines(), PRIMA di remove_uturn_spikes e di A/B/B2/C/D - SOLO
        linee, nessun pallino: il concetto di nodo non esiste ancora a
        questo stadio (Alessandro, 2026-10-03: "_orig mostra dei nodi. non
        ci possono essere nodi" - corretto, il nodo nasce solo al passo A).
      - {source}_auto_<pxkm>pxkm.jpg: questa sorgente dopo la sua passata
        isolata A->B->B2->C->D (build_source_graph) - ESATTAMENTE lo
        stadio reale che build() calcola per questa sorgente prima di
        metterla insieme alle altre quattro, non un rifacimento ipotetico.
      - {source}_final_<pxkm>pxkm.jpg: il grafo consolidato finale, dopo la
        seconda passata globale (solo B, C e D, tutte le sorgenti insieme -
        mai B2) - stesso identico dato che finisce in graph.json.

    <pxkm> e' la risoluzione reale dell'immagine in pixel per chilometro
    (identica per le tre JPEG e per tutte le sorgenti, stessa area fissa) -
    Alessandro, 2026-10-03: serve per misurare a occhio le distanze reali
    guardando i pixel, senza dover leggere il codice per sapere la scala.

    Area fissa e tile condivisi identici a prima (vedi sopra) - solo il
    numero di canvas per sorgente passa da 1 a 3, stesso identico stile
    (colori/spessori da precompila_parametri.txt). Un fallimento (rete
    assente, tile irraggiungibili, dati mancanti per una delle tre fasi) non
    deve mai interrompere il resto della build - solo saltare quella singola
    immagine, loggato chiaramente."""
    out_dir.mkdir(parents=True, exist_ok=True)

    by_source = defaultdict(list)
    for e in edges:
        by_source[e["src"]].append(e)

    zoom, factor = _pick_fixed_area_zoom_and_factor(
        DEBUG_IMAGE_CENTER_LAT, DEBUG_IMAGE_RADIUS_KM, TARGET_SIZE_PX)
    native_res = _resolution_m_per_px(zoom, DEBUG_IMAGE_CENTER_LAT)
    target_res = (DEBUG_IMAGE_RADIUS_KM * 2000.0) / TARGET_SIZE_PX
    # Risoluzione in pixel/km inserita nel nome dei file (Alessandro,
    # 2026-10-03: serve per poter misurare a occhio, pixel per pixel, la
    # distanza reale fra un nodo e un arco vicino in una JPEG di debug,
    # senza dover leggere il codice per sapere la scala) - identica per
    # tutte le immagini (stessa area fissa/zoom condivisi).
    px_per_km = 1000.0 / target_res
    span_px = TARGET_SIZE_PX / factor
    cx = _lon_to_x(DEBUG_IMAGE_CENTER_LON, zoom)
    cy = _lat_to_y(DEBUG_IMAGE_CENTER_LAT, zoom)
    world_left = cx - span_px / 2
    world_top = cy - span_px / 2

    tile_x0 = int(world_left // TILE_SIZE_PX)
    tile_x1 = int((world_left + span_px) // TILE_SIZE_PX)
    tile_y0 = int(world_top // TILE_SIZE_PX)
    tile_y1 = int((world_top + span_px) // TILE_SIZE_PX)
    n_tiles = (tile_x1 - tile_x0 + 1) * (tile_y1 - tile_y0 + 1)
    tile_size_scaled = max(1, round(TILE_SIZE_PX * factor))
    print(f"  [debug_images] area fissa: centro ({DEBUG_IMAGE_CENTER_LAT:.6f}, "
          f"{DEBUG_IMAGE_CENTER_LON:.6f}), raggio {DEBUG_IMAGE_RADIUS_KM}km, "
          f"zoom={zoom} (nativo {native_res:.2f}m/px -> target {target_res:.2f}m/px), "
          f"{n_tiles} tile condivisi fra le {len(SOURCE_LABELS) * 3} immagini")

    # Metadati dell'area fissa, un solo file condiviso (Alessandro,
    # 2026-10-03: utile per completezza anche se lo script MATLAB plotta
    # direttamente in pixel e non ne ha bisogno per funzionare).
    metadata_path = out_dir / "area_metadata.json"
    try:
        metadata_path.write_text(json.dumps({
            "center_lat": DEBUG_IMAGE_CENTER_LAT, "center_lon": DEBUG_IMAGE_CENTER_LON,
            "radius_km": DEBUG_IMAGE_RADIUS_KM, "canvas_size_px": TARGET_SIZE_PX,
            "zoom": zoom, "factor": factor, "px_per_km": px_per_km,
            "native_res_m_per_px": native_res, "target_res_m_per_px": target_res,
            "edge_color_rgb": list(EDGE_LINE_COLOR), "node_color_rgb": list(NODE_DOT_COLOR),
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"  [debug_images] scritto {metadata_path}")
    except Exception as e:
        print(f"  [debug_images] area_metadata.json: fallito ({e!r}), salto")

    def to_canvas_px(lat, lon):
        return (
            (_lon_to_x(lon, zoom) - world_left) * factor,
            (_lat_to_y(lat, zoom) - world_top) * factor,
        )

    def new_canvas_with_tiles():
        canvas = Image.new("RGB", (TARGET_SIZE_PX, TARGET_SIZE_PX), (230, 230, 230))
        for tx in range(tile_x0, tile_x1 + 1):
            for ty in range(tile_y0, tile_y1 + 1):
                tile_img = _fetch_tile(zoom, tx, ty)
                if tile_img is None:
                    continue
                scaled = (tile_img.resize((tile_size_scaled, tile_size_scaled), Image.BILINEAR)
                          if factor != 1.0 else tile_img)
                px = round((tx * TILE_SIZE_PX - world_left) * factor)
                py = round((ty * TILE_SIZE_PX - world_top) * factor)
                canvas.paste(scaled, (px, py))
        return canvas

    background_path = out_dir / f"background_{px_per_km:.1f}pxkm.jpg"
    try:
        new_canvas_with_tiles().save(background_path, "JPEG", quality=90)
        print(f"  [debug_images] scritto {background_path} (solo sfondo, nessun overlay)")
    except Exception as e:
        print(f"  [debug_images] background: fallito ({e!r}), salto")

    def draw_and_save(line_segments, node_latlon, out_path, label):
        canvas = new_canvas_with_tiles()
        draw = ImageDraw.Draw(canvas)
        for seg in line_segments:
            pts = [to_canvas_px(lat, lon) for lat, lon in seg]
            if len(pts) >= 2:
                draw.line(pts, fill=EDGE_LINE_COLOR, width=EDGE_LINE_WIDTH_PX)
        for lat, lon in node_latlon:
            x, y = to_canvas_px(lat, lon)
            r = NODE_DOT_RADIUS_PX
            draw.ellipse([(x - r, y - r), (x + r, y + r)], fill=NODE_DOT_COLOR)
        canvas.save(out_path, "JPEG", quality=90)
        print(f"  [debug_images] scritto {out_path} ({label})")

    for source_key in SOURCE_LABELS:
        raw_lines = raw_lines_by_source.get(source_key)
        if not raw_lines:
            print(f"  [debug_images] {source_key}_orig: nessuna linea grezza, salto")
        else:
            try:
                segments = [line["coords"] for line in raw_lines]
                draw_and_save(segments, [], out_dir / f"{source_key}_orig_{px_per_km:.1f}pxkm.jpg",
                              f"{len(raw_lines)} linee grezze, nessun nodo (non esiste ancora)")
                _write_raw_lines_json(raw_lines, to_canvas_px, out_dir,
                                       f"{source_key}_orig_{px_per_km:.1f}pxkm")
            except Exception as e:
                print(f"  [debug_images] {source_key}_orig: fallito ({e!r}), salto")

        iso = auto_by_source.get(source_key)
        if not iso:
            print(f"  [debug_images] {source_key}_auto: nessun arco isolato, salto")
        else:
            try:
                iso_nodes, iso_edges = iso
                node_ids = set()
                for e in iso_edges:
                    node_ids.add(e["a"])
                    node_ids.add(e["b"])
                segments = [e["geom"] for e in iso_edges]
                node_latlon = [iso_nodes[nid] for nid in node_ids]
                draw_and_save(segments, node_latlon, out_dir / f"{source_key}_auto_{px_per_km:.1f}pxkm.jpg",
                              f"{len(iso_edges)} archi, {len(node_ids)} nodi (isolata dalle altre sorgenti)")
                _write_stage_json(iso_nodes, iso_edges, to_canvas_px, out_dir,
                                   f"{source_key}_auto_{px_per_km:.1f}pxkm")
            except Exception as e:
                print(f"  [debug_images] {source_key}_auto: fallito ({e!r}), salto")

        source_edges = by_source.get(source_key)
        if not source_edges:
            print(f"  [debug_images] {source_key}_final: nessun arco, salto")
        else:
            try:
                node_ids = set()
                for e in source_edges:
                    node_ids.add(e["a"])
                    node_ids.add(e["b"])
                segments = [e["geom"] for e in source_edges]
                node_latlon = [nodes[nid] for nid in node_ids]
                draw_and_save(segments, node_latlon, out_dir / f"{source_key}_final_{px_per_km:.1f}pxkm.jpg",
                              f"{len(source_edges)} archi, {len(node_ids)} nodi (grafo consolidato finale)")
                _write_stage_json(nodes, source_edges, to_canvas_px, out_dir,
                                   f"{source_key}_final_{px_per_km:.1f}pxkm")
            except Exception as e:
                print(f"  [debug_images] {source_key}_final: fallito ({e!r}), salto")


def build():
    """Punto di ingresso richiamato da precompila.py a fine fetch. Ricostruisce
    sempre il grafo per intero dai .geojson gia' scaricati in percorsi/raw/ -
    e' un'elaborazione locale (nessuna rete), pochi secondi anche con tutte
    le sorgenti, quindi non serve una logica di skip/resume separata."""
    RESIDUAL_BY_SOURCE.clear()
    RESIDUAL_PASSES.clear()
    lines, stats = load_lines()
    if not lines:
        print("  [build_routing_graph_v2] nessuna geometria lineare trovata in percorsi/raw/ - "
              "esegui prima il fetch delle sorgenti (precompila.py o precompila_v2.py)")
        return {"status": "skipped", "reason": "no_line_data"}

    # Dato grezzo per sorgente, per {source}_orig.jpg - catturato qui, PRIMA
    # di qualunque elaborazione (spike removal/A/B/B2/C), apposta
    # (Alessandro, 2026-10-02: "_orig.jpg il dato grezzo").
    raw_lines_by_source = defaultdict(list)
    for line in lines:
        raw_lines_by_source[line["source"]].append(line)

    lines, uturn_stats = remove_uturn_spikes(lines)
    print(f"  [remove_uturn_spikes] {uturn_stats['lines_modified']} linee modificate, "
          f"{uturn_stats['vertices_removed']} vertici di inversione rimossi")

    # Primo run: A->B->B2->C, UNA sorgente alla volta (anche per
    # {source}_auto.jpg - Alessandro, 2026-10-02: "primo run A B Bb C").
    # Niente piu' deduplica di linee intere qui (vedi il docstring del
    # modulo): due tracciati sovrapposti restano due archi distinti.
    auto_by_source = {}
    for source_key in SOURCE_LABELS:
        if not _source_enabled(source_key):
            print(f"  [{source_key}] disabilitata da precompila_parametri.txt "
                  f"(process_{source_key} = false) - saltata per intero")
            continue
        source_lines = [l for l in lines if l["source"] == source_key]
        if not source_lines:
            continue
        method = {
            "disable": f"loop A/B/B2/C/D (max {SOURCE_PASS_MAX_ITERATIONS} iterazioni)",
            "enable": "neatnet + build_source_graph (una passata) come rete di sicurezza",
            "both": f"neatnet + loop A/B/B2/C/D (max {SOURCE_PASS_MAX_ITERATIONS} iterazioni)",
        }[NEATNET_MODE]
        print(f"  {'#' * 70}")
        print(f"  [{source_key}] passata isolata - metodo: {method}")
        print(f"  {'#' * 70}")
        if source_key == "raccordi":
            # I raccordi (scripts/raccordi.py) sono gia' una rete pulita (archi
            # stradali OSM fra incroci veri) i cui estremi "a vuoto" stanno
            # ESATTAMENTE su SIC: niente loop e soprattutto niente D per-sorgente,
            # che li sposterebbe su un altro raccordo vicino staccandoli da SIC.
            # Solo A/B/B2/C qui; l'aggancio a SIC lo fa la passata globale (B, C, D).
            iso_nodes, iso_edges = build_nodes_from_endpoints(source_lines)
            iso_nodes, iso_edges = cluster_merge_nodes(iso_nodes, iso_edges, NODE_CLUSTER_MAX_DIAMETER_M)
            iso_nodes, iso_edges = collapse_degree2_nodes(iso_nodes, iso_edges)
            iso_nodes, iso_edges = create_crossing_nodes(iso_nodes, iso_edges, MAX_CROSSINGS_PER_PAIR)
            auto_by_source[source_key] = (iso_nodes, iso_edges)
            print(f"    -> {len(iso_edges)} archi, {len(iso_nodes)} nodi (raccordi: solo A/B/B2/C)")
            continue
        if NEATNET_MODE in ("enable", "both"):
            try:
                source_lines = _neatnet_preprocess(
                    source_lines, NODE_CLUSTER_MAX_DIAMETER_M, DANGLE_TOLERANCE_M, source_key)
            except Exception as e:
                print(f"  [{source_key}] neatnet fallito ({e!r}) - sorgente SALTATA per "
                      f"intero (non blocca le altre sorgenti ne' il resto della build)")
                continue
        if NEATNET_MODE == "enable":
            iso_nodes, iso_edges = build_source_graph(
                source_lines, MAX_CROSSINGS_PER_PAIR, NODE_CLUSTER_MAX_DIAMETER_M,
                DANGLE_TOLERANCE_M)
        else:
            iso_nodes, iso_edges = build_source_graph_looped(
                source_lines, MAX_CROSSINGS_PER_PAIR, NODE_CLUSTER_MAX_DIAMETER_M,
                DANGLE_TOLERANCE_M, SOURCE_PASS_MAX_ITERATIONS, source_key)
        auto_by_source[source_key] = (iso_nodes, iso_edges)
        print(f"    -> {len(iso_edges)} archi, {len(iso_nodes)} nodi")

    # Secondo run: solo B e C, su tutte le sorgenti insieme (Alessandro,
    # 2026-10-02: "secondo run BC e basta, non si eliminano nodi tra
    # sorgenti diverse") - ogni arco resta attribuito alla sua sorgente
    # originale, solo i nodi possono essere condivisi fra sorgenti diverse.
    print("  [globale] seconda passata (solo B/C, tutte le sorgenti insieme):")
    nodes, edges = build_global_graph(
        auto_by_source, MAX_CROSSINGS_PER_PAIR, NODE_CLUSTER_MAX_DIAMETER_M,
        DANGLE_TOLERANCE_M)
    # Archi ad anello (stesso nodo ai due capi, tipicamente un arco corto i cui
    # estremi la passata globale B ha fuso in un solo nodo - 2026-10-13): inutili
    # per l'instradamento e fatali per il motore JGraphT dell'app
    # ("loops not allowed"). Scartati qui, prima di scrivere graph.json.
    n_loop = sum(1 for e in edges if e["a"] == e["b"])
    if n_loop:
        edges = [e for e in edges if e["a"] != e["b"]]
        print(f"  [globale] scartati {n_loop} archi ad anello (a == b)")

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    graph_path = ASSETS_DIR / "graph.json"
    graph_path.write_text(json.dumps({
        "nodes": [[round(lat, 6), round(lon, 6)] for lat, lon in nodes],
        "edges": edges,
    }, ensure_ascii=False))

    edge_counts = defaultdict(int)
    for e in edges:
        edge_counts[e["src"]] += 1
    manifest = {
        "sources": [
            {"key": key, "label": label, "edges": edge_counts.get(key, 0)}
            for key, label in SOURCE_LABELS.items()
            if edge_counts.get(key, 0) > 0
        ]
    }
    manifest_path = ASSETS_DIR / "sources_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False))

    print(f"  build_routing_graph: {len(nodes)} nodi, {len(edges)} archi "
          f"({', '.join(f'{k}={v}' for k, v in edge_counts.items())})")
    print(f"  scritto {graph_path}")
    print(f"  scritto {manifest_path}")

    debug_images_dir = ASSETS_DIR / "debug_images"
    try:
        render_source_debug_images(nodes, edges, debug_images_dir, raw_lines_by_source, auto_by_source)
    except Exception as e:
        print(f"  [debug_images] fallite per intero ({e!r}) - non blocca il resto della build")

    try:
        _scritti = _write_residual_pass_reports(debug_images_dir)
        print(f"  scritti {len(_scritti)} file residual_errors_passo_NN.txt in {debug_images_dir}")
    except Exception as e:
        print(f"  [residual_errors_passo] scrittura fallita ({e!r}) - non blocca il resto della build")

    residual_path = debug_images_dir / "residual_errors.txt"
    try:
        _write_residual_report(residual_path)
        print(f"  scritto {residual_path}")
    except Exception as e:
        print(f"  [residual_errors] scrittura fallita ({e!r}) - non blocca il resto della build")

    return {"status": "complete", "nodes": len(nodes), "edges": len(edges),
            "edges_by_source": dict(edge_counts)}


# ---------------------------------------------------------------------------
# Layer "di dettaglio": punti di interesse e tracciati informativi (bike box,
# rastrelliere, punti critici, passaggi segreti, ferrovie, confini... -
# Alessandro, 2026-10-02) che l'app mostra a scelta dall'utente (pulsante
# "layers" in MapScreen.kt) INDIPENDENTEMENTE dal fatto che la loro sorgente
# partecipi al routing - per questo sono un file a parte da graph.json, non
# altri archi instradabili. Tutti i file sotto gia' scaricati da fetch_sicrm()
# o fetch_g3w(): qui solo riletti, non c'e' nessuna nuova fetch di rete.
# ---------------------------------------------------------------------------
DETAIL_LAYER_FILES = {
    "sic_punti_riparazione": ("Punti_Riparazione_Bici.geojson", "Punti riparazione bici"),
    "sic_ciclostazioni": ("Ciclostazioni.geojson", "Ciclostazioni"),
    "sic_passaggi_segreti": ("Passaggi_Segreti.geojson", "Passaggi segreti"),
    "sic_bikeboxes": ("layer_correlati/BikeBoxes_Comune_di_Roma.geojson", "Bike box"),
    "sic_negozi_bike_friendly": ("layer_correlati/NegoziBikeFriendly.geojson", "Negozi bike friendly"),
    "sic_rastrelliere": ("layer_correlati/Rastrelliere.geojson", "Rastrelliere"),
    "sic_punti_critici": ("layer_correlati/Infrastrutture_Ostacoli_Interruzioni.geojson", "Punti critici"),
    "sic_autorimesse_bike_friendly": ("layer_correlati/Autorimessebikefriendly.geojson", "Autorimesse bike friendly"),
    "sic_strade_scolastiche": ("layer_correlati/Strade_Scolastiche.geojson", "Strade scolastiche"),
}
# "fontanelle" (i nasoni) chieste da Alessandro: nessuna fonte vettoriale
# individuata finora (non e' tra i layer di SICRM_All_New, ne' altrove in
# questo script) - layer assente di proposito finche' non se ne trova una,
# non un errore di questo elenco.


def _transform_coord(source_key, c):
    """Stessa trasformazione di coordinate di load_lines(): alcune sorgenti
    (g3w, rsm) arrivano in proiezione piana (metri), non in gradi - vedi
    utm33n_to_wgs84/web_mercator_to_wgs84. Riusata qui per i layer di
    dettaglio, cosi' un layer non-ciclabile preso da g3w (es. ferrovie) non
    finisce disegnato nel posto sbagliato sulla mappa."""
    if source_key == "g3w_ciclovie_metropolitane":
        return utm33n_to_wgs84(c[0], c[1])
    if source_key == "rsm_piste_ciclabili":
        return web_mercator_to_wgs84(c[0], c[1])
    return c[1], c[0]


def _extract_points_lines(data, source_key):
    """Punti (Point/MultiPoint) e tracciati (LineString/MultiLineString),
    feature per feature (non assunto dal nome del file: un file come
    Strade_Scolastiche puo' in teoria mescolare punti e linee, e cosi' si
    evita di indovinare male). Polygon/altro ignorati, stesso criterio di
    load_lines() per il grafo di routing."""
    points = []
    lines_geom = []
    for feature in data.get("features", []):
        geom = feature.get("geometry") or {}
        gtype = geom.get("type")
        coords = geom.get("coordinates")
        name = extract_name(feature.get("properties") or {})
        if gtype == "Point" and coords:
            lat, lon = _transform_coord(source_key, coords)
            points.append({"lat": round(lat, 6), "lon": round(lon, 6), "name": name})
        elif gtype == "MultiPoint":
            for c in (coords or []):
                lat, lon = _transform_coord(source_key, c)
                points.append({"lat": round(lat, 6), "lon": round(lon, 6), "name": name})
        elif gtype == "LineString" and coords and len(coords) >= 2:
            lines_geom.append({
                "geom": [[round(lat, 6), round(lon, 6)]
                         for lat, lon in (_transform_coord(source_key, c) for c in coords)],
                "name": name,
            })
        elif gtype == "MultiLineString":
            for part in (coords or []):
                if len(part) >= 2:
                    lines_geom.append({
                        "geom": [[round(lat, 6), round(lon, 6)]
                                 for lat, lon in (_transform_coord(source_key, c) for c in part)],
                        "name": name,
                    })
    return points, lines_geom


def build_detail_layers():
    """Punti e tracciati informativi, scritti in layers.json accanto a
    graph.json, mostrati a scelta dall'utente (pulsante "layers" in
    MapScreen.kt) INDIPENDENTEMENTE dal fatto che la loro sorgente partecipi
    al routing. Due provenienze, unite nella stessa lista:

    - [DETAIL_LAYER_FILES]: i 9 layer SIC gia' noti (bike box, rastrelliere,
      passaggi segreti...) - "dash" assente, disegnati a tratto continuo
      (Passaggi_Segreti e' anche instradabile, entra pure in graph.json via
      load_lines - gli altri 8 sono solo punti, mai nel grafo).
    - [NON_CICLABILE_LAYERS]: file con geometrie LineString che pero' NON
      sono piste ciclabili (ferrovie, confini amministrativi...) - esclusi
      esplicitamente dal grafo di routing in load_lines(), riesposti qui
      SOLO come riferimento visivo, con un tratteggio (campo "dash") per non
      confonderli con un percorso vero della stessa sorgente/colore - vedi
      le note di progetto per il perche'.
    """
    layers = []

    for key, (rel_path, label) in DETAIL_LAYER_FILES.items():
        path = RAW_DIR / "salvaiciclisti_sicrm" / rel_path
        if not path.exists():
            continue
        if not _layer_enabled("salvaiciclisti_sicrm", path):
            continue
        try:
            data = json.loads(path.read_text())
        except Exception as e:
            print(f"  [build_detail_layers] impossibile leggere {path}: {e}")
            continue
        points, lines_geom = _extract_points_lines(data, "salvaiciclisti_sicrm")
        if not points and not lines_geom:
            continue
        layers.append({
            "key": key, "label": label, "source": "salvaiciclisti_sicrm",
            "points": points, "lines": lines_geom,
        })

    for (source_key, filename), info in NON_CICLABILE_LAYERS.items():
        path = RAW_DIR / source_key / filename
        if not path.exists():
            continue
        if not _layer_enabled(source_key, path):
            continue
        try:
            data = json.loads(path.read_text())
        except Exception as e:
            print(f"  [build_detail_layers] impossibile leggere {path}: {e}")
            continue
        points, lines_geom = _extract_points_lines(data, source_key)
        if not points and not lines_geom:
            continue
        layers.append({
            "key": info["key"], "label": info["label"], "source": source_key,
            "points": points, "lines": lines_geom, "dash": info["dash"],
        })

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    layers_path = ASSETS_DIR / "layers.json"
    layers_path.write_text(json.dumps({"layers": layers}, ensure_ascii=False))
    total_points = sum(len(l["points"]) for l in layers)
    total_lines = sum(len(l["lines"]) for l in layers)
    print(f"  build_detail_layers: {len(layers)} layer, {total_points} punti, {total_lines} tracciati")
    print(f"  scritto {layers_path}")
    return {"status": "complete", "layers": len(layers), "points": total_points, "lines": total_lines}


if __name__ == "__main__":
    import sys
    sys.exit(0 if build().get("status") in ("complete", "skipped") else 1)
