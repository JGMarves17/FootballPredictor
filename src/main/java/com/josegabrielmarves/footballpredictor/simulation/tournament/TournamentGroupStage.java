package com.josegabrielmarves.footballpredictor.simulation.tournament;

import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;

import java.util.*;

/**
 * Simula la fase de grupos del Mundial 2026 mediante Monte Carlo:
 * 12 grupos × 4 equipos, top-2 de cada grupo avanzan + 8 mejores terceros.
 *
 * <p>Uso típico:
 * <pre>
 *   Map&lt;String, List&lt;Match&gt;&gt; groups = GroupExtractor.extractGroups(fixture);
 *   Map&lt;String, EloRating&gt; ratings = buildRatings(); // CalibratedEloRatings + updates
 *   GroupStageResult result = TournamentGroupStage.run(groups, ratings,
 *       TournamentGroupStage.DEFAULT_SIMULATIONS, 42L);
 *   result.printSummary();
 * </pre>
 */
public final class TournamentGroupStage {

    public static final int DEFAULT_SIMULATIONS = 50_000;

    /** Mejores terceros que avanzan en el formato 2026 (12 grupos → 8 terceros). */
    private static final int BEST_THIRDS_ADVANCING = 8;

    private TournamentGroupStage() {}

    /**
     * Ejecuta la simulación Monte Carlo de la fase de grupos.
     *
     * @param groups      mapa groupName → partidos (de GroupExtractor)
     * @param ratings     mapa teamName → EloRating (actualizado con resultados reales)
     * @param simulations número de simulaciones (recomendado: {@value #DEFAULT_SIMULATIONS})
     * @param seed        semilla para reproducibilidad
     */
    public static GroupStageResult run(
            Map<String, List<Match>> groups,
            Map<String, EloRating> ratings,
            int simulations,
            long seed) {

        Map<String, Integer> advanceCount  = new HashMap<>();
        Map<String, Integer> firstCount    = new HashMap<>();
        Map<String, Integer> secondCount   = new HashMap<>();
        Map<String, Integer> bestThirdCount= new HashMap<>();

        Random rng = new Random(seed);

        for (int i = 0; i < simulations; i++) {
            List<GroupStanding> allThirds = new ArrayList<>();

            for (List<Match> groupMatches : groups.values()) {
                List<GroupStanding> standings =
                        GroupSimulator.simulate(groupMatches, ratings, rng);

                // 1° y 2° avanzan directamente
                increment(firstCount,  standings.get(0).teamName());
                increment(advanceCount,standings.get(0).teamName());
                increment(secondCount, standings.get(1).teamName());
                increment(advanceCount,standings.get(1).teamName());

                // 3° va al pool de mejores terceros
                if (standings.size() > 2) {
                    allThirds.add(standings.get(2));
                }
            }

            // Seleccionar los 8 mejores terceros
            Collections.sort(allThirds);
            int advance = Math.min(BEST_THIRDS_ADVANCING, allThirds.size());
            for (int j = 0; j < advance; j++) {
                increment(bestThirdCount, allThirds.get(j).teamName());
                increment(advanceCount,   allThirds.get(j).teamName());
            }
        }

        return new GroupStageResult(
                toProbabilities(advanceCount,   simulations),
                toProbabilities(firstCount,     simulations),
                toProbabilities(secondCount,    simulations),
                toProbabilities(bestThirdCount, simulations),
                simulations);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void increment(Map<String, Integer> map, String key) {
        map.merge(key, 1, Integer::sum);
    }

    private static Map<String, Double> toProbabilities(
            Map<String, Integer> counts, int simulations) {
        Map<String, Double> probs = new LinkedHashMap<>();
        counts.forEach((k, v) -> probs.put(k, (double) v / simulations));
        return Collections.unmodifiableMap(probs);
    }

    // ── resultado ────────────────────────────────────────────────────────────

    /**
     * Resultado de la simulación de fase de grupos.
     * Todas las probabilidades son independientes por equipo.
     * Invariante: sum(pAdvance) ≈ 24 + 8 = 32 (2 por grupo + 8 mejores terceros).
     */
    public record GroupStageResult(
            Map<String, Double> pAdvance,
            Map<String, Double> p1st,
            Map<String, Double> p2nd,
            Map<String, Double> pBestThird,
            int simulations
    ) {
        /** Imprime un resumen ordenado por P(avanzar) descendente. */
        public void printSummary() {
            System.out.printf("%n=== Fase de grupos — %,d simulaciones ===%n", simulations);
            pAdvance.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .forEach(e -> System.out.printf(
                            "  %-22s  P(avanzar)=%5.1f%%  (1°=%5.1f%%  2°=%5.1f%%  3°best=%5.1f%%)%n",
                            e.getKey(),
                            e.getValue() * 100,
                            p1st.getOrDefault(e.getKey(), 0.0) * 100,
                            p2nd.getOrDefault(e.getKey(), 0.0) * 100,
                            pBestThird.getOrDefault(e.getKey(), 0.0) * 100));
        }
    }
}