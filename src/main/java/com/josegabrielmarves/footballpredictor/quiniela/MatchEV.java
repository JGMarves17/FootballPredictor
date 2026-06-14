package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Evalúa todos los marcadores candidatos para un partido y devuelve
 * el ranking por EV esperado de puntos de quiniela.
 *
 * Uso típico:
 *   List<Candidate> ranking = MatchEV.rank(home, away, homeBonus, Stage.GRUPOS);
 *   Candidate best = ranking.get(0); // predicción óptima
 */
public final class MatchEV {

    /** Marcadores a evaluar: 0-0 hasta MAX_GOALS-MAX_GOALS. */
    private static final int MAX_GOALS = 5; // cubre >99% de la masa real

    private MatchEV() {}

    /**
     * Candidato: un marcador con sus métricas de EV.
     *
     * @param score          marcador predicho
     * @param pExact         P(este marcador exacto)
     * @param pResult        P(resultado 1X2 correcto)
     * @param expectedPoints EV de puntos de quiniela
     * @param expectedFine   multa esperada en lempiras (≤ 0)
     */
    public record Candidate(
            Score score,
            double pExact,
            double pResult,
            double expectedPoints,
            double expectedFine
    ) {
        @Override
        public String toString() {
            return String.format("%d-%d  pts=%.3f  multa=%.2fL  P(exacto)=%.1f%%  P(result)=%.1f%%",
                    score.homeGoals(), score.awayGoals(),
                    expectedPoints, expectedFine,
                    pExact * 100, pResult * 100);
        }
    }

    /**
     * Genera el ranking de todos los marcadores candidatos ordenados por
     * puntos esperados descendente.
     *
     * @param home      EloRating del equipo local (con rating ya actualizado)
     * @param away      EloRating del visitante
     * @param homeBonus ventaja de local (HOME_ADVANTAGE o 0 si neutral)
     * @param stage     fase del torneo para la tabla de puntos
     */
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

                // Resultado 1X2 al que pertenece este marcador
                double pResult;
                if (h > a) pResult = probs.homeWin();
                else if (h < a) pResult = probs.awayWin();
                else pResult = probs.draw();

                double evPts  = QuinielaScorer.expectedPoints(pExact, pResult, stage);
                double evFine = QuinielaScorer.expectedFine(pResult);

                candidates.add(new Candidate(new Score(h, a), pExact, pResult, evPts, evFine));
            }
        }

        candidates.sort(Comparator.comparingDouble(Candidate::expectedPoints).reversed());
        return candidates;
    }

    /**
     * Predicción óptima: marcador que maximiza EV de puntos.
     */
    public static Candidate best(EloRating home, EloRating away,
                                 double homeBonus, Stage stage) {
        return rank(home, away, homeBonus, stage).get(0);
    }

    /**
     * Predicción honesta: marcador más probable (modal de la matriz).
     */
    public static Score honest(EloRating home, EloRating away, double homeBonus) {
        return PoissonPredictor.mostLikelyScore(home, away, homeBonus);
    }
}