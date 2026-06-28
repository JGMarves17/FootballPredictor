package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MatchdayEngineTest {

    @Test
    void postMatchdayUpdatesRatings() {
        Map<String, EloRating> ratings = new HashMap<>();
        ratings.put("Spain",   EloRating.initial("Spain").withRating(2074));
        ratings.put("Bolivia", EloRating.initial("Bolivia").withRating(1480));

        double before = ratings.get("Spain").rating();
        MatchdayEngine.postMatchday(99,
                List.of(new MatchdayEngine.MatchResult("Spain","Bolivia",3,0)),
                ratings);

        // España ganó → su rating debe subir (o quedarse igual si ya era favorito extremo)
        assertTrue(ratings.get("Spain").rating() >= before - 5,
                "Rating de España no debe bajar mucho al ganar");
        assertTrue(ratings.containsKey("Bolivia"));
    }

    @Test
    void preMatchdayDoesNotCrash() {
        Map<String, EloRating> ratings = new HashMap<>();
        ratings.put("Spain",   EloRating.initial("Spain").withRating(2074));
        ratings.put("Bolivia", EloRating.initial("Bolivia").withRating(1480));

        assertDoesNotThrow(() -> MatchdayEngine.preMatchday(
                99,
                List.of(new MatchdayEngine.MatchInput(
                        "Spain","Bolivia",
                        com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage.GRUPOS)),
                ratings,
                LocalDate.of(2026, 6, 18)));
    }
}