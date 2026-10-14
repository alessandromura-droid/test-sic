# Geoportale Città Metropolitana di Roma Capitale — Mappatura progetti Ciclovie (WFS REALE)

**Pagina descrittiva:** https://geoportale.cittametropolitanaroma.it/analisi-e-studi/mappatura-progetti-ciclovie
**Mappa interattiva (G3W-Suite/QGIS Server):** https://g3w-suite.cittametropolitanaroma.it/it/map/ciclovie/
**Endpoint WFS (verificato funzionante):** https://g3w-suite.cittametropolitanaroma.it/ows/ciclovie/?SERVICE=WFS&REQUEST=GetCapabilities
**Contatto GIS:** gis@cittametropolitanaroma.it

**Tipo:** Servizio OGC WFS 1.1.0 reale — operazioni GetCapabilities, DescribeFeatureType, GetFeature, Transaction. Output disponibile in GML 2.1.2/3.1.1 e **GeoJSON**. Supporta filtri spaziali (Intersects, BBOX, Contains...) e scalari.
**Importabile direttamente in QGIS come layer WFS.**

**Layer individuati (da GetCapabilities WMS):**
- Gruppo "PROGETTI CMRC": tratti puntuali per comune (es. Cerveteri, Ladispoli, Fiumicino, Fonte Nuova, Marino...)
- Gruppo "PROGETTI CICLOVIE COMUNI": Bando Regione Lazio 2018 / Progetti Finanziati / Progetti Definitivi, suddivisi per comune (incluso Roma, con codici tipo B496_01, C858_01, F127_01...)

**Note/trappole:**
- È la fonte più solida trovata finora per dati vettoriali scaricabili/interrogabili davvero.
- Non è "il" Biciplan romano in un unico layer: è la mappatura progetti-ciclovie dell'ente metropolitano, organizzata per comune e stato di finanziamento — copre soprattutto infrastrutture pianificate/finanziate, non l'intera rete esistente né percorsi suggeriti.
