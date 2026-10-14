package it.salvaiciclisti.app.map

/**
 * Stile della mappa di base (Impostazioni, 2026-10-02): MAPNIK e' lo stile
 * OSM "carto" classico usato finora, che pero' disegna dentro le tile
 * stesse (non un overlay nostro, non filtrabile) icone di ospedali/farmacie/
 * parcheggi/stazioni di polizia che Alessandro trova inutili sulla mappa
 * ciclabile.
 *
 * CYCLOSM e' l'alternativa senza chiave API (vedi tileSourceFor() in
 * MapScreen.kt): uno stile dedicato al ciclismo ospitato dal capitolo
 * francese di OpenStreetMap (tile-cyclosm.openstreetmap.fr), nessun account
 * da creare. La wishlist iniziale era CartoDB Positron, scartata perche'
 * CARTO richiede ora una API key gratuita (fino a 5M richieste/mese per uso
 * non commerciale) che Alessandro dovrebbe creare lui stesso. Il primo
 * sostituto provato era Wikimedia (maps.wikimedia.org/osm-intl), scartato a
 * sua volta: i suoi tile pubblici rispondono 403 fuori dai soli siti
 * Wikimedia/affiliati, quindi inutilizzabili da un'app come la nostra senza
 * un accordo con loro. CyclOSM, oltre a non avere questo problema, e' anche
 * piu' pertinente per un'app di ciclabili.
 */
enum class MapStyle { MAPNIK, CYCLOSM }
