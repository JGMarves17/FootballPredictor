package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuinielaTest {

    // ── QuinielaScorer ────────────────────────────────────────────────────────

    @Test
    void pointsTableIsCorrect() {
        assertEquals(1, QuinielaScorer.pointsResult(Stage.GRUPOS));
        assertEquals(3, QuinielaScorer.pointsExact(Stage.GRUPOS));
        assertEquals(6, QuinielaScorer.pointsResult(Stage.FINAL));
        assertEquals(8, QuinielaScorer.pointsExact(Stage.FINAL));
    }

    @Test
    void perfectPredictionGivesExactPoints() {
        // P(exacto)=1, P(resultado)=1 → ptsExacto
        double ev = QuinielaScorer.expectedPoints(1.0, 1.0, Stage.GRUPOS);
        assertEquals(3.0, ev, 1e-9);
    }

    @Test
    void correctResultButWrongScoreGivesResultPoints() {
        // P(exacto)=0, P(resultado)=1 → ptsResultado
        double ev = QuinielaScorer.expectedPoints(0.0, 1.0, Stage.GRUPOS);
        assertEquals(1.0, ev, 1e-9);
    }

    @Test
    void wrongPredictionGivesZeroPointsAndFine() {
        double ev   = QuinielaScorer.expectedPoints(0.0, 0.0, Stage.GRUPOS);
        double fine = QuinielaScorer.expectedFine(0.0);
        assertEquals(0.0, ev,    1e-9);
        assertEquals(-10.0, fine, 1e-9);
    }

    @Test
    void expectedPointsFormula() {
        // EV = p(exacto)*3 + (p(resultado) - p(exacto))*1
        // Con p(exacto)=0.1, p(resultado)=0.5, grupos:
        // EV = 0.1*3 + (0.5-0.1)*1 = 0.3 + 0.4 = 0.7
        double ev = QuinielaScorer.expectedPoints(0.1, 0.5, Stage.GRUPOS);
        assertEquals(0.7, ev, 1e-9);
    }

    @Test
    void fineIsProportionalToFailProbability() {
        // P(resultado)=0.6 → multa esperada = -10 * 0.4 = -4.0
        double fine = QuinielaScorer.expectedFine(0.6);
        assertEquals(-4.0, fine, 1e-9);
    }

    // ── MatchEV ───────────────────────────────────────────────────────────────

    @Test
    void rankReturnsSortedByExpectedPoints() {
        EloRating strong = EloRating.initial("Strong").withRating(2000);
        EloRating weak   = EloRating.initial("Weak").withRating(1400);

        List<MatchEV.Candidate> ranking = MatchEV.rank(strong, weak, 0.0, Stage.GRUPOS);

        assertFalse(ranking.isEmpty());
        // Verificar que está ordenado descendente
        for (int i = 0; i < ranking.size() - 1; i++) {
            assertTrue(ranking.get(i).expectedPoints() >= ranking.get(i + 1).expectedPoints());
        }
    }

    @Test
    void bestCandidateHasPositiveExpectedPoints() {
        EloRating home = EloRating.initial("Spain").withRating(2074);
        EloRating away = EloRating.initial("Bolivia").withRating(1480);

        MatchEV.Candidate best = MatchEV.best(home, away, 0.0, Stage.GRUPOS);

        assertTrue(best.expectedPoints() > 0);
        assertTrue(best.pResult() > 0);
    }

    @Test
    void strongTeamWinsIsOptimalPrediction() {
        // Con diferencia grande de ratings, el equipo fuerte debería ganar en la predicción óptima
        EloRating strong = EloRating.initial("Strong").withRating(2100);
        EloRating weak   = EloRating.initial("Weak").withRating(1300);

        MatchEV.Candidate best = MatchEV.best(strong, weak, 0.0, Stage.GRUPOS);

        assertTrue(best.score().homeGoals() > best.score().awayGoals(),
                "El equipo fuerte como local debería ganar en la predicción óptima");
    }

    // ── JornadaOptimizer ──────────────────────────────────────────────────────

    @Test
    void optimizerProducesOneRecommendationPerMatch() {
        JornadaOptimizer opt = new JornadaOptimizer(Stage.GRUPOS);
        opt.addMatch("Spain",  EloRating.initial("Spain").withRating(2074),
                "Morocco", EloRating.initial("Morocco").withRating(1880), 0.0);
        opt.addMatch("France", EloRating.initial("France").withRating(2040),
                "Brazil",  EloRating.initial("Brazil").withRating(1994), 0.0);

        var recs = opt.optimize();
        assertEquals(2, recs.size());
    }

    @Test
    void optimizerOptimalEvIsAtLeastHonestEv() {
        // El óptimo siempre tiene EV >= honesto (por definición de máximo)
        JornadaOptimizer opt = new JornadaOptimizer(Stage.GRUPOS);
        opt.addMatch("Spain",  EloRating.initial("Spain").withRating(2074),
                "Bolivia", EloRating.initial("Bolivia").withRating(1480), 0.0);

        var recs = opt.optimize();
        var r = recs.get(0);
        assertTrue(r.optimalEV() >= r.honestEV() - 1e-9,
                "EV óptimo debe ser >= EV honesto");
    }
}