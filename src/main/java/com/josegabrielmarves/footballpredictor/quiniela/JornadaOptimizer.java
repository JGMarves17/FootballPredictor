package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * Genera las predicciones óptimas para una jornada completa.
 *
 * Para cada partido:
 *   - Honesta: marcador modal del modelo
 *   - Óptima:  marcador que maximiza EV de puntos de quiniela
 *   - Top 3 MC: los 3 marcadores más frecuentes por Monte Carlo
 *   - Riesgo:  clasificación Fijo/Fuerte/Doble/Triple
 */
public final class JornadaOptimizer {

    private static final int MC_N    = 10_000;
    private static final long MC_SEED = 42L;

    private final Stage stage;

    private record MatchEntry(String homeTeam, EloRating home,
                              String awayTeam, EloRating away, double homeBonus) {}

    private final List<MatchEntry> matches = new ArrayList<>();

    public JornadaOptimizer(Stage stage) { this.stage = stage; }

    public void addMatch(String homeTeam, EloRating home,
                         String awayTeam, EloRating away, double homeBonus) {
        matches.add(new MatchEntry(homeTeam, home, awayTeam, away, homeBonus));
    }

    public record MatchRecommendation(
            String homeTeam, String awayTeam,
            Score honest, Score optimal,
            double honestEV, double optimalEV,
            double optimalFine, boolean sameChoice,
            List<MatchEV.MCScore> top3,
            MatchEV.Risk risk,
            String bestResult
    ) {}

    public List<MatchRecommendation> optimize() {
        List<MatchRecommendation> results = new ArrayList<>();
        for (MatchEntry m : matches) {
            Score honest = MatchEV.honest(m.home(), m.away(), m.homeBonus());
            MatchEV.Candidate optimal = MatchEV.best(m.home(), m.away(), m.homeBonus(), stage);

            double[][] matrix = PoissonPredictor.scoreMatrix(m.home(), m.away(), m.homeBonus());
            var probs = PoissonPredictor.matchProbabilities(m.home(), m.away(), m.homeBonus());
            double pHonestExact = matrix[honest.homeGoals()][honest.awayGoals()];
            double pHonestResult = honest.homeGoals() > honest.awayGoals() ? probs.homeWin()
                    : honest.homeGoals() < honest.awayGoals() ? probs.awayWin() : probs.draw();
            double honestEV = QuinielaScorer.expectedPoints(pHonestExact, pHonestResult, stage);

            boolean same = honest.homeGoals() == optimal.score().homeGoals()
                    && honest.awayGoals() == optimal.score().awayGoals();

            List<MatchEV.MCScore> top3 = MatchEV.top3MC(
                    m.home(), m.away(), m.homeBonus(), MC_N, MC_SEED);
            MatchEV.Risk risk = MatchEV.risk(m.home(), m.away(), m.homeBonus());
            String bestResult = MatchEV.bestResult(
                    m.home(), m.away(), m.homeBonus(), m.homeTeam(), m.awayTeam());

            results.add(new MatchRecommendation(
                    m.homeTeam(), m.awayTeam(),
                    honest, optimal.score(),
                    honestEV, optimal.expectedPoints(), optimal.expectedFine(),
                    same, top3, risk, bestResult));
        }
        return results;
    }

    public void printReport() {
        var recs = optimize();
        System.out.printf("%n╔══════════════════════════════════════════════════════════════╗%n");
        System.out.printf("║  JORNADA — Fase: %-43s║%n", stage);
        System.out.printf("╚══════════════════════════════════════════════════════════════╝%n");

        for (var r : recs) {
            System.out.printf("%n  %-30s%n", r.homeTeam() + " vs " + r.awayTeam());
            System.out.printf("  %-14s %s%n", "Resultado:", r.bestResult());
            System.out.printf("  %-14s %s%n", "Riesgo:", r.risk().label);
            System.out.printf("  %-14s %s  (EV %.3f pts)%n",
                    "Honesta:", r.honest().homeGoals() + "-" + r.honest().awayGoals(), r.honestEV());
            System.out.printf("  %-14s %s  (EV %.3f pts | multa %.2fL)%n",
                    "Óptima:",
                    r.optimal().homeGoals() + "-" + r.optimal().awayGoals(),
                    r.optimalEV(), r.optimalFine());

            // Top 3 MC
            System.out.printf("  %-14s ", "Top 3 MC:");
            for (int i = 0; i < r.top3().size(); i++) {
                MatchEV.MCScore mc = r.top3().get(i);
                System.out.printf("%s%s", mc, i < r.top3().size() - 1 ? "  ·  " : "");
            }
            System.out.println();

            if (!r.sameChoice()) {
                System.out.printf("  ⚡ La predicción óptima difiere de la honesta (+%.3f pts)%n",
                        r.optimalEV() - r.honestEV());
            }
            System.out.println("  " + "─".repeat(60));
        }
        System.out.println();
    }
}