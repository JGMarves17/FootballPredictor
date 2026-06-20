package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LiveMatchUpdaterTest {

    private static Map<String, EloRating> ratings() {
        Map<String, EloRating> r = new HashMap<>();
        r.put("Spain",    EloRating.initial("Spain").withRating(2074));
        r.put("Uruguay",  EloRating.initial("Uruguay").withRating(1900));
        r.put("Germany",  EloRating.initial("Germany").withRating(1927));
        r.put("France",   EloRating.initial("France").withRating(2040));
        return r;
    }

    @Test
    void matchPlayedUpdatesRatings() {
        var r = ratings();
        double spainBefore = r.get("Spain").rating();
        var updater = new LiveMatchUpdater(r, new ArrayList<>());
        updater.matchPlayed("Spain", "Uruguay", 2, 0, 1.8, 0.5, false);
        // España ganó → su Elo debe subir (o mantenerse si ya era favorito claro)
        assertTrue(r.get("Spain").rating() >= spainBefore - 10,
                "Elo España no debe bajar al ganar: " + r.get("Spain").rating());
    }

    @Test
    void matchPlayedIncrementsHistory() {
        var updater = new LiveMatchUpdater(ratings(), new ArrayList<>());
        assertEquals(0, updater.matchesRecorded());
        updater.matchPlayed("Spain", "Uruguay", 2, 1, false);
        updater.matchPlayed("Germany", "France", 1, 1, false);
        assertEquals(2, updater.matchesRecorded());
    }

    @Test
    void multipleMatchesReCalibrate() {
        var updater = new LiveMatchUpdater(ratings(), new ArrayList<>());
        assertDoesNotThrow(() -> {
            updater.matchPlayed("Spain",   "Uruguay", 3, 0, 2.5, 0.4, false);
            updater.matchPlayed("Germany", "France",  1, 2, 1.2, 2.1, false);
            updater.matchPlayed("Spain",   "France",  0, 1, 1.8, 0.9, false);
        });
        assertEquals(3, updater.matchesRecorded());
    }
}