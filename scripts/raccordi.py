#!/usr/bin/env python3
"""
raccordi.py - mappa supplementare "raccordi" (Alessandro, 2026-10-13).

Scopo: la sorgente primaria (SIC) e' frammentata - archi isolati, gruppi di
archi scollegati, giri lunghi inutili - e partenza/arrivo vengono agganciati
in linea d'aria al punto SIC piu' vicino anche quando in mezzo c'e' un
fiume, una ferrovia o il GRA. OSM ciclabile (osm_extract) non risolve: e'
frammentata quanto SIC. Questo script costruisce, a partire dalla rete
stradale OSM completa filtrata con un profilo bici (sorgente supplente),
SOLO i tratti di strada che servono a:

  1. CONNESSIONE: collegare fra loro tutte le componenti di SIC (albero di
     costo minimo fra le componenti, piu' - "generoso" - il raccordo
     migliore fra OGNI coppia di componenti vicine entro connessione_max_m);
  2. ANTI-GIRO: eliminare i giri lunghi - per punti campione su SIC, se un
     altro punto SIC e' raggiungibile su strada in dr metri ma sulla rete
     SIC+raccordi solo in piu' di antigiro_rapporto*dr + antigiro_tolleranza_m,
     si aggiunge il tratto di strada;
  3. ACCESSO: per ogni punto dell'indice indirizzi (gli stessi da cui l'app
     fa partire e arrivare i percorsi), se la distanza su strada dalla rete
     e' molto maggiore di quella in linea d'aria (= c'e' una barriera in
     mezzo), si aggiunge il tratto di strada che porta alla rete; il
     raccordo comprende l'intero tratto di strada su cui cade l'indirizzo,
     cosi' l'aggancio dell'app (arco piu' vicino) sceglie lui e non la SIC
     oltre la barriera.

La sorgente primaria NON viene letta da un graph.json gia' prodotto (che
potrebbe essere vecchio): lo script importa build_routing_graph_v3 e
riesegue lui stesso la pulizia A/B/B2/C/D sulla sola primaria, con lo
stesso codice e gli stessi parametri della pipeline.

Output: percorsi/raw/raccordi/raccordi.geojson (una sorgente come le altre,
chiave "raccordi" in SOURCE_LABELS di build_routing_graph_v3.py) e
percorsi/raw/raccordi/raccordi_impronta.json. L'impronta e' un hash di tutto
cio' da cui i raccordi dipendono (codice v3 e di questo script, parametri
ABCD, parametri di raccordi_parametri.txt, file grezzi della primaria):
precompila_v3.py la ricalcola prima di costruire il grafo e si FERMA con
errore se non coincide, cosi' non si pubblicano raccordi vecchi dopo una
modifica ad ABCD o ai dati.

Dati stradali: query Overpass propria a tasselli rettangolari (tasselli_n x
tasselli_n), con piu' server di riserva (overpass_server); cache per tassello
in percorsi/raw/_cache_raccordi/ (cartella ignorata dalla pipeline), rifatta
solo se cambia la query (area, tipi di strada, tasselli) o con
forza_download = true.
I sensi unici NON sono considerati (il grafo dell'app non e' orientato).

Ordine corretto dopo un aggiornamento dei dati o di ABCD:
    python3 scripts/precompila_v3.py          (fetch; si ferma se i raccordi sono vecchi)
    python3 scripts/raccordi.py
    python3 scripts/precompila_v3.py --reprocess-only

Parametri: raccordi_parametri.txt nella cartella principale (creato con i
default se manca; i parametri nuovi vengono aggiunti in coda).
"""
import gzip
import hashlib
import heapq
import json
import math
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
ROOT = SCRIPTS_DIR.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

PARAMS_FILE = ROOT / "raccordi_parametri.txt"
RAW_DIR = ROOT / "percorsi" / "raw"
SOURCE_KEY = "raccordi"
OUT_DIR = RAW_DIR / SOURCE_KEY
OUT_GEOJSON = OUT_DIR / "raccordi.geojson"
OUT_IMPRONTA = OUT_DIR / "raccordi_impronta.json"
CACHE_DIR = RAW_DIR / "_cache_raccordi"
REPORT_FILE = ROOT / "percorsi" / "build" / "raccordi_report.txt"
INDIRIZZI_FILE = ROOT / "percorsi" / "build" / "routing" / "indirizzi.json"

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
UA_HEADERS = {"User-Agent": "salvaiciclisti-raccordi/1.0 (+script di raccolta dati per app di navigazione bici)"}

# Tipi di strada OSM (highway=*) del profilo bici, ciascuno con un peso
# moltiplicativo sulla lunghezza (1 = neutro, >1 = evitato, <=0 = escluso).
# I *_link usano il peso del tipo base. motorway/trunk mai inclusi.
TIPI_STRADA = [
    ("cycleway", 1.0), ("living_street", 1.0), ("residential", 1.1),
    ("unclassified", 1.2), ("pedestrian", 1.3), ("path", 1.3), ("footway", 1.3),
    ("track", 1.5), ("service", 1.6), ("tertiary", 1.4), ("secondary", 1.8),
    ("primary", 2.5),
]
CON_LINK = {"primary", "secondary", "tertiary"}
BICI_SI = {"yes", "designated", "permissive"}

