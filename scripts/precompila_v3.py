#!/usr/bin/env python3
"""
precompila_v3.py — VARIANTE SPERIMENTALE di precompila.py (2026-10-03,
"vai" di Alessandro, dopo build_routing_graph_v2.py): identica in tutto
fuorche' due cose, per poter provare la pipeline v3 (loop A/B/B2/C/D fino a
convergenza, oppure neatnet - vedi USE_NEATNET nel docstring di quel
modulo) senza toccare v1 ne' v2, che restano entrambe intoccate e
funzionanti:

  1. usa build_routing_graph_v3.py al posto di build_routing_graph_v2.py
     (vedi il docstring di quel modulo per cosa cambia). Scrive in
     percorsi/build/routing_v3/, una cartella separata da v1 e da v2.
  2. Pubblica su GitHub come precompila.py/precompila_v2.py - STESSA
     release (GITHUB_REPO/GITHUB_RELEASE_TAG non cambiati per v3): ATTENZIONE,
     da quando viene lanciato con successo, l'app vera scarica i dati v3
     (sperimentali) al posto di quelli v2 attualmente pubblicati - esattamente
     come gia' succedeva passando da v1 a v2. Copia da se' indirizzi.json
     dalla cartella v1 (routing/, build_index_indirizzi.py non e' stato
     duplicato per v3: l'indice indirizzi non dipende dalla logica di
     grafo) a quella v3 (routing_v3/) prima di pubblicare, altrimenti
     upload_to_github() lo troverebbe mancante.

Per il resto (fetch delle sorgenti raw, build_index_indirizzi.py, logging)
e' lo stesso identico codice di precompila.py.

Scarica/aggiorna le sorgenti vettoriali dei percorsi ciclabili di Roma usate
dall'app Salvaiciclisti, poi costruisce da queste il grafo di instradamento
e l'indice indirizzi.

Richiede Shapely (vedi scripts/requirements.txt) per la parte di
costruzione del grafo - unica dipendenza esterna, il resto e' libreria
standard (urllib, json, logging) come in precompila.py.

Uso:
    python3 scripts/precompila.py                 # salta le sorgenti gia' complete
    python3 scripts/precompila.py --force          # riscarica tutto da zero
    python3 scripts/precompila.py --only rsm_piste_ciclabili osm_extract   # solo alcune sorgenti

Invocabile anche via Gradle:
    ./gradlew precompila
    ./gradlew precompila -Pforce=true

Log: ogni esecuzione scrive un log dettagliato (con traceback completi sugli
errori) in percorsi/raw/logs/precompila_<timestamp>.log. Il terminale mostra
una versione sintetica in tempo reale, riga per riga.

Cache/skip: per le sorgenti multi-layer (g3w_ciclovie_metropolitane,
salvaiciclisti_sicrm) il progresso e' salvato layer per layer in
_layers_state.json dentro la cartella della sorgente. Se un run si interrompe
o alcuni layer falliscono (es. errori di rete transitori), il run successivo
(senza --force) salta solo i layer gia' scaricati con successo e ritenta solo
quelli falliti/mancanti.

Uscita: termina con codice diverso da zero se la pubblicazione su GitHub
fallisce per qualunque motivo, incluso GITHUB_TOKEN mancante (Alessandro,
2026-10-02, dopo un run senza token passato inosservato) - vedi
upload_to_github()/main(). Il fetch e la costruzione locale degli asset
procedono comunque fino in fondo anche in quel caso: solo questo esito
finale fa fallire l'intero run.
"""
import argparse
import gzip
import hashlib
import json
import logging
import os
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RAW_DIR = ROOT / "percorsi" / "raw"
LOG_DIR = RAW_DIR / "logs"

UA_HEADERS = {"User-Agent": "salvaiciclisti-precompila/1.1 (+script di raccolta dati per app di navigazione bici)"}

RETRIES = 3
BACKOFF_BASE = 2.0

LOG = logging.getLogger("precompila")


def setup_logging():
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    log_path = LOG_DIR / f"precompila_{ts}.log"

    LOG.setLevel(logging.DEBUG)
    LOG.handlers.clear()

    console = logging.StreamHandler(sys.stdout)
    console.setLevel(logging.INFO)
    console.setFormatter(logging.Formatter("%(message)s"))
    LOG.addHandler(console)

    file_handler = logging.FileHandler(log_path, encoding="utf-8")
    file_handler.setLevel(logging.DEBUG)
    file_handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s"))
    LOG.addHandler(file_handler)

    return log_path


def info(msg):
    LOG.info(msg)


def warn(msg):
    LOG.warning(msg)


def debug(msg):
    LOG.debug(msg)


def error(msg):
    LOG.error(msg)
    LOG.debug("Traceback:\n%s", traceback.format_exc())


def iso_now():
    return datetime.now(timezone.utc).isoformat()


def sanitize(name):
    return "".join(c if c.isalnum() else "_" for c in name).strip("_")


