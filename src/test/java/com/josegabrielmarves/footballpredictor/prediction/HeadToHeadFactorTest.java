package com.josegabrielmarves.footballpredictor.prediction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para HeadToHeadFactor.
 *
 * Verifica que:
 * - Equipos sin historial devuelven neutral (1.0)
 * - Equipos con historial devuelven factores en rango
 * - La caché funciona correctamente
 * - Reset limpia el estado
 */
class HeadToHeadFactorTest {

    @AfterEach
    void tearDown() {
        HeadToHeadFactor.reset();
    }

    @Test
    void unknownTeamsReturnNeutral() {
        HeadToHeadFactor.H2HResult r = HeadToHeadFactor.getH2H("FakeTeam_XYZ", "OtherFake_ABC");
        assertEquals(1.0, r.homeAdvantage(), 1e-9);
        assertEquals(1.0, r.awayAdvantage(), 1e-9);
        assertEquals(0, r.matchesPlayed());
    }

    @Test
    void teamsWithHistoryHaveNonNeutralFactor() {
        // España vs Francia: se enfrentaron en Euro 2024 (semifinal)
        HeadToHeadFactor.H2HResult r = HeadToHeadFactor.getH2H("Spain", "France");
        assertTrue(r.matchesPlayed() > 0,
                "España vs Francia debería tener ≥1 enfrentamiento, tuvo: " + r.matchesPlayed());
        // El factor debe estar en rango válido
        assertTrue(r.homeAdvantage() >= 0.80, "Factor clamp inferior: " + r.homeAdvantage());
        assertTrue(r.homeAdvantage() <= 1.20, "Factor clamp superior: " + r.homeAdvantage());
        // Con pocos datos, el smooth debe acercarlo a 1.0
        assertTrue(Math.abs(r.homeAdvantage() - 1.0) <= 0.20,
                "Factor no debe desviarse más de 20%: " + r.homeAdvantage());
    }

    @Test
    void factorIsClampedToReasonableRange() {
        // Francia vs Bélgica: tienen varios enfrentamientos recientes
        HeadToHeadFactor.H2HResult r = HeadToHeadFactor.getH2H("Belgium", "France");
        assertTrue(r.homeAdvantage() >= 0.80, "Clampeo inferior: " + r.homeAdvantage());
        assertTrue(r.homeAdvantage() <= 1.20, "Clampeo superior: " + r.homeAdvantage());
    }

    @Test
    void cacheReturnsSameResult() {
        HeadToHeadFactor.H2HResult r1 = HeadToHeadFactor.getH2H("Spain", "France");
        HeadToHeadFactor.H2HResult r2 = HeadToHeadFactor.getH2H("Spain", "France");
        assertEquals(r1.homeAdvantage(), r2.homeAdvantage(), 1e-9);
        assertEquals(r1.matchesPlayed(), r2.matchesPlayed());
    }

    @Test
    void resetAndReloadWorks() {
        HeadToHeadFactor.reset();
        // Después de reset, debe recargar al llamar getH2H
        HeadToHeadFactor.H2HResult r = HeadToHeadFactor.getH2H("Spain", "France");
        assertTrue(r.matchesPlayed() > 0,
                "Reset no debería perder datos: " + r.matchesPlayed());
    }

    @Test
    void franceVsBelgiumHasHistory() {
        // Francia y Bélgica se han enfrentado múltiples veces
        HeadToHeadFactor.H2HResult r = HeadToHeadFactor.getH2H("France", "Belgium");
        assertTrue(r.matchesPlayed() > 0,
                "Francia vs Bélgica debería tener enfrentamientos: " + r.matchesPlayed());
    }
}
