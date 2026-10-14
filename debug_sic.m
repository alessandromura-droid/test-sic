% una volta: aggiungi la cartella scripts al path di MATLAB
addpath('/Users/utente/JIRAM_SERENA Dropbox/Alessandro Mura/doc/linguaggi/android/salvaiciclisti/scripts');

debug_dir = '/Users/utente/JIRAM_SERENA Dropbox/Alessandro Mura/doc/linguaggi/android/salvaiciclisti/percorsi/build/routing_v3/debug_images';

% passata isolata per sorgente
% plot_debug_graph(debug_dir, 'salvaiciclisti_sicrm', 'orig');

% passata isolata per sorgente
plot_debug_graph(debug_dir, 'salvaiciclisti_sicrm', 'auto');

% grafo consolidato finale, stessa sorgente
% plot_debug_graph(debug_dir, 'salvaiciclisti_sicrm', 'final');

axis([     3300.4       3401.8       4749.7       4851.1])


% extract_debug_subset('percorsi/build/routing_v3/debug_images', ...
%     'salvaiciclisti_sicrm', 'auto', [1885, 2701], ...
%     'scripts/debug_fixtures/debug_input.json')
% 
% 
% plot_debug_graph(debug_dir, 'debug', 'auto');
