package com.josegabrielmarves.footballpredictor.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.ScoreMatrix;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.MatchEV;

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

    private static final String[] COLUMNS = {
            "Local","Visitante","Fecha","Grupo / Ronda","Resultado",
            "Seguro","Exacto arriesgado","Riesgo"
    };
    private final DefaultTableModel tableModel;
    private final JButton btnPredict;
    private final JLabel statusLabel;
    private List<Match> loadedMatches;
    private BracketPanel bracketPanel;
    private MatrixPanel matrixPanel;

    public MainWindow() {
        setTitle("Football Predictor — Quiniela Mundial 2026");
        setSize(1500, 850);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JLabel title = new JLabel("⚽  FOOTBALL PREDICTOR — QUINIELA MUNDIAL 2026", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));
        title.setForeground(TEXT_MAIN);
        title.setBorder(BorderFactory.createEmptyBorder(10, 0, 10, 0));

        // ── Pestaña Grupos & Bracket ──────────────────────────────────────────
        bracketPanel = new BracketPanel(this);
        JScrollPane bracketScroll = new JScrollPane(bracketPanel);
        bracketScroll.getViewport().setBackground(BG);
        bracketScroll.setBorder(BorderFactory.createEmptyBorder());
        bracketScroll.getHorizontalScrollBar().setUnitIncrement(20);
        bracketScroll.getVerticalScrollBar().setUnitIncrement(20);

        // ── Pestaña Tabla ─────────────────────────────────────────────────────
        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(tableModel);
        table.setRowHeight(26);
        table.setFont(new Font("Consolas", Font.PLAIN, 13));
        table.getTableHeader().setFont(new Font("Arial", Font.BOLD, 13));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(2, SortOrder.ASCENDING)));

        JTextField searchField = new JTextField();
        searchField.setFont(new Font("Arial", Font.PLAIN, 13));
        JLabel searchLabel = new JLabel("🔍  Buscar: ");
        searchLabel.setForeground(TEXT_DIM);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void filter() {
                String txt = searchField.getText().trim();
                if (txt.isEmpty()) sorter.setRowFilter(null);
                else sorter.setRowFilter(RowFilter.regexFilter("(?i)" + txt));
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { filter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { filter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
        });
        JPanel searchBar = new JPanel(new BorderLayout(6, 0));
        searchBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        searchBar.add(searchLabel, BorderLayout.WEST);
        searchBar.add(searchField, BorderLayout.CENTER);

        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(searchBar,   BorderLayout.NORTH);
        tablePanel.add(new JScrollPane(table), BorderLayout.CENTER);

        // ── Pestaña Matriz 500k ───────────────────────────────────────────────
        matrixPanel = new MatrixPanel();

        // ── Tabs ──────────────────────────────────────────────────────────────
        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("Arial", Font.BOLD, 13));
        tabs.addTab("🌍  Grupos & Bracket",   bracketScroll);
        tabs.addTab("📋  Todos los partidos", tablePanel);
        tabs.addTab("🎯  Matriz 500k",        matrixPanel);

        btnPredict = new JButton("⚡  Generar Predicciones");
        btnPredict.setFont(new Font("Arial", Font.BOLD, 13));
        btnPredict.setEnabled(false);
        statusLabel = new JLabel("  Cargando fixture 2026...");
        statusLabel.setForeground(TEXT_DIM);

        JPanel bottom = new JPanel(new BorderLayout());
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
                    matrixPanel.setData(loadedMatches, buildRatings());
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
                    "—", "—", "—"
            });
        }
    }

    // ── Predicciones (usa motor de TORNEO: xG + GLM) ──────────────────────────

    private void generatePredictions() {
        if (loadedMatches == null) return;
        btnPredict.setEnabled(false);
        statusLabel.setText("  Calculando predicciones con motor de torneo...");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                Map<String, EloRating> ratings = buildRatings();
                for (int i = 0; i < loadedMatches.size(); i++) {
                    Match m  = loadedMatches.get(i);
                    EloRating h = ratings.getOrDefault(m.homeTeam, EloRating.initial(m.homeTeam));
                    EloRating a = ratings.getOrDefault(m.awayTeam, EloRating.initial(m.awayTeam));
                    double bonus = isHost(m.homeTeam) ? EloCalculator.HOME_ADVANTAGE : 0.0;

                    MatchEV.DualPick pick = MatchEV.dualPick(m.homeTeam, h, m.awayTeam, a, bonus);
                    String seguro = String.format("%d-%d (%.0f%%)",
                            pick.seguro().homeGoals(), pick.seguro().awayGoals(), pick.pSeguro()*100);
                    String exacto = String.format("%d-%d (%.0f%%)",
                            pick.exacto().homeGoals(), pick.exacto().awayGoals(), pick.pExacto()*100);

                    final int row = i;
                    SwingUtilities.invokeLater(() -> {
                        tableModel.setValueAt(seguro, row, 5);
                        tableModel.setValueAt(exacto, row, 6);
                        tableModel.setValueAt(pick.risk().label, row, 7);
                    });
                }
                return null;
            }
            @Override protected void done() {
                btnPredict.setEnabled(true);
                statusLabel.setText("  ✓ Predicciones generadas — Seguro=resultado · Exacto=marcador pico");
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
        return r;
    }

    boolean isHost(String t) {
        return t.equals("Mexico")||t.equals("USA")||t.equals("United States")||t.equals("Canada");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pestaña Matriz 500k — mapa de calor visual
    // ════════════════════════════════════════════════════════════════════════
    private static class MatrixPanel extends JPanel {
        private final DefaultListModel<String> listModel = new DefaultListModel<>();
        private final JList<String> matchList = new JList<>(listModel);
        private final HeatmapView heatmap = new HeatmapView();
        private final JLabel info = new JLabel("Selecciona un partido", SwingConstants.CENTER);
        private List<Match> matches;
        private Map<String, EloRating> ratings;
        private final Map<Integer, ScoreMatrix> cache = new HashMap<>();

        MatrixPanel() {
            setLayout(new BorderLayout());
            matchList.setFont(new Font("Consolas", Font.PLAIN, 13));
            matchList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) showMatch(matchList.getSelectedIndex());
            });
            JScrollPane listScroll = new JScrollPane(matchList);
            listScroll.setPreferredSize(new Dimension(340, 0));

            info.setFont(new Font("Arial", Font.BOLD, 17));
            info.setForeground(TEXT_MAIN);
            info.setBorder(BorderFactory.createEmptyBorder(12, 0, 12, 0));

            JPanel right = new JPanel(new BorderLayout());
            right.add(info, BorderLayout.NORTH);
            right.add(heatmap, BorderLayout.CENTER);

            add(listScroll, BorderLayout.WEST);
            add(right, BorderLayout.CENTER);
        }

        void setData(List<Match> matches, Map<String, EloRating> ratings) {
            this.matches = matches;
            this.ratings = ratings;
            listModel.clear();
            for (Match m : matches) {
                if (m.homeTeam == null || m.awayTeam == null) continue;
                if (m.homeTeam.matches(".*\\d.*")) continue; // saltar placeholders 2A/1E
                listModel.addElement(m.homeTeam + " vs " + m.awayTeam);
            }
            if (!listModel.isEmpty()) matchList.setSelectedIndex(0);
        }

        private void showMatch(int idx) {
            if (idx < 0 || matches == null) return;
            // mapear índice de lista (filtrada) al match real
            int realIdx = -1, count = 0;
            for (int i = 0; i < matches.size(); i++) {
                Match m = matches.get(i);
                if (m.homeTeam == null || m.awayTeam == null) continue;
                if (m.homeTeam.matches(".*\\d.*")) continue;
                if (count == idx) { realIdx = i; break; }
                count++;
            }
            if (realIdx < 0) return;
            final int fi = realIdx;
            Match m = matches.get(fi);
            info.setText("Calculando 500.000 simulaciones...");
            heatmap.clear();

            new SwingWorker<ScoreMatrix, Void>() {
                @Override protected ScoreMatrix doInBackground() {
                    if (cache.containsKey(fi)) return cache.get(fi);
                    EloRating h = ratings.getOrDefault(m.homeTeam, EloRating.initial(m.homeTeam));
                    EloRating a = ratings.getOrDefault(m.awayTeam, EloRating.initial(m.awayTeam));
                    double bonus = (m.homeTeam.equals("Mexico")||m.homeTeam.equals("USA")
                            ||m.homeTeam.equals("Canada")) ? EloCalculator.HOME_ADVANTAGE : 0.0;
                    ScoreMatrix sm = ScoreMatrix.compute(m.homeTeam, h, m.awayTeam, a, bonus, 42L);
                    cache.put(fi, sm);
                    return sm;
                }
                @Override protected void done() {
                    try {
                        ScoreMatrix sm = get();
                        Score modal = sm.mostLikelyScore();
                        info.setText(String.format(
                                "<html><span style='color:#E8ECF2;'>%s vs %s</span>" +
                                        "&nbsp;&nbsp;&nbsp;<span style='color:#00A3FF;'>Local %.0f%%</span>&nbsp;&nbsp;" +
                                        "<span style='color:#9BA4B5;'>Empate %.0f%%</span>&nbsp;&nbsp;" +
                                        "<span style='color:#00A3FF;'>Visitante %.0f%%</span>" +
                                        "&nbsp;&nbsp;&nbsp;<span style='color:#FFD700;'>Exacto pico: %d-%d (%.1f%%)</span></html>",
                                m.homeTeam, m.awayTeam,
                                sm.pHomeWin()*100, sm.pDraw()*100, sm.pAwayWin()*100,
                                modal.homeGoals(), modal.awayGoals(),
                                sm.probability(modal.homeGoals(), modal.awayGoals())*100));
                        heatmap.setMatrix(sm, m.homeTeam, m.awayTeam);
                    } catch (Exception ex) {
                        info.setText("Error: " + ex.getMessage());
                    }
                }
            }.execute();
        }
    }

    // ── Mapa de calor (cuadrícula coloreada) ──────────────────────────────────
    private static class HeatmapView extends JPanel {
        private ScoreMatrix sm;
        private String homeTeam = "", awayTeam = "";
        private static final int N = 6; // mostrar 0..5

        void setMatrix(ScoreMatrix sm, String h, String a) {
            this.sm = sm; this.homeTeam = h; this.awayTeam = a; repaint();
        }
        void clear() { this.sm = null; repaint(); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (sm == null) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int cell = Math.min((getWidth()-120)/N, (getHeight()-120)/N);
            cell = Math.max(48, Math.min(cell, 92));
            int x0 = 90, y0 = 70;

            double max = 0;
            for (int h=0; h<N; h++) for (int a=0; a<N; a++) max = Math.max(max, sm.probability(h,a));

            // marcador modal
            int mh=0, ma=0; double best=-1;
            for (int h=0; h<N; h++) for (int a=0; a<N; a++)
                if (sm.probability(h,a)>best){best=sm.probability(h,a);mh=h;ma=a;}

            g2.setFont(new Font("Arial", Font.BOLD, 13));
            g2.setColor(ACCENT);
            g2.drawString(awayTeam + " (goles →)", x0, y0 - 40);
            // etiqueta vertical
            g2.rotate(-Math.PI/2);
            g2.drawString(homeTeam + " (goles →)", -(y0 + N*cell - 10), 30);
            g2.rotate(Math.PI/2);

            for (int a=0; a<N; a++) {
                g2.setColor(TEXT_DIM);
                g2.setFont(new Font("Arial", Font.BOLD, 12));
                g2.drawString(String.valueOf(a), x0 + a*cell + cell/2 - 4, y0 - 8);
            }
            for (int h=0; h<N; h++) {
                g2.setColor(TEXT_DIM);
                g2.drawString(String.valueOf(h), x0 - 24, y0 + h*cell + cell/2 + 5);
            }

            for (int h=0; h<N; h++) {
                for (int a=0; a<N; a++) {
                    double p = sm.probability(h,a);
                    double intensity = max>0 ? p/max : 0;
                    g2.setColor(new Color(0, (int)(0x60+0x90*intensity), (int)(0x90+0x6F*intensity),
                            (int)(40+200*intensity)));
                    g2.fillRoundRect(x0+a*cell+2, y0+h*cell+2, cell-4, cell-4, 8, 8);

                    if (h==mh && a==ma) {
                        g2.setColor(GOLD);
                        g2.setStroke(new BasicStroke(3));
                        g2.drawRoundRect(x0+a*cell+2, y0+h*cell+2, cell-4, cell-4, 8, 8);
                    }
                    g2.setColor(p>max*0.25 ? Color.WHITE : TEXT_DIM);
                    g2.setFont(new Font("Arial", Font.BOLD, 12));
                    String sc = h+"-"+a;
                    g2.drawString(sc, x0+a*cell+cell/2-12, y0+h*cell+cell/2-2);
                    g2.setFont(new Font("Arial", Font.PLAIN, 11));
                    String pct = String.format("%.1f%%", p*100);
                    g2.drawString(pct, x0+a*cell+cell/2-14, y0+h*cell+cell/2+13);
                }
            }
        }
    }

    // ── main con FlatLaf ──────────────────────────────────────────────────────
    public static void main(String[] args) {
        FlatDarkLaf.setup();
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
