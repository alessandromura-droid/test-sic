#!/usr/bin/env python3
"""
build_routing_graph.py - assembla il grafo di instradamento per l'app da tutte
le geometrie lineari raccolte in percorsi/raw/ (le "3+3 sorgenti" di
precompila.py), taggando ogni arco con la sorgente di provenienza cosi' che
l'app possa filtrare quali sorgenti partecipano al calcolo del percorso
(schermata Impostazioni) senza bisogno di ricostruire il grafo.

Il routing usa ESCLUSIVAMENTE queste sorgenti (nessuna rete stradale generica
di supporto): due punti sono collegabili solo se esiste una catena di
segmenti, tra quelli raccolti, che li unisce. Per questo la fase piu'
delicata e' lo "snap" dei nodi: segmenti che vengono da provenienze diverse
(RSM, OSM, G3W, SICRM) non condividono le stesse coordinate esatte agli
incroci, quindi i nodi vengono uniti con una tolleranza (SNAP_TOLERANCE_M),
non per uguaglianza esatta.

Nessuna dipendenza esterna: solo libreria standard.

Uso (di norma invocato automaticamente da precompila.py a fine fetch):
    python3 scripts/build_routing_graph.py
"""
import json
import math
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "percorsi" / "raw"
# Non piu' dentro app/src/main/assets/: Alessandro (2026-09-21) non vuole i
# dati bundlati nell'APK, li scarica invece l'app da GitHub Releases (vedi
# upload_to_github() in precompila.py) - questa resta la cartella dove
# vengono costruiti/letti in locale (dal test JVM, da upload_to_github),
# semplicemente fuori da qualunque source set Android.
ASSETS_DIR = ROOT / "percorsi" / "build" / "routing"

