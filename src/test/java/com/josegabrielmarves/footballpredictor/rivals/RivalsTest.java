package com.josegabrielmarves.footballpredictor.rivals;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RivalsTest {

    private static double[][] matrix() {
        EloRating h = EloRating.initial("H").withRating(1700);
        EloRating a = EloRating.initial("A").withRating(1500);
        return PoissonPredictor.scoreMatrix(h, a, 0.0);
    }

    // ── RivalSimulator ────────────────────────────────────────────────────────

    @Test
    void conservativeProfilePreddictsMaxTwoGoalsPerSide() {
        double[][] m = matrix();
        Random rng = new Random(42);
        RivalProfile profile = new RivalProfile("R", RivalProfile.Type.CONSERVATIVE);
        for (int i = 0; i < 200; i++) {
            Score s = RivalSimulator.predict(profile, m, "H", "A", rng);
            assertTrue(s.homeGoals() <= 2 && s.awayGoals() <= 2,
                    "Conservative predice máximo 2 goles por lado: " + s);
        }
    }

    @Test
    void favoriteProfilePreddictsModalScore() {
        double[][] m = matrix();
        Score modal = RivalSimulator.predictModal(m);
        RivalProfile profile = new RivalProfile("R", RivalProfile.Type.FAVORITE);
        Score pred = RivalSimulator.predict(profile, m, "H", "A", new Random(1));
        assertEquals(modal.homeGoals(), pred.homeGoals());
        assertEquals(modal.awayGoals(), pred.awayGoals());
    }

    @Test
    void fanProfileAlwaysBacksFavoriteTeam() {
        double[][] m = matrix();
        RivalProfile fan = new RivalProfile("Fan", RivalProfile.Type.FAN, "Spain");
        Score pred = RivalSimulator.predict(fan, m, "Spain", "Morocco", new Random(1));
        assertTrue(pred.homeGoals() > pred.awayGoals(),
                "Fanático de Spain predice victoria local cuando Spain es local");
    }

    @Test
    void fanProfileBacksAwayTeamWhenFavoriteIsAway() {
        double[][] m = matrix();
        RivalProfile fan = new RivalProfile("Fan", RivalProfile.Type.FAN, "Spain");
        Score pred = RivalSimulator.predict(fan, m, "Morocco", "Spain", new Random(1));
        assertTrue(pred.awayGoals() > pred.homeGoals(),
                "Fanático de Spain predice victoria visitante cuando Spain es visitante");
    }

    // ── StandingsSimulator ────────────────────────────────────────────────────

    private static Map<String, Integer> equalStandings() {
        Map<String, Integer> s = new LinkedHashMap<>();
        s.put(StandingsSimulator.US, 0);
        for (int i = 1; i <= 13; i++) s.put("Rival" + i, 0);
        return s;
    }

    private static List<RivalProfile> randomRivals() {
        List<RivalProfile> list = new ArrayList<>();
        for (int i = 1; i <= 13; i++)
            list.add(new RivalProfile("Rival" + i, RivalProfile.Type.RANDOM));
        return list;
    }

    @Test
    void simulationProducesProbabilitiesInValidRange() {
        double[][] m = matrix();
        List<StandingsSimulator.JornadaMatch> jornada = List.of(
                new StandingsSimulator.JornadaMatch("H", "A", m, new Score(1, 0)));

        StandingsSimulator.StandingsResult result = StandingsSimulator.simulate(
                equalStandings(), jornada, randomRivals(), Stage.GRUPOS, 10_000, 42L);

        // Con predicción inteligente (1-0 para local favorito) vs 13 rivales aleatorios
        // debemos ganar mucho más que el baseline 3/14 ≈ 21.4%
        assertTrue(result.pPodio() > 0.3,
                () -> "P(podio) debe superar el baseline aleatorio: " + result.pPodio());
        assertTrue(result.pPodio() <= 1.0,
                () -> "P(podio) no puede superar 1.0: " + result.pPodio());
        assertTrue(result.p1st() + result.p2nd() + result.p3rd() <= 1.0 + 1e-9,
                "Las probabilidades de posición no pueden sumar más de 1.0");
    }

    @Test
    void deterministicWithSameSeed() {
        double[][] m = matrix();
        List<StandingsSimulator.JornadaMatch> jornada = List.of(
                new StandingsSimulator.JornadaMatch("H", "A", m, new Score(1, 0)));
        Map<String, Integer> standings = equalStandings();
        List<RivalProfile> rivals = randomRivals();

        StandingsSimulator.StandingsResult r1 = StandingsSimulator.simulate(
                standings, jornada, rivals, Stage.GRUPOS, 5_000, 99L);
        StandingsSimulator.StandingsResult r2 = StandingsSimulator.simulate(
                standings, jornada, rivals, Stage.GRUPOS, 5_000, 99L);

        assertEquals(r1.pPodio(), r2.pPodio(), 1e-9);
    }

    @Test
    void leadingInStandingsIncreasesWinProbability() {
        // Si llevamos ventaja, P(1°) debe ser mayor que 1/14
        double[][] m = matrix();
        Map<String, Integer> standings = equalStandings();
        standings.put(StandingsSimulator.US, 10); // ventaja de 10 puntos

        List<StandingsSimulator.JornadaMatch> jornada = List.of(
                new StandingsSimulator.JornadaMatch("H", "A", m, new Score(1, 0)));

        StandingsSimulator.StandingsResult result = StandingsSimulator.simulate(
                standings, jornada, randomRivals(), Stage.GRUPOS, 10_000, 42L);

        assertTrue(result.p1st() > 1.0 / 14.0,
                () -> "Con ventaja, P(1°) debe ser > 1/14. Fue: " + result.p1st());
    }
}