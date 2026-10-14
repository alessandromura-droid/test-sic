function extract_debug_subset(debug_dir, source_key, stage, indices, out_path)
%EXTRACT_DEBUG_SUBSET Estrae un sottoinsieme minimo di archi (per indice -
% lo stesso Tag mostrato da plot_debug_graph.m su ogni linea) dal json
% _latlon di una fase del grafo, e li scrive nel formato che fetch_debug()
% (precompila_v3.py, Alessandro 2026-10-03: "la sorgente 'debug' deve
% entrare come tutte le altre [...] il precompila non deve 'sapere' che e'
% un test") si aspetta come input per la sorgente locale "debug" - un
% sottoinsieme minimo di dati REALI con cui riprodurre un bug isolandolo
% dal rumore delle migliaia di altri archi di SIC.
%
%   extract_debug_subset(debug_dir, source_key, stage, indices, out_path)
%
%   debug_dir  - cartella debug_images, stessa di plot_debug_graph, es.
%                percorsi/build/routing_v3/debug_images
%   source_key - chiave sorgente, es. 'salvaiciclisti_sicrm'
%   stage      - 'orig', 'auto' oppure 'final' (stessa fase che hai
%                plottato con plot_debug_graph per leggere i Tag)
%   indices    - indici (1-based, gli stessi Tag di plot_debug_graph.m,
%                letti con get(gco,'Tag') cliccando le linee di interesse)
%                degli archi da estrarre, es. [12, 47] per FG e HI
%   out_path   - dove scrivere il json di input per fetch_debug(), di
%                norma scripts/debug_fixtures/debug_input.json (la
%                cartella viene creata se non esiste)
%
% Esempio (dopo aver cliccato le linee FG e HI nella figura aperta da
% plot_debug_graph e letto i loro Tag, qui 12 e 47):
%   extract_debug_subset('percorsi/build/routing_v3/debug_images', ...
%       'salvaiciclisti_sicrm', 'auto', [12, 47], ...
%       'scripts/debug_fixtures/debug_input.json')
%
% Dopo questo, process_debug = true in precompila_parametri.txt (le altre
% sorgenti possono restare commentate/false per isolare solo "debug") e un
% normale precompila_v3.py --reprocess-only --no-publish rigenera il
% grafo SOLO sui due archi estratti - stessa identica pipeline, senza il
% rumore del resto della mappa.

    if nargin < 5
        error('Uso: extract_debug_subset(debug_dir, source_key, stage, indices, out_path)');
    end

    bg_files = dir(fullfile(debug_dir, 'background_*pxkm.jpg'));
    if isempty(bg_files)
        error('Nessun file background_*pxkm.jpg trovato in %s', debug_dir);
    end
    tok = regexp(bg_files(1).name, '^background_(.+pxkm)\.jpg$', 'tokens', 'once');
    suffix = tok{1};

    json_path = fullfile(debug_dir, sprintf('%s_%s_%s_latlon.json', source_key, stage, suffix));
    if ~isfile(json_path)
        error('File non trovato: %s\n(controlla source_key/stage - deve essere la stessa fase gia'' plottata con plot_debug_graph.m)', json_path);
    end
    data = jsondecode(fileread(json_path));

    edges = data.edges;
    n_edges = numel(edges);
    if n_edges == 0
        error('%s non contiene archi', json_path);
    end

    out_entries = cell(1, numel(indices));
    for k = 1:numel(indices)
        idx = indices(k);
        if idx < 1 || idx > n_edges
            error('Indice %d fuori range (1..%d) per %s', idx, n_edges, json_path);
        end
        if n_edges == 1
            e = edges;  % jsondecode: un solo edge decodifica a struct singola, non struct array
        else
            e = edges(idx);
        end
        name = e.name;
        if isempty(name)
            name = '';
        end
        s = struct();
        s.coords = num2cell(e.geom, 2);  % Nx1 cell di righe [lat, lon] -> array JSON di coppie
        s.name = name;
        out_entries{k} = s;
    end

    out_json = jsonencode(out_entries);

    out_dir_path = fileparts(out_path);
    if ~isempty(out_dir_path) && ~isfolder(out_dir_path)
        mkdir(out_dir_path);
    end
    fid = fopen(out_path, 'w');
    if fid < 0
        error('Impossibile scrivere %s', out_path);
    end
    fprintf(fid, '%s', out_json);
    fclose(fid);

    fprintf('scritto %s (%d archi estratti da %s, indici: %s)\n', ...
            out_path, numel(indices), json_path, mat2str(indices));
end
