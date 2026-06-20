package com.josegabrielmarves.footballpredictor.prediction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TournamentConditionerTest {

    private final TournamentConditioner cond = TournamentConditioner.getInstance();

    @Test
    void spainAttackAdjustmentLessThanOne() {
        // España tuvo 2.29 xG pero marcó 0 → debería tener ajuste < 1.0
        double adj = cond.attackAdjustment("Spain");
        assertTrue(adj < 1.0, "España falló chances → ajuste ataque < 1.0: " + adj);
    }

    @Test
    void germanyAttackAdjustmentGreaterThanOne() {
        // Alemania tuvo 4.22 xG y marcó 7 → sobrerindió → ajuste > 1.0
        double adj = cond.attackAdjustment("Germany");
        assertTrue(adj > 1.0, "Alemania sobrerindió → ajuste ataque > 1.0: " + adj);
    }

    @Test
    void unknownTeamReturnsNeutral() {
        assertEquals(1.0, cond.attackAdjustment("EquipoFantasma999"), 1e-9);
        assertEquals(1.0, cond.defenseAdjustment("EquipoFantasma999"), 1e-9);
    }

    @Test
    void adjustLambdasPreservesOrderOfMagnitude() {
        double[] adj = cond.adjustLambdas("Spain", 1.8, "Cape Verde", 0.5);
        assertTrue(adj[0] > 0.1 && adj[0] < 5.0, "λHome debe ser razonable: " + adj[0]);
        assertTrue(adj[1] > 0.1 && adj[1] < 5.0, "λAway debe ser razonable: " + adj[1]);
    }

    @Test
    void australiaOverperformingHasHigherAttack() {
        // Australia marcó 2 pero tenía 0.77 xG → attack adj > 1
        double adj = cond.attackAdjustment("Australia");
        assertTrue(adj > 1.0, "Australia sobrerindió en ataque: " + adj);
    }
}