function plot_raw_source(name)
% PLOT_RAW_SOURCE  Disegna su Roma una o piu' sorgenti GREZZE (percorsi/raw/),
% prima di qualunque elaborazione della pipeline.
%
%   plot_raw_source('sic')        chiavi: osm, sic, g3w, cpol, rsm, racc
%   plot_raw_source('sic+racc')   piu' sorgenti sulla stessa mappa
%   plot_raw_source({'sic','racc'})  idem
%   plot_raw_source               elenca le chiavi, non disegna nulla
%
% Tutte le LineString/MultiLineString di tutti i .geojson della sorgente
% (sottocartelle comprese), senza filtri: anche i layer G3W non ciclabili
% (ferrovie, confini...). Punti e poligoni non sono disegnati.
% Coordinate: G3W arriva in UTM33N, RSM in Web Mercator (come in
% build_routing_graph_v3.py), le altre in lon/lat.
% cpol (Ciclopolitana) = i due file SIC Ciclopolitana_di_Roma_2021 e
% Ciclopolitane_Ciclonvallazioni; 'sic' e' SIC senza quei due file.
% racc = raccordi generati da scripts/raccordi.py (percorsi/raw/raccordi/);
% NB: sono calcolati su SIC gia' corretta da ABCD, mentre qui SIC e' grezza.
%
% Usa SOLO i file accesi in precompila_parametri.txt (process_<mappa> e
% layer_<...>, stessa logica e stessi default della pipeline v3). Errore se
% una mappa richiesta e' spenta o non ha nessun layer acceso.
%
% Basemap online (serve internet): geobasemap 'streets-light'.

% {chiave, cartella, proiezione, etichetta, colore, mappa (per gli interruttori)}
src = {
    'osm',  'osm_extract',                'lonlat', 'OSM',           [0.90 0.00 0.00], 'osm_extract'
    'sic',  'salvaiciclisti_sicrm',       'lonlat', 'SIC',           [0.00 0.70 0.00], 'salvaiciclisti_sicrm'
    'g3w',  'g3w_ciclovie_metropolitane', 'utm33n', 'G3W',           [0.00 0.20 0.90], 'g3w_ciclovie_metropolitane'
    'cpol', 'salvaiciclisti_sicrm',       'lonlat', 'Ciclopolitana', [0.85 0.00 0.85], 'salvaiciclisti_ciclopolitana'
    'rsm',  'rsm_piste_ciclabili',        'merc',   'RSM',           [0.00 0.75 1.00], 'rsm_piste_ciclabili'
    'racc', 'raccordi',                   'lonlat', 'Raccordi',      [0.94 0.42 0.00], 'raccordi'
};

