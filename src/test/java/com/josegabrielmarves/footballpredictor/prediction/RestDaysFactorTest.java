package com.josegabrielmarves.footballpredictor.prediction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests para RestDaysFactor.
 *
 * Verifica que:
 * - Sin inicialización, devuelve 1.0 (neutral)
 * - Equipo con más descanso tiene factor > 1.0
 * - Equipo con menos descanso tiene factor < 1.0
 * - Factor está clampado en [0.88, 1.12]
 */
class RestDaysFactorTest {

    @AfterEach
    void tearDown() {
        RestDaysFactor.reset();
    }

    @Test
    void uninitializedReturnsNeutral() {
        assertEquals(1.0, RestDaysFactor.getHomeRestFactor("TeamA", "TeamB", LocalDate.now()), 1e-9);
    }

    @Test
    void homeWithMoreRestHasHigherFactor() {
        Map<String, LocalDate> lastDates = new HashMap<>();
        LocalDate today = LocalDate.of(2026, 6, 28);
        lastDates.put("TeamA", today.minusDays(7));  // descansó 7 días
        lastDates.put("TeamB", today.minusDays(3));  // descansó 3 días
        RestDaysFactor.initialize(lastDates);

        double factor = RestDaysFactor.getHomeRestFactor("TeamA", "TeamB", today);
        assertTrue(factor > 1.0, "TeamA tiene más descanso, factor debería ser > 1.0: " + factor);
        // Diferencia de 4 días → 1 + 0.03*4 = 1.12
        assertEquals(1.12, factor, 0.001);
    }

    @Test
    void homeWithLessRestHasLowerFactor() {
        Map<String, LocalDate> lastDates = new HashMap<>();
        LocalDate today = LocalDate.of(2026, 6, 28);
        lastDates.put("TeamA", today.minusDays(2));  // descansó 2 días
        lastDates.put("TeamB", today.minusDays(6));  // descansó 6 días
        RestDaysFactor.initialize(lastDates);

        double factor = RestDaysFactor.getHomeRestFactor("TeamA", "TeamB", today);
        assertTrue(factor < 1.0, "TeamA tiene menos descanso, factor debería ser < 1.0: " + factor);
    }

    @Test
    void factorIsClamped() {
        Map<String, LocalDate> lastDates = new HashMap<>();
        LocalDate today = LocalDate.of(2026, 6, 28);
        lastDates.put("TeamA", today.minusDays(30));  // descansó 30 días
        lastDates.put("TeamB", today.minusDays(1));   // descansó 1 día
        RestDaysFactor.initialize(lastDates);

        double factor = RestDaysFactor.getHomeRestFactor("TeamA", "TeamB", today);
        assertTrue(factor <= 1.12, "Factor clampeado a máx 1.12: " + factor);
        assertTrue(factor >= 0.88, "Factor clampeado a mín 0.88: " + factor);
    }

    @Test
    void equalRestReturnsOne() {
        Map<String, LocalDate> lastDates = new HashMap<>();
        LocalDate today = LocalDate.of(2026, 6, 28);
        lastDates.put("TeamA", today.minusDays(4));
        lastDates.put("TeamB", today.minusDays(4));
        RestDaysFactor.initialize(lastDates);

        assertEquals(1.0, RestDaysFactor.getHomeRestFactor("TeamA", "TeamB", today), 0.001);
    }

    @Test
    void resetClearsState() {
        Map<String, LocalDate> lastDates = new HashMap<>();
        lastDates.put("TeamA", LocalDate.of(2026, 6, 20));
        RestDaysFactor.initialize(lastDates);
        assertTrue(RestDaysFactor.getHomeRestFactor("TeamA", "TeamB", LocalDate.now()) != 1.0);

        RestDaysFactor.reset();
        assertEquals(1.0, RestDaysFactor.getHomeRestFactor("TeamA", "TeamB", LocalDate.now()), 1e-9);
    }
}