def fetch_json(url, data=None, timeout=180):
    req = urllib.request.Request(url, data=data, headers=UA_HEADERS)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def fetch_bytes(url, data=None, timeout=180):
    req = urllib.request.Request(url, data=data, headers=UA_HEADERS)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def with_retries(fn, *args, retries=RETRIES, backoff=BACKOFF_BASE, desc="", **kwargs):
    """Richiama fn(*args, **kwargs), ritentando su qualunque eccezione (errori di
    rete transitori: connection reset, timeout, HTTP 5xx, ecc.) con backoff
    esponenziale. Logga ogni tentativo fallito nel dettaglio del file di log."""
    last_exc = None
    for attempt in range(1, retries + 1):
        try:
            return fn(*args, **kwargs)
        except Exception as e:
            last_exc = e
            debug(f"tentativo {attempt}/{retries} fallito per {desc or fn.__name__}: {e!r}")
            if attempt < retries:
                warn(f"    [retry {attempt}/{retries}] {desc}: {e} - riprovo tra {backoff:.0f}s")
                time.sleep(backoff)
                backoff *= 2
    raise last_exc


def load_layer_state(out_dir):
    p = out_dir / "_layers_state.json"
    if p.exists():
        try:
            return json.loads(p.read_text())
        except Exception:
            debug(f"stato layer illeggibile in {p}, riparto da zero")
            return {}
    return {}


def save_layer_state(out_dir, state):
    (out_dir / "_layers_state.json").write_text(json.dumps(state, indent=2, ensure_ascii=False))


# ---------------------------------------------------------------------------
# 1) RSM (Roma Servizi per la Mobilita') — Open Data "Piste ciclabili"
# ---------------------------------------------------------------------------
RSM_URL = ("https://data-rsm.opendata.arcgis.com/api/download/v1/items/"
           "806ef9fcc24f450185e948b518baa842/geojson?layers=0")


def fetch_rsm(out_dir, force=False):
    data = with_retries(fetch_bytes, RSM_URL, desc="RSM geojson")
    (out_dir / "piste_ciclabili_rsm.geojson").write_bytes(data)
    try:
        n = len(json.loads(data).get("features", []))
    except Exception:
        n = None
    info(f"  RSM: {n} feature scaricate")
    return {"status": "complete", "features": n, "source_url": RSM_URL,
            "note": "dato RSM, ultimo aggiornamento noto gen. 2023"}


# ---------------------------------------------------------------------------
# 2) OpenStreetMap — estrazione infrastruttura ciclabile su Roma (Overpass)
# ---------------------------------------------------------------------------
OVERPASS_URL = "https://overpass-api.de/api/interpreter"
OVERPASS_QUERY = """
[out:json][timeout:180];
area["boundary"="administrative"]["admin_level"="8"]["name"="Roma"]->.roma;
(
  way["highway"="cycleway"](area.roma);
  way["cycleway"](area.roma);
  way["cycleway:left"](area.roma);
  way["cycleway:right"](area.roma);
  way["cycleway:both"](area.roma);
  way["bicycle"="designated"](area.roma);
  way["highway"="path"]["bicycle"](area.roma);
);
out geom;
"""


def overpass_to_geojson(raw):
    features = []
    for el in raw.get("elements", []):
        if el.get("type") == "way" and "geometry" in el:
            coords = [[pt["lon"], pt["lat"]] for pt in el["geometry"]]
            features.append({
                "type": "Feature",
                "properties": {"osm_id": el.get("id"), **el.get("tags", {})},
                "geometry": {"type": "LineString", "coordinates": coords},
            })
    return {"type": "FeatureCollection", "features": features}


def fetch_osm(out_dir, force=False):
    body = urllib.parse.urlencode({"data": OVERPASS_QUERY}).encode()
    raw = with_retries(fetch_json, OVERPASS_URL, data=body, timeout=220, desc="Overpass query")
    (out_dir / "overpass_raw.json").write_text(json.dumps(raw, ensure_ascii=False))
    geo = overpass_to_geojson(raw)
    (out_dir / "roma_ciclabili.geojson").write_text(json.dumps(geo, ensure_ascii=False))
    info(f"  OSM: {len(raw.get('elements', []))} elementi, {len(geo['features'])} feature convertite")
    return {"status": "complete", "elements": len(raw.get("elements", [])), "features": len(geo["features"]),
            "note": "solo way con geometria (out geom); le relation route=bicycle non sono ancora stitchate"}


# ---------------------------------------------------------------------------
# 3) Geoportale Citta' Metropolitana di Roma Capitale — G3W-Suite "ciclovie"
#    (progetti di ciclovie per comune: bando 2018, finanziati, definitivi)
# ---------------------------------------------------------------------------
G3W_CONFIG_URL = "https://g3w-suite.cittametropolitanaroma.it/api/config/18/qdjango/157"
G3W_PROJECT_ID = "157"
G3W_VECTOR_BASE = "https://g3w-suite.cittametropolitanaroma.it/vector/api/data/qdjango/" + G3W_PROJECT_ID

# Layer esposti dal progetto G3W ma da non scaricare mai: non sono progetti
# di ciclovia ma dati di supporto/base del portale. igm_010109_tr_str_tratto_stradale
# e' la rete stradale generica IGM (non instradabile in bici) - 137MB/144.232
# feature, contro i ~13MB di tutti gli altri layer g3w messi insieme; oltre a
# non servire (vedi anche SKIP_FILENAMES in build_routing_graph.py, che la
# esclude comunque dal grafo se per qualche motivo fosse gia' su disco),
# scaricarla ogni volta con --force costa diversi minuti inutili.
G3W_EXCLUDED_LAYER_NAMES = {"igm_010109_tr_str_tratto_stradale"}


