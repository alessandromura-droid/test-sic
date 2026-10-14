function plot_residui()
% PLOT_RESIDUI  Residui del loop ABCD di SIC (residual_errors.txt della v3).
%
% Per ogni cluster una figura con due pannelli, stesso zoom (centro = cluster,
% raggio = estensione, minimo minRaggio):
%   sinistra: SIC di partenza = file grezzi di percorsi/raw/salvaiciclisti_sicrm/
%             (stessi file che entrano nella pipeline: niente Ciclopolitana ne'
%             layer obsoleti), un colore per layer, estremi = nodi in rosso
%   destra:   SIC a fine processo (archi in blu, nodi in magenta) - si toglie
%             con destra = false
% Titolo con lat, lon, raggio; stampa JPEG di alta qualita' in
% percorsi/build/routing_v3/debug_images/residui_jpg/.
% In ogni pannello si disegnano solo archi e nodi dentro il riquadro del
% cluster (piu' un margine), e ogni figura viene chiusa dopo la stampa:
% cosi' anche centinaia di cluster non bloccano il PC.
% I cluster sono ordinati per numero di bachi decrescente; maxCluster limita
% quanti stamparne.
%
% Dati: partenza = percorsi/raw/salvaiciclisti_sicrm/ (file grezzi, per layer);
% fine = debug_images/salvaiciclisti_sicrm_final_..._latlon.json (build v3).

% ---------------- parametri ----------------
ipause      = false;  % true: vista d'insieme iniziale e pause dopo ogni cluster
maxCluster  = Inf;    % quanti cluster stampare (i primi per numero di bachi)
minRaggio   = 50;     % m, raggio minimo dello zoom
margine     = 1.5;    % archi/nodi disegnati entro margine * raggio dal centro
risoluzione = 200;    % dpi della stampa
qualitaJpeg = 95;     % qualita' JPEG (1-100)
attesaTile  = 10;      % s di attesa per scaricare le tessere prima di stampare
destra      = false;   % false: solo il pannello sinistro (SIC di partenza)
% -------------------------------------------

baseDir = fileparts(mfilename('fullpath'));
% SEMPRE l'ultima versione della pipeline (vedi le note di progetto)
dbgDir  = fullfile(baseDir, 'percorsi', 'build', 'routing_v3', 'debug_images');
outDir  = fullfile(dbgDir, 'residui_jpg');
if ~isfolder(outDir), mkdir(outDir); end
src = 'salvaiciclisti_sicrm';

% --- cluster, ordinati per numero di bachi decrescente ---
fid = fopen(fullfile(dbgDir, 'residual_errors.txt'), 'r');
C = textscan(fid, '%f %f %f %f %s %s', 'CommentStyle', '#', 'Delimiter', '\t');
fclose(fid);
sel = strcmp(C{5}, src);
cLat = C{1}(sel); cLon = C{2}(sel); cBachi = C{3}(sel); cEst = C{4}(sel);
[~, ord] = sort(cBachi, 'descend');
cLat = cLat(ord); cLon = cLon(ord); cBachi = cBachi(ord); cEst = cEst(ord);
nC = min(numel(cLat), maxCluster);
fprintf('%d cluster per %s, ne stampo %d\n', numel(cLat), src, nC);

% --- SIC di partenza: file grezzi, un layer per file (nodi = estremi) ---
% Stessi file di load_lines() in build_routing_graph_v3.py: tutti i .geojson
% (anche layer_correlati/) tranne Ciclopolitana (sorgente a parte) e i file
% di SKIP_FILENAMES.
rawDir = fullfile(baseDir, 'percorsi', 'raw', src);
escludi = {'Ciclopolitana_di_Roma_2021.geojson', 'Ciclopolitane_Ciclonvallazioni.geojson', ...
           'Ciclabili_Esistenti.geojson', 'igm_010109_tr_str_tratto_stradale.geojson'};
fr = dir(fullfile(rawDir, '**', '*.geojson'));
fr = fr(~ismember({fr.name}, escludi));
% interruttori di precompila_parametri.txt (process_ e layer_, come la pipeline)
I = leggi_interruttori(baseDir);
if ~mappa_attiva(I, src)
    error('plot_residui:mappaSpenta', ...
        'Mappa %s disabilitata in precompila_parametri.txt (process_%s = false)', src, src);
end
fr = fr(arrayfun(@(f) layer_attivo(I, src, f.name), fr));
if isempty(fr)
    error('plot_residui:nessunLayer', ...
        'Mappa %s: nessun layer abilitato in precompila_parametri.txt', src);
end
oG = {}; oLay = []; layerNomi = {};
for i = 1:numel(fr)
    L = leggi_linee(fullfile(fr(i).folder, fr(i).name));
    if isempty(L), continue, end
    layerNomi{end+1} = erase(fr(i).name, '.geojson'); %#ok<AGROW>
    oG = [oG; L(:)]; %#ok<AGROW>
    oLay = [oLay; repmat(numel(layerNomi), numel(L), 1)]; %#ok<AGROW>
end
oBox = cell2mat(cellfun(@(g) [min(g(:,1)) max(g(:,1)) min(g(:,2)) max(g(:,2))], oG, 'UniformOutput', false));
oN = cell2mat(cellfun(@(g) [g(1, :); g(end, :)], oG(:), 'UniformOutput', false));
coloriLayer = lines(numel(layerNomi));
fprintf('SIC di partenza: %d polilinee in %d layer: %s\n', numel(oG), numel(layerNomi), strjoin(layerNomi, ', '));

% --- SIC a fine processo (archi e nodi del grafo finale) ---
ff = dir(fullfile(dbgDir, [src '_final_*_latlon.json']));
gf = jsondecode(fileread(fullfile(ff(1).folder, ff(1).name)));
[fG, fBox] = archi(gf.edges);
E = gf.edges; if ~iscell(E), E = num2cell(E); end
ids = unique(cell2mat(cellfun(@(e) [e.a; e.b], E(:), 'UniformOutput', false)));
fN = gf.nodes(ids + 1, 1:2);

% --- vista d'insieme (solo con ipause) ---
if ipause
    fig = nuova_figura(true, destra);
    [gl, gr] = pannelli(fig, oG, oLay, layerNomi, coloriLayer, oN, fG, fN, destra);
    for gx = [gl gr]
        geolimits(gx, [41.75 42.05], [12.30 12.70]);
    end
    title(gl, 'SIC di partenza');
    if destra, title(gr, 'SIC a fine processo'); end
    pause
    close(fig);
end

for k = 1:nC
    r = max(cEst(k), minRaggio);
    dlat = r / 110540;
    dlon = r / (111320 * cosd(cLat(k)));
    % riquadro di selezione (margine * raggio)
    q = [cLat(k) - margine*dlat, cLat(k) + margine*dlat, ...
         cLon(k) - margine*dlon, cLon(k) + margine*dlon];
    oSel = dentro_box(oBox, q);  fSel = dentro_box(fBox, q);
    oNs = oN(punti_in(oN, q), :); fNs = fN(punti_in(fN, q), :);

    fig = nuova_figura(ipause, destra);
    [gl, gr] = pannelli(fig, oG(oSel), oLay(oSel), layerNomi, coloriLayer, oNs, fG(fSel), fNs, destra);
    for gx = [gl gr]
        geolimits(gx, cLat(k) + [-dlat dlat], cLon(k) + [-dlon dlon]);
    end
    t = sprintf('lat %.6f, lon %.6f, raggio %.0f m', cLat(k), cLon(k), r);
    if destra
        title(gl, {'SIC di partenza', t});
        title(gr, {'SIC a fine processo', sprintf('%s (%d bachi)', t, cBachi(k))});
    else
        title(gl, {'SIC di partenza', sprintf('%s (%d bachi)', t, cBachi(k))});
    end

    % le tessere arrivano in modo asincrono: ridisegno, attesa, ridisegno
    drawnow
    pause(attesaTile)
    drawnow
    base = fullfile(outDir, sprintf('cluster_%03d_%.5f_%.5f', k, cLat(k), cLon(k)));
    tmp  = [base '_tmp.png'];
    exportgraphics(fig, tmp, 'Resolution', risoluzione);
    imwrite(imread(tmp), [base '.jpg'], 'jpg', 'Quality', qualitaJpeg);
    delete(tmp);
    fprintf('cluster %d/%d: %s -> %s.jpg\n', k, nC, t, base);

    if ipause
        pause
    end
    close(fig);
end
end

function fig = nuova_figura(visibile, destra)
if visibile, v = 'on'; else, v = 'off'; end
if destra, w = 1600; else, w = 900; end
fig = figure('Name', 'Residui ABCD SIC', 'NumberTitle', 'off', 'Color', 'w', ...
             'Position', [50 50 w 800], 'Visible', v);
end

function [gl, gr] = pannelli(fig, oG, oLay, layerNomi, coloriLayer, oN, fG, fN, destra)
% gr = gl se destra = false (cosi' i cicli "for gx = [gl gr]" restano validi).
if destra
    gl = geoaxes(fig, 'Position', [0.03 0.06 0.45 0.84]);
else
    gl = geoaxes(fig, 'Position', [0.07 0.06 0.66 0.84]);
end
geobasemap(gl, 'streets-light'); hold(gl, 'on');
h = gobjects(0); nomi = {};
for j = unique(oLay(:))'
    [la, lo] = concatena(oG(oLay == j));
    h(end+1) = geoplot(gl, la, lo, '-', 'Color', coloriLayer(j, :), 'LineWidth', 1.4); %#ok<AGROW>
    nomi{end+1} = layerNomi{j}; %#ok<AGROW>
end
if ~isempty(oN), geoplot(gl, oN(:,1), oN(:,2), '.', 'Color', [0.9 0 0], 'MarkerSize', 14); end
hold(gl, 'off');
if ~isempty(h)
    if destra
        legend(gl, h, nomi, 'Location', 'southwest', 'Interpreter', 'none', 'FontSize', 7);
    else
        legend(gl, h, nomi, 'Location', 'eastoutside', 'Interpreter', 'none', 'FontSize', 8);
    end
end
% Niente pan/zoom da tastiera sulle geoaxes: il tasto che sblocca pause
% altrimenti arriva anche a loro e collide con exportgraphics.
disableDefaultInteractivity(gl);

if ~destra
    gr = gl;
    return
end
gr = geoaxes(fig, 'Position', [0.52 0.06 0.45 0.84]);
geobasemap(gr, 'streets-light'); hold(gr, 'on');
[la, lo] = concatena(fG);
geoplot(gr, la, lo, '-', 'Color', [0 0.2 0.9], 'LineWidth', 1.2);
if ~isempty(fN), geoplot(gr, fN(:,1), fN(:,2), '.', 'Color', [0.85 0 0.85], 'MarkerSize', 14); end
hold(gr, 'off');
disableDefaultInteractivity(gr);
end

function [G, box] = archi(edges)
% Geometria di ogni arco ([lat lon]) e suo riquadro [latmin latmax lonmin lonmax].
if ~iscell(edges), edges = num2cell(edges); end
G = cell(numel(edges), 1);
box = zeros(numel(edges), 4);
for i = 1:numel(edges)
    g = edges{i}.geom;
    if iscell(g), g = vertcat(g{:}); end
    g = g(:, 1:2);
    G{i} = g;
    box(i, :) = [min(g(:,1)) max(g(:,1)) min(g(:,2)) max(g(:,2))];
end
end

function s = dentro_box(box, q)
% Archi il cui riquadro interseca il riquadro q.
s = box(:,2) >= q(1) & box(:,1) <= q(2) & box(:,4) >= q(3) & box(:,3) <= q(4);
end

function s = punti_in(P, q)
s = P(:,1) >= q(1) & P(:,1) <= q(2) & P(:,2) >= q(3) & P(:,2) <= q(4);
end

function [lat, lon] = concatena(G)
if isempty(G), lat = NaN; lon = NaN; return, end
xy = cell2mat(cellfun(@(g) [g; NaN NaN], G(:), 'UniformOutput', false));
lat = xy(:,1); lon = xy(:,2);
end

function parts = leggi_linee(fname)
% Tutte le polilinee (LineString/MultiLineString) del file, come [lat lon].
parts = {};
try
    g = jsondecode(fileread(fname));
catch
    fprintf('impossibile leggere %s\n', fname);
    return
end
if ~isfield(g, 'features') || isempty(g.features), return, end
feats = g.features;
if ~iscell(feats), feats = num2cell(feats); end
for i = 1:numel(feats)
    f = feats{i};
    if ~isfield(f, 'geometry') || ~isstruct(f.geometry) || ~isfield(f.geometry, 'type')
        continue
    end
    c = f.geometry.coordinates;
    switch f.geometry.type
        case 'LineString'
            L = {c};
        case 'MultiLineString'
            if iscell(c)
                L = c(:)';
            elseif ndims(c) == 3              % parti di uguale lunghezza
                L = arrayfun(@(p) squeeze(c(p,:,:)), 1:size(c,1), 'UniformOutput', false);
            else
                L = {c};
            end
        otherwise
            continue
    end
    for j = 1:numel(L)
        X = L{j};
        if iscell(X), X = vertcat(X{:}); end
        if ~isnumeric(X) || size(X, 1) < 2 || size(X, 2) < 2, continue, end
        parts{end+1} = [X(:, 2) X(:, 1)]; %#ok<AGROW>   % GeoJSON e' lon,lat
    end
end
end

% ---- interruttori di precompila_parametri.txt (stessa logica della pipeline v3) ----
function I = leggi_interruttori(baseDir)
% process_<mappa> e layer_<...> = true/false (righe commentate ignorate).
I = containers.Map('KeyType', 'char', 'ValueType', 'logical');
f = fullfile(baseDir, 'precompila_parametri.txt');
if ~isfile(f), return, end
righe = splitlines(fileread(f));
for i = 1:numel(righe)
    r = strtrim(regexprep(righe{i}, '#.*$', ''));
    tok = regexp(r, '^((?:process|layer)_\w+)\s*=\s*(\w+)', 'tokens', 'once');
    if ~isempty(tok)
        I(lower(tok{1})) = any(strcmpi(tok{2}, {'true', '1', 'si', 'yes'}));
    end
end
end

function ok = mappa_attiva(I, mappa)
k = ['process_' mappa];
if isKey(I, k), ok = I(k); else, ok = true; end
end

function ok = layer_attivo(I, mappa, fname)
% mappa = mappa EFFETTIVA (i file della Ciclopolitana stanno nella cartella di
% SIC ma appartengono a 'salvaiciclisti_ciclopolitana'). Spento se e' spenta la
% mappa o il layer; senza riga nel file: default della pipeline (SIC: solo
% SIC_LAYER_DEFAULT_ON; altre mappe: acceso).
if ~mappa_attiva(I, mappa), ok = false; return, end
[~, stem] = fileparts(fname);
key = ['layer_' prefisso_layer(mappa) '_' ...
       regexprep(regexprep(lower(stem), '[^0-9a-z]+', '_'), '^_+|_+$', '')];
if isKey(I, key)
    ok = I(key);
elseif strcmp(mappa, 'salvaiciclisti_sicrm')
    ok = ismember(stem, {'CiclabiliEsist_Municipi', 'Passaggi_Segreti', 'Punti_Riparazione_Bici', ...
        'Strade_Scolastiche', 'Autorimessebikefriendly', 'Infrastrutture_Ostacoli_Interruzioni', ...
        'Rastrelliere', 'NegoziBikeFriendly', 'BikeBoxes_Comune_di_Roma'});
else
    ok = true;
end
end

function p = prefisso_layer(mappa)
switch mappa
    case 'salvaiciclisti_sicrm',         p = 'sic';
    case 'salvaiciclisti_ciclopolitana', p = 'ciclopolitana';
    case 'g3w_ciclovie_metropolitane',   p = 'g3w';
    case 'rsm_piste_ciclabili',          p = 'rsm';
    case 'osm_extract',                  p = 'osm';
    case 'raccordi',                     p = 'raccordi';
    case 'debug',                        p = 'debug';
    otherwise,                           p = regexprep(lower(mappa), '[^0-9a-z]+', '_');
end
end