# (chiave, default, tipo, commento) - ordine = ordine nel file parametri.
_PARAMS_SPEC = [
    ("sorgente_primaria", "salvaiciclisti_sicrm", str,
     "Sorgente da collegare (chiave di SOURCE_LABELS in build_routing_graph_v3.py)."),
    ("sorgente_supplente", "osm_strade", str,
     "Rete da cui si prendono i raccordi. Oggi l'unica supportata e' 'osm_strade':\n"
     "# la rete stradale OpenStreetMap completa, scaricata da questo script e\n"
     "# filtrata con i pesi peso_* qui sotto (NON osm_extract, che e' solo ciclabile)."),
    ("centro_lat", 41.896435, float, "Centro dell'area di lavoro (default: Piazza Venezia)."),
    ("centro_lon", 12.482531, float, "Centro dell'area di lavoro."),
    ("raggio_km", 12.0, float,
     "Raggio (km) dell'area di lavoro: strade scaricate, raccordi e indirizzi solo\n"
     "# qui dentro (12 km ~ GRA compreso)."),
] + [
    (f"peso_{t}", w, float,
     f"Peso di highway={t}" + (f" (e {t}_link)" if t in CON_LINK else "") +
     ": moltiplica la lunghezza; <= 0 esclude il tipo."
     + ("\n# footway solo con bicycle=yes/designated/permissive." if t == "footway" else ""))
    for t, w in TIPI_STRADA
] + [
    ("aggancio_raggio_m", 40.0, float,
     "Distanza massima (m) fra un punto della primaria e la strada a cui viene agganciato."),
    ("aggancio_passo_m", 25.0, float,
     "Passo (m) dei punti di aggancio lungo gli archi della primaria."),
    ("peso_aggancio", 1.5, float,
     "Peso del tratto in linea d'aria fra primaria e strada (moltiplica la distanza)."),
    ("connessione_max_m", 1500.0, float,
     "CONNESSIONE: oltre all'albero minimo che collega tutto, si aggiunge il raccordo\n"
     "# migliore fra ogni coppia di componenti vicine con costo (m pesati) <= di questo."),
    ("antigiro_campionamento_m", 250.0, float,
     "ANTI-GIRO: distanza (m) fra i punti campione sulla primaria (piu' piccolo = piu'\n"
     "# raccordi e piu' tempo di calcolo)."),
    ("antigiro_raggio_m", 2000.0, float,
     "ANTI-GIRO: distanza su strada (m) entro cui si cercano punti della primaria."),
    ("antigiro_rapporto", 1.5, float,
     "ANTI-GIRO: si aggiunge la strada se rete > rapporto * strada + tolleranza."),
    ("antigiro_tolleranza_m", 300.0, float, "ANTI-GIRO: tolleranza (m)."),
    ("accesso_rapporto", 2.0, float,
     "ACCESSO: si aggiunge la strada se distanza su strada dalla rete >\n"
     "# rapporto * distanza in linea d'aria + tolleranza (= barriera in mezzo)."),
    ("accesso_tolleranza_m", 150.0, float, "ACCESSO: tolleranza (m)."),
    ("accesso_aggancio_max_m", 100.0, float,
     "ACCESSO: distanza massima (m) fra un punto indirizzo e la strada piu' vicina."),
    ("forza_download", False, "bool",
     "true = riscarica la rete stradale anche se la cache e' valida."),
    ("tasselli_n", 4, int,
     "La rete stradale si scarica in tasselli_n x tasselli_n rettangoli (cache\n"
     "# separata per ciascuno): alzarlo se Overpass da' timeout (504)."),
    ("overpass_server",
     "https://overpass-api.de/api/interpreter,https://overpass.kumi.systems/api/interpreter,"
     "https://overpass.private.coffee/api/interpreter", str,
     "Server Overpass da provare in ordine, separati da virgola."),
]
_NON_IMPRONTA = {"forza_download", "tasselli_n", "overpass_server"}


def _cast(tipo, s):
    if tipo == "bool":
        v = s.strip().lower()
        if v in ("true", "1", "si", "yes"):
            return True
        if v in ("false", "0", "no"):
            return False
        raise ValueError(s)
    return tipo(s)


