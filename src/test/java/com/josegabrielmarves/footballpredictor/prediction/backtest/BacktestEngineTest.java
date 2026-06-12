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
}