package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TournamentGLMTest {

    private static final Map<String, EloRating> RATINGS = Map.of(
            "Spain",  EloRating.initial("Spain").withRating(2074),
            "France", EloRating.initial("France").withRating(2040),
            "Germany", EloRating.initial("Germany").withRating(1927),
            "Brazil", EloRating.initial("Brazil").withRating(2100)
    );

    @Test
    void emptyMatchesReturnsEmptyGLM() {
        TournamentGLM glm = TournamentGLM.fit(List.of(), RATINGS);
        assertEquals(0, glm.matchesUsed());
    }

    @Test
    void fitWithOneMatchProducesSaneLambdas() {
        var data = List.of(
                new TournamentGLM.MatchData("Spain", "France", 2, 1, false)
        );
        TournamentGLM glm = TournamentGLM.fit(data, RATINGS);
        assertTrue(glm.matchesUsed() >= 1);
        double lH = glm.lambdaHome("Spain", "France", false);
        double lA = glm.lambdaAway("Spain", "France");
        assertTrue(lH > 0);
        assertTrue(lA > 0);
    }

    @Test
    void priorReflectsEloOrdering() {
        // Without any matches, the GLM should use Elo priors
        var data = List.<TournamentGLM.MatchData>of();
        TournamentGLM glm = TournamentGLM.fit(data, RATINGS);
        double lBrazilAtHome = glm.lambdaHome("Brazil", "Germany", false);
        double lGermanyAtHome = glm.lambdaHome("Germany", "Brazil", false);
        // Brazil's attack prior > Germany's attack prior (Elo 2100 vs 1927)
        assertTrue(lBrazilAtHome > lGermanyAtHome,
                "Brazil should have higher expected goals than Germany at home");
    }

    @Test
    void homeAdvantageIncreasesLambda() {
        var data = List.of(
                new TournamentGLM.MatchData("Spain", "France", 2, 1, false)
        );
        TournamentGLM glm = TournamentGLM.fit(data, RATINGS);
        double lNeutral = glm.lambdaHome("Spain", "Germany", false);
        double lHome = glm.lambdaHome("Spain", "Germany", true);
        assertTrue(lHome >= lNeutral);
    }
}
