package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchEVExtendedTest {

    private static final EloRating STRONG = EloRating.initial("Strong").withRating(2050);
    private static final EloRating WEAK   = EloRating.initial("Weak").withRating(1480);
    private static final EloRating EQUAL  = EloRating.initial("Equal").withRating(1750);

    // ── top3MC ────────────────────────────────────────────────────────────────

    @Test
    void top3MCReturnsList3() {
        List<MatchEV.MCScore> top3 = MatchEV.top3MC(STRONG, WEAK, 0.0, 10_000, 42L);
        assertEquals(3, top3.size(), "Debe devolver exactamente 3 marcadores");
    }

    @Test
    void top3MCIsDeterministic() {
        List<MatchEV.MCScore> a = MatchEV.top3MC(STRONG, WEAK, 0.0, 10_000, 42L);
        List<MatchEV.MCScore> b = MatchEV.top3MC(STRONG, WEAK, 0.0, 10_000, 42L);
        assertEquals(a.get(0).score(), b.get(0).score(), "Misma semilla → mismo resultado");
    }

    @Test
    void top3MCFrequenciesSumToAtMost1() {
        List<MatchEV.MCScore> top3 = MatchEV.top3MC(EQUAL, EQUAL, 0.0, 10_000, 99L);
        double sum = top3.stream().mapToDouble(MatchEV.MCScore::frequency).sum();
        assertTrue(sum <= 1.0 + 1e-9, "La suma de frecuencias top3 no puede superar 1: " + sum);
    }

    @Test
    void top3MCOrderedByFrequencyDesc() {
        List<MatchEV.MCScore> top3 = MatchEV.top3MC(STRONG, WEAK, 75.0, 10_000, 7L);
        for (int i = 0; i < top3.size() - 1; i++) {
            assertTrue(top3.get(i).frequency() >= top3.get(i+1).frequency(),
                    "El top3 debe estar ordenado por frecuencia descendente");
        }
    }

    // ── risk ──────────────────────────────────────────────────────────────────

    @Test
    void strongVsWeakIsFijoOrFuerte() {
        MatchEV.Risk r = MatchEV.risk(STRONG, WEAK, 75.0);
        assertTrue(r == MatchEV.Risk.FIJO || r == MatchEV.Risk.FUERTE,
                "Favorito claro debería ser FIJO o FUERTE, fue: " + r);
    }

    @Test
    void equalTeamsIsDobleOrTriple() {
        MatchEV.Risk r = MatchEV.risk(EQUAL, EQUAL, 0.0);
        assertTrue(r == MatchEV.Risk.DOBLE || r == MatchEV.Risk.TRIPLE,
                "Equipos iguales deberían ser DOBLE o TRIPLE, fue: " + r);
    }

    @Test
    void riskCoversAllFourValues() {
        // Verificar que los 4 niveles son distintos y tienen labels
        for (MatchEV.Risk risk : MatchEV.Risk.values()) {
            assertNotNull(risk.label);
            assertFalse(risk.label.isBlank());
        }
        assertEquals(4, MatchEV.Risk.values().length);
    }

    // ── bestResult ────────────────────────────────────────────────────────────

    @Test
    void bestResultContainsTeamName() {
        String result = MatchEV.bestResult(STRONG, WEAK, 0.0, "España", "Bolivia");
        assertTrue(result.contains("España") || result.contains("Bolivia") || result.contains("Empate"),
                "bestResult debe contener nombre de equipo o 'Empate': " + result);
    }
}