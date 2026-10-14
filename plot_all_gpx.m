function plot_all_gpx(pattern)
% PLOT_ALL_GPX  Disegna su mappa stradale TUTTI i GPX di RouteCoherenceTest
% (i 20 della mappa integrata: 10 inHouse + 10 JGraphT), falliti e non.
%
%   plot_all_gpx               -> tutti i *.gpx
%   plot_all_gpx('inHouse-*')  -> solo quelli che corrispondono al pattern
%                                 (il suffisso .gpx si aggiunge da solo)
% Nel titolo i falliti sono marcati FALLITO.
%
% Legge app/build/test-outputs/route-coherence/ relativo alla cartella di
% questo script. Basemap online (serve internet): geobasemap 'streets-light'.
% Un figure per file: tracciato blu con i punti, partenza verde, arrivo rosso.
% Sotto, gli archi del grafo di routing (lo stesso graph.json letto da
% RouteCoherenceTest, oggi percorsi/build/routing_v3/): SIC in verde, OSM
% (osm_extract) in rosso.

if nargin < 1 || isempty(pattern)
    pattern = '*';
end

baseDir = fileparts(mfilename('fullpath'));
gpxDir  = fullfile(baseDir, 'app', 'build', 'test-outputs', 'route-coherence');
files   = dir(fullfile(gpxDir, [pattern '.gpx']));

if isempty(files)
    fprintf('Nessun file %s.gpx in %s\n', pattern, gpxDir);
    return
end

% SEMPRE l'ultima versione della pipeline, la stessa di GRAPH_BUILD_DIR in
% RouteCoherenceTest.kt (con una v4 diventa 'routing_v4'). Vedi le note di progetto.
graphBuildDir = 'routing_v3';
graphFile = fullfile(baseDir, 'percorsi', 'build', graphBuildDir, 'graph.json');
[sicLat, sicLon, osmLat, osmLon] = load_graph_lines(graphFile);

for k = 1:numel(files)
    fname = fullfile(files(k).folder, files(k).name);
    [lat, lon] = read_gpx_trkpt(fname);
    if isempty(lat)
        fprintf('%s: nessun trkpt, saltato\n', files(k).name);
        continue
    end

    figure('Name', files(k).name, 'NumberTitle', 'off');
    gx = geoaxes;
    geobasemap(gx, 'streets-light');
    hold(gx, 'on');
    hOsm = geoplot(gx, osmLat, osmLon, '-', 'Color', [0.9 0 0], 'LineWidth', 1);
    hSic = geoplot(gx, sicLat, sicLon, '-', 'Color', [0 0.7 0], 'LineWidth', 1.5);
    hRoute = geoplot(gx, lat, lon, '-', 'Color', [0 0.3 1], 'LineWidth', 2.5);
    geoplot(gx, lat, lon, '.', 'Color', [0 0 0.5], 'MarkerSize', 10);
    geoplot(gx, lat(1),   lon(1),   'o', 'MarkerSize', 10, ...
        'MarkerFaceColor', [0 0.7 0], 'MarkerEdgeColor', 'k');
    geoplot(gx, lat(end), lon(end), 'o', 'MarkerSize', 10, ...
        'MarkerFaceColor', [0.9 0 0], 'MarkerEdgeColor', 'k');
    hold(gx, 'off');
    mLat = 0.1*max(max(lat) - min(lat), 0.005);
    mLon = 0.1*max(max(lon) - min(lon), 0.005);
    geolimits(gx, [min(lat)-mLat max(lat)+mLat], [min(lon)-mLon max(lon)+mLon]);
    legend([hRoute hSic hOsm], {'percorso', 'SIC', 'OSM'}, 'Location', 'northeast');
    if contains(files(k).name, '_failed')
        stato = '  -  FALLITO';
    else
        stato = '  -  ok';
    end
    title(gx, [strrep(files(k).name, '_', '\_') stato]);
    fprintf('%s: %d punti\n', files(k).name, numel(lat));
    pause
end
end

function [lat, lon] = read_gpx_trkpt(fname)
% Estrae lat/lon dei <trkpt> nell'ordine del file (nessun toolbox richiesto).
txt = fileread(fname);
tok = regexp(txt, '<trkpt\s+lat="([-\d.eE+]+)"\s+lon="([-\d.eE+]+)"', 'tokens');
if isempty(tok)
    lat = []; lon = [];
    return
end
v   = str2double(vertcat(tok{:}));
lat = v(:, 1);
lon = v(:, 2);
end

function [sicLat, sicLon, osmLat, osmLon] = load_graph_lines(graphFile)
% Archi di SIC e OSM da graph.json, concatenati con NaN come separatore
% (un solo oggetto grafico per sorgente). Letto una volta e tenuto in cache.
persistent cacheFile cacheData
if isequal(cacheFile, graphFile) && ~isempty(cacheData)
    [sicLat, sicLon, osmLat, osmLon] = cacheData{:};
    return
end
sicLat = []; sicLon = []; osmLat = []; osmLon = [];
if ~isfile(graphFile)
    fprintf('graph.json non trovato (%s): disegno solo i percorsi\n', graphFile);
    return
end
g = jsondecode(fileread(graphFile));
edges = g.edges;
if ~iscell(edges)
    edges = num2cell(edges);
end
sicC = {}; osmC = {};
for i = 1:numel(edges)
    e = edges{i};
    switch e.src
        case 'salvaiciclisti_sicrm'
            sicC{end+1} = [e.geom; NaN NaN]; %#ok<AGROW>
        case 'osm_extract'
            osmC{end+1} = [e.geom; NaN NaN]; %#ok<AGROW>
    end
end
sic = vertcat(sicC{:});
osm = vertcat(osmC{:});
if ~isempty(sic), sicLat = sic(:,1); sicLon = sic(:,2); end
if ~isempty(osm), osmLat = osm(:,1); osmLon = osm(:,2); end
fprintf('graph.json: %d archi SIC, %d archi OSM\n', numel(sicC), numel(osmC));
cacheFile = graphFile;
cacheData = {sicLat, sicLon, osmLat, osmLon};
end
