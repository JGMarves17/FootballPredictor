package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Genera las predicciones óptimas para una jornada completa.
 *
 * Para cada partido de la jornada:
 *  - Calcula la predicción honesta (modal del modelo)
 *  - Calcula la predicción óptima (máximo EV de puntos)
 *  - Muestra ambas con sus métricas para que el usuario decida
 *
 * Uso:
 *   JornadaOptimizer opt = new JornadaOptimizer(Stage.GRUPOS);
 *   opt.addMatch("México", mexicoRating, "Sudáfrica", saRating, 75.0);
 *   opt.addMatch("Corea del Sur", koreaRating, "Chequia", czechRating, 0.0);
 *   opt.printReport();
 */
public final class JornadaOptimizer {

    private final Stage stage;

    /** Entrada por partido. */
    private record MatchEntry(
            String homeTeam, EloRating home,
            String awayTeam, EloRating away,
            double homeBonus
    ) {}

    private final java.util.List<MatchEntry> matches = new java.util.ArrayList<>();

    public JornadaOptimizer(Stage stage) {
        this.stage = stage;
    }

    /** Agrega un partido a la jornada. */
    public void addMatch(String homeTeam, EloRating home,
                         String awayTeam, EloRating away,
                         double homeBonus) {
        matches.add(new MatchEntry(homeTeam, home, awayTeam, away, homeBonus));
    }

    /**
     * Resultado de optimización por partido.
     *
     * @param homeTeam      nombre del local
     * @param awayTeam      nombre del visitante
     * @param honest        predicción honesta (marcador más probable)
     * @param optimal       predicción óptima (máximo EV de puntos)
     * @param honestEV      EV de puntos de la predicción honesta
     * @param optimalEV     EV de puntos de la predicción óptima
     * @param optimalFine   multa esperada de la predicción óptima (lempiras)
     * @param sameChoice    true si honesta == óptima
     */
    public record MatchRecommendation(
            String homeTeam, String awayTeam,
            Score honest, Score optimal,
            double honestEV, double optimalEV,
            double optimalFine, boolean sameChoice
    ) {}

    /** Genera las recomendaciones para todos los partidos. */
    public java.util.List<MatchRecommendation> optimize() {
        java.util.List<MatchRecommendation> results = new java.util.ArrayList<>();

        for (MatchEntry m : matches) {
            Score honest = MatchEV.honest(m.home(), m.away(), m.homeBonus());
            MatchEV.Candidate optimal = MatchEV.best(m.home(), m.away(), m.homeBonus(), stage);

            // EV de la predicción honesta
            double[][] matrix = com.josegabrielmarves.footballpredictor.prediction.poisson
                    .PoissonPredictor.scoreMatrix(m.home(), m.away(), m.homeBonus());
            double pHonestExact = matrix[honest.homeGoals()][honest.awayGoals()];
            var probsHonest = com.josegabrielmarves.footballpredictor.prediction.poisson
                    .PoissonPredictor.matchProbabilities(m.home(), m.away(), m.homeBonus());
            double pHonestResult = honest.homeGoals() > honest.awayGoals() ? probsHonest.homeWin()
                    : honest.homeGoals() < honest.awayGoals() ? probsHonest.awayWin()
                      : probsHonest.draw();
            double honestEV = QuinielaScorer.expectedPoints(pHonestExact, pHonestResult, stage);

            boolean same = honest.homeGoals() == optimal.score().homeGoals()
                    && honest.awayGoals() == optimal.score().awayGoals();

            results.add(new MatchRecommendation(
                    m.homeTeam(), m.awayTeam(),
                    honest, optimal.score(),
                    honestEV, optimal.expectedPoints(),
                    optimal.expectedFine(), same));
        }
        return results;
    }

    /** Imprime el reporte completo de la jornada en consola. */
    public void printReport() {
        var recs = optimize();
        System.out.printf("%n=== Jornada — Fase: %s ===%n%n", stage);
        System.out.printf("%-28s %-10s %-10s %8s %8s %9s %s%n",
                "Partido", "Honesta", "Óptima", "EV(hon)", "EV(opt)", "Multa(L)", "");
        System.out.println("-".repeat(85));

        for (var r : recs) {
            String diff = r.sameChoice() ? "✓ iguales"
                    : String.format("⚡ dif (+%.3f pts)", r.optimalEV() - r.honestEV());
            System.out.printf("%-28s %-10s %-10s %8.3f %8.3f %9.2f %s%n",
                    r.homeTeam() + " vs " + r.awayTeam(),
                    r.honest().homeGoals() + "-" + r.honest().awayGoals(),
                    r.optimal().homeGoals() + "-" + r.optimal().awayGoals(),
                    r.honestEV(), r.optimalEV(), r.optimalFine(), diff);
        }
        System.out.println();
    }
}