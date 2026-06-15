package com.josegabrielmarves.footballpredictor.rivals;

import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MetaSimulatorTest {

    private static Match match(String home, String away, String group) {
        Match m = new Match(0, home, away, "2026-06-20", "SCHEDULED", null, null);
        m.group = group;
        return m;
    }

    private static Map<String, Integer> standings() {
        Map<String, Integer> s = new LinkedHashMap<>();
        s.put(StandingsSimulator.US, 1);
        for (int i = 1; i <= 13; i++) s.put("Rival" + i, 0);
        return s;
    }

    private static List<RivalProfile> rivals() {
        List<RivalProfile> list = new ArrayList<>();
        for (int i = 1; i <= 13; i++)
            list.add(new RivalProfile("Rival" + i, RivalProfile.Type.RANDOM));
        return list;
    }

    @Test
    void metaSimulatorIsDeterministicWithSameSeed() {
        List<Match> matches = List.of(
                match("Spain", "Morocco", "Group A"),
                match("France", "Brazil",  "Group C"));

        Map<String, EloRating> ratings = new HashMap<>();
        ratings.put("Spain",   EloRating.initial("Spain").withRating(2074));
        ratings.put("Morocco", EloRating.initial("Morocco").withRating(1880));
        ratings.put("France",  EloRating.initial("France").withRating(2040));
        ratings.put("Brazil",  EloRating.initial("Brazil").withRating(1994));

        MetaSimulator.MetaResult r1 = MetaSimulator.run(
                matches, ratings, new HashMap<>(), standings(), rivals(), 2000, 42L);
        MetaSimulator.MetaResult r2 = MetaSimulator.run(
                matches, ratings, new HashMap<>(), standings(), rivals(), 2000, 42L);

        assertEquals(r1.pPodio(), r2.pPodio(), 1e-9);
    }

    @Test
    void pPodioIsInValidRange() {
        List<Match> matches = List.of(match("Spain", "Bolivia", "Group A"));

        Map<String, EloRating> ratings = new HashMap<>();
        ratings.put("Spain",   EloRating.initial("Spain").withRating(2074));
        ratings.put("Bolivia", EloRating.initial("Bolivia").withRating(1480));

        MetaSimulator.MetaResult r = MetaSimulator.run(
                matches, ratings, new HashMap<>(), standings(), rivals(), 2000, 99L);

        assertTrue(r.pPodio() >= 0.0 && r.pPodio() <= 1.0);
        assertTrue(r.p1st() + r.p2nd() + r.p3rd() <= 1.0 + 1e-9);
    }

    @Test
    void leadingInstandingsHelps() {
        List<Match> matches = List.of(match("Spain", "Bolivia", "Group A"));

        Map<String, EloRating> ratings = new HashMap<>();
        ratings.put("Spain",   EloRating.initial("Spain").withRating(2074));
        ratings.put("Bolivia", EloRating.initial("Bolivia").withRating(1480));

        // Con ventaja grande en standings
        Map<String, Integer> bigLead = standings();
        bigLead.put(StandingsSimulator.US, 50);

        MetaSimulator.MetaResult r = MetaSimulator.run(
                matches, ratings, new HashMap<>(), bigLead, rivals(), 2000, 42L);

        assertTrue(r.p1st() > 0.5, "Con ventaja de 50 pts debemos liderar: " + r.p1st());
    }
}