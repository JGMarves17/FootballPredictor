package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import com.josegabrielmarves.footballpredictor.simulation.montecarlo.MonteCarloSimulator;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Evalúa todos los marcadores candidatos para un partido y devuelve
 * el ranking por EV esperado de puntos de quiniela.
 *
 * Métodos nuevos (Fase 13+):
 *   - top3MC()  → Top 3 marcadores más frecuentes por Monte Carlo
 *   - risk()    → Clasificación de riesgo del pick (Fijo/Fuerte/Doble/Triple)
 *   - bestResult() → Resultado 1X2 más probable con su probabilidad
 */
public final class MatchEV {

    private static final int MAX_GOALS = 5;

    private MatchEV() {}

    // ── Tipos ─────────────────────────────────────────────────────────────────

    /**
     * Nivel de riesgo del pick basado en P(mejor resultado 1X2).
     *   Fijo    ≥ 65% — alta confianza
     *   Fuerte  ≥ 55% — buena confianza
     *   Doble   ≥ 45% — confianza media (considera 2 opciones)
     *   Triple  < 45% — incertidumbre alta (considera las 3 opciones)
     */
    public enum Risk {
        FIJO   ("🔒 FIJO"),
        FUERTE ("💪 FUERTE"),
        DOBLE  ("⚖️  DOBLE"),
        TRIPLE ("🎲 TRIPLE");

        public final String label;
        Risk(String label) { this.label = label; }
    }

    /** Candidato: marcador con sus métricas de EV de quiniela. */
    public record Candidate(
            Score score,
            double pExact,
            double pResult,
            double expectedPoints,
            double expectedFine
    ) {
        @Override public String toString() {
            return String.format("%d-%d  pts=%.3f  multa=%.2fL  P(exacto)=%.1f%%  P(result)=%.1f%%",
                    score.homeGoals(), score.awayGoals(),
                    expectedPoints, expectedFine,
                    pExact * 100, pResult * 100);
        }
    }

    /** Marcador con su frecuencia relativa en simulaciones Monte Carlo. */
    public record MCScore(Score score, double frequency) {
        @Override public String toString() {
            return String.format("%d-%d (%.1f%%)",
                    score.homeGoals(), score.awayGoals(), frequency * 100);
        }
    }

    // ── Métodos principales ───────────────────────────────────────────────────

    /** Ranking completo de marcadores candidatos por EV de puntos (desc). */
    public static List<Candidate> rank(EloRating home, EloRating away,
                                       double homeBonus, Stage stage) {
        double[][] matrix = PoissonPredictor.scoreMatrix(home, away, homeBonus);
        PoissonPredictor.MatchProbabilities probs =
                PoissonPredictor.matchProbabilities(home, away, homeBonus);

        List<Candidate> candidates = new ArrayList<>();
        for (int h = 0; h <= MAX_GOALS; h++) {
            for (int a = 0; a <= MAX_GOALS; a++) {
                double pExact = (h < matrix.length && a < matrix[h].length)
                        ? matrix[h][a] : 0.0;
                double pResult = h > a ? probs.homeWin()
                        : h < a ? probs.awayWin()
                          : probs.draw();
                candidates.add(new Candidate(new Score(h, a), pExact, pResult,
                        QuinielaScorer.expectedPoints(pExact, pResult, stage),
                        QuinielaScorer.expectedFine(pResult)));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::expectedPoints).reversed());
        return candidates;
    }

    /** Predicción óptima: marcador que maximiza EV de puntos de quiniela. */
    public static Candidate best(EloRating home, EloRating away,
                                 double homeBonus, Stage stage) {
        return rank(home, away, homeBonus, stage).get(0);
    }

    /** Predicción honesta: marcador modal de la matriz Poisson+DC. */
    public static Score honest(EloRating home, EloRating away, double homeBonus) {
        return PoissonPredictor.mostLikelyScore(home, away, homeBonus);
    }

    // ── Nuevos métodos ────────────────────────────────────────────────────────

    /**
     * Top 3 marcadores más frecuentes por simulación Monte Carlo.
     *
     * A diferencia del modal (honest), MC captura la distribución completa
     * incluyendo la corrección Dixon-Coles. El top 3 puede diferir del modal
     * cuando hay varios marcadores con probabilidades similares.
     *
     * @param n    número de simulaciones (10_000 recomendado)
     * @param seed semilla para reproducibilidad
     */
    public static List<MCScore> top3MC(EloRating home, EloRating away,
                                       double homeBonus, int n, long seed) {
        double[][] matrix = PoissonPredictor.scoreMatrix(home, away, homeBonus);
        Random rng = new Random(seed);
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < n; i++) {
            Score s = MonteCarloSimulator.sample(matrix, rng);
            counts.merge(s.homeGoals() + "-" + s.awayGoals(), 1, Integer::sum);
        }

        return counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(3)
                .map(e -> {
                    String[] p = e.getKey().split("-");
                    return new MCScore(
                            new Score(Integer.parseInt(p[0]), Integer.parseInt(p[1])),
                            (double) e.getValue() / n);
                })
                .collect(Collectors.toList());
    }

    /**
     * Clasificación de riesgo del pick.
     * Se basa en P(mejor resultado 1X2), no en el marcador exacto.
     */
    public static Risk risk(EloRating home, EloRating away, double homeBonus) {
        PoissonPredictor.MatchProbabilities probs =
                PoissonPredictor.matchProbabilities(home, away, homeBonus);
        double best = Math.max(probs.homeWin(), Math.max(probs.draw(), probs.awayWin()));
        if (best >= 0.65) return Risk.FIJO;
        if (best >= 0.55) return Risk.FUERTE;
        if (best >= 0.45) return Risk.DOBLE;
        return Risk.TRIPLE;
    }

    /**
     * Resultado 1X2 más probable con su probabilidad.
     * Ejemplo: "Local (58%)"
     */
    public static String bestResult(EloRating home, EloRating away,
                                    double homeBonus,
                                    String homeTeam, String awayTeam) {
        PoissonPredictor.MatchProbabilities p =
                PoissonPredictor.matchProbabilities(home, away, homeBonus);
        if (p.homeWin() >= p.draw() && p.homeWin() >= p.awayWin())
            return String.format("%s (%.0f%%)", homeTeam, p.homeWin() * 100);
        if (p.awayWin() >= p.draw())
            return String.format("%s (%.0f%%)", awayTeam, p.awayWin() * 100);
        return String.format("Empate (%.0f%%)", p.draw() * 100);
    }
}