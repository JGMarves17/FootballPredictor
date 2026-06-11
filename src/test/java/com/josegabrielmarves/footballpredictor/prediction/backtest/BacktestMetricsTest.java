package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestMetrics.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestMetricsTest {

    private static final double TOL = 1e-9;

    @Test
    void perfectPredictionScoresPerfectly() {
        BacktestMetrics m = new BacktestMetrics();
        m.add(1.0, 0.0, 0.0, Outcome.HOME_WIN);
        m.add(0.0, 1.0, 0.0, Outcome.DRAW);
        m.add(0.0, 0.0, 1.0, Outcome.AWAY_WIN);

        assertEquals(1.0, m.accuracy(), TOL);
        assertEquals(0.0, m.brier(), TOL);
        assertEquals(0.0, m.logLoss(), TOL);
        assertEquals(3, m.matches());
    }

    @Test
    void uniformPredictorMatchesTheoreticalBaselines() {
        BacktestMetrics m = new BacktestMetrics();
        double third = 1.0 / 3.0;
        m.add(third, third, third, Outcome.HOME_WIN);
        m.add(third, third, third, Outcome.AWAY_WIN);

        // Baselines documentados en el javadoc de la clase
        assertEquals(2.0 / 3.0, m.brier(), TOL);
        assertEquals(Math.log(3), m.logLoss(), TOL);
    }

    @Test
    void confidentlyWrongPredictionIsPunished() {
        BacktestMetrics m = new BacktestMetrics();
        m.add(1.0, 0.0, 0.0, Outcome.AWAY_WIN);

        assertEquals(0.0, m.accuracy(), TOL);
        assertEquals(2.0, m.brier(), TOL);   // máximo del Brier multiclase
        assertTrue(m.logLoss() > 30);        // −ln(EPSILON) ≈ 34.5, gracias al clamp
    }

    @Test
    void metricsAreAveragedAcrossMatches() {
        BacktestMetrics m = new BacktestMetrics();
        m.add(1.0, 0.0, 0.0, Outcome.HOME_WIN);  // perfecto: Brier 0
        m.add(1.0, 0.0, 0.0, Outcome.AWAY_WIN);  // pésimo: Brier 2

        assertEquals(0.5, m.accuracy(), TOL);
        assertEquals(1.0, m.brier(), TOL);
        assertEquals(2, m.matches());
    }

    @Test
    void outcomeOfDerivesResultFromScore() {
        assertEquals(Outcome.HOME_WIN, Outcome.of(new Score(2, 0)));
        assertEquals(Outcome.DRAW, Outcome.of(new Score(1, 1)));
        assertEquals(Outcome.AWAY_WIN, Outcome.of(new Score(0, 3)));
    }

    @Test
    void emptyMetricsThrow() {
        BacktestMetrics m = new BacktestMetrics();
        assertThrows(IllegalStateException.class, m::accuracy);
    }
}
