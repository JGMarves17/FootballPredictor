package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.MatchEV;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard principal del sistema de quiniela.
 * Carga el fixture en un hilo separado (ventana aparece instantánea).
 * El botón genera predicciones honestas y óptimas para los 104 partidos.
 */
public class MainWindow extends JFrame {

    private static final String[] COLUMNS = {
            "Local", "Visitante", "Fecha", "Grupo / Ronda", "Resultado", "Honesta", "Óptima (EV)"
    };

    private final DefaultTableModel tableModel;
    private final JButton btnPredict;
    private final JLabel statusLabel;
    private List<Match> loadedMatches;

    public MainWindow() {
        setTitle("Football Predictor — Quiniela Mundial 2026");
        setSize(1400, 750);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // ── Header ────────────────────────────────────────────────────────────
        JLabel title = new JLabel("⚽  Football Predictor — Quiniela Mundial 2026",
                SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 22));
        title.setBorder(BorderFactory.createEmptyBorder(12, 0, 12, 0));
        add(title, BorderLayout.NORTH);

        // ── Tabla ─────────────────────────────────────────────────────────────
        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setFont(new Font("Consolas", Font.PLAIN, 13));
        table.getTableHeader().setFont(new Font("Arial", Font.BOLD, 13));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        add(new JScrollPane(table), BorderLayout.CENTER);

        // ── Barra inferior ────────────────────────────────────────────────────
        btnPredict  = new JButton("⚡  Generar Predicciones");
        btnPredict.setFont(new Font("Arial", Font.BOLD, 14));
        btnPredict.setEnabled(false);   // se activa cuando termina la carga

        statusLabel = new JLabel("  Cargando fixture 2026...");
        statusLabel.setFont(new Font("Arial", Font.PLAIN, 12));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        bottom.add(statusLabel, BorderLayout.WEST);
        bottom.add(btnPredict,  BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);

        // ── Acción del botón ──────────────────────────────────────────────────
        btnPredict.addActionListener(e -> generatePredictions());

        // ── Carga en background (ventana aparece de inmediato) ────────────────
        loadFixtureInBackground();
    }

    // ── Carga del fixture ─────────────────────────────────────────────────────

    private void loadFixtureInBackground() {
        new SwingWorker<List<Match>, Void>() {
            @Override protected List<Match> doInBackground() throws Exception {
                return new OpenFootballProvider().getWorldCupMatches(2026);
            }
            @Override protected void done() {
                try {
                    loadedMatches = get();
                    populateTable(loadedMatches);
                    btnPredict.setEnabled(true);
                    statusLabel.setText("  ✓ " + loadedMatches.size() +
                            " partidos cargados — pulsa el botón para generar predicciones");
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
                    m.homeTeam,
                    m.awayTeam,
                    m.date,
                    m.group != null ? m.group : m.status,
                    m.score != null ? m.score.toString() : "—",
                    "—",
                    "—"
            });
        }
    }

    // ── Generación de predicciones ────────────────────────────────────────────

    private void generatePredictions() {
        if (loadedMatches == null) return;
        btnPredict.setEnabled(false);
        statusLabel.setText("  Calculando predicciones...");

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                // Ratings calibrados + jornada 1 aplicada
                Map<String, EloRating> ratings = buildRatings();

                for (int i = 0; i < loadedMatches.size(); i++) {
                    Match m  = loadedMatches.get(i);
                    EloRating home = ratings.getOrDefault(m.homeTeam,
                            EloRating.initial(m.homeTeam));
                    EloRating away = ratings.getOrDefault(m.awayTeam,
                            EloRating.initial(m.awayTeam));
                    double bonus = isHost(m.homeTeam) ? EloCalculator.HOME_ADVANTAGE : 0.0;

                    // Predicción honesta (modal de la matriz)
                    var honest  = MatchEV.honest(home, away, bonus);
                    // Predicción óptima (máximo EV de puntos)
                    var optimal = MatchEV.best(home, away, bonus, QuinielaScorer.Stage.GRUPOS);

                    String honestStr  = honest.homeGoals() + "-" + honest.awayGoals();
                    String optimalStr = optimal.score().homeGoals() + "-"
                            + optimal.score().awayGoals()
                            + String.format("  (%.2f pts)", optimal.expectedPoints());

                    final int row = i;
                    SwingUtilities.invokeLater(() -> {
                        tableModel.setValueAt(honestStr,  row, 5);
                        tableModel.setValueAt(optimalStr, row, 6);
                    });
                }
                return null;
            }

            @Override protected void done() {
                btnPredict.setEnabled(true);
                statusLabel.setText("  ✓ Predicciones generadas — " +
                        "columna 'Honesta' = modal · 'Óptima' = máximo EV");
            }
        }.execute();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, EloRating> buildRatings() {
        Map<String, EloRating> ratings = new HashMap<>();
        for (Match m : loadedMatches) {
            ratings.putIfAbsent(m.homeTeam, CalibratedEloRatings.getRating(m.homeTeam));
            ratings.putIfAbsent(m.awayTeam, CalibratedEloRatings.getRating(m.awayTeam));
        }
        // Aplicar resultados reales — actualizar cada jornada
        applyResult(ratings, "Mexico",      "South Africa",  2, 1, EloCalculator.HOME_ADVANTAGE);
        applyResult(ratings, "South Korea", "Czech Republic",1, 1, 0.0);
        // → agregar jornadas siguientes aquí
        return ratings;
    }

    private void applyResult(Map<String, EloRating> ratings,
                             String home, String away,
                             double hg, double ag, double homeBonus) {
        EloRating h = ratings.getOrDefault(home, EloRating.initial(home));
        EloRating a = ratings.getOrDefault(away, EloRating.initial(away));
        var u = EloCalculator.updateRatings(h, a, hg, ag,
                EloCalculator.K_WORLD_CUP, homeBonus);
        ratings.put(home, u.home());
        ratings.put(away, u.away());
    }

    private boolean isHost(String team) {
        return team.equals("Mexico") || team.equals("USA")
                || team.equals("United States") || team.equals("Canada");
    }
}