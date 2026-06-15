package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class BacktestEngineTest {

    // ── tests unitarios (sin archivo) ────────────────────────────────────────

    @Test
    void leagueToKMapsAllKnownCategories() {
        // World Cup (sin qualifier)
        assertEquals(55.0, BacktestEngine.leagueToK("World Cup"),             1e-9);
        // Qualifiers — varios formatos del dataset
        assertEquals(40.0, BacktestEngine.leagueToK("World Cup - Qualification Europe"), 1e-9);
        assertEquals(40.0, BacktestEngine.leagueToK("Euro Championship - Qualification"), 1e-9);
        assertEquals(40.0, BacktestEngine.leagueToK("Africa Cup of Nations - Qualification"), 1e-9);
        // Continentales
        assertEquals(50.0, BacktestEngine.leagueToK("Copa America"),           1e-9);
        assertEquals(50.0, BacktestEngine.leagueToK("Euro Championship"),      1e-9);
        assertEquals(50.0, BacktestEngine.leagueToK("Africa Cup of Nations"),  1e-9);
        assertEquals(50.0, BacktestEngine.leagueToK("CONCACAF Gold Cup"),      1e-9);
        // Nations League / Cup
        assertEquals(32.0, BacktestEngine.leagueToK("UEFA Nations League"),    1e-9);
        assertEquals(32.0, BacktestEngine.leagueToK("CONCACAF Nations League"),1e-9);
        assertEquals(28.0, BacktestEngine.leagueToK("Gulf Cup of Nations"),    1e-9);
        // Amistosos
        assertEquals(18.0, BacktestEngine.leagueToK("Friendlies"),             1e-9);
        // Default (Arab Cup, FIFA Series, COSAFA Cup…)
        assertEquals(28.0, BacktestEngine.leagueToK("Arab Cup"),               1e-9);
        assertEquals(28.0, BacktestEngine.leagueToK("FIFA Series"),            1e-9);
        assertEquals(28.0, BacktestEngine.leagueToK(null),                     1e-9);
    }

    // ── test de integración (requiere data/results.json) ─────────────────────

    @Test
    void reproducesReferenceBaseline() {
        // Carga el dataset real y verifica que las métricas estén cerca
        // del baseline de la referencia (mismo dato, mismo protocolo):
        //   accuracy ~61%  ·  Brier ~0.54  ·  RPS < uniforme (0.333)
        // Tolerancia ±3%: cubre pequeñas diferencias en seeds y rounding.
        BacktestMetrics m = BacktestEngine.run(Paths.get("data/results.json"));

        assertTrue(m.matches() > 700,
                () -> "Partidos evaluados: " + m.matches() + " (esperado >700)");
        assertEquals(0.61, m.accuracy(), 0.03,
                () -> "Accuracy: " + m.accuracy());
        assertEquals(0.54, m.brier(),    0.05,
                () -> "Brier: "    + m.brier());
        assertTrue(m.rps() < 0.33,
                () -> "RPS debe mejorar al uniforme (0.333): " + m.rps());

        // Log informativo: facilita comparar contra la referencia
        System.out.printf(
                "%n=== Backtest walk-forward — %d partidos (burn-in %d) ===%n" +
                        "  Accuracy : %.1f%%%n" +
                        "  Brier    : %.3f%n" +
                        "  Log-loss : %.3f%n" +
                        "  RPS      : %.4f%n",
                m.matches(), BacktestEngine.DEFAULT_BURN_IN,
                m.accuracy() * 100, m.brier(), m.logLoss(), m.rps());
    }

    @Test
    void honestModeIsLeakFreeAndLowerThanCalibrated() {
        // Calibrado = replica la referencia, pero la semilla incluye info del futuro.
        BacktestMetrics calibrated = BacktestEngine.run(
                Paths.get("data/results.json"), BacktestEngine.DEFAULT_BURN_IN, true);
        // Honesto = arranque plano, Elo construido solo hacia adelante (sin fuga).
        BacktestMetrics honest = BacktestEngine.run(
                Paths.get("data/results.json"), BacktestEngine.DEFAULT_BURN_IN, false);

        // Evalúa el mismo universo de partidos…
        assertTrue(honest.matches() > 700,
                () -> "Partidos evaluados (honesto): " + honest.matches());
        // …mejora claramente al modelo bobo uniforme (Brier 0.667 / RPS 0.333)…
        assertTrue(honest.brier() < 0.667, () -> "Brier honesto: " + honest.brier());
        assertTrue(honest.rps()   < 0.333, () -> "RPS honesto: "   + honest.rps());
        // …pero, al quitar la fuga del futuro, es PEOR (más honesto) que el calibrado:
        assertTrue(honest.accuracy() < calibrated.accuracy(),
                () -> "Accuracy honesto " + honest.accuracy()
                        + " debe ser < calibrado " + calibrated.accuracy());
        assertTrue(honest.brier() > calibrated.brier(),
                () -> "Brier honesto " + honest.brier()
                        + " debe ser > calibrado " + calibrated.brier());

        System.out.printf(
                "%n=== Backtest: HONESTO (sin fuga) vs CALIBRADO (referencia) ===%n" +
                        "  CALIBRADO (con fuga del futuro): Accuracy %.1f%%  Brier %.3f  RPS %.4f%n" +
                        "  HONESTO   (causal, sin fuga)   : Accuracy %.1f%%  Brier %.3f  RPS %.4f%n" +
                        "  -> El número honesto es %.1f%%.%n",
                calibrated.accuracy()*100, calibrated.brier(), calibrated.rps(),
                honest.accuracy()*100, honest.brier(), honest.rps(),
                honest.accuracy()*100);
    }
}