def collect_vector_layers(node, out):
    """Raccoglie (id, name) dei soli layer vettoriali veri: esclude i basemap
    (es. 'Google Satellite', 'OSM Standard') che nel config hanno
    geometrytype nullo/assente."""
    if isinstance(node, list):
        for n in node:
            collect_vector_layers(n, out)
        return
    if isinstance(node, dict):
        gt = node.get("geometrytype")
        if "id" in node and "name" in node and isinstance(gt, str) and gt:
            out.append((node["id"], node["name"]))
        for v in node.values():
            if isinstance(v, (dict, list)):
                collect_vector_layers(v, out)


def fetch_g3w(out_dir, force=False):
    config = with_retries(fetch_json, G3W_CONFIG_URL, desc="config G3W")
    layers = []
    collect_vector_layers(config, layers)
    info(f"  g3w: {len(layers)} layer vettoriali individuati nel progetto")

    state = {} if force else load_layer_state(out_dir)
    ok = skipped = excluded = failed = 0

    for i, (lid, name) in enumerate(layers, 1):
        if name in G3W_EXCLUDED_LAYER_NAMES:
            excluded += 1
            debug(f"[{i}/{len(layers)}] layer '{name}' escluso (vedi G3W_EXCLUDED_LAYER_NAMES), salto")
            continue
        fpath = out_dir / (sanitize(name) + ".geojson")
        # Il segnale di skip primario e' la presenza del file scaricato: cosi'
        # anche i layer scaricati da una versione precedente dello script
        # (prima che esistesse _layers_state.json) vengono correttamente
        # riconosciuti come "gia' presenti" e non riscaricati.
        if not force and fpath.exists():
            skipped += 1
            debug(f"[{i}/{len(layers)}] layer '{name}' gia' presente, salto")
            continue
        info(f"  [{i}/{len(layers)}] layer '{name}'...")
        try:
            url = f"{G3W_VECTOR_BASE}/{urllib.parse.quote(lid, safe='')}/"
            resp = with_retries(fetch_json, url, desc=f"layer '{name}'")
            vec = resp.get("vector", {})
            geo = vec.get("data")
            if not geo:
                raise ValueError("risposta senza campo 'vector.data'")
            fpath.write_text(json.dumps(geo, ensure_ascii=False))
            state[lid] = {"status": "ok", "name": name, "features": vec.get("count"), "fetched_at": iso_now()}
            ok += 1
        except Exception as e:
            warn(f"    saltato: {e}")
            debug(f"Traceback layer '{name}':\n{traceback.format_exc()}")
            state[lid] = {"status": "error", "name": name, "error": str(e), "fetched_at": iso_now()}
            failed += 1
        save_layer_state(out_dir, state)

    info(f"  g3w: {ok} ok, {skipped} gia' presenti, {excluded} esclusi, {failed} falliti (su {len(layers)} totali)")
    return {"status": "complete" if failed == 0 else "partial",
            "layers_ok": ok, "layers_skipped": skipped, "layers_excluded": excluded,
            "layers_failed": failed, "layers_total": len(layers)}


# ---------------------------------------------------------------------------
# 4) Salvaiciclisti Roma — servizio ArcGIS dietro "Il Mappone" / PUMS+Ciclopolitana
#    (Biciplan 2025, PUMS 2018, Ciclopolitana 2021, Ciclabili Esistenti,
#     Passaggi Segreti = scorciatoie/percorsi informali, ecc.)
# ---------------------------------------------------------------------------
SICRM_BASE = "https://services-eu1.arcgis.com/kKyIW1RHTCR9ShdF/arcgis/rest/services/SICRM_All_New/FeatureServer"
SICRM_LAYERS = {
    1: "Biciplan_Municipi",
    2: "CiclabiliEsist_Municipi",
    3: "Punti_Riparazione_Bici",
    # 4: "Ciclabili_Esistenti" - NON scaricato: layer obsoleto (Irene,
    # 2026-10-14: si usa solo CiclabiliEsist_Municipi). Escluso anche in
    # SKIP_FILENAMES di build_routing_graph_v3.py, per sicurezza.
    5: "Passaggi_Segreti",          # <-- percorsi/scorciatoie informali
    6: "Biciplan_2025",
    7: "PUMS_2018",
    8: "Ciclopolitana_di_Roma_2021",
    10: "Ciclostazioni",
    11: "Ciclopolitane_Ciclonvallazioni",
    12: "WEB_Zone30P",
    13: "Municipi",
    # 14, 30, 34 = layer di contesto generico (landuse/water/natural da OSM), esclusi apposta
}

