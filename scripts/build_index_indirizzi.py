#!/usr/bin/env python3
"""
build_index_indirizzi.py - indice locale di ricerca indirizzi (per le caselle
"partenza"/"arrivo" della mappa), limitato al Comune di Roma.

E' un indice a livello di VIA, non di civico: interroga Overpass per tutte le
strade con un nome (way["highway"]["name"]) nell'area amministrativa di Roma
(stessa area gia' usata per l'estrazione ciclabile OSM in precompila.py), e
per ognuna salva un pugno di punti rappresentativi lungo il suo tracciato.

Questo e' volutamente piu' leggero di un indice civico completo (che
richiederebbe scaricare/indicizzare milioni di building/indirizzo e non ha
molto senso qui): dato che il routing aggancia comunque il punto di
partenza/arrivo al punto piu' vicino su una sorgente ciclabile abilitata (vedi
scripts/build_routing_graph.py), sapere "dov'e' via X" a livello di via e'
sufficiente per far partire la ricerca - la precisione del civico non
aggiunge nulla al risultato finale.

Nessuna dipendenza esterna: solo libreria standard. Riusa le utility di
fetch/retry di precompila.py (stesso modulo, importato) cosi' errori di rete
finiscono nello stesso log invece di duplicare la logica.
"""
import json
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# Stessa cartella di build_routing_graph.ASSETS_DIR (vedi la nota li') -
# duplicata qui invece di importarla perche' questo script gira anche da
# solo (__main__), prima ancora che precompila.py sia stato importato.
ASSETS_DIR = ROOT / "percorsi" / "build" / "routing"
INDEX_PATH = ASSETS_DIR / "indirizzi.json"

sys.path.insert(0, str(Path(__file__).resolve().parent))
import precompila  # noqa: E402  (riusa with_retries/fetch_json/logging gia' configurato dal chiamante)

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
OVERPASS_QUERY = """
[out:json][timeout:180];
area["boundary"="administrative"]["admin_level"="8"]["name"="Roma"]->.roma;
way["highway"]["name"](area.roma);
out geom;
"""

# Punti rappresentativi per via: troppo pochi (es. 1) rende male la ricerca
# "vai al punto piu' vicino a dove sono" su vie lunghe; troppi gonfiano
# inutilmente l'asset. Un punto ogni ~ MAX_POINTS_PER_STREET segmenti e'
# un compromesso ragionevole.
MAX_POINTS_PER_STREET = 12


def build():
    body = urllib.parse.urlencode({"data": OVERPASS_QUERY}).encode()
    try:
        raw = precompila.with_retries(
            precompila.fetch_json, OVERPASS_URL, data=body, timeout=220,
            desc="Overpass query indirizzi",
        )
    except Exception as e:
        precompila.error(f"  [build_index_indirizzi] fetch fallito: {e}")
        return {"status": "error", "error": str(e)}

    streets = {}
    for el in raw.get("elements", []):
        if el.get("type") != "way" or "geometry" not in el:
            continue
        name = (el.get("tags", {}) or {}).get("name")
        if not name:
            continue
        points = [[pt["lat"], pt["lon"]] for pt in el["geometry"]]
        streets.setdefault(name, []).extend(points)

    # Se una via ha molti punti (piu' segmenti uniti sotto lo stesso nome),
    # sottocampiona uniformemente invece di tenerli tutti.
    for name, points in streets.items():
        if len(points) > MAX_POINTS_PER_STREET:
            step = len(points) / MAX_POINTS_PER_STREET
            streets[name] = [points[int(i * step)] for i in range(MAX_POINTS_PER_STREET)]

    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    INDEX_PATH.write_text(json.dumps({"strade": streets}, ensure_ascii=False))

    precompila.info(f"  build_index_indirizzi: {len(streets)} vie indicizzate")
    precompila.info(f"  scritto {INDEX_PATH}")
    return {"status": "complete", "strade": len(streets)}


if __name__ == "__main__":
    precompila.setup_logging()
    sys.exit(0 if build().get("status") == "complete" else 1)
