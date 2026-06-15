package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.quiniela.QuinielaRanking.Tally;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica que QuinielaRanking aplica los desempates oficiales en el orden correcto:
 * puntos → marcadores exactos → puntos en eliminatorias → cercanía a la final.
 */
class QuinielaRankingTest {

    private static Tally tally(int points, int exacts, int ko, int finalErr) {
        Tally t = new Tally(points);
        t.exactScores = exacts;
        t.knockoutPoints = ko;
        t.finalError = finalErr;
        return t;
    }

    @Test
    void morePointsAlwaysWins() {
        Tally a = tally(10, 0, 0, 99);   // menos exactos y peor final…
        Tally b = tally(9, 5, 5, 0);     // …pero A tiene más puntos
        assertTrue(QuinielaRanking.isBetter(a, b));
        assertFalse(QuinielaRanking.isBetter(b, a));
    }

    @Test
    void tiebreak1_moreExactScoresWins() {
        Tally a = tally(10, 4, 0, 99);
        Tally b = tally(10, 3, 9, 0);    // mismos puntos, menos exactos (aunque más KO)
        assertTrue(QuinielaRanking.isBetter(a, b),
                "A puntos iguales gana quien tiene más marcadores exactos");
    }

    @Test
    void tiebreak2_moreKnockoutPointsWins() {
        Tally a = tally(10, 3, 7, 99);
        Tally b = tally(10, 3, 6, 0);    // empate en puntos y exactos → decide KO
        assertTrue(QuinielaRanking.isBetter(a, b),
                "Empate en puntos y exactos: gana más puntos en eliminatorias");
    }

    @Test
    void tiebreak3_closerToFinalWins() {
        Tally a = tally(10, 3, 6, 1);    // error 1 (más cerca de la final)
        Tally b = tally(10, 3, 6, 4);    // error 4 (más lejos)
        assertTrue(QuinielaRanking.isBetter(a, b),
                "Empate total salvo final: gana quien quedó más cerca del marcador de la final");
    }

    @Test
    void exactTieIsNotBetterEitherWay() {
        Tally a = tally(10, 3, 6, 2);
        Tally b = tally(10, 3, 6, 2);
        assertFalse(QuinielaRanking.isBetter(a, b));
        assertFalse(QuinielaRanking.isBetter(b, a));
    }

    @Test
    void rankOfAppliesTiebreakers() {
        Map<String, Tally> t = new LinkedHashMap<>();
        t.put("Nosotros", tally(10, 2, 0, 99)); // 10 pts, 2 exactos
        t.put("A", tally(12, 0, 0, 99));        // más puntos → por delante
        t.put("B", tally(10, 5, 0, 99));        // empata puntos, más exactos → por delante
        t.put("C", tally(10, 1, 0, 99));        // empata puntos, menos exactos → detrás
        t.put("D", tally(8, 9, 9, 0));          // menos puntos → detrás
        // Por delante de Nosotros: A y B → posición 3
        assertEquals(3, QuinielaRanking.rankOf(t, "Nosotros"));
    }
}
