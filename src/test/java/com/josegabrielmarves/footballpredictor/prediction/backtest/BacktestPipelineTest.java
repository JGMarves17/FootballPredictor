package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

class BacktestPipelineTest {

    @Test
    void eloOnlyReproducesReferenceBaseline() {
        BacktestPipeline.PipelineResult r = BacktestPipeline.run(
                Paths.get("data/results.json"),
                150, true, BacktestPipeline.PipelineConfig.eloOnly());
        assertTrue(r.metrics().matches() > 700);
        assertEquals(0.61, r.metrics().accuracy(), 0.03);
        assertEquals(0.54, r.metrics().brier(), 0.05);
        assertTrue(r.metrics().rps() < 0.33);
    }

    @Test
    void honestModeIsLeakFree() {
        BacktestPipeline.PipelineResult cal = BacktestPipeline.run(
                Paths.get("data/results.json"), 150, true,
                BacktestPipeline.PipelineConfig.eloOnly());
        BacktestPipeline.PipelineResult honest = BacktestPipeline.run(
                Paths.get("data/results.json"), 150, false,
                BacktestPipeline.PipelineConfig.eloOnly());
        assertTrue(honest.metrics().brier() < 0.667);
        assertTrue(honest.metrics().accuracy() < cal.metrics().accuracy());
        assertTrue(honest.metrics().brier() > cal.metrics().brier());
    }

    @Test
    void tripleBlendDoesNotRegress() {
        BacktestPipeline.PipelineResult elo = BacktestPipeline.run(
                Paths.get("data/results.json"), 150, false,
                BacktestPipeline.PipelineConfig.eloOnly());
        BacktestPipeline.PipelineResult blend = BacktestPipeline.run(
                Paths.get("data/results.json"), 150, false,
                BacktestPipeline.PipelineConfig.tripleBlendDefault());
        assertTrue(blend.metrics().accuracy() >= elo.metrics().accuracy() - 0.02,
                () -> "Triple Blend accuracy " + blend.metrics().accuracy()
                     + " should be near Elo " + elo.metrics().accuracy());
    }

    @Test
    void residualReportHasSegments() {
        BacktestPipeline.PipelineResult r = BacktestPipeline.run(
                Paths.get("data/results.json"), 150, false,
                BacktestPipeline.PipelineConfig.eloOnly());
        assertNotNull(r.residuals());
        assertFalse(r.residuals().byFavorite().isEmpty());
    }

    @Test
    void runTournamentProducesMetrics() {
        BacktestPipeline.PipelineResult r = BacktestPipeline.runTournament(
                Paths.get("data/xg_wc2026.json"),
                BacktestPipeline.PipelineConfig.tripleBlendDefault());
        assertTrue(r.metrics().matches() > 10,
                () -> "Tournament matches: " + r.metrics().matches());
        assertTrue(r.metrics().brier() < 0.667);
    }
}
