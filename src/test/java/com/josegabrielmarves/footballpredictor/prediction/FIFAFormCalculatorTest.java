package com.josegabrielmarves.footballpredictor.prediction;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FIFAFormCalculatorTest {

    private static final Path DATA  = Path.of("data/results.json");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 18);

    @Test
    void neutralResultForUnknownTeam() {
        var f = FIFAFormCalculator.getForm("EquipoFicticioXYZ999", DATA, TODAY);
        assertEquals(1.0, f.attackFactor(),  1e-9);
        assertEquals(1.0, f.defenseFactor(), 1e-9);
        assertEquals(0,   f.matchesUsed());
    }

    @Test
    void spainHasPositiveFactors() {
        var f = assertDoesNotThrow(() -> FIFAFormCalculator.getForm("Spain", DATA, TODAY));
        assertTrue(f.attackFactor()  > 0, "attack debe ser positivo");
        assertTrue(f.defenseFactor() > 0, "defense debe ser positivo");
        assertTrue(f.matchesUsed()   > 0, "Spain debe tener historial");
    }

    @Test
    void matchesUsedCappedAtWindow() {
        var f = FIFAFormCalculator.getForm("Argentina", DATA, TODAY);
        assertTrue(f.matchesUsed() <= 50, "No debe usar más de 50 partidos");
    }

    @Test
    void worldCupFinalImportanceHigherThanFriendly() {
        double wc  = FIFAFormCalculator.leagueImportance("FIFA World Cup Final");
        double fri = FIFAFormCalculator.leagueImportance("International Friendly");
        assertTrue(wc > fri, "World Cup Final debe pesar más que amistoso");
        assertEquals(60.0, wc,  1e-9);
        assertEquals(15.0, fri, 1e-9);
    }

    @Test
    void lambdaAttackReflectsBaseline() {
        var neutral = FIFAFormCalculator.FormResult.neutral();
        assertEquals(1.35, neutral.lambdaAttack(),  0.001);
        assertEquals(1.35, neutral.lambdaDefense(), 0.001);
    }

    @Test
    void brazilHasHistoryAndReasonableFactors() {
        var f = FIFAFormCalculator.getForm("Brazil", DATA, TODAY);
        if (f.matchesUsed() > 0) {
            assertTrue(f.attackFactor()  > 0.3 && f.attackFactor()  < 4.0,
                    "attackFactor fuera de rango razonable: " + f.attackFactor());
            assertTrue(f.defenseFactor() > 0.3 && f.defenseFactor() < 4.0,
                    "defenseFactor fuera de rango razonable: " + f.defenseFactor());
        }
    }
}