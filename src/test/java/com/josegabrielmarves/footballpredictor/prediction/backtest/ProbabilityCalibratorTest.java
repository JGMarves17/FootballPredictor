package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.josegabrielmarves.footballpredictor.prediction.ProbabilityCalibrator;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestMetrics.Outcome;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProbabilityCalibratorTest {

    @Test
    void platScalingFitsSyntheticData() {
        List<double[]> probs = List.of(
            new double[]{0.60, 0.25, 0.15},
            new double[]{0.55, 0.25, 0.20},
            new double[]{0.65, 0.20, 0.15},
            new double[]{0.40, 0.30, 0.30},
            new double[]{0.70, 0.20, 0.10},
            new double[]{0.45, 0.30, 0.25}
        );
        List<Outcome> actuals = List.of(
            Outcome.HOME_WIN, Outcome.DRAW, Outcome.AWAY_WIN,
            Outcome.HOME_WIN, Outcome.HOME_WIN, Outcome.DRAW
        );

        ProbabilityCalibrator cal = ProbabilityCalibrator.trainPlatt(probs, actuals);
        assertNotNull(cal);
    }

    @Test
    void calibrateArrayStillWorks() {
        ProbabilityCalibrator cal = new ProbabilityCalibrator(0.95, 1.10, 0.95);
        double[] result = cal.calibrateArray(new double[]{0.50, 0.30, 0.20});
        assertEquals(3, result.length);
        assertTrue(Math.abs(result[0] + result[1] + result[2] - 1.0) < 1e-9);
    }

    @Test
    void defaultCalibratorProducesExpectedShift() {
        ProbabilityCalibrator cal = new ProbabilityCalibrator();
        PoissonPredictor.MatchProbabilities input = new PoissonPredictor.MatchProbabilities(0.50, 0.25, 0.25);
        PoissonPredictor.MatchProbabilities output = cal.calibrate(input);
        assertTrue(output.draw() / output.homeWin() > input.draw() / input.homeWin());
    }
}
