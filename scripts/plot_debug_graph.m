function plot_debug_graph(debug_dir, source_key, stage)
%PLOT_DEBUG_GRAPH Plotta su un'immagine di sfondo OSM i nodi/archi di una
% fase del grafo di routing (build_routing_graph_v3.py, Alessandro 2026-10-03).
%
%   plot_debug_graph(debug_dir, source_key, stage)
%
%   debug_dir  - cartella debug_images, es.
%                percorsi/build/routing_v3/debug_images
%   source_key - chiave sorgente, es. 'salvaiciclisti_sicrm'
%   stage      - 'auto' (passata isolata per sorgente) oppure 'final'
%                (grafo consolidato finale)
%
% Usa i file {source}_{stage}_<N>pxkm.json (coordinate gia' in pixel,
% stesso spazio esatto della JPEG di sfondo corrispondente - nessuna
% riproiezione necessaria) insieme a background_<N>pxkm.jpg e
% area_metadata.json (tutti scritti da render_source_debug_images()).
% Zoom/pan liberi con gli strumenti normali della figure MATLAB, senza la
% perdita di risoluzione di uno zoom su una JPEG gia' pixelata.
%
% Esempio:
%   plot_debug_graph('percorsi/build/routing_v3/debug_images', ...
%                     'salvaiciclisti_sicrm', 'auto')

    if nargin < 3
        error('Uso: plot_debug_graph(debug_dir, source_key, stage)');
    end

    bg_files = dir(fullfile(debug_dir, 'background_*pxkm.jpg'));
    if isempty(bg_files)
        error('Nessun file background_*pxkm.jpg trovato in %s', debug_dir);
    end
    bg_path = fullfile(bg_files(1).folder, bg_files(1).name);

    % Il suffisso "_<N>pxkm" e' lo stesso per lo sfondo e per i dati di
    % questa run (area fissa, condivisa da tutte le sorgenti) - lo si
    % ricava dal nome dello sfondo trovato invece di doverlo passare a
    % mano ogni volta.
    tok = regexp(bg_files(1).name, '^background_(.+pxkm)\.jpg$', 'tokens', 'once');
    suffix = tok{1};

    json_path = fullfile(debug_dir, sprintf('%s_%s_%s.json', source_key, stage, suffix));
    if ~isfile(json_path)
        error('File non trovato: %s\n(controlla source_key/stage, oppure che quella sorgente abbia prodotto archi in questa run)', json_path);
    end

    img = imread(bg_path);
    data = jsondecode(fileread(json_path));

    meta_path = fullfile(debug_dir, 'area_metadata.json');
    if isfile(meta_path)
        meta = jsondecode(fileread(meta_path));
        edge_color = meta.edge_color_rgb(:)' / 255;
        node_color = meta.node_color_rgb(:)' / 255;
    else
        % Fallback ai colori di default dello script Python, se per
        % qualche motivo area_metadata.json non c'e'.
        edge_color = [0, 70, 220] / 255;
        node_color = [220, 20, 20] / 255;
    end

    figure('Name', sprintf('%s - %s', source_key, stage), 'NumberTitle', 'off');
    imshow(img);
    hold on;

    edges = data.edges;
    n_edges = numel(edges);
    for i = 1:n_edges
        if n_edges == 1
            e = edges;      % jsondecode: un solo edge decodifica a struct singola, non struct array
        else
            e = edges(i);
        end
        geom = e.geom_px;
        % Tag = indice dell'arco in questo json (1-based, stesso ordine di
        % data.edges) - Alessandro, 2026-10-03: clicca una linea e leggi
        % get(gco,'Tag') in command window per isolare gli archi di un bug
        % (es. FG e HI) con scripts/extract_debug_subset.m.
       p= plot(geom(:, 1), geom(:, 2), '-', 'Color', edge_color, 'LineWidth', 2, 'Tag', num2str(i));
        num_points = size(geom, 1);
tag_row = dataTipTextRow('Tag', repmat({p.Tag}, num_points, 1));
p.DataTipTemplate.DataTipRows(end+1) = tag_row;
    end

    nodes_px = data.nodes_px;
    if ~isempty(nodes_px)
        % Stadio _orig (Alessandro, 2026-10-03: niente pallini qui, il
        % concetto di nodo non esiste ancora a questo stadio): nodes_px
        % arriva vuoto ([] dopo jsondecode di un array JSON vuoto), su cui
        % nodes_px(:,1) darebbe 'Index in position 2 exceeds array bounds'
        % - si salta semplicemente il plot dei pallini, nessun errore.
        plot(nodes_px(:, 1), nodes_px(:, 2), 'o', ...
             'MarkerFaceColor', node_color, 'MarkerEdgeColor', node_color, 'MarkerSize', 4);
    end

    title_str = sprintf('%s (%s) - %d archi, %d nodi', ...
                         strrep(source_key, '_', '\_'), stage, n_edges, size(nodes_px, 1));
    title(title_str, 'Interpreter', 'tex');
    hold off;
end