SICRM_RELATED_SERVICES = {
    "BikeBoxes_Comune_di_Roma": "https://services-eu1.arcgis.com/kKyIW1RHTCR9ShdF/arcgis/rest/services/BikeBoxes_Comune_di_Roma/FeatureServer",
    "NegoziBikeFriendly": "https://services-eu1.arcgis.com/kKyIW1RHTCR9ShdF/arcgis/rest/services/NegoziBikeFriendly/FeatureServer",
    "Rastrelliere": "https://services-eu1.arcgis.com/kKyIW1RHTCR9ShdF/arcgis/rest/services/Rastrelliere/FeatureServer",
    "Infrastrutture_Ostacoli_Interruzioni": "https://services-eu1.arcgis.com/kKyIW1RHTCR9ShdF/arcgis/rest/services/Infrastrutture_Ostacoli_Interruzioni/FeatureServer",
    "Autorimessebikefriendly": "https://services-eu1.arcgis.com/kKyIW1RHTCR9ShdF/arcgis/rest/services/Autorimessebikefriendly/FeatureServer",
    "Strade_Scolastiche": "https://services-eu1.arcgis.com/kKyIW1RHTCR9ShdF/arcgis/rest/services/Strade_Scolastiche/FeatureServer",
}


def query_arcgis_layer(layer_url):
    features = []
    offset = 0
    page = 2000
    while True:
        params = urllib.parse.urlencode({
            "where": "1=1", "outFields": "*", "f": "geojson",
            "outSR": 4326, "resultRecordCount": page, "resultOffset": offset,
        })
        data = with_retries(fetch_json, f"{layer_url}/query?{params}", desc=f"query {layer_url}")
        feats = data.get("features", [])
        features.extend(feats)
        if len(feats) < page:
            break
        offset += page
    return {"type": "FeatureCollection", "features": features}


def discover_layer_id(feature_server_url):
    info_json = with_retries(fetch_json, f"{feature_server_url}?f=json", desc=f"discover {feature_server_url}")
    layers = info_json.get("layers", [])
    return layers[0]["id"] if layers else 0


def fetch_sicrm(out_dir, force=False):
    state = {} if force else load_layer_state(out_dir)
    ok = skipped = failed = 0
    total = len(SICRM_LAYERS) + len(SICRM_RELATED_SERVICES)
    i = 0

    for lid, name in SICRM_LAYERS.items():
        i += 1
        key = f"main:{lid}"
        fpath = out_dir / (sanitize(name) + ".geojson")
        if not force and fpath.exists():
            skipped += 1
            debug(f"[{i}/{total}] layer '{name}' gia' presente, salto")
            continue
        info(f"  [{i}/{total}] layer '{name}'...")
        try:
            geo = query_arcgis_layer(f"{SICRM_BASE}/{lid}")
            fpath.write_text(json.dumps(geo, ensure_ascii=False))
            state[key] = {"status": "ok", "name": name, "features": len(geo["features"]), "fetched_at": iso_now()}
            ok += 1
        except Exception as e:
            warn(f"    saltato: {e}")
            debug(f"Traceback layer '{name}':\n{traceback.format_exc()}")
            state[key] = {"status": "error", "name": name, "error": str(e), "fetched_at": iso_now()}
            failed += 1
        save_layer_state(out_dir, state)

    related_dir = out_dir / "layer_correlati"
    related_dir.mkdir(exist_ok=True)
    for name, url in SICRM_RELATED_SERVICES.items():
        i += 1
        key = f"related:{name}"
        fpath = related_dir / (sanitize(name) + ".geojson")
        if not force and fpath.exists():
            skipped += 1
            debug(f"[{i}/{total}] servizio correlato '{name}' gia' presente, salto")
            continue
        info(f"  [{i}/{total}] servizio correlato '{name}'...")
        try:
            lid = discover_layer_id(url)
            geo = query_arcgis_layer(f"{url}/{lid}")
            fpath.write_text(json.dumps(geo, ensure_ascii=False))
            state[key] = {"status": "ok", "name": name, "features": len(geo["features"]), "fetched_at": iso_now()}
            ok += 1
        except Exception as e:
            warn(f"    saltato: {e}")
            debug(f"Traceback servizio '{name}':\n{traceback.format_exc()}")
            state[key] = {"status": "error", "name": name, "error": str(e), "fetched_at": iso_now()}
            failed += 1
        save_layer_state(out_dir, state)

    info(f"  sicrm: {ok} ok, {skipped} gia' presenti, {failed} falliti (su {total} totali)")
    return {"status": "complete" if failed == 0 else "partial",
            "layers_ok": ok, "layers_skipped": skipped, "layers_failed": failed, "layers_total": total}


# ---------------------------------------------------------------------------
# 5) / 6) Segnaposto: fonti dove esiste solo un'immagine/PDF, non un vettoriale
#    pubblico individuato finora.
# ---------------------------------------------------------------------------
def make_placeholder(note):
    def _fn(out_dir, force=False):
        (out_dir / "PLACEHOLDER.md").write_text(note, encoding="utf-8")
        return {"status": "complete", "note": note}
    return _fn


PIANO_150KM_NOTE = (
    "Nessuna mappa vettoriale trovata. La pagina "
    "(salvaiciclistiroma.it/mappa-piano-ciclabili-150km) mostra un iframe vuoto "
    "sbloccato solo dopo consenso cookie marketing, e linka solo un PDF "
    "(mappa-pdf-23-11-edit.pdf). Progetto del 2020, probabilmente superato dal "
    "layer Biciplan_2025 gia' incluso nella sorgente salvaiciclisti_sicrm. "
    "Segnaposto in attesa di verifica manuale (accettare i cookie e ricontrollare)."
)

