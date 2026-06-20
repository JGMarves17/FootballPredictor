package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.MatchEV;
import com.josegabrielmarves.footballpredictor.quiniela.StageDetector;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainWindow extends JFrame {

    // ── Colores ───────────────────────────────────────────────────────────────
    static final Color BG        = new Color(0x0F, 0x12, 0x17);
    static final Color CARD_BG   = new Color(0x2A, 0x2F, 0x3B);
    static final Color ACCENT    = new Color(0x00, 0xA3, 0xFF);
    static final Color GOLD      = new Color(0xFF, 0xD7, 0x00);
    static final Color TEXT_MAIN = new Color(0xE8, 0xEC, 0xF2);
    static final Color TEXT_DIM  = new Color(0x9B, 0xA4, 0xB5);
    static final Color WINNER_BG = new Color(0x00, 0xA3, 0xFF, 45);
    static final Color DIVIDER   = new Color(0x3A, 0x41, 0x52);

    // ── Tabla ─────────────────────────────────────────────────────────────────
    private static final String[] COLUMNS = {
            "Local","Visitante","Fecha","Grupo / Ronda","Resultado","Honesta","Óptima (EV)"
    };
    private final DefaultTableModel tableModel;
    private final JButton btnPredict;
    private final JLabel statusLabel;
    private List<Match> loadedMatches;
    private BracketPanel bracketPanel;

    public MainWindow() {
        setTitle("Football Predictor — Quiniela Mundial 2026");
        setSize(1500, 850);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setBackground(BG);

        // Header
        JLabel title = new JLabel("⚽  FOOTBALL PREDICTOR — QUINIELA MUNDIAL 2026", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));
        title.setForeground(TEXT_MAIN);
        title.setBackground(BG);
        title.setOpaque(true);
        title.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));

        // ── Pestaña Grupos & Bracket ──────────────────────────────────────────
        bracketPanel = new BracketPanel(this);
        JScrollPane bracketScroll = new JScrollPane(bracketPanel);
        bracketScroll.setBackground(BG);
        bracketScroll.getViewport().setBackground(BG);
        bracketScroll.setBorder(BorderFactory.createEmptyBorder());
        bracketScroll.getHorizontalScrollBar().setUnitIncrement(20);
        bracketScroll.getVerticalScrollBar().setUnitIncrement(20);

        // ── Pestaña Tabla ─────────────────────────────────────────────────────
        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setFont(new Font("Consolas", Font.PLAIN, 13));
        table.getTableHeader().setFont(new Font("Arial", Font.BOLD, 13));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setBackground(new Color(0x1A, 0x1E, 0x28));
        table.setForeground(TEXT_MAIN);
        table.setGridColor(DIVIDER);
        table.getTableHeader().setBackground(CARD_BG);
        table.getTableHeader().setForeground(TEXT_MAIN);

        // Ordenar por columna al hacer clic en el header
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        // Orden inicial: por fecha (columna 2)
        sorter.setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.ASCENDING)));

        // Barra de búsqueda
        JTextField searchField = new JTextField();
        searchField.setFont(new Font("Arial", Font.PLAIN, 13));
        searchField.setBackground(CARD_BG);
        searchField.setForeground(TEXT_MAIN);
        searchField.setCaretColor(TEXT_MAIN);
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DIVIDER),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));

        JLabel searchLabel = new JLabel("🔍  Buscar: ");
        searchLabel.setForeground(TEXT_DIM);
        searchLabel.setFont(new Font("Arial", Font.PLAIN, 13));

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void filter() {
                String txt = searchField.getText().trim().toLowerCase();
                if (txt.isEmpty()) sorter.setRowFilter(null);
                else sorter.setRowFilter(RowFilter.regexFilter("(?i)" + txt));
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { filter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { filter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
        });

        JPanel searchBar = new JPanel(new BorderLayout(6, 0));
        searchBar.setBackground(CARD_BG);
        searchBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        searchBar.add(searchLabel, BorderLayout.WEST);
        searchBar.add(searchField, BorderLayout.CENTER);

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBackground(BG);

        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.setBackground(BG);
        tablePanel.add(searchBar,   BorderLayout.NORTH);
        tablePanel.add(tableScroll, BorderLayout.CENTER);

        // ── JTabbedPane ───────────────────────────────────────────────────────
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(BG);
        tabs.setForeground(TEXT_MAIN);
        tabs.setFont(new Font("Arial", Font.BOLD, 13));
        tabs.addTab("🌍  Grupos & Bracket",   bracketScroll);
        tabs.addTab("📋  Todos los partidos", tablePanel);

        // ── Barra inferior ────────────────────────────────────────────────────
        btnPredict = new JButton("⚡  Generar Predicciones");
        btnPredict.setFont(new Font("Arial", Font.BOLD, 13));
        btnPredict.setBackground(ACCENT);
        btnPredict.setForeground(Color.WHITE);
        btnPredict.setFocusPainted(false);
        btnPredict.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        btnPredict.setEnabled(false);

        statusLabel = new JLabel("  Cargando fixture 2026...");
        statusLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        statusLabel.setForeground(TEXT_DIM);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(CARD_BG);
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        bottom.add(statusLabel, BorderLayout.WEST);
        bottom.add(btnPredict,  BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(title,  BorderLayout.NORTH);
        add(tabs,   BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        btnPredict.addActionListener(e -> generatePredictions());
        loadFixtureInBackground();
    }

    // ── Carga del fixture ─────────────────────────────────────────────────────

    void loadFixtureInBackground() {
        new SwingWorker<List<Match>, Void>() {
            @Override protected List<Match> doInBackground() throws Exception {
                return new OpenFootballProvider().getWorldCupMatches(2026);
            }
            @Override protected void done() {
                try {
                    loadedMatches = get();
                    populateTable(loadedMatches);
                    bracketPanel.setMatches(loadedMatches, buildRatings());
                    btnPredict.setEnabled(true);
                    statusLabel.setText("  ✓ " + loadedMatches.size() +
                            " partidos cargados — pulsa ⚡ para predicciones");
                } catch (Exception ex) {
                    statusLabel.setText("  ✗ Error: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void populateTable(List<Match> matches) {
        tableModel.setRowCount(0);
        for (Match m : matches) {
            tableModel.addRow(new Object[]{
                    m.homeTeam, m.awayTeam, m.date,
                    m.group != null ? m.group : m.status,
                    m.score != null ? m.score.toString() : "—",
                    "—", "—"
            });
        }
    }

    // ── Predicciones ──────────────────────────────────────────────────────────

    private void generatePredictions() {
        if (loadedMatches == null) return;
        btnPredict.setEnabled(false);
        statusLabel.setText("  Calculando predicciones...");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                Map<String, EloRating> ratings = buildRatings();
                for (int i = 0; i < loadedMatches.size(); i++) {
                    Match m  = loadedMatches.get(i);
                    EloRating h = ratings.getOrDefault(m.homeTeam, EloRating.initial(m.homeTeam));
                    EloRating a = ratings.getOrDefault(m.awayTeam, EloRating.initial(m.awayTeam));
                    double bonus = isHost(m.homeTeam) ? EloCalculator.HOME_ADVANTAGE : 0.0;
                    var honest  = MatchEV.honest(h, a, bonus);
                    var optimal = MatchEV.best(h, a, bonus, StageDetector.detect(m));
                    String hon = honest.homeGoals() + "-" + honest.awayGoals();
                    String opt = optimal.score().homeGoals() + "-" + optimal.score().awayGoals()
                            + String.format("  (%.2f pts)", optimal.expectedPoints());
                    final int row = i;
                    SwingUtilities.invokeLater(() -> {
                        tableModel.setValueAt(hon, row, 5);
                        tableModel.setValueAt(opt, row, 6);
                    });
                }
                return null;
            }
            @Override protected void done() {
                btnPredict.setEnabled(true);
                statusLabel.setText("  ✓ Predicciones generadas");
            }
        }.execute();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    Map<String, EloRating> buildRatings() {
        Map<String, EloRating> r = new HashMap<>();
        if (loadedMatches != null)
            for (Match m : loadedMatches) {
                r.putIfAbsent(m.homeTeam, CalibratedEloRatings.getRating(m.homeTeam));
                r.putIfAbsent(m.awayTeam, CalibratedEloRatings.getRating(m.awayTeam));
            }
        applyResult(r,"Mexico","South Africa",   2,1, EloCalculator.HOME_ADVANTAGE);
        applyResult(r,"South Korea","Czech Republic",1,1,0.0);
        // → agregar resultados de jornadas siguientes aquí
        return r;
    }

    void applyResult(Map<String,EloRating> r, String home, String away,
                     double hg, double ag, double bonus) {
        EloRating h = r.getOrDefault(home, EloRating.initial(home));
        EloRating a = r.getOrDefault(away, EloRating.initial(away));
        var u = EloCalculator.updateRatings(h, a, hg, ag, EloCalculator.K_WORLD_CUP, bonus);
        r.put(home, u.home()); r.put(away, u.away());
    }

    boolean isHost(String t) {
        return t.equals("Mexico")||t.equals("USA")||t.equals("United States")||t.equals("Canada");
    }
}