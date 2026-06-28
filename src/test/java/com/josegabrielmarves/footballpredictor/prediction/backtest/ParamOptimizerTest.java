package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ParamOptimizerTest {

    @Test
    void individualSearchProducesSortedResults() {
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        var specs = List.of(new ParamOptimizer.ParamSpec("BASELINE_GOALS", 1.3, 1.4, 0.1));
        var results = ParamOptimizer.individualSearch(
                Paths.get("data/results.json"), 150, false, base, specs);
        assertFalse(results.isEmpty());
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i-1).score() >= results.get(i).score() - 1e-9);
        }
    }

    @Test
    void randomSearchReturnsCorrectCount() {
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        var specs = List.of(new ParamOptimizer.ParamSpec("BASELINE_GOALS", 1.0, 1.8, 0.1));
        var results = ParamOptimizer.randomSearch(
                Paths.get("data/results.json"), 150, false, base, specs, 10);
        assertEquals(10, results.size());
    }
}