BICIPLAN_PDF_NOTE = (
    "Documenti ufficiali del Biciplan (Quadro Conoscitivo, Documento di Piano) "
    "disponibili solo come PDF con mappe incorporate come immagini. Nessun "
    "servizio vettoriale pubblico individuato. Segnaposto: si presuppone che un "
    "geodatabase esista internamente all'ente, ma non risulta pubblicato."
)


# ---------------------------------------------------------------------------
# Sorgente locale di debug (Alessandro, 2026-10-03: "la sorgente 'debug' deve
# entrare come tutte le altre [...] il precompila non deve 'sapere' che e' un
# test. deve pensare che sia una mappa, semplicemente molto semplice, di
# percorsi ciclabili"): a differenza delle altre, non tocca la rete - legge
# un file curato a mano (prodotto da scripts/extract_debug_subset.m a partire
# dai Tag impostati su plot_debug_graph.m, un sottoinsieme minimo - tipico 2
# archi - estratto da un run reale) e lo riscrive come normale .geojson sotto
# out_dir, esattamente come farebbe una fetch vera: da qui in poi
# iter_line_sources()/load_lines()/build() non sanno (e non devono sapere)
# che non viene da una fonte dati vera - serve a riprodurre un bug su un
# sottoinsieme minimo di dati REALI, dentro la stessa identica pipeline usata
# in produzione, senza il rumore delle migliaia di altri archi di SIC.
# ---------------------------------------------------------------------------
DEBUG_INPUT_PATH = ROOT / "scripts" / "debug_fixtures" / "debug_input.json"


def fetch_debug(out_dir, force=False):
    if not DEBUG_INPUT_PATH.exists():
        return {"status": "error",
                "reason": f"{DEBUG_INPUT_PATH} non trovato - genera prima il sottoinsieme "
                          f"con scripts/extract_debug_subset.m"}
    try:
        entries = json.loads(DEBUG_INPUT_PATH.read_text(encoding="utf-8"))
    except Exception as e:
        return {"status": "error", "reason": f"{DEBUG_INPUT_PATH} illeggibile: {e}"}

    features = []
    for entry in entries:
        coords = entry.get("coords") or []
        if len(coords) < 2:
            continue
        # debug_input.json e' in (lat, lon) come il resto dello script -
        # GeoJSON vuole (lon, lat): si inverte qui, una volta sola, cosi'
        # load_lines() la rilegge con l'identica trasformazione "else" che
        # usa per qualunque altra sorgente normale (ne' UTM33N ne' Web
        # Mercator, quelle sono solo per g3w/rsm).
        lonlat = [[lon, lat] for lat, lon in coords]
        features.append({
            "type": "Feature",
            "properties": {"name": entry.get("name")},
            "geometry": {"type": "LineString", "coordinates": lonlat},
        })

    geojson = {"type": "FeatureCollection", "features": features}
    (out_dir / "debug.geojson").write_text(json.dumps(geojson, ensure_ascii=False), encoding="utf-8")
    info(f"  [debug] {len(features)} archi letti da {DEBUG_INPUT_PATH} e scritti come debug.geojson "
         f"(sorgente locale, nessuna rete coinvolta)")
    return {"status": "complete", "features": len(features),
            "note": f"sorgente locale di debug, non da rete - input: {DEBUG_INPUT_PATH}"}


SOURCES = [
    {"key": "rsm_piste_ciclabili", "fetch": fetch_rsm,
     "desc": "RSM Open Data - piste ciclabili ufficiali"},
    {"key": "osm_extract", "fetch": fetch_osm,
     "desc": "OpenStreetMap - infrastruttura ciclabile estratta via Overpass"},
    {"key": "g3w_ciclovie_metropolitane", "fetch": fetch_g3w,
     "desc": "Geoportale Citta' Metropolitana di Roma - progetti ciclovie (G3W-Suite)"},
    {"key": "salvaiciclisti_sicrm", "fetch": fetch_sicrm,
     "desc": "Salvaiciclisti Roma - Mappone/Biciplan 2025/PUMS/Ciclopolitana/Passaggi Segreti (ArcGIS)"},
    {"key": "debug", "fetch": fetch_debug,
     "desc": "Debug locale - sottoinsieme minimo estratto a mano per isolare un bug (non da rete)"},
    {"key": "piano_150km", "fetch": make_placeholder(PIANO_150KM_NOTE),
     "desc": "Piano Straordinario Ciclabilita' 150km (2020) - segnaposto"},
    {"key": "biciplan_pdf_ufficiale", "fetch": make_placeholder(BICIPLAN_PDF_NOTE),
     "desc": "Biciplan - documenti di piano ufficiali - segnaposto"},
]