# Cartelle sotto percorsi/raw/ che contengono geometrie effettivamente
# instradabili. I segnaposto (piano_150km, biciplan_pdf_ufficiale) non hanno
# file .geojson e vengono quindi ignorati automaticamente.
SOURCE_LABELS = {
    "rsm_piste_ciclabili": "RSM - piste ciclabili ufficiali",
    "osm_extract": "OpenStreetMap - infrastruttura ciclabile",
    "g3w_ciclovie_metropolitane": "Citta' Metropolitana - progetti ciclovie",
    "salvaiciclisti_sicrm": "SIC",
    "salvaiciclisti_ciclopolitana": "Salvaiciclisti - Ciclopolitana (rete concettuale, non e' un tracciato reale)",
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
SKIP_FILENAMES = {"overpass_raw.json", "igm_010109_tr_str_tratto_stradale.geojson"}

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

SNAP_TOLERANCE_M = 15.0
GRID_CELL_DEG = 0.0003  # ~30m: abbastanza grande da trovare vicini in un
                        # blocco di celle 3x3 senza scansionare tutto il grafo

# Tolleranza di CONSOLIDAMENTO finale, applicata ai nodi gia' costruiti (non
# ai singoli vertici grezzi delle linee). SNAP_TOLERANCE_M da solo lascia
# frammentati incroci reali dove le 4 fonti si scostano di piu' di 15m ma
# meno di questa soglia - il caso concreto che l'ha fatta scoprire e' Piazza
# di Porta San Giovanni, dove nodi diversi (OSM/SICRM) della stessa
# intersezione risultavano a 17-24m l'uno dall'altro e MAI collegati fra
# loro, costringendo il router a percorsi contorti solo per "cucire" insieme
# i frammenti. E' un secondo passaggio, piu' permissivo, apposta: applicarlo
# gia' in SNAP_TOLERANCE_M vorrebbe dire confrontare ogni singolo vertice
# grezzo (molto piu' rumoroso) invece che i pochi nodi finali gia' emersi
# dal primo passaggio, quindi il rischio di fondere due incroci realmente
# distinti resta basso nonostante la soglia piu' larga.
NODE_MERGE_TOLERANCE_M = 25.0

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


class NodeGrid:
    """Indice spaziale a griglia per lo snap dei nodi: trova/crea nodi entro
    tolerance_m senza confrontare ogni punto con tutti gli altri. "Trova
    l'ancora gia' esistente piu' vicina, altrimenti diventa una nuova ancora
    fissa" (mai spostata, mai fatta scivolare) - deliberatamente NON una
    fusione transitiva stile union-find: quella incatena punti vicini in
    sequenza (A-B, B-C, ... anche se A e C sono lontani) e su un grafo denso
    puo' far collassare un intero quartiere in un solo nodo, come successo
    nel primo tentativo di consolidate_graph qui sotto (21mila archi
    diventati auto-archi su un run reale). Con le ancore fisse invece un
    nodo entra in un gruppo solo se e' entro tolleranza dal PRIMO punto di
    quel gruppo, quindi il diametro di ogni gruppo resta limitato."""

    def __init__(self, tolerance_m=None):
        self.tolerance_m = tolerance_m if tolerance_m is not None else SNAP_TOLERANCE_M
        self.nodes = []  # list of (lat, lon)
        self.grid = defaultdict(list)  # (cx, cy) -> [node indices]

    def _cell(self, lat, lon):
        return (int(lat / GRID_CELL_DEG), int(lon / GRID_CELL_DEG))

    def _nearest(self, lat, lon):
        cx, cy = self._cell(lat, lon)
        best_idx, best_dist = None, self.tolerance_m
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for idx in self.grid.get((cx + dx, cy + dy), ()):
                    nlat, nlon = self.nodes[idx]
                    d = haversine_m(lat, lon, nlat, nlon)
                    if d < best_dist:
                        best_dist, best_idx = d, idx
        return best_idx

    def find_or_create(self, lat, lon):
        existing = self._nearest(lat, lon)
        if existing is not None:
            return existing
        idx = len(self.nodes)
        self.nodes.append((lat, lon))
        self.grid[self._cell(lat, lon)].append(idx)
        return idx

    def find_existing(self, lat, lon):
        return self._nearest(lat, lon)


def consolidate_graph(nodes, edges, tolerance_m):
    """Passata finale di consolidamento: rifà lo snap dei nodi gia' emersi dal
    doppio passaggio sopra, con una tolerance_m un po' piu' larga di
    SNAP_TOLERANCE_M (vedi NODE_MERGE_TOLERANCE_M) - stesso identico
    meccanismo a "ancore fisse" di NodeGrid (mai fusione transitiva stile
    union-find: su un primo tentativo con union-find, un incrocio ha finito
    per incatenarsi con punti lontani centinaia di metri, collassando un
    quarto del grafo in pochi super-nodi). Nello stesso passaggio ripulisce
    gli archi degenerati che ne risultano - un arco che finisce per
    collegare un nodo a se stesso (gia' presente anche prima di qualunque
    fusione) viene scartato, e i duplicati esatti (stessa coppia di nodi,
    stessa sorgente, stessa lunghezza) sono tenuti una sola volta."""
    grid = NodeGrid(tolerance_m)
    remap = [grid.find_or_create(lat, lon) for lat, lon in nodes]

    new_edges = []
    seen = set()
    dropped_self_loops = 0
    dropped_duplicates = 0
    for e in edges:
        a2, b2 = remap[e["a"]], remap[e["b"]]
        if a2 == b2:
            dropped_self_loops += 1
            continue
        key = (min(a2, b2), max(a2, b2), e["src"], e["len"])
        if key in seen:
            dropped_duplicates += 1
            continue
        seen.add(key)
        new_edges.append({**e, "a": a2, "b": b2})

    return grid.nodes, new_edges, {
        "nodes_merged": len(nodes) - len(grid.nodes),
        "self_loops_dropped": dropped_self_loops,
        "duplicate_edges_dropped": dropped_duplicates,
    }


def build_graph(lines):
    grid = NodeGrid()

    # Passata 1: crea (o riusa) un nodo per ogni estremo reale di ogni linea.
    for line in lines:
        coords = line["coords"]
        grid.find_or_create(*coords[0])
        grid.find_or_create(*coords[-1])

    # Passata 2: per ogni linea, individua tra i vertici INTERNI quelli che
    # coincidono (entro tolleranza) con un nodo gia' esistente - createo da
    # un'altra linea che la tocca a meta' strada, non solo alle estremita'.
    # Questi diventano punti di taglio, cosi' un "passaggio segreto" che si
    # innesta a meta' di una ciclabile OSM resta collegato al grafo.
    edges = []
    for line in lines:
        coords = line["coords"]
        cut_points = [(0, grid.find_or_create(*coords[0]))]
        for i in range(1, len(coords) - 1):
            match = grid.find_existing(*coords[i])
            if match is not None:
                cut_points.append((i, match))
        cut_points.append((len(coords) - 1, grid.find_or_create(*coords[-1])))

        for (i0, n0), (i1, n1) in zip(cut_points, cut_points[1:]):
            if i1 <= i0:
                continue
            segment = coords[i0:i1 + 1]
            length_m = polyline_length_m(segment)
            if length_m < 0.5:
                continue
            edges.append({
                "a": n0, "b": n1, "len": round(length_m, 1),
                "src": line["source"], "name": line["name"],
                "geom": [[round(lat, 6), round(lon, 6)] for lat, lon in segment],
            })

    nodes, edges, consolidate_stats = consolidate_graph(grid.nodes, edges, NODE_MERGE_TOLERANCE_M)
    return nodes, edges, consolidate_stats


def build():
    """Punto di ingresso richiamato da precompila.py a fine fetch. Ricostruisce
    sempre il grafo per intero dai .geojson gia' scaricati in percorsi/raw/ -
    e' un'elaborazione locale (nessuna rete), pochi secondi anche con tutte
    le sorgenti, quindi non serve una logica di skip/resume separata."""
    lines, stats = load_lines()
    if not lines:
        print("  [build_routing_graph] nessuna geometria lineare trovata in percorsi/raw/ - "
              "esegui prima il fetch delle sorgenti (precompila.py)")
        return {"status": "skipped", "reason": "no_line_data"}

    nodes, edges, consolidate_stats = build_graph(lines)

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
    print(f"  consolidamento nodi vicini (tolleranza {NODE_MERGE_TOLERANCE_M}m): "
          f"{consolidate_stats['nodes_merged']} nodi fusi, "
          f"{consolidate_stats['self_loops_dropped']} auto-archi scartati, "
          f"{consolidate_stats['duplicate_edges_dropped']} archi duplicati scartati")
    print(f"  scritto {graph_path}")
    print(f"  scritto {manifest_path}")
    return {"status": "complete", "nodes": len(nodes), "edges": len(edges),
            "edges_by_source": dict(edge_counts), "consolidate": consolidate_stats}


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
