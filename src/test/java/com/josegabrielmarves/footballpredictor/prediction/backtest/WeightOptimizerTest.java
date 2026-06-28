package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WeightOptimizerTest {

    @Test
    void gridSearchReturnsSortedResults() {
        List<WeightOptimizer.WeightResult> results = WeightOptimizer.gridSearch(
                Paths.get("data/results.json"), 150, false);
        assertFalse(results.isEmpty());
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i-1).score() >= results.get(i).score() - 1e-9);
        }
    }

    @Test
    void randomSearchProducesDiverseResults() {
        List<WeightOptimizer.WeightResult> results = WeightOptimizer.randomSearch(
                Paths.get("data/results.json"), 150, false, 30);
        assertFalse(results.isEmpty());
        double uniqueWe = results.stream().mapToDouble(WeightOptimizer.WeightResult::wElo).distinct().count();
        assertTrue(uniqueWe > 5, "Should have diverse wElo values: " + uniqueWe);
    }

    @Test
    void bestOfReturnsHighestScore() {
        List<WeightOptimizer.WeightResult> results = WeightOptimizer.gridSearch(
                Paths.get("data/results.json"), 150, true);
        WeightOptimizer.WeightResult best = WeightOptimizer.bestOf(results);
        assertNotNull(best);
        for (var r : results) {
            assertTrue(best.score() >= r.score() - 1e-9);
        }
    }
}
