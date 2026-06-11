package com.josegabrielmarves.footballpredictor.prediction.poisson;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de PoissonPredictor. Origen: auditoría QA del 10-jun-2026
 * (Observación 2: las afirmaciones cuantitativas requieren evidencia
 * automatizada).
 */
class PoissonPredictorTest {

    private static final EloRating STRONG = new EloRating("Strong", 1900);
    private static final EloRating WEAK = new EloRating("Weak", 1600);
    private static final EloRating EQUAL_A = new EloRating("A", 1700);
    private static final EloRating EQUAL_B = new EloRating("B", 1700);

    @Test
    void pmfSumsToOne() {
        double sum = 0;
        for (int k = 0; k <= 30; k++) {
            sum += PoissonPredictor.poissonPmf(k, 1.5);
        }
        assertEquals(1.0, sum, 1e-9);
    }

    @Test
    void coverageOfMaxGoalsMatchesDocumentation() {
        // QA Obs. 2: el javadoc de MAX_GOALS afirma >99.7% para lambdas
        // reales (<=2.8, medido 99.76%) y ~99.0% en el clamp 3.5. Verificarlo.
        assertTrue(cumulative(2.8) > 0.997, "cobertura con lambda real");
        assertTrue(cumulative(PoissonPredictor.MAX_LAMBDA) > 0.99,
                "cobertura con lambda en el clamp");
    }

    private static double cumulative(double lambda) {
        double c = 0;
        for (int k = 0; k <= PoissonPredictor.MAX_GOALS; k++) {
            c += PoissonPredictor.poissonPmf(k, lambda);
        }
        return c;
    }

    @Test
    void expectedGoalsRespectsClamps() {
        assertEquals(PoissonPredictor.MAX_LAMBDA,
                PoissonPredictor.expectedGoals(2500, 1000, 0), 1e-9);
        assertEquals(PoissonPredictor.MIN_LAMBDA,
                PoissonPredictor.expectedGoals(1000, 2500, 0), 1e-9);
        assertEquals(PoissonPredictor.BASE_GOALS,
                PoissonPredictor.expectedGoals(1700, 1700, 0), 1e-9);
    }

    @Test
    void scoreMatrixIsNormalized() {
        double[][] m = PoissonPredictor.scoreMatrix(STRONG, WEAK, 0);
        double total = 0;
        for (double[] row : m) {
            for (double p : row) {
                assertTrue(p >= 0, "ninguna celda negativa");
                total += p;
            }
        }
        assertEquals(1.0, total, 1e-9);
    }

    @Test
    void equalRatingsOnNeutralGroundAreSymmetric() {
        var p = PoissonPredictor.matchProbabilities(EQUAL_A, EQUAL_B, 0);
        assertEquals(p.homeWin(), p.awayWin(), 1e-9);
    }

    @Test
    void dixonColesAdjustsLowScoresInTheRightDirection() {
        // Comparar la matriz (con DC) contra el producto Poisson puro,
        // ambos normalizados igual.
        double lH = PoissonPredictor.expectedGoals(STRONG.rating(), WEAK.rating(), 0);
        double lA = PoissonPredictor.expectedGoals(WEAK.rating(), STRONG.rating(), 0);
        int max = PoissonPredictor.MAX_GOALS;
        double[][] pure = new double[max + 1][max + 1];
        double total = 0;
        for (int h = 0; h <= max; h++) {
            for (int a = 0; a <= max; a++) {
                pure[h][a] = PoissonPredictor.poissonPmf(h, lH)
                        * PoissonPredictor.poissonPmf(a, lA);
                total += pure[h][a];
            }
        }
        for (int h = 0; h <= max; h++) {
            for (int a = 0; a <= max; a++) {
                pure[h][a] /= total;
            }
        }
        double[][] dc = PoissonPredictor.scoreMatrix(STRONG, WEAK, 0);

        assertTrue(dc[0][0] > pure[0][0], "0-0 debe subir");
        assertTrue(dc[1][1] > pure[1][1], "1-1 debe subir");
        assertTrue(dc[1][0] < pure[1][0], "1-0 debe bajar");
        assertTrue(dc[0][1] < pure[0][1], "0-1 debe bajar");
    }

    @Test
    void mostLikelyScoreFavorsTheStrongerTeam() {
        var score = PoissonPredictor.mostLikelyScore(STRONG, WEAK, 0);
        assertTrue(score.homeGoals() >= score.awayGoals(),
                "el favorito no debe perder en el marcador modal");
    }
}