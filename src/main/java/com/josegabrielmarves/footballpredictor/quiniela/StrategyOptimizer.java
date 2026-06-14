package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import com.josegabrielmarves.footballpredictor.rivals.RivalProfile;
import com.josegabrielmarves.footballpredictor.rivals.StandingsSimulator;
import com.josegabrielmarves.footballpredictor.rivals.StandingsSimulator.JornadaMatch;
import com.josegabrielmarves.footballpredictor.rivals.StandingsSimulator.StandingsResult;

import java.util.*;

/**
 * Optimizador de estrategia para una jornada completa (Fase 10).
 *
 * A diferencia de MatchEV (Fase 8, que maximiza EV por partido individual),
 * StrategyOptimizer maximiza P(podio) considerando:
 * - La clasificación actual y la de los rivales.
 * - Los perfiles de predicción de los 13 rivales (Fase 9).
 * - Las combinaciones de predicciones para la jornada completa.
 *
 * Algoritmo: evalúa las top-K candidatos por partido (de MatchEV.rank),
 * genera todas las combinaciones y elige la que maximiza P(podio).
 * Con K=3 y 2 partidos = 9 combinaciones; con 6 partidos = 729.
 */
public final class StrategyOptimizer {

    /** Partido de la jornada para el optimizador. */
    public record StrategyMatch(
            String homeTeam, EloRating home,
            String awayTeam, EloRating away,
            double homeBonus) {}

    /** Resultado de la optimización. */
    public record OptimizationResult(
            List<Score> predictions,
            double pPodio,
            double p1st, double p2nd, double p3rd,
            double expectedPosition,
            int combinationsEvaluated) {

        public void print(List<StrategyMatch> matches) {
            System.out.printf("%n=== Strategy Optimizer — %d combinaciones evaluadas ===%n",
                    combinationsEvaluated);
            System.out.printf("  P(podio) óptimo = %.1f%%  " +
                            "(P1°=%.1f%%  P2°=%.1f%%  P3°=%.1f%%)%n",
                    pPodio*100, p1st*100, p2nd*100, p3rd*100);
            System.out.printf("  Posición esperada = %.2f / 14%n%n", expectedPosition());
            System.out.println("  Predicciones óptimas:");
            for (int i = 0; i < matches.size(); i++) {
                StrategyMatch m = matches.get(i);
                Score p = predictions.get(i);
                System.out.printf("    %-22s vs %-22s → %d-%d%n",
                        m.homeTeam(), m.awayTeam(), p.homeGoals(), p.awayGoals());
            }
        }
    }

    private StrategyOptimizer() {}

    /**
     * Optimiza las predicciones de la jornada para maximizar P(podio).
     *
     * @param matches       partidos de la jornada
     * @param standings     clasificación actual (debe incluir {@link StandingsSimulator#US})
     * @param rivals        13 perfiles de rivales
     * @param stage         fase del torneo
     * @param topK          candidatos por partido a evaluar (recomendado: 3)
     * @param simPerCombo   simulaciones por combinación (recomendado: 2000)
     * @param seed          semilla para reproducibilidad
     */
    public static OptimizationResult optimize(
            List<StrategyMatch> matches,
            Map<String, Integer> standings,
            List<RivalProfile> rivals,
            Stage stage,
            int topK, int simPerCombo, long seed) {

        // Precalcular matrices (evitar recomputar en el loop)
        double[][][] matrices = new double[matches.size()][][];
        for (int i = 0; i < matches.size(); i++) {
            StrategyMatch m = matches.get(i);
            matrices[i] = PoissonPredictor.scoreMatrix(m.home(), m.away(), m.homeBonus());
        }

        // Top-K candidatos por partido según EV de puntos
        List<List<Score>> candidatesPerMatch = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            StrategyMatch m = matches.get(i);
            List<Score> cands = MatchEV.rank(m.home(), m.away(), m.homeBonus(), stage)
                    .stream().limit(topK).map(MatchEV.Candidate::score).toList();
            candidatesPerMatch.add(cands);
        }

        // Enumerar todas las combinaciones
        List<List<Score>> combos = combinations(candidatesPerMatch);
        OptimizationResult best = null;

        for (List<Score> combo : combos) {
            List<JornadaMatch> jornada = new ArrayList<>();
            for (int i = 0; i < matches.size(); i++) {
                StrategyMatch m = matches.get(i);
                jornada.add(new JornadaMatch(
                        m.homeTeam(), m.awayTeam(), matrices[i], combo.get(i)));
            }

            StandingsResult r = StandingsSimulator.simulate(
                    standings, jornada, rivals, stage,
                    simPerCombo, seed + Math.abs(combo.hashCode()));

            if (best == null || r.pPodio() > best.pPodio()) {
                best = new OptimizationResult(
                        new ArrayList<>(combo),
                        r.pPodio(), r.p1st(), r.p2nd(), r.p3rd(),
                        r.expectedPosition(), combos.size());
            }
        }

        return best;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<List<Score>> combinations(List<List<Score>> lists) {
        List<List<Score>> result = new ArrayList<>();
        combine(lists, 0, new ArrayList<>(), result);
        return result;
    }

    private static void combine(List<List<Score>> lists, int idx,
                                List<Score> current, List<List<Score>> result) {
        if (idx == lists.size()) { result.add(new ArrayList<>(current)); return; }
        for (Score s : lists.get(idx)) {
            current.add(s);
            combine(lists, idx + 1, current, result);
            current.remove(current.size() - 1);
        }
    }
}