# ---------------------------------------------------------------------------
# 4) Pubblicazione su GitHub Releases - l'app scarica da qui a runtime
#    (vedi RemoteMapDataRepository nell'app, 2026-09-21): niente piu' grafo/
#    indice indirizzi/manifest sorgenti dentro l'APK, li scarica lei stessa
#    al bisogno e chiede conferma quando trova una versione piu' recente.
#    Repo scelto da Alessandro: https://github.com/alessandromura-droid/test-sic
#    (pubblico, vuoto - serve solo a ospitare questi asset, non e' il codice
#    dell'app). Si pubblica su una release fissa (tag GITHUB_RELEASE_TAG): ad
#    ogni run i suoi asset vengono sostituiti, non se ne accumula una nuova
#    per build - piu' semplice per l'app, che scarica sempre lo stesso URL.
# ---------------------------------------------------------------------------
GITHUB_REPO = "alessandromura-droid/test-sic"
GITHUB_RELEASE_TAG = "dati"
GITHUB_API = "https://api.github.com"
DATA_MANIFEST_NAME = "data_manifest.json"

# File locale (dentro assets/routing/) -> nome dell'asset pubblicato. I due
# piu' grossi vanno gzippati (JSON comprime molto, e Alessandro voleva
# comunque ridurre i tempi di download sull'app); il manifest resta JSON
# semplice cosi' l'app lo legge con una GET diretta per il solo controllo
# versione, senza dover gunzippare nulla prima di sapere se serve altro.
UPLOAD_FILES = {
    "graph.json": "graph.json.gz",
    "sources_manifest.json": "sources_manifest.json.gz",
    "indirizzi.json": "indirizzi.json.gz",
    "layers.json": "layers.json.gz",
}