def carica_parametri():
    P = {k: d for k, d, _t, _c in _PARAMS_SPEC}
    spec = {k: (t, c) for k, _d, t, c in _PARAMS_SPEC}
    if not PARAMS_FILE.exists():
        righe = ["# Parametri di scripts/raccordi.py (mappa supplementare 'raccordi').", ""]
        for k, d, _t, c in _PARAMS_SPEC:
            righe += [f"# {c}", f"{k} = {str(d).lower() if isinstance(d, bool) else d}", ""]
        PARAMS_FILE.write_text("\n".join(righe), encoding="utf-8")
        print(f"[parametri] creato {PARAMS_FILE} con i default")
        return P
    visti = set()
    for n, raw in enumerate(PARAMS_FILE.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if "=" not in line:
            continue
        k, _, v = line.partition("=")
        k = k.strip().lower()
        if k not in spec:
            print(f"[parametri] riga {n}: '{k}' sconosciuto, ignorato")
            continue
        try:
            P[k] = _cast(spec[k][0], v.strip())
            visti.add(k)
        except ValueError:
            print(f"[parametri] riga {n}: valore non valido per '{k}', resto al default {P[k]}")
    mancanti = [(k, d, c) for k, d, _t, c in _PARAMS_SPEC if k not in visti]
    if mancanti:
        testo = PARAMS_FILE.read_text(encoding="utf-8").rstrip("\n")
        testo += "\n\n# --- parametri aggiunti automaticamente ---\n"
        for k, d, c in mancanti:
            testo += f"\n# {c}\n{k} = {str(d).lower() if isinstance(d, bool) else d}\n"
        PARAMS_FILE.write_text(testo, encoding="utf-8")
        print(f"[parametri] aggiunti in coda a {PARAMS_FILE.name}: {', '.join(k for k, _d, _c in mancanti)}")
    return P


# ---------------------------------------------------------------------------
# Impronta: verificata da precompila_v3.py prima di costruire il grafo.
# ---------------------------------------------------------------------------
def _cartella_primaria(primaria):
    # la Ciclopolitana non ha una cartella propria: sta dentro quella di SIC
    return "salvaiciclisti_sicrm" if primaria == "salvaiciclisti_ciclopolitana" else primaria


def impronta(P=None):
    import build_routing_graph_v3 as v3
    if P is None:
        P = carica_parametri()
    h = hashlib.sha256()
    for f in (SCRIPTS_DIR / "build_routing_graph_v3.py", SCRIPTS_DIR / "raccordi.py"):
        h.update(f.name.encode())
        h.update(f.read_bytes())
    abcd = {k: getattr(v3, k, None) for k in (
        "NODE_CLUSTER_MAX_DIAMETER_M", "MAX_CROSSINGS_PER_PAIR", "DANGLE_TOLERANCE_M",
        "NEATNET_MODE", "SOURCE_PASS_MAX_ITERATIONS", "UTURN_ANGLE_THRESHOLD_DEG",
        "GRID_CELL_DEG")}
    h.update(json.dumps(abcd, sort_keys=True, default=str).encode())
    # interruttori dei singoli layer (layer_<...>): cambiano le linee della primaria
    pref = "LAYER_" + v3._layer_prefix(P["sorgente_primaria"]).upper() + "_"
    layer_flags = {k: v for k, v in vars(v3).items() if k.startswith(pref) and isinstance(v, bool)}
    h.update(json.dumps(layer_flags, sort_keys=True).encode())
    h.update(json.dumps({k: v for k, v in P.items() if k not in _NON_IMPRONTA},
                        sort_keys=True, default=str).encode())
    cartella = RAW_DIR / _cartella_primaria(P["sorgente_primaria"])
    for f in sorted(cartella.rglob("*.geojson")):
        h.update(str(f.relative_to(RAW_DIR)).encode())
        h.update(f.read_bytes())
    return h.hexdigest()


def verifica_impronta():
    """Per precompila_v3.py. Ritorna (stato, messaggio), stato in
    'ok' | 'assente' | 'disabilitata' | 'vecchia'."""
    import build_routing_graph_v3 as v3
    if not v3._source_enabled(SOURCE_KEY):
        return "disabilitata", "raccordi disabilitati (process_raccordi = false)"
    if not OUT_GEOJSON.exists():
        return "assente", (f"{OUT_GEOJSON} non esiste: il grafo verra' costruito SENZA raccordi "
                           f"(per crearli: python3 scripts/raccordi.py)")
    try:
        salvata = json.loads(OUT_IMPRONTA.read_text()).get("impronta")
    except Exception:
        salvata = None
    attuale = impronta()
    if salvata != attuale:
        return "vecchia", ("i raccordi non corrispondono piu' a codice/parametri/dati attuali "
                           "(ABCD, raccordi_parametri.txt o sorgente primaria cambiati). Rilancia "
                           "python3 scripts/raccordi.py e poi precompila_v3.py --reprocess-only")
    return "ok", "raccordi aggiornati (impronta coincide)"


# ---------------------------------------------------------------------------
# Geometria: proiezione locale equirettangolare (errore trascurabile entro
# ~20 km) e indice spaziale a griglia.
# ---------------------------------------------------------------------------
class Proiezione:
    def __init__(self, lat0, lon0):
        self.lat0, self.lon0 = lat0, lon0
        self.kx = 111320.0 * math.cos(math.radians(lat0))
        self.ky = 110540.0

    def xy(self, lat, lon):
        return ((lon - self.lon0) * self.kx, (lat - self.lat0) * self.ky)


class Griglia:
    def __init__(self, cella_m):
        self.c = cella_m
        self.celle = {}

    def add(self, x, y, item):
        self.celle.setdefault((int(x // self.c), int(y // self.c)), []).append((x, y, item))

    def vicini(self, x, y, r):
        """(distanza, item) entro r metri."""
        c = self.c
        out = []
        for ix in range(int((x - r) // c), int((x + r) // c) + 1):
            for iy in range(int((y - r) // c), int((y + r) // c) + 1):
                for (px, py, it) in self.celle.get((ix, iy), ()):
                    d = math.hypot(px - x, py - y)
                    if d <= r:
                        out.append((d, it))
        return out

    def piu_vicino(self, x, y, r_max):
        r = self.c
        while True:
            v = self.vicini(x, y, min(r, r_max))
            if v:
                return min(v, key=lambda t: t[0])
            if r >= r_max:
                return None
            r *= 2


# ---------------------------------------------------------------------------
# Rete stradale OSM (sorgente supplente)
# ---------------------------------------------------------------------------
def _tipi_regex(P):
    tipi = []
    for t, _w in TIPI_STRADA:
        if P[f"peso_{t}"] > 0:
            tipi.append(t)
            if t in CON_LINK:
                tipi.append(f"{t}_link")
    return "|".join(tipi)


def _tasselli(P):
    """Rettangoli (sud, ovest, nord, est) che coprono il quadrato circoscritto
    al cerchio dell'area, divisi in tasselli_n x tasselli_n."""
    lat0, lon0, r = P["centro_lat"], P["centro_lon"], P["raggio_km"] * 1000.0
    dlat = r / 110540.0
    dlon = r / (111320.0 * math.cos(math.radians(lat0)))
    n = max(1, int(P["tasselli_n"]))
    s0, w0 = lat0 - dlat, lon0 - dlon
    hl, hw = 2 * dlat / n, 2 * dlon / n
    return [(i, j, round(s0 + i * hl, 6), round(w0 + j * hw, 6),
             round(s0 + (i + 1) * hl, 6), round(w0 + (j + 1) * hw, 6))
            for i in range(n) for j in range(n)]


def _scarica_query(query, server):
    body = urllib.parse.urlencode({"data": query}).encode()
    ultimo = None
    for url in server:
        for tentativo in range(1, 3):
            try:
                req = urllib.request.Request(url, data=body, headers=UA_HEADERS)
                with urllib.request.urlopen(req, timeout=700) as resp:
                    return json.loads(resp.read().decode("utf-8"))
            except Exception as e:
                ultimo = e
                print(f"    {url} tentativo {tentativo}/2 fallito: {e!r}")
                time.sleep(15 * tentativo)
    raise SystemExit(f"[strade] download fallito su tutti i server: {ultimo!r}")


def scarica_strade(P, proj):
    """Scarica la rete stradale a tasselli rettangolari (molto piu' leggeri per
    Overpass di un unico 'around' su 12 km), ciascuno con la propria cache;
    in caso di errore prova i server successivi di overpass_server. Unisce i
    tasselli (vie e nodi senza doppioni) e tiene solo le vie con almeno un
    nodo dentro il cerchio dell'area."""
    regex = _tipi_regex(P)
    server = [u.strip() for u in P["overpass_server"].split(",") if u.strip()]
    tasselli = _tasselli(P)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    nodi, vie = {}, {}
    for k, (i, j, s_, w_, n_, e_) in enumerate(tasselli, 1):
        query = (f'[out:json][timeout:600];\n'
                 f'way["highway"~"^({regex})$"]({s_},{w_},{n_},{e_});\n'
                 f'out body;\n>;\nout skel qt;\n')
        cache = CACHE_DIR / f"tassello_{i}_{j}.json.gz"
        data = None
        if cache.exists() and not P["forza_download"]:
            try:
                with gzip.open(cache, "rt", encoding="utf-8") as f:
                    c = json.load(f)
                if c.get("query") == query:
                    data = c["data"]
            except Exception as e:
                print(f"[strade] cache {cache.name} illeggibile ({e!r}): riscarico")
        if data is None:
            print(f"[strade] tassello {k}/{len(tasselli)} ({s_},{w_},{n_},{e_}): download...")
            data = _scarica_query(query, server)
            with gzip.open(cache, "wt", encoding="utf-8") as f:
                json.dump({"query": query, "data": data}, f)
            time.sleep(2)
        else:
            print(f"[strade] tassello {k}/{len(tasselli)}: dalla cache")
        for el in data.get("elements", []):
            if el.get("type") == "node":
                nodi[el["id"]] = el
            elif el.get("type") == "way":
                vie[el["id"]] = el
    R = P["raggio_km"] * 1000.0

    def dentro(nid):
        n = nodi.get(nid)
        return n is not None and math.hypot(*proj.xy(n["lat"], n["lon"])) <= R

    vie_ok = [w for w in vie.values() if any(dentro(n) for n in w.get("nodes", []))]
    print(f"[strade] {len(vie)} vie scaricate, {len(vie_ok)} dentro il cerchio, {len(nodi)} nodi")
    return {"elements": list(nodi.values()) + vie_ok}


class Strade:
    """Grafo stradale: nodi = incroci/estremi (solo nodi OSM condivisi da
    piu' vie o estremi di via), archi = tratti fra due incroci con la loro
    geometria. Costo = lunghezza * peso del tipo di strada."""

    def __init__(self, data, P, proj):
        pesi = {t: P[f"peso_{t}"] for t, _w in TIPI_STRADA}
        coord = {}
        vie = []
        for el in data.get("elements", []):
            if el.get("type") == "node":
                coord[el["id"]] = (el["lat"], el["lon"])
            elif el.get("type") == "way":
                vie.append(el)
        uso = {}
        tenute = []
        for w in vie:
            tags = w.get("tags", {}) or {}
            hw = tags.get("highway", "")
            base = hw[:-5] if hw.endswith("_link") else hw
            peso = pesi.get(base, 0)
            if peso <= 0:
                continue
            bici = tags.get("bicycle")
            if bici in ("no", "use_sidepath"):
                continue
            if tags.get("access") in ("private", "no") and bici not in BICI_SI:
                continue
            if base == "footway" and bici not in BICI_SI:
                continue
            if base == "service" and tags.get("service") in ("parking_aisle", "driveway"):
                continue
            if tags.get("area") == "yes":
                continue
            nds = [n for n in w.get("nodes", []) if n in coord]
            if len(nds) < 2:
                continue
            tenute.append((nds, peso, tags.get("name")))
            for n in nds:
                uso[n] = uso.get(n, 0) + 1
            uso[nds[0]] = uso.get(nds[0], 0) + 1
            uso[nds[-1]] = uso.get(nds[-1], 0) + 1
        self.idx = {}
        self.nodi = []        # (lat, lon)
        self.nodi_xy = []
        self.archi = []       # dict a, b, geom [(lat,lon)], cum [m], len, w, nome
        self.adj = []

        def nodo(osm_id):
            i = self.idx.get(osm_id)
            if i is None:
                i = len(self.nodi)
                self.idx[osm_id] = i
                lat, lon = coord[osm_id]
                self.nodi.append((lat, lon))
                self.nodi_xy.append(proj.xy(lat, lon))
                self.adj.append([])
            return i

        for nds, peso, nome in tenute:
            inizio = 0
            for i in range(1, len(nds)):
                if uso[nds[i]] >= 2 or i == len(nds) - 1:
                    tratto = nds[inizio:i + 1]
                    a, b = nodo(tratto[0]), nodo(tratto[-1])
                    geom = [coord[n] for n in tratto]
                    cum = [0.0]
                    for k in range(1, len(geom)):
                        x1, y1 = proj.xy(*geom[k - 1])
                        x2, y2 = proj.xy(*geom[k])
                        cum.append(cum[-1] + math.hypot(x2 - x1, y2 - y1))
                    if a != b and cum[-1] > 0:
                        eid = len(self.archi)
                        self.archi.append({"a": a, "b": b, "geom": geom, "cum": cum,
                                           "len": cum[-1], "w": peso, "nome": nome})
                        self.adj[a].append((b, eid))
                        self.adj[b].append((a, eid))
                    inizio = i
        # indice dei vertici (per agganciare punti qualunque alle strade)
        self.vertici = Griglia(50.0)
        for eid, e in enumerate(self.archi):
            for k, (lat, lon) in enumerate(e["geom"]):
                x, y = proj.xy(lat, lon)
                self.vertici.add(x, y, (eid, k))
        km = sum(e["len"] for e in self.archi) / 1000
        print(f"[strade] grafo stradale: {len(self.nodi)} nodi, {len(self.archi)} archi, {km:.0f} km")

    def mezzi(self, eid, k):
        """Dal vertice k dell'arco eid ai due estremi: [(nodo, costo, fisico, geom da k)]."""
        e = self.archi[eid]
        c = e["cum"][k]
        return [(e["a"], c * e["w"], c, e["geom"][k::-1]),
                (e["b"], (e["len"] - c) * e["w"], e["len"] - c, e["geom"][k:])]


# ---------------------------------------------------------------------------
# Sorgente primaria pulita con ABCD (stesso codice della pipeline v3)
# ---------------------------------------------------------------------------
def primaria_pulita(primaria):
    import build_routing_graph_v3 as v3
    lines, _stats = v3.load_lines()
    lines, _u = v3.remove_uturn_spikes(lines)
    src = [l for l in lines if l["source"] == primaria]
    if not src:
        raise SystemExit(f"[primaria] nessuna linea per '{primaria}' in percorsi/raw/")
    print(f"[primaria] {primaria}: {len(src)} linee grezze, pulizia ABCD (metodo {v3.NEATNET_MODE})")
    if v3.NEATNET_MODE in ("enable", "both"):
        src = v3._neatnet_preprocess(src, v3.NODE_CLUSTER_MAX_DIAMETER_M, v3.DANGLE_TOLERANCE_M, primaria)
    if v3.NEATNET_MODE == "enable":
        nodes, edges = v3.build_source_graph(
            src, v3.MAX_CROSSINGS_PER_PAIR, v3.NODE_CLUSTER_MAX_DIAMETER_M, v3.DANGLE_TOLERANCE_M)
    else:
        nodes, edges = v3.build_source_graph_looped(
            src, v3.MAX_CROSSINGS_PER_PAIR, v3.NODE_CLUSTER_MAX_DIAMETER_M, v3.DANGLE_TOLERANCE_M,
            v3.SOURCE_PASS_MAX_ITERATIONS, primaria)
    print(f"[primaria] dopo ABCD: {len(nodes)} nodi, {len(edges)} archi")
    return nodes, edges


class UnionFind:
    def __init__(self, n=0):
        self.p = list(range(n))

    def add(self):
        self.p.append(len(self.p))
        return len(self.p) - 1

    def find(self, i):
        while self.p[i] != i:
            self.p[i] = self.p[self.p[i]]
            i = self.p[i]
        return i

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra == rb:
            return False
        self.p[ra] = rb
        return True


# ---------------------------------------------------------------------------
# Script principale
# ---------------------------------------------------------------------------
def main():
    t0 = time.time()
    P = carica_parametri()
    if P["sorgente_supplente"] != "osm_strade":
        raise SystemExit(f"sorgente_supplente = {P['sorgente_supplente']!r} non supportata (solo 'osm_strade')")
    proj = Proiezione(P["centro_lat"], P["centro_lon"])
    R = P["raggio_km"] * 1000.0

    def in_area(x, y):
        return math.hypot(x, y) <= R

    strade = Strade(scarica_strade(P, proj), P, proj)
    S = strade
    nodes, edges = primaria_pulita(P["sorgente_primaria"])

    # --- componenti della primaria ---
    uf = UnionFind(len(nodes))
    for e in edges:
        uf.union(e["a"], e["b"])
    comp_di_nodo = [uf.find(i) for i in range(len(nodes))]

    # --- punti di aggancio: nodi della primaria + punti ogni 'passo' lungo gli
    # archi. Formano anche il grafo H (primaria densificata) usato per le
    # distanze "sulla rete".
    passo = P["aggancio_passo_m"]
    att_ll = [tuple(n) for n in nodes]
    att_xy = [proj.xy(*n) for n in nodes]
    att_comp = list(comp_di_nodo)
    att_arco = [-1] * len(nodes)          # arco della primaria (per gli interni)
    H = [[] for _ in range(len(nodes))]

    def h_add(u, v, l):
        while max(u, v) >= len(H):
            H.append([])
        H[u].append((v, l))
        H[v].append((u, l))

    for ei, e in enumerate(edges):
        g = e["geom"]
        pts = [proj.xy(lat, lon) for lat, lon in g]
        prev, prev_d, dist = e["a"], 0.0, 0.0
        prossimo = passo
        for k in range(1, len(g)):
            seg = math.hypot(pts[k][0] - pts[k - 1][0], pts[k][1] - pts[k - 1][1])
            while seg > 0 and prossimo <= dist + seg and prossimo < e["len"] - passo / 2:
                f = (prossimo - dist) / seg
                lat = g[k - 1][0] + f * (g[k][0] - g[k - 1][0])
                lon = g[k - 1][1] + f * (g[k][1] - g[k - 1][1])
                nid = len(att_ll)
                att_ll.append((lat, lon))
                att_xy.append(proj.xy(lat, lon))
                att_comp.append(comp_di_nodo[e["a"]])
                att_arco.append(ei)
                h_add(prev, nid, prossimo - prev_d)
                prev, prev_d = nid, prossimo
                prossimo += passo
            dist += seg
        h_add(prev, e["b"], max(dist - prev_d, 0.0))
    n_att = len(att_ll)
    comp_area = {att_comp[i] for i in range(n_att) if in_area(*att_xy[i])}
    print(f"[primaria] {n_att} punti di aggancio, {len(comp_area)} componenti nell'area")

    # --- agganci primaria -> strade (fino a 3 archi stradali per punto) ---
    links = []               # dict att, node, cost, phys, eid, k, verso
    links_by_att = {}
    links_by_node = {}
    peso_agg = P["peso_aggancio"]
    for i in range(n_att):
        x, y = att_xy[i]
        if not in_area(x, y):
            continue
        per_arco = {}
        for d, (eid, k) in S.vertici.vicini(x, y, P["aggancio_raggio_m"]):
            if eid not in per_arco or d < per_arco[eid][0]:
                per_arco[eid] = (d, k)
        migliori = sorted(per_arco.items(), key=lambda t: t[1][0])[:3]
        visti = {}
        for eid, (d, k) in migliori:
            for verso, (nodo, c, ph, _g) in enumerate(S.mezzi(eid, k)):
                costo = d * peso_agg + c
                if nodo not in visti or costo < links[visti[nodo]]["cost"]:
                    rec = {"att": i, "node": nodo, "cost": costo, "phys": d + ph,
                           "eid": eid, "k": k, "verso": verso}
                    if nodo in visti:
                        links[visti[nodo]] = rec
                    else:
                        visti[nodo] = len(links)
                        links.append(rec)
        for nodo, li in visti.items():
            links_by_att.setdefault(i, []).append(li)
            links_by_node.setdefault(nodo, []).append(li)
    print(f"[agganci] {len(links)} agganci primaria-strade")

    OFF = n_att                      # in H: nodo stradale r -> OFF + r
    sel_archi = {}                   # eid -> tipo
    sel_link = {}                    # link id -> tipo
    parziali = []                    # (geom [(lat,lon)], nome, tipo) - solo ACCESSO

    def seleziona_link(li, tipo):
        if li not in sel_link:
            sel_link[li] = tipo
            L = links[li]
            h_add(L["att"], OFF + L["node"], L["phys"])

    def seleziona_arco(eid, tipo):
        if eid not in sel_archi:
            sel_archi[eid] = tipo
            e = S.archi[eid]
            h_add(OFF + e["a"], OFF + e["b"], e["len"])

    def risali(nodo, pred, tipo):
        while True:
            p = pred[nodo]
            if p[0] == "l":
                seleziona_link(p[1], tipo)
                return
            if p[0] == "s":
                return
            seleziona_arco(p[1], tipo)
            nodo = p[2]

    def dijkstra_strade(sorgenti, limite_phys=None):
        """sorgenti: [(nodo, costo, fisico, pred, etichetta)]. Ritorna dict
        nodo -> [costo, fisico, pred, etichetta]."""
        best = {}
        heap = []
        for nodo, c, ph, pr, lab in sorgenti:
            if nodo not in best or c < best[nodo][0]:
                best[nodo] = [c, ph, pr, lab]
                heapq.heappush(heap, (c, nodo))
        fatti = set()
        while heap:
            c, u = heapq.heappop(heap)
            if u in fatti or c > best[u][0]:
                continue
            fatti.add(u)
            _c, ph_u, _p, lab = best[u]
            if limite_phys is not None and ph_u > limite_phys:
                continue
            for v, eid in S.adj[u]:
                e = S.archi[eid]
                nc = c + e["len"] * e["w"]
                if v not in best or nc < best[v][0]:
                    best[v] = [nc, ph_u + e["len"], ("e", eid, u), lab]
                    heapq.heappush(heap, (nc, v))
        return best

    # =====================================================================
    # Passata 1 - CONNESSIONE
    # =====================================================================
    t1 = time.time()
    sorg = [(L["node"], L["cost"], L["phys"], ("l", li), att_comp[L["att"]]) for li, L in enumerate(links)]
    D = dijkstra_strade(sorg)
    candidati = {}                   # (ca, cb) -> (costo, tipo record)

    def proponi(ca, cb, costo, rec):
        key = (min(ca, cb), max(ca, cb))
        if key not in candidati or costo < candidati[key][0]:
            candidati[key] = (costo, rec)

    for eid, e in enumerate(S.archi):
        da, db = D.get(e["a"]), D.get(e["b"])
        if da and db and da[3] != db[3]:
            proponi(da[3], db[3], da[0] + e["len"] * e["w"] + db[0], ("e", eid))
    for li, L in enumerate(links):
        dn = D.get(L["node"])
        ca = att_comp[L["att"]]
        if dn and dn[3] != ca:
            proponi(ca, dn[3], dn[0] + L["cost"], ("l", li))

    def realizza(rec, tipo):
        if rec[0] == "e":
            e = S.archi[rec[1]]
            seleziona_arco(rec[1], tipo)
            _risali_D(e["a"], tipo)
            _risali_D(e["b"], tipo)
        else:
            L = links[rec[1]]
            seleziona_link(rec[1], tipo)
            _risali_D(L["node"], tipo)

    def _risali_D(nodo, tipo):
        while True:
            p = D[nodo][2]
            if p[0] == "l":
                seleziona_link(p[1], tipo)
                return
            seleziona_arco(p[1], tipo)
            nodo = p[2]

    ordinati = sorted(candidati.items(), key=lambda kv: kv[1][0])
    uf_c = {}

    def trova(c):
        uf_c.setdefault(c, c)
        while uf_c[c] != c:
            uf_c[c] = uf_c[uf_c[c]]
            c = uf_c[c]
        return c

    n_albero = n_generosi = 0
    for (ca, cb), (costo, rec) in ordinati:
        ra, rb = trova(ca), trova(cb)
        albero = ra != rb
        if albero:
            uf_c[ra] = rb
        if albero or costo <= P["connessione_max_m"]:
            realizza(rec, "connessione")
            n_albero += albero
            n_generosi += (not albero)
    print(f"[connessione] {len(candidati)} coppie di componenti candidate, "
          f"{n_albero} raccordi d'albero + {n_generosi} aggiuntivi (costo <= "
          f"{P['connessione_max_m']:.0f} m) - {time.time() - t1:.0f} s")

    # =====================================================================
    # Passata 1b - ANTI-GIRO
    # =====================================================================
    t1 = time.time()
    camp = Griglia(P["antigiro_campionamento_m"])
    campioni = []
    for i in range(n_att):
        x, y = att_xy[i]
        if i in links_by_att and in_area(x, y):
            key = (int(x // camp.c), int(y // camp.c))
            if key not in camp.celle:
                camp.celle[key] = True
                campioni.append(i)
    rapp, toll, raggio = P["antigiro_rapporto"], P["antigiro_tolleranza_m"], P["antigiro_raggio_m"]
    lim_h = rapp * raggio + toll
    n_aggiunti = 0
    for n_c, s in enumerate(campioni, 1):
        sorg = [(links[li]["node"], links[li]["cost"], links[li]["phys"], ("l", li), 0)
                for li in links_by_att[s]]
        Ds = dijkstra_strade(sorg, limite_phys=raggio)
        mete = {}                    # att -> (dr, nodo, link)
        for nodo, (c, ph, _p, _l) in Ds.items():
            if ph > raggio:
                continue
            for li in links_by_node.get(nodo, ()):
                t = links[li]["att"]
                if t == s:
                    continue
                dr = ph + links[li]["phys"]
                if t not in mete or dr < mete[t][0]:
                    mete[t] = (dr, nodo, li)
        if not mete:
            continue
        # distanze sulla rete attuale (primaria + raccordi)
        dh = {s: 0.0}
        heap = [(0.0, s)]
        while heap:
            d, u = heapq.heappop(heap)
            if d > dh.get(u, math.inf) or d > lim_h:
                continue
            for v, l in H[u]:
                nd = d + l
                if nd < dh.get(v, math.inf):
                    dh[v] = nd
                    heapq.heappush(heap, (nd, v))
        # una sola meta per arco della primaria (la piu' vicina su strada)
        per_arco = {}
        for t, (dr, nodo, li) in mete.items():
            if dh.get(t, math.inf) > rapp * dr + toll:
                chiave = att_arco[t] if att_arco[t] >= 0 else ("n", t)
                if chiave not in per_arco or dr < per_arco[chiave][0]:
                    per_arco[chiave] = (dr, nodo, li)
        pred_s = {n: v[2] for n, v in Ds.items()} if per_arco else None
        for dr, nodo, li in per_arco.values():
            prima = len(sel_archi) + len(sel_link)
            seleziona_link(li, "antigiro")
            risali(nodo, pred_s, "antigiro")
            n_aggiunti += (len(sel_archi) + len(sel_link)) > prima
        if n_c % 200 == 0:
            print(f"[anti-giro] {n_c}/{len(campioni)} campioni, {n_aggiunti} raccordi aggiunti "
                  f"({time.time() - t1:.0f} s)")
    print(f"[anti-giro] {len(campioni)} campioni, {n_aggiunti} raccordi aggiunti - {time.time() - t1:.0f} s")

    # =====================================================================
    # Passata 2 - ACCESSO (punti dell'indice indirizzi)
    # =====================================================================
    t1 = time.time()
    rete = Griglia(50.0)             # punti della rete a cui l'app puo' agganciarsi
    for i in range(n_att):
        rete.add(*att_xy[i], i)

    def rete_add_geom(geom):
        for lat, lon in geom:
            rete.add(*proj.xy(lat, lon), -1)

    for eid in sel_archi:
        rete_add_geom(S.archi[eid]["geom"])
    for li in sel_link:
        L = links[li]
        rete_add_geom(S.mezzi(L["eid"], L["k"])[L["verso"]][3])
    sorg = [(L["node"], L["cost"], L["phys"], ("l", li), 0) for li, L in enumerate(links)]
    nodi_rete = {S.archi[eid]["a"] for eid in sel_archi} | {S.archi[eid]["b"] for eid in sel_archi}
    sorg += [(n, 0.0, 0.0, ("s",), 0) for n in nodi_rete]
    Da = dijkstra_strade(sorg)
    pred_a = {n: v[2] for n, v in Da.items()}

    punti = set()
    if INDIRIZZI_FILE.exists():
        strade_ind = json.loads(INDIRIZZI_FILE.read_text()).get("strade", {})
        for pts in strade_ind.values():
            for lat, lon in pts:
                if in_area(*proj.xy(lat, lon)):
                    punti.add((round(lat, 6), round(lon, 6)))
    else:
        print(f"[accesso] ATTENZIONE: {INDIRIZZI_FILE} non trovato - passata ACCESSO saltata")
    rapp_a, toll_a = P["accesso_rapporto"], P["accesso_tolleranza_m"]
    n_acc = 0
    for lat, lon in sorted(punti):
        x, y = proj.xy(lat, lon)
        v = S.vertici.piu_vicino(x, y, P["accesso_aggancio_max_m"])
        if v is None:
            continue
        d0, (eid, k) = v
        scelta = None
        for nodo, c, ph, g in S.mezzi(eid, k):
            if nodo in Da:
                tot = c + Da[nodo][0]
                if scelta is None or tot < scelta[0]:
                    scelta = (tot, d0 + ph + Da[nodo][1], nodo, g)
        if scelta is None:
            continue
        _tot, d_strada, nodo, g = scelta
        if d_strada <= toll_a:
            continue
        r_max = (d_strada - toll_a) / rapp_a
        vic = rete.piu_vicino(x, y, r_max)
        if vic is None:
            continue                 # rete piu' lontana di r_max in linea d'aria: nessuna barriera
        d_aria = vic[0]
        if d_strada > rapp_a * d_aria + toll_a:
            # Il punto sta gia' su un raccordo: niente da aggiungere.
            if eid in sel_archi:
                continue
            # Si aggiunge l'INTERO tratto di strada su cui cade il punto (non un
            # pezzo dal punto all'incrocio, che duplicava strade gia' scelte),
            # poi il percorso fino alla rete.
            seleziona_arco(eid, "accesso")
            rete_add_geom(S.archi[eid]["geom"])
            prima_a, prima_l = set(sel_archi), set(sel_link)
            risali(nodo, pred_a, "accesso")
            for e2 in set(sel_archi) - prima_a:
                rete_add_geom(S.archi[e2]["geom"])
            for l2 in set(sel_link) - prima_l:
                L2 = links[l2]
                rete_add_geom(S.mezzi(L2["eid"], L2["k"])[L2["verso"]][3])
            n_acc += 1
    print(f"[accesso] {len(punti)} punti indirizzo, {n_acc} accessi aggiunti - {time.time() - t1:.0f} s")

    # =====================================================================
    # Output
    # =====================================================================
    feats = []
    km = {"connessione": 0.0, "antigiro": 0.0, "accesso": 0.0}

    def feat(geom, nome, tipo):
        feats.append({"type": "Feature",
                      "properties": {"name": nome or "raccordo", "tipo": tipo},
                      "geometry": {"type": "LineString",
                                   "coordinates": [[round(lon, 7), round(lat, 7)] for lat, lon in geom]}})

    for eid, tipo in sel_archi.items():
        e = S.archi[eid]
        feat(e["geom"], e["nome"], tipo)
        km[tipo] += e["len"] / 1000
    for li, tipo in sel_link.items():
        L = links[li]
        _n, _c, ph, g = S.mezzi(L["eid"], L["k"])[L["verso"]]
        geom = [att_ll[L["att"]]] + list(g)
        feat(geom, S.archi[L["eid"]]["nome"], tipo)
        km[tipo] += L["phys"] / 1000
    for geom, nome, tipo in parziali:
        feat(geom, nome, tipo)

    # componenti della primaria nell'area dopo i raccordi
    uf2 = UnionFind(len(H))
    for u in range(len(H)):
        for v, _l in H[u]:
            uf2.union(u, v)
    comp_dopo = {uf2.find(i) for i in range(n_att) if in_area(*att_xy[i])}

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    OUT_GEOJSON.write_text(json.dumps({"type": "FeatureCollection", "features": feats}, ensure_ascii=False))
    OUT_IMPRONTA.write_text(json.dumps({
        "impronta": impronta(P),
        "creato": time.strftime("%Y-%m-%d %H:%M:%S"),
        "nota": "verificata da precompila_v3.py: se non coincide, rilanciare scripts/raccordi.py",
    }, indent=2))
    righe = [
        f"raccordi.py - {time.strftime('%Y-%m-%d %H:%M:%S')}",
        f"primaria: {P['sorgente_primaria']}, supplente: {P['sorgente_supplente']}, "
        f"area: raggio {P['raggio_km']} km da ({P['centro_lat']}, {P['centro_lon']})",
        f"componenti della primaria nell'area: prima {len(comp_area)}, dopo {len(comp_dopo)}",
        f"archi stradali scelti: {len(sel_archi)}, agganci: {len(sel_link)}, indirizzi con accesso aggiunto: {n_acc}",
        "km per tipo: " + ", ".join(f"{k} {v:.1f}" for k, v in km.items()),
        f"feature scritte: {len(feats)} in {OUT_GEOJSON}",
        f"tempo totale: {time.time() - t0:.0f} s",
    ]
    REPORT_FILE.parent.mkdir(parents=True, exist_ok=True)
    REPORT_FILE.write_text("\n".join(righe) + "\n")
    print("\n" + "\n".join(righe))
    print(f"report in {REPORT_FILE}")
    print("Ora: python3 scripts/precompila_v3.py --reprocess-only")


if __name__ == "__main__":
    main()
