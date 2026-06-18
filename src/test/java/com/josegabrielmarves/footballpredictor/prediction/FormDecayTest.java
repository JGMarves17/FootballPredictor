package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class FormDecayTest {

    private static final Path DATA = Path.of("data/results.json");
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 17);

    @Test
    void formFactorsArePositive() {
        // Spain tiene historial en results.json
        FormDecay.FormFactors f = assertDoesNotThrow(
                () -> FormDecay.computeFormFactors("Spain", DATA, TODAY));
        assertTrue(f.attackFactor() > 0, "attackFactor debe ser positivo");
        assertTrue(f.defenseFactor() > 0, "defenseFactor debe ser positivo");
    }

    @Test
    void unknownTeamReturnsNeutralFactors() {
        FormDecay.FormFactors f = assertDoesNotThrow(
                () -> FormDecay.computeFormFactors("EquipoFicticio_XYZ_999", DATA, TODAY));
        assertEquals(1.0, f.attackFactor(), 1e-9);
        assertEquals(1.0, f.defenseFactor(), 1e-9);
    }

    @Test
    void adjustDoesNotCrashForUnknownTeam() {
        EloRating base = EloRating.initial("Unknown").withRating(1800);
        EloRating adjusted = assertDoesNotThrow(
                () -> FormDecay.adjust(base, "EquipoFicticio", DATA, TODAY));
        assertEquals(base.rating(), adjusted.rating(), 1e-9,
                "Sin datos, el rating no debe cambiar");
    }

    @Test
    void adjustedRatingIsWithinBounds() {
        EloRating base = EloRating.initial("Spain").withRating(2074);
        EloRating adjusted = assertDoesNotThrow(
                () -> FormDecay.adjust(base, "Spain", DATA, TODAY));
        assertTrue(adjusted.rating() >= 1200 && adjusted.rating() <= 2500,
                "Rating ajustado debe estar en [1200, 2500]: " + adjusted.rating());
    }

    @Test
    void eloBonusIsWithinCap() {
        FormDecay.FormFactors f = new FormDecay.FormFactors(2.0, 0.0); // caso extremo
        double bonus = f.eloBonus();
        assertTrue(Math.abs(bonus) <= 200.0, "Bonus Elo no debe exceder ±200: " + bonus);
    }
}