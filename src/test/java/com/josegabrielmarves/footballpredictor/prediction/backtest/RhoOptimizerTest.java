package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RhoOptimizerTest {

    @Test
    void gridSearchProducesOutput() {
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        List<RhoOptimizer.RhoResult> results = RhoOptimizer.gridSearch(
                Paths.get("data/results.json"), 150, false, base);
        assertFalse(results.isEmpty());
        assertEquals(18, results.size());
    }

    @Test
    void bestRhoInExpectedRange() {
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        List<RhoOptimizer.RhoResult> results = RhoOptimizer.gridSearch(
                Paths.get("data/results.json"), 150, true, base);
        RhoOptimizer.RhoResult best = results.get(0);
        assertTrue(best.rho() >= -0.20 && best.rho() <= -0.03,
                () -> "Best \u03C1 = " + best.rho() + " should be in [-0.20, -0.03]");
    }
}
