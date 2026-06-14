package com.josegabrielmarves.footballpredictor.rivals;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer;
import com.josegabrielmarves.footballpredictor.simulation.tournament.GroupSimulator;

import java.util.*;

/**
 * Simula la clasificación final de la quiniela con Monte Carlo.
 *
 * Para cada simulación:
 * 1. Genera el resultado real de cada partido (muestra de la matriz Poisson).
 * 2. Genera las predicciones de los 13 rivales según sus perfiles.
 * 3. Puntúa a todos y actualiza el ranking.
 * 4. Registra la posición de "Nosotros".
 *
 * Devuelve P(1°), P(2°), P(3°) y P(podio) para el usuario.
 *
 * Uso típico:
 * <pre>
 *   Map&lt;String, Integer&gt; standings = new LinkedHashMap&lt;&gt;();
 *   standings.put(StandingsSimulator.US, 1); // nuestros puntos actuales
 *   standings.put("Rival1", 3);
 *   // ... los 13 rivales
 *
 *   List&lt;JornadaMatch&gt; jornada = List.of(
 *       new JornadaMatch("Mexico", "Qatar", matrix, new Score(2, 0))
 *   );
 *
 *   StandingsResult result = StandingsSimulator.simulate(
 *       standings, jornada, rivalProfiles, Stage.GRUPOS, 50_000, 42L);
 *   result.print();
 * </pre>
 */
public final class StandingsSimulator {

    /** Nombre reservado para el usuario en el mapa de puntos. */
    public static final String US = "Nosotros";

    public static final int DEFAULT_SIMULATIONS = 50_000;

    private StandingsSimulator() {}

    /**
     * Partido de la jornada con toda la info necesaria para la simulación.
     *
     * @param homeTeam      equipo local
     * @param awayTeam      equipo visitante
     * @param matrix        matriz Poisson para simular el resultado real
     * @param ourPrediction nuestra predicción
     */
    public record JornadaMatch(
            String homeTeam, String awayTeam,
            double[][] matrix, Score ourPrediction) {}

    /**
     * Resultado de la simulación: probabilidades de posición para nosotros.
     */
    public record StandingsResult(
            double p1st, double p2nd, double p3rd, double pPodio,
            double expectedPosition, int simulations) {

        public void print() {
            System.out.printf(
                    "%n=== Clasificación simulada (%,d iteraciones, 14 participantes) ===%n" +
                            "  P(1°)    = %5.1f%%%n" +
                            "  P(2°)    = %5.1f%%%n" +
                            "  P(3°)    = %5.1f%%%n" +
                            "  ─────────────────%n" +
                            "  P(podio) = %5.1f%%%n" +
                            "  Posición esperada = %.2f / 14%n" +
                            "  Base sin modelo  ≈ 21.4%% (3/14)%n",
                    simulations,
                    p1st*100, p2nd*100, p3rd*100, pPodio*100, expectedPosition);
        }
    }

    /**
     * Corre la simulación de clasificación.
     *
     * @param currentPoints puntos actuales de los 14 participantes;
     *                      incluir {@value #US} como clave para nosotros
     * @param jornada       partidos de la jornada con nuestras predicciones
     * @param rivalProfiles 13 perfiles de rivales (nombres = claves en currentPoints)
     * @param stage         fase del torneo (afecta la tabla de puntos)
     * @param simulations   número de simulaciones
     * @param seed          semilla
     */
    public static StandingsResult simulate(
            Map<String, Integer> currentPoints,
            List<JornadaMatch> jornada,
            List<RivalProfile> rivalProfiles,
            QuinielaScorer.Stage stage,
            int simulations, long seed) {

        Random rng = new Random(seed);
        int[] posCount = new int[14];
        long posSum = 0;

        // Precalcular nuestras predicciones
        List<Score> ourPreds = jornada.stream().map(JornadaMatch::ourPrediction).toList();

        for (int sim = 0; sim < simulations; sim++) {

            // 1. Simular resultados reales
            Score[] actuals = new Score[jornada.size()];
            for (int i = 0; i < jornada.size(); i++)
                actuals[i] = GroupSimulator.sampleScore(jornada.get(i).matrix(), rng);

            // 2. Puntuar todos
            Map<String, Integer> totals = new HashMap<>();
            for (Map.Entry<String, Integer> e : currentPoints.entrySet())
                totals.put(e.getKey(), e.getValue());

            // Nuestros puntos de jornada
            totals.merge(US, calcPoints(actuals, ourPreds, stage), Integer::sum);

            // Puntos de rivales
            for (RivalProfile rival : rivalProfiles) {
                List<Score> preds = new ArrayList<>();
                for (JornadaMatch m : jornada)
                    preds.add(RivalSimulator.predict(
                            rival, m.matrix(), m.homeTeam(), m.awayTeam(), rng));
                totals.merge(rival.name(), calcPoints(actuals, preds, stage), Integer::sum);
            }

            // 3. Posición de US
            int pos = rankOf(totals, US);
            posCount[pos - 1]++;
            posSum += pos;
        }

        double p1 = (double) posCount[0] / simulations;
        double p2 = (double) posCount[1] / simulations;
        double p3 = (double) posCount[2] / simulations;
        return new StandingsResult(p1, p2, p3, p1+p2+p3,
                (double) posSum / simulations, simulations);
    }

    /** Calcula los puntos de quiniela para una lista de predicciones vs resultados reales. */
    private static int calcPoints(Score[] actuals, List<Score> preds, QuinielaScorer.Stage stage) {
        int pts = 0;
        for (int i = 0; i < actuals.length; i++) {
            Score pred = preds.get(i), actual = actuals[i];
            boolean exact  = pred.homeGoals() == actual.homeGoals()
                    && pred.awayGoals() == actual.awayGoals();
            boolean result = sign(pred) == sign(actual);
            if (exact)        pts += QuinielaScorer.pointsExact(stage);
            else if (result)  pts += QuinielaScorer.pointsResult(stage);
        }
        return pts;
    }

    private static int sign(Score s) {
        return Integer.compare(s.homeGoals(), s.awayGoals());
    }

    /** Posición de 'name' en el ranking total (1 = primero). */
    private static int rankOf(Map<String, Integer> totals, String name) {
        int score = totals.getOrDefault(name, 0);
        int pos = 1;
        for (Map.Entry<String, Integer> e : totals.entrySet())
            if (!e.getKey().equals(name) && e.getValue() > score) pos++;
        return pos;
    }
}