def _github_request(method, url, token, data=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read()
        return json.loads(body) if body else None


def _get_or_create_release(token):
    """La release e' identificata dal tag (non da un id fisso, che non
    conosciamo a priori): se non esiste ancora la crea. Ritorna il JSON
    della release, che include gia' 'assets' e 'upload_url'."""
    url = f"{GITHUB_API}/repos/{GITHUB_REPO}/releases/tags/{GITHUB_RELEASE_TAG}"
    try:
        return _github_request("GET", url, token)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    create_url = f"{GITHUB_API}/repos/{GITHUB_REPO}/releases"
    payload = json.dumps({
        "tag_name": GITHUB_RELEASE_TAG,
        "name": "Dati precompilati",
        "body": "Grafo di instradamento e indice indirizzi per la app, "
                "aggiornati automaticamente da precompila.py. Non e' un "
                "rilascio dell'app.",
        "draft": False,
        "prerelease": False,
    }).encode("utf-8")
    return _github_request("POST", create_url, token, data=payload,
                            headers={"Content-Type": "application/json"})


def _delete_existing_asset(token, release, name):
    # GitHub non permette due asset con lo stesso nome sulla stessa release:
    # va cancellato l'asset vecchio prima di caricare quello nuovo.
    for asset in release.get("assets", []):
        if asset.get("name") == name:
            _github_request("DELETE",
                             f"{GITHUB_API}/repos/{GITHUB_REPO}/releases/assets/{asset['id']}",
                             token)
            return


def _upload_asset(token, upload_url_template, name, data, content_type):
    # upload_url arriva come URI template tipo ".../assets{?name,label}":
    # basta troncare alla graffa e aggiungere ?name= a mano.
    upload_url = upload_url_template.split("{")[0] + f"?name={urllib.parse.quote(name)}"
    return _github_request("POST", upload_url, token, data=data,
                            headers={"Content-Type": content_type})


def upload_to_github():
    """Comprime e pubblica graph.json/sources_manifest.json/indirizzi.json
    come asset della release fissa GITHUB_RELEASE_TAG, insieme a un piccolo
    data_manifest.json con un hash di versione (sha256 dei contenuti non
    compressi): e' quello che l'app confronta ad ogni avvio per sapere se
    c'e' un aggiornamento, senza dover riscaricare 30+ MB solo per saperlo.

    Richiede GITHUB_TOKEN nell'ambiente (Personal Access Token fine-grained,
    scope limitato al repo GITHUB_REPO, permesso "Contents: Read and write").
    Se manca (o la pubblicazione fallisce per qualunque altro motivo), NON
    salta piu' in silenzio: il run intero di precompila.py termina con un
    errore (vedi main()), invece di scoprirlo solo dopo - a telefono alla
    mano, con l'app che continua a dire "dati non ancora pubblicati" -
    Alessandro, 2026-10-02. I file restano comunque salvati in locale sotto
    app/src/main/assets/routing/ come sempre, solo la pubblicazione fallisce."""
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        error("[github] GITHUB_TOKEN non impostato nell'ambiente: impossibile "
              "pubblicare su GitHub (serve un Personal Access Token "
              "fine-grained, scope 'Contents: Read and write' sul repo "
              f"{GITHUB_REPO}). Esporta GITHUB_TOKEN prima di rilanciare "
              "precompila.py.")
        return {"status": "error", "reason": "GITHUB_TOKEN mancante"}

    import build_routing_graph_v3 as build_routing_graph
    assets_dir = build_routing_graph.ASSETS_DIR
    missing = [name for name in UPLOAD_FILES if not (assets_dir / name).exists()]
    if missing:
        error(f"[github] file mancanti in {assets_dir}, impossibile pubblicare: {missing}")
        return {"status": "error", "reason": f"file mancanti: {missing}"}

    hasher = hashlib.sha256()
    gzipped = {}
    for local_name in sorted(UPLOAD_FILES):
        raw = (assets_dir / local_name).read_bytes()
        hasher.update(raw)
        gzipped[UPLOAD_FILES[local_name]] = gzip.compress(raw, compresslevel=9)
    version = hasher.hexdigest()[:16]

    data_manifest = json.dumps({
        "version": version,
        "built_at": iso_now(),
        "files": {asset_name: len(blob) for asset_name, blob in gzipped.items()},
    }, ensure_ascii=False).encode("utf-8")

    try:
        release = with_retries(_get_or_create_release, token, desc="release GitHub")
        upload_url_template = release["upload_url"]

        to_publish = dict(gzipped)
        to_publish[DATA_MANIFEST_NAME] = data_manifest

        for asset_name, blob in to_publish.items():
            with_retries(_delete_existing_asset, token, release, asset_name,
                         desc=f"rimozione asset precedente '{asset_name}'")
            content_type = "application/json" if asset_name.endswith(".json") else "application/gzip"
            with_retries(_upload_asset, token, upload_url_template, asset_name, blob, content_type,
                         desc=f"upload '{asset_name}'")
            info(f"  [github] pubblicato {asset_name} ({len(blob)} byte)")

        info(f"[github] pubblicazione completata, versione {version}")
        return {"status": "complete", "version": version}
    except Exception as e:
        error(f"[github] pubblicazione fallita: {e}")
        debug(f"Traceback pubblicazione GitHub:\n{traceback.format_exc()}")
        return {"status": "error", "error": str(e)}


def build_derived_assets(skip_publish=False):
    """Seconda fase, sempre eseguita a fine fetch (anche quando tutte le
    sorgenti grezze erano gia' presenti e sono state saltate): ricostruisce
    da percorsi/raw/ il grafo di instradamento e l'indice indirizzi che
    l'app legge come asset. E' elaborazione puramente locale (build_
    routing_graph.py non tocca la rete), quindi gira in pochi secondi anche
    con tutte le sorgenti presenti - non serve una logica di skip separata.
    build_index_indirizzi.py invece interroga di nuovo Overpass (dati
    diversi: vie con nome, non piste ciclabili) e quindi puo' fallire per
    ragioni di rete come qualunque altra sorgente; un fallimento qui non
    invalida il resto del fetch.

    skip_publish=True (da --no-publish, Alessandro 2026-10-03: "switch per
    non uploadare"): costruisce comunque grafo/layer/indice indirizzi in
    locale come sempre, ma salta la copia di indirizzi.json verso
    routing_v3/ (serve solo alla pubblicazione) e la chiamata a
    upload_to_github() - utile per testare in locale senza toccare la
    release pubblica che la app vera scarica. Ritorna
    {"status": "skipped", ...}, non {"status": "error", ...}: una
    pubblicazione saltata DI PROPOSITO non e' un fallimento (vedi main()).

    Ritorna il risultato di upload_to_github() (o lo stesso skip apposta
    qui sopra se skip_publish): e' quello che main() guarda per decidere il
    codice di uscita dell'intero script - vedi upload_to_github().
    """
    info("\n=== fase 2: costruzione asset per l'app (grafo + indirizzi) ===")

    def _log_build_result(label, result):
        status = result.get("status")
        if status == "complete":
            info(f"[ok]   {label}: {result}")
        elif status == "skipped":
            info(f"[skip] {label}: {result}")
        elif status == "error":
            error(f"[errore] {label}: {result}")
        else:
            warn(f"[parziale] {label}: {result}")

    try:
        import build_routing_graph_v3 as build_routing_graph
        _log_build_result("grafo di instradamento", build_routing_graph.build())
    except Exception as e:
        error(f"[error] costruzione grafo di instradamento: {e}")

    try:
        import build_routing_graph_v3 as build_routing_graph
        _log_build_result("layer di dettaglio (POI)", build_routing_graph.build_detail_layers())
    except Exception as e:
        error(f"[error] costruzione layer di dettaglio: {e}")

    try:
        import build_index_indirizzi
        _log_build_result("indice indirizzi", build_index_indirizzi.build())
    except Exception as e:
        error(f"[error] costruzione indice indirizzi: {e}")

    # Come per precompila_v2.py: ripubblica DAVVERO, sulla stessa release
    # GitHub (stesso GITHUB_REPO/GITHUB_RELEASE_TAG, non cambiati per v3) -
    # da qui in poi l'app vera scarichera' i dati v3 (sperimentali), non
    # piu' quelli v2, al prossimo avvio. build_index_indirizzi.py scrive
    # sempre in routing/ (v1, non duplicato per v3 - l'indice indirizzi
    # non dipende dalla logica di grafo), quindi va copiato in routing_v3/
    # prima di pubblicare o upload_to_github() lo troverebbe mancante.
    if skip_publish:
        info("[skip] pubblicazione GitHub saltata (--no-publish): grafo/layer/indice indirizzi "
             "restano comunque salvati in locale sotto app/src/main/assets/routing_v3/ come sempre, "
             "solo la copia di indirizzi.json pre-pubblicazione e l'upload sono stati saltati")
        return {"status": "skipped", "reason": "pubblicazione disabilitata da --no-publish"}

    import build_routing_graph_v3
    import build_index_indirizzi
    v1_indirizzi = build_index_indirizzi.ASSETS_DIR / "indirizzi.json"
    v3_indirizzi = build_routing_graph_v3.ASSETS_DIR / "indirizzi.json"
    if v1_indirizzi.exists():
        build_routing_graph_v3.ASSETS_DIR.mkdir(parents=True, exist_ok=True)
        v3_indirizzi.write_bytes(v1_indirizzi.read_bytes())
        info(f"[ok]   copiato {v1_indirizzi} -> {v3_indirizzi} (indice indirizzi, invariato in v3)")
    else:
        warn(f"[warn] {v1_indirizzi} non trovato - la pubblicazione fallira' per file mancante")

    try:
        publish_result = upload_to_github()
        _log_build_result("pubblicazione GitHub", publish_result)
    except Exception as e:
        error(f"[error] pubblicazione GitHub: {e}")
        publish_result = {"status": "error", "error": str(e)}

    return publish_result


def main():
    parser = argparse.ArgumentParser(
        description="Scarica le sorgenti dati per l'app Salvaiciclisti.")
    parser.add_argument("--force", action="store_true", help="riscarica anche le sorgenti/i layer gia' presenti")
    parser.add_argument("--only", nargs="*", default=None, help="chiavi delle sole sorgenti da processare")
    parser.add_argument("--reprocess-only", action="store_true",
        help="salta del tutto il fetch in rete e riprocessa solo percorsi/raw/ gia' scaricato "
             "(grafo di instradamento, layer di dettaglio, indice indirizzi, pubblicazione) - utile "
             "dopo aver cambiato un parametro in precompila_parametri.txt senza dover riscaricare nulla. "
             "Ignora --force e --only, che riguardano solo il fetch.")
    parser.add_argument("--no-publish", action="store_true",
        help="costruisce comunque grafo/layer/indice indirizzi in locale, ma salta la pubblicazione "
             "su GitHub (e la copia di indirizzi.json che serve solo a quella) - utile per test locali "
             "senza toccare la release pubblica che la app vera scarica.")
    args = parser.parse_args()

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    log_path = setup_logging()
    info(f"=== precompila.py avviato ({iso_now()}) ===")
    info(f"Log dettagliato (con traceback completi): {log_path}")

    if args.reprocess_only:
        if args.force or args.only:
            warn("[--reprocess-only] --force/--only ignorati: riguardano solo il fetch, che qui viene saltato")
        info("[--reprocess-only] fetch saltato, riprocesso solo percorsi/raw/ gia' presente")
    else:
        manifest = {}
        for source in SOURCES:
            key = source["key"]
            if args.only and key not in args.only:
                continue
            out_dir = RAW_DIR / key
            marker = out_dir / "_done.json"

            if marker.exists() and not args.force:
                info(f"[skip] {key} - gia' completo (usa --force per riscaricare)")
                manifest[key] = json.loads(marker.read_text())
                continue

            info(f"[fetch] {key} - {source['desc']}")
            out_dir.mkdir(parents=True, exist_ok=True)
            try:
                summary = source["fetch"](out_dir, force=args.force)
                record = {"fetched_at": iso_now(), "desc": source["desc"], **summary}
                manifest[key] = record
                if record.get("status") == "complete":
                    marker.write_text(json.dumps(record, indent=2, ensure_ascii=False))
                    info(f"[ok]   {key}")
                else:
                    info(f"[parziale] {key} - alcuni elementi falliti (vedi log); "
                         f"un prossimo giro senza --force ritentera' solo quelli")
            except Exception as e:
                error(f"[error] {key}: {e}")
                manifest[key] = {"error": str(e), "desc": source["desc"], "fetched_at": iso_now()}

        (RAW_DIR / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False))
        info(f"\nFatto il fetch delle sorgenti grezze. Manifest: {RAW_DIR / 'manifest.json'}")

    # Mappa supplementare "raccordi" (scripts/raccordi.py, Alessandro
    # 2026-10-13): generata a parte, dipende dal codice ABCD, dai suoi
    # parametri e dai dati grezzi della sorgente primaria. Se l'impronta
    # salvata non coincide piu' con quella attuale ci si FERMA qui, prima di
    # costruire/pubblicare un grafo con raccordi vecchi.
    try:
        import raccordi
        stato_racc, msg_racc = raccordi.verifica_impronta()
    except Exception as e:
        error(f"[fatal] verifica dei raccordi fallita: {e}")
        return 1
    if stato_racc == "vecchia":
        error(f"[fatal] raccordi: {msg_racc}")
        return 1
    elif stato_racc == "assente":
        warn(f"[warn] raccordi: {msg_racc}")
    else:
        info(f"[ok]   raccordi: {msg_racc}")

    publish_result = build_derived_assets(skip_publish=args.no_publish)

    info(f"\nLog completo di questa run: {log_path}")

    if publish_result.get("status") == "skipped":
        info(f"[ok]   {publish_result.get('reason')} - nessun errore, precompila_v3.py termina normalmente.")
        return 0

    if publish_result.get("status") != "complete":
        reason = publish_result.get("reason") or publish_result.get("error") or "motivo sconosciuto"
        error(f"[fatal] pubblicazione su GitHub non riuscita ({reason}) - precompila_v3.py termina con errore.")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
