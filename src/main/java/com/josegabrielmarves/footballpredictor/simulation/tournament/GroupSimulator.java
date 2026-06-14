package com.josegabrielmarves.footballpredictor.simulation.tournament;

import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

import java.util.*;

/**
 * Simula UNA VEZ los 6 partidos de un grupo de fase de grupos y devuelve
 * el standing final ordenado (1°→4°).
 *
 * <p>Ventaja de local: solo se aplica a las naciones anfitrionas del Mundial
 * 2026 ({@link #HOST_NATIONS}) cuando juegan como equipo local en el fixture.
 * Resto de partidos: cancha neutral.
 *
 * <p>Tiebreaker: puntos → DG → GF → nombre de equipo (determinista;
 * no se aplica head-to-head en MC, el impacto es negligible en 50k sim).
 */
public final class GroupSimulator {

    /** Naciones anfitrionas del Mundial 2026 con ventaja de local. */
    static final Set<String> HOST_NATIONS = Set.of(
            "Mexico", "United States", "USA", "Canada");

    private GroupSimulator() {}

    /**
     * Simula un grupo completo una vez.
     *
     * @param groupMatches los 6 partidos del grupo (del fixture real)
     * @param ratings      mapa teamName → EloRating (con ratings actualizados)
     * @param rng          generador de números aleatorios (seedable para reproducibilidad)
     * @return standings ordenados de 1° a 4°
     */
    public static List<GroupStanding> simulate(
            List<Match> groupMatches,
            Map<String, EloRating> ratings,
            Random rng) {

        // [points, gf, ga, wins, draws, losses]
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (Match m : groupMatches) {
            stats.putIfAbsent(m.homeTeam, new int[6]);
            stats.putIfAbsent(m.awayTeam, new int[6]);
        }

        for (Match m : groupMatches) {
            EloRating home = ratings.getOrDefault(m.homeTeam, EloRating.initial(m.homeTeam));
            EloRating away = ratings.getOrDefault(m.awayTeam, EloRating.initial(m.awayTeam));
            double homeBonus = HOST_NATIONS.contains(m.homeTeam)
                    ? EloCalculator.HOME_ADVANTAGE : 0.0;

            double[][] matrix = PoissonPredictor.scoreMatrix(home, away, homeBonus);
            Score score = sampleScore(matrix, rng);

            applyResult(stats.get(m.homeTeam), score.homeGoals(), score.awayGoals());
            applyResult(stats.get(m.awayTeam), score.awayGoals(), score.homeGoals());
        }

        List<GroupStanding> standings = new ArrayList<>();
        for (Map.Entry<String, int[]> e : stats.entrySet()) {
            int[] s = e.getValue();
            standings.add(new GroupStanding(e.getKey(),
                    s[0], s[1], s[2], s[3], s[4], s[5]));
        }
        Collections.sort(standings);
        return standings;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void applyResult(int[] stats, int gf, int ga) {
        stats[1] += gf;
        stats[2] += ga;
        if      (gf > ga) { stats[0] += 3; stats[3]++; }
        else if (gf == ga){ stats[0] += 1; stats[4]++; }
        else              {               stats[5]++; }
    }

    /**
     * Muestrea un marcador de la matriz de probabilidades usando sorteo
     * acumulativo. Equivalente a MonteCarloSimulator.sample pero con el
     * RNG externo del simulador (necesario para reproducibilidad seedable).
     */
    public static Score sampleScore(double[][] matrix, Random rng) {
        double r = rng.nextDouble();
        double cum = 0.0;
        for (int h = 0; h < matrix.length; h++) {
            for (int a = 0; a < matrix[h].length; a++) {
                cum += matrix[h][a];
                if (r <= cum) return new Score(h, a);
            }
        }
        return new Score(matrix.length - 1, matrix.length - 1);
    }
}