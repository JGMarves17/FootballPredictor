package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QBacktestTest {

    @Test
    void compareStrategiesRunsWithoutError() {
        Map<String, QBacktest.QResult> results = QBacktest.compareStrategies(
                Paths.get("data/results.json"));
        assertFalse(results.isEmpty());
    }

    @Test
    void allStrategiesPresent() {
        Map<String, QBacktest.QResult> results = QBacktest.compareStrategies(
                Paths.get("data/results.json"));
        for (String s : java.util.List.of("SeguroSiempre", "DualPick", "Conservative", "Random")) {
            assertTrue(results.containsKey(s), "Missing strategy: " + s);
        }
    }
}
