package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import com.josegabrielmarves.footballpredictor.rivals.RivalProfile;
import com.josegabrielmarves.footballpredictor.rivals.StandingsSimulator;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class StrategyOptimizerTest {

    private static EloRating r(double rating) {
        return EloRating.initial("T").withRating(rating);
    }

    private static Map<String, Integer> standings() {
        Map<String, Integer> s = new LinkedHashMap<>();
        s.put(StandingsSimulator.US, 0);
        for (int i = 1; i <= 13; i++) s.put("Rival" + i, 0);
        return s;
    }

    private static List<RivalProfile> rivals() {
        List<RivalProfile> list = new ArrayList<>();
        for (int i = 1; i <= 13; i++)
            list.add(new RivalProfile("Rival" + i, RivalProfile.Type.CONSERVATIVE));
        return list;
    }

    @Test
    void optimizerReturnsOnePredictionPerMatch() {
        List<FastStrategyOptimizer.StrategyMatch> matches = List.of(
                new FastStrategyOptimizer.StrategyMatch("Spain", r(2074), "Bolivia", r(1480), 0.0),
                new FastStrategyOptimizer.StrategyMatch("France", r(2040), "Ecuador", r(1650), 0.0)
        );

        FastStrategyOptimizer.OptimizationResult result = FastStrategyOptimizer.optimize(
                matches, standings(), rivals(), Stage.GRUPOS, 2, 500, 42L,
                FastStrategyOptimizer.Objective.EXPECTED_PAYOUT);

        assertNotNull(result);
        assertEquals(matches.size(), result.predictions().size());
        assertEquals(4, result.combinationsEvaluated()); // 2^2 = 4
    }

    @Test
    void optimizerIsDeterministicWithSameSeed() {
        List<FastStrategyOptimizer.StrategyMatch> matches = List.of(
                new FastStrategyOptimizer.StrategyMatch("Spain", r(2074), "Bolivia", r(1480), 0.0)
        );

        FastStrategyOptimizer.OptimizationResult r1 = FastStrategyOptimizer.optimize(
                matches, standings(), rivals(), Stage.GRUPOS, 2, 1000, 99L,
                FastStrategyOptimizer.Objective.EXPECTED_PAYOUT);
        FastStrategyOptimizer.OptimizationResult r2 = FastStrategyOptimizer.optimize(
                matches, standings(), rivals(), Stage.GRUPOS, 2, 1000, 99L,
                FastStrategyOptimizer.Objective.EXPECTED_PAYOUT);

        assertEquals(r1.pPodio(), r2.pPodio(), 1e-9);
        assertEquals(r1.predictions().get(0).homeGoals(), r2.predictions().get(0).homeGoals());
    }

    @Test
    void pPodioIsInValidRange() {
        List<FastStrategyOptimizer.StrategyMatch> matches = List.of(
                new FastStrategyOptimizer.StrategyMatch("Spain", r(2074), "Morocco", r(1880), 0.0)
        );

        FastStrategyOptimizer.OptimizationResult result = FastStrategyOptimizer.optimize(
                matches, standings(), rivals(), Stage.GRUPOS, 3, 1000, 42L,
                FastStrategyOptimizer.Objective.EXPECTED_PAYOUT);

        assertTrue(result.pPodio() >= 0.0 && result.pPodio() <= 1.0);
        assertTrue(result.p1st() + result.p2nd() + result.p3rd() <= 1.0 + 1e-9);
        assertEquals(3, result.combinationsEvaluated()); // 3^1 = 3
    }
}