if nargin < 1 || isempty(name)
    fprintf('Uso: plot_raw_source(chiave) o plot_raw_source(''chiave1+chiave2''), chiavi = %s\n', ...
        strjoin(src(:,1)', ', '));
    return
end
if ischar(name) || isstring(name)
    keys = strsplit(char(name), '+');
else
    keys = name;
end
idx = zeros(1, numel(keys));
for q = 1:numel(keys)
    s = find(strcmpi(src(:,1), strtrim(keys{q})), 1);
    if isempty(s)
        fprintf('Chiave ''%s'' sconosciuta. Chiavi: %s\n', keys{q}, strjoin(src(:,1)', ', '));
        return
    end
    idx(q) = s;
end

baseDir = fileparts(mfilename('fullpath'));
rawDir  = fullfile(baseDir, 'percorsi', 'raw');
ciclopolitanaFiles = {'Ciclopolitana_di_Roma_2021.geojson', ...
                      'Ciclopolitane_Ciclonvallazioni.geojson'};
skipFiles = {'igm_010109_tr_str_tratto_stradale.geojson', 'Ciclabili_Esistenti.geojson'};  % SKIP_FILENAMES della v3
I = leggi_interruttori(baseDir);

% controlli PRIMA di aprire la figura: mappa spenta o senza layer accesi = errore
fileSel = cell(1, numel(idx));
for q = 1:numel(idx)
    [key, folder, ~, label, ~, mappa] = src{idx(q), :};
    if ~mappa_attiva(I, mappa)
        error('plot_raw_source:mappaSpenta', ...
            'Mappa %s (%s) disabilitata in precompila_parametri.txt (process_%s = false)', label, key, mappa);
    end
    files = dir(fullfile(rawDir, folder, '**', '*.geojson'));
    names = {files.name};
    files = files(~ismember(names, skipFiles));
    names = {files.name};
    switch key
        case 'cpol', files = files(ismember(names, ciclopolitanaFiles));
        case 'sic',  files = files(~ismember(names, ciclopolitanaFiles));
    end
    files = files(arrayfun(@(f) layer_attivo(I, mappa, f.name), files));
    if isempty(files)
        error('plot_raw_source:nessunLayer', ...
            'Mappa %s (%s): nessun layer abilitato in precompila_parametri.txt', label, key);
    end
    fileSel{q} = files;
end

figure('Name', ['Sorgenti grezze ' strjoin(src(idx,4)', ' + ')], 'NumberTitle', 'off');
gx = geoaxes;
geobasemap(gx, 'streets-light');
hold(gx, 'on');
h = gobjects(0); labels = {};

for q = 1:numel(idx)
    [~, ~, proj, label, color] = src{idx(q), 1:5};
    files = fileSel{q};

    parts = {};
    for k = 1:numel(files)
        parts = [parts, read_lines(fullfile(files(k).folder, files(k).name))]; %#ok<AGROW>
    end
    if isempty(parts)
        fprintf('%s: nessuna linea\n', label);
        continue
    end
    xy = vertcat(parts{:});            % colonne: x/lon, y/lat (NaN separatori)
    switch proj
        case 'utm33n', [lat, lon] = utm33n_to_wgs84(xy(:,1), xy(:,2));
        case 'merc',   [lat, lon] = merc_to_wgs84(xy(:,1), xy(:,2));
        otherwise,     lat = xy(:,2); lon = xy(:,1);
    end
    fprintf('%s: %d file, %d polilinee\n', label, numel(files), numel(parts));
    h(end+1) = geoplot(gx, lat, lon, '-', 'Color', color, 'LineWidth', 1.2); %#ok<AGROW>
    labels{end+1} = sprintf('%s (%d)', label, numel(parts)); %#ok<AGROW>
end

hold(gx, 'off');
geolimits(gx, [41.75 42.05], [12.30 12.70]);
if ~isempty(h)
    legend(h, labels, 'Location', 'northeast', 'Interpreter', 'none');
end
title(gx, ['Sorgenti grezze: ' strjoin(src(idx,4)', ' + ')], 'Interpreter', 'none');
end

function parts = read_lines(fname)
% Tutte le polilinee del file, ognuna come [x y] seguita da una riga NaN.
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
            lines = {c};
        case 'MultiLineString'
            if iscell(c)
                lines = c(:)';
            elseif ndims(c) == 3              % parti di uguale lunghezza
                lines = arrayfun(@(p) squeeze(c(p,:,:)), 1:size(c,1), 'UniformOutput', false);
            else
                lines = {c};
            end
        otherwise
            continue
    end
    for j = 1:numel(lines)
        L = lines{j};
        if iscell(L), L = vertcat(L{:}); end
        if ~isnumeric(L) || size(L, 1) < 2 || size(L, 2) < 2, continue, end
        parts{end+1} = [L(:, 1:2); NaN NaN]; %#ok<AGROW>
    end
end
end

function [lat, lon] = merc_to_wgs84(x, y)
% Web Mercator (EPSG:3857) -> WGS84, sfera.
r = 6378137.0;
lon = rad2deg(x / r);
lat = rad2deg(2 * atan(exp(y / r)) - pi/2);
end

function [lat, lon] = utm33n_to_wgs84(E, N)
% UTM 33N (EPSG:32633) -> WGS84, Redfearn inversa (come in build_routing_graph_v3.py).
a = 6378137.0; f = 1/298.257223563;
e2 = 2*f - f^2; e2p = e2/(1 - e2); k0 = 0.9996;
x = E - 500000.0; y = N;
e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2));
M = y / k0;
mu = M / (a * (1 - e2/4 - 3*e2^2/64 - 5*e2^3/256));
phi1 = mu + (3*e1/2 - 27*e1^3/32)*sin(2*mu) ...
          + (21*e1^2/16 - 55*e1^4/32)*sin(4*mu) ...
          + (151*e1^3/96)*sin(6*mu);
N1 = a ./ sqrt(1 - e2*sin(phi1).^2);
T1 = tan(phi1).^2;
C1 = e2p*cos(phi1).^2;
R1 = a*(1 - e2) ./ (1 - e2*sin(phi1).^2).^1.5;
D  = x ./ (N1*k0);
latr = phi1 - (N1.*tan(phi1)./R1) .* ( D.^2/2 ...
       - (5 + 3*T1 + 10*C1 - 4*C1.^2 - 9*e2p).*D.^4/24 ...
       + (61 + 90*T1 + 298*C1 + 45*T1.^2 - 252*e2p - 3*C1.^2).*D.^6/720 );
lonr = (D - (1 + 2*T1 + C1).*D.^3/6 ...
       + (5 - 2*C1 + 28*T1 - 3*C1.^2 + 8*e2p + 24*T1.^2).*D.^5/120) ./ cos(phi1);
lat = rad2deg(latr);
lon = 15 + rad2deg(lonr);              % meridiano centrale zona 33 = 15 E
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
