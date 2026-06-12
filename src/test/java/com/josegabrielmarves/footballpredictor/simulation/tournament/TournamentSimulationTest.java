package com.josegabrielmarves.footballpredictor.simulation.tournament;

import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TournamentSimulationTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Match match(String home, String away, String group) {
        Match m = new Match(0, home, away, "2026-06-01", "SCHEDULED", null, null);
        m.group = group;
        return m;
    }

    private static List<Match> roundRobin(String g, String t1, String t2, String t3, String t4) {
        return List.of(
                match(t1, t2, g), match(t3, t4, g),
                match(t1, t3, g), match(t2, t4, g),
                match(t1, t4, g), match(t2, t3, g));
    }

    // Builds 12 groups named "Group A" … "Group L" with generic team names
    private static Map<String, List<Match>> build12Groups() {
        Map<String, List<Match>> groups = new LinkedHashMap<>();
        for (char c = 'A'; c <= 'L'; c++) {
            String g = "Group " + c;
            String t1 = "T1" + c, t2 = "T2" + c, t3 = "T3" + c, t4 = "T4" + c;
            groups.put(g, roundRobin(g, t1, t2, t3, t4));
        }
        return groups;
    }

    private static Map<String, EloRating> uniformRatings(Map<String, List<Match>> groups) {
        Map<String, EloRating> r = new HashMap<>();
        for (List<Match> ms : groups.values()) {
            for (Match m : ms) {
                r.putIfAbsent(m.homeTeam, EloRating.initial(m.homeTeam));
                r.putIfAbsent(m.awayTeam, EloRating.initial(m.awayTeam));
            }
        }
        return r;
    }

    // ── GroupStanding ─────────────────────────────────────────────────────────

    @Test
    void groupStandingComputesGoalDiffAndPlayed() {
        GroupStanding s = new GroupStanding("Spain", 6, 5, 1, 2, 0, 0);
        assertEquals(4, s.goalDiff());
        assertEquals(2, s.played());
    }

    @Test
    void groupStandingSortsByPointsThenGdThenGf() {
        GroupStanding winner = new GroupStanding("A", 7, 6, 1, 2, 1, 0);
        GroupStanding second = new GroupStanding("B", 6, 4, 2, 2, 0, 1);
        GroupStanding third  = new GroupStanding("C", 4, 3, 3, 1, 1, 1);

        List<GroupStanding> list = new ArrayList<>(List.of(third, winner, second));
        Collections.sort(list);

        assertEquals("A", list.get(0).teamName());
        assertEquals("B", list.get(1).teamName());
        assertEquals("C", list.get(2).teamName());
    }

    // ── GroupExtractor ────────────────────────────────────────────────────────

    @Test
    void extractorFiltersKnockoutMatches() {
        List<Match> fixture = List.of(
                match("Spain", "Morocco", "Group A"),
                match("USA",   "Bolivia", "Group B"),
                match("Spain", "USA",     null));

        Map<String, List<Match>> groups = GroupExtractor.extractGroups(fixture);

        assertEquals(2, groups.size());
        assertTrue(groups.containsKey("Group A"));
        assertTrue(groups.containsKey("Group B"));
    }

    @Test
    void extractorFindsCorrectTeamsPerGroup() {
        List<Match> groupA = List.of(
                match("Spain", "Morocco", "Group A"),
                match("Croatia","Belgium","Group A"),
                match("Spain", "Croatia","Group A"),
                match("Morocco","Belgium","Group A"),
                match("Spain", "Belgium","Group A"),
                match("Morocco","Croatia","Group A"));

        List<String> teams = GroupExtractor.teamsInGroup(groupA);
        assertEquals(4, teams.size());
        assertTrue(teams.containsAll(List.of("Spain","Morocco","Croatia","Belgium")));
    }

    // ── GroupSimulator ────────────────────────────────────────────────────────

    @Test
    void groupSimulatorIsDeterministicWithSameSeed() {
        List<Match> groupA = roundRobin("A", "Spain","Morocco","Croatia","Bolivia");
        Map<String, EloRating> r = new HashMap<>();
        r.put("Spain",   EloRating.initial("Spain").withRating(2074));
        r.put("Morocco", EloRating.initial("Morocco").withRating(1880));
        r.put("Croatia", EloRating.initial("Croatia").withRating(1870));
        r.put("Bolivia", EloRating.initial("Bolivia").withRating(1480));

        List<GroupStanding> run1 = GroupSimulator.simulate(groupA, r, new Random(42));
        List<GroupStanding> run2 = GroupSimulator.simulate(groupA, r, new Random(42));

        assertEquals(run1.get(0).teamName(), run2.get(0).teamName());
        assertEquals(run1.get(0).points(),   run2.get(0).points());
    }

    @Test
    void strongTeamFinishesFirstMostOfTheTime() {
        List<Match> groupA = roundRobin("A","Spain","Morocco","Croatia","Bolivia");
        Map<String, EloRating> r = new HashMap<>();
        r.put("Spain",   EloRating.initial("Spain").withRating(2074));
        r.put("Morocco", EloRating.initial("Morocco").withRating(1700));
        r.put("Croatia", EloRating.initial("Croatia").withRating(1700));
        r.put("Bolivia", EloRating.initial("Bolivia").withRating(1300));

        int spainFirst = 0;
        Random rng = new Random(99);
        for (int i = 0; i < 1000; i++)
            if ("Spain".equals(GroupSimulator.simulate(groupA, r, rng).get(0).teamName())) spainFirst++;

        assertTrue(spainFirst > 500, "España debe terminar 1° >50% con rating 2074 vs 1300");
    }

    // ── TournamentGroupStage ──────────────────────────────────────────────────

    @Test
    void groupStageAdvanceSumToThirtyTwo() {
        Map<String, List<Match>> groups = build12Groups();
        Map<String, EloRating> ratings = uniformRatings(groups);

        TournamentGroupStage.GroupStageResult result =
                TournamentGroupStage.run(groups, ratings, 5_000, 42L);

        double sum = result.pAdvance().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(32.0, sum, 0.5, () -> "Suma P(avanzar) debe ser ≈ 32: " + sum);
    }

    // ── TournamentSimulator ───────────────────────────────────────────────────

    @Test
    void championProbabilitiesSumToOne() {
        Map<String, List<Match>> groups = build12Groups();
        Map<String, EloRating> ratings = uniformRatings(groups);

        TournamentSimulator.TournamentResult result =
                TournamentSimulator.run(groups, ratings, 2_000, 42L);

        double champSum = result.pChampion().values().stream().mapToDouble(Double::doubleValue).sum();
        double advSum   = result.pAdvance().values().stream().mapToDouble(Double::doubleValue).sum();

        assertEquals(1.0,  champSum, 0.02, () -> "sum(pChampion) debe ser ≈ 1.0: " + champSum);
        assertEquals(32.0, advSum,   0.5,  () -> "sum(pAdvance) debe ser ≈ 32.0: " + advSum);
        assertEquals(2_000, result.simulations());
    }

    @Test
    void strongTeamHasHigherChampionProbability() {
        // España con rating 2074 en un grupo de equipos a 1500
        Map<String, List<Match>> groups = build12Groups();
        Map<String, EloRating> ratings = uniformRatings(groups);
        // Sobrescribir España (T1A) con rating alto
        ratings.put("T1A", EloRating.initial("T1A").withRating(2074));

        TournamentSimulator.TournamentResult result =
                TournamentSimulator.run(groups, ratings, 5_000, 42L);

        double spainChamp   = result.pChampion().getOrDefault("T1A", 0.0);
        double uniformChamp = 1.0 / 48.0;
        assertTrue(spainChamp > uniformChamp * 3,
                () -> "El equipo fuerte debe tener P(campeón) mucho mayor que 1/48: " + spainChamp);
    }
}