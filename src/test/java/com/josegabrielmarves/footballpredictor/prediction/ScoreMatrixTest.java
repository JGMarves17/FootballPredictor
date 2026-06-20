package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScoreMatrixTest {

    private static final EloRating SPAIN    = EloRating.initial("Spain").withRating(2074);
    private static final EloRating BOLIVIA  = EloRating.initial("Bolivia").withRating(1480);
    private static final EloRating EQUAL_A  = EloRating.initial("TeamA").withRating(1800);
    private static final EloRating EQUAL_B  = EloRating.initial("TeamB").withRating(1800);

    @Test
    void probabilitiesSumToOne() {
        ScoreMatrix m = ScoreMatrix.compute("Spain", SPAIN, "Bolivia", BOLIVIA, 0.0, 42L, 50_000);
        double sum = m.pHomeWin() + m.pDraw() + m.pAwayWin();
        assertEquals(1.0, sum, 0.01);
    }

    @Test
    void top5HasAtMost5Entries() {
        ScoreMatrix m = ScoreMatrix.compute("Spain", SPAIN, "Bolivia", BOLIVIA, 0.0, 42L, 50_000);
        assertTrue(m.top5().size() <= 5);
    }

    @Test
    void top5IsSortedByProbabilityDesc() {
        ScoreMatrix m = ScoreMatrix.compute("Spain", SPAIN, "Bolivia", BOLIVIA, 0.0, 7L, 50_000);
        for (int i = 0; i < m.top5().size() - 1; i++)
            assertTrue(m.top5().get(i).probability() >= m.top5().get(i+1).probability());
    }

    @Test
    void deterministicWithSameSeed() {
        ScoreMatrix a = ScoreMatrix.compute("Spain", SPAIN, "Bolivia", BOLIVIA, 0.0, 99L, 50_000);
        ScoreMatrix b = ScoreMatrix.compute("Spain", SPAIN, "Bolivia", BOLIVIA, 0.0, 99L, 50_000);
        assertEquals(a.pHomeWin(), b.pHomeWin(), 1e-9);
        assertEquals(a.mostLikelyScore(), b.mostLikelyScore());
    }

    @Test
    void favoriteWinsMoreThanHalf() {
        ScoreMatrix m = ScoreMatrix.compute("Spain", SPAIN, "Bolivia", BOLIVIA, 0.0, 42L, 50_000);
        assertTrue(m.pHomeWin() > 0.5, "Spain debería ganar >50%: " + m.pHomeWin());
    }

    @Test
    void equalTeamsDrawIsSignificant() {
        ScoreMatrix m = ScoreMatrix.compute("TeamA", EQUAL_A, "TeamB", EQUAL_B, 0.0, 42L, 50_000);
        assertTrue(m.pDraw() > 0.20, "Equipos iguales deben tener >20% empate: " + m.pDraw());
    }

    @Test
    void matrixDiagonalHasDrawProbability() {
        ScoreMatrix m = ScoreMatrix.compute("TeamA", EQUAL_A, "TeamB", EQUAL_B, 0.0, 42L, 50_000);
        double diagonalSum = 0;
        double[][] mat = m.matrix();
        for (int i = 0; i < mat.length; i++) diagonalSum += mat[i][i];
        assertEquals(m.pDraw(), diagonalSum, 0.02);
    }
}