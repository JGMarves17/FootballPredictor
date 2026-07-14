package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.EnsemblePredictor;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaRanking.Tally;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import com.josegabrielmarves.footballpredictor.rivals.RivalProfile;
import com.josegabrielmarves.footballpredictor.rivals.RivalSimulator;
import com.josegabrielmarves.footballpredictor.rivals.StandingsSimulator;
import com.josegabrielmarves.footballpredictor.simulation.tournament.GroupSimulator;

import java.util.*;

/**
 * Optimizador de estrategia ultrarrápido para una jornada completa.
 * <p>
 * Usa CRN (Common Random Numbers) para evaluar hasta 531k combinaciones de
 * predicciones en ~3-5 segundos en vez de 10+ minutos.
 * <p>
 * Algoritmo:
 * <ol>
 *   <li><b>Thresholds</b> (5000 sims, UNA VEZ): para cada simulación s, simula
 *       los resultados reales y las predicciones de los 16 rivales. Registra los
 *       puntos totales de cada rival ordenados → thresholds t1[s], t2[s], t3[s].</li>
 *   <li><b>Deltas</b> (12 partidos × 3 candidatos × 5000 sims): para cada
 *       (sim, partido, candidato) codifica en 1 byte los puntos obtenidos.</li>
 *   <li><b>Evaluación</b> (531k combos): combo = número en base K. Para cada
 *       sim: ourPts = base + sum(deltas) → comparar con thresholds → P(1°) etc.</li>
 * </ol>
 */
public final class FastStrategyOptimizer {

    /** Partido de la jornada (misma firma que StrategyOptimizer.StrategyMatch). */
    public record StrategyMatch(
            String homeTeam, EloRating home,
            String awayTeam, EloRating away,
            double homeBonus) {}

    /** Objetivo de la optimización. */
    public enum Objective {
        P1_FIRST, P2_SECOND, P3_THIRD, PODIUM, EXPECTED_PAYOUT
    }

    /** Resultado de la optimización. */
    public record OptimizationResult(
            List<Score> predictions,
            double pPodio,
            double p1st, double p2nd, double p3rd,
            double expectedPosition,
            double expectedPayout,
            int participants,
            int combinationsEvaluated) {

        public void print(List<StrategyMatch> matches) {
            System.out.printf("%n=== FastStrategyOptimizer — %d combinaciones evaluadas ===%n",
                    combinationsEvaluated);
            System.out.printf("  EV de premio = %.1f%% del pozo  " +
                            "(P1\u00B0=%.1f%%  P2\u00B0=%.1f%%  P3\u00B0=%.1f%%  \u2192  P(podio)=%.1f%%)%n",
                    expectedPayout * 100, p1st * 100, p2nd * 100, p3rd * 100, pPodio * 100);
            System.out.printf("  Posici\u00F3n esperada = %.2f / %d%n%n", expectedPosition(), participants());
            System.out.println("  Predicciones \u00F3ptimas:");
            for (int i = 0; i < matches.size(); i++) {
                StrategyMatch m = matches.get(i);
                Score p = predictions.get(i);
                System.out.printf("    %-22s vs %-22s \u2192 %d-%d%n",
                        m.homeTeam(), m.awayTeam(), p.homeGoals(), p.awayGoals());
            }
        }
    }

    /** Datos precomputados (para reutilización en análisis). */
    public record ThresholdPrecomputation(
            int[] ptsFor1st, int[] ptsFor2nd, int[] ptsFor3rd,
            int basePoints, int simulations) {

        public int median(int[] arr) {
            int[] sorted = arr.clone();
            Arrays.sort(sorted);
            return sorted[sorted.length / 2];
        }
    }

    /** Reporte legible de thresholds. */
    public record ThresholdReport(
            int thresholdP1, int thresholdP2, int thresholdP3,
            double p1, double p2, double p3) {

        public void print(int basePoints) {
            System.out.printf("%n=== An\u00E1lisis de Thresholds ===%n");
            if (thresholdP1 > 0) {
                System.out.printf("  Para P(1\u00B0) necesitas +%d pts  (base=%d)  \u2192 P(1\u00B0) actual = %.1f%%%n",
                        thresholdP1, basePoints, p1 * 100);
            } else {
                System.out.printf("  Ya est\u00E1s en P(1\u00B0) en %.1f%% de las sims  (base=%d)%n",
                        p1 * 100, basePoints);
            }
            if (thresholdP2 > 0) {
                System.out.printf("  Para P(2\u00B0) necesitas +%d pts               \u2192 P(2\u00B0) actual = %.1f%%%n",
                        thresholdP2, p2 * 100);
            } else {
                System.out.printf("  Ya est\u00E1s en P(2\u00B0) en %.1f%% de las sims%n", p2 * 100);
            }
            if (thresholdP3 > 0) {
                System.out.printf("  Para P(3\u00B0) necesitas +%d pts               \u2192 P(3\u00B0) actual = %.1f%%%n",
                        thresholdP3, p3 * 100);
            } else {
                System.out.printf("  Ya est\u00E1s en P(3\u00B0) en %.1f%% de las sims%n", p3 * 100);
            }
            System.out.println();
        }
    }

    public static final double PRIZE_1ST = 0.60;
    public static final double PRIZE_2ND = 0.30;
    public static final double PRIZE_3RD = 0.10;

    private FastStrategyOptimizer() {}

    // ──────────────────────────────────────────────────────────────────────────
    //  API pública
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Optimiza las predicciones de la jornada usando el algoritmo CRN.
     *
     * @param matches   partidos
     * @param standings clasificación actual (incluir {@link StandingsSimulator#US})
     * @param rivals    perfiles de rivales
     * @param stage     fase del torneo
     * @param topK      candidatos por partido (recomendado: 3)
     * @param sims      simulaciones (recomendado: 5000)
     * @param seed      semilla CRN
     * @param objective objetivo
     */
    public static OptimizationResult optimize(
            List<StrategyMatch> matches,
            Map<String, Integer> standings,
            List<RivalProfile> rivals,
            Stage stage,
            int topK, int sims, long seed,
            Objective objective) {
        return optimize(matches, standings, rivals, stage, topK, sims, seed, objective, null);
    }

    /**
     * Igual que {@link #optimize(List, Map, List, Stage, int, int, long, Objective)} pero,
     * cuando se provee un {@link EnsemblePredictor} con odds de mercado disponibles, ajusta
     * las matrices y el ranking de candidatos hacia las probabilidades implícitas del mercado
     * (The Odds API) en vez de usar solo el modelo propio.
     *
     * @param ep EnsemblePredictor con acceso a odds, o {@code null} para usar solo el modelo
     */
    public static OptimizationResult optimize(
            List<StrategyMatch> matches,
            Map<String, Integer> standings,
            List<RivalProfile> rivals,
            Stage stage,
            int topK, int sims, long seed,
            Objective objective,
            EnsemblePredictor ep) {

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No hay partidos para optimizar.");
        }

        final int M = matches.size();
        final int S = sims;
        final int basePts = standings.getOrDefault(StandingsSimulator.US, 0);
        final boolean knockout = stage != Stage.GRUPOS;
        final int ptsResult = QuinielaScorer.pointsResult(stage);
        final int ptsExact  = QuinielaScorer.pointsExact(stage);
        final int numRivals = rivals.size();

        // ── 1. Matrices Poisson (ajustadas a mercado si hay odds disponibles) ────
        double[][][] matrices = new double[M][][];
        for (int i = 0; i < M; i++) {
            StrategyMatch m = matches.get(i);
            matrices[i] = ep != null
                    ? PoissonPredictor.scoreMatrixTournamentWithMarket(
                            m.homeTeam(), m.home(), m.awayTeam(), m.away(), m.homeBonus(), stage, ep)
                    : PoissonPredictor.scoreMatrixTournament(
                            m.homeTeam(), m.home(), m.awayTeam(), m.away(), m.homeBonus(), stage);
        }

        // ── 2. Top-K candidatos por partido (modelo torneo: triple-blend + xG + GLM + H2H + Descanso + mercado) ──
        List<List<Score>> candidates = new ArrayList<>(M);
        for (int i = 0; i < M; i++) {
            StrategyMatch m = matches.get(i);
            List<MatchEV.Candidate> ranked = ep != null
                    ? MatchEV.rankTournamentWithMarket(m.homeTeam(), m.home(), m.awayTeam(), m.away(), m.homeBonus(), stage, ep)
                    : MatchEV.rankTournament(m.homeTeam(), m.home(), m.awayTeam(), m.away(), m.homeBonus(), stage);
            candidates.add(ranked.stream().limit(topK).map(MatchEV.Candidate::score).toList());
        }
        int K = candidates.stream().mapToInt(List::size).min().orElse(topK);

        // ── 3. Precomputación (UNA SOLA VEZ para todas las combos) ──────────
        // rivalPoints[s][r] = puntos finales del rival r en sim s (orden descendente)
        int[][] rivalPoints = new int[S][numRivals];

        // deltas[s][i][k] = byte: bits 0-3 = puntos, bit 4 = exact flag
        byte[][][] deltas = new byte[S][M][K];

        Random rng = new Random(seed);

        for (int s = 0; s < S; s++) {
            // 3a. Resultados reales
            Score[] actuals = new Score[M];
            for (int i = 0; i < M; i++) {
                actuals[i] = GroupSimulator.sampleScore(matrices[i], rng);
            }

            // 3b. Puntos finales de cada rival (base + jornada)
            Map<String, Integer> rivalTotals = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : standings.entrySet()) {
                String name = e.getKey();
                if (!name.equals(StandingsSimulator.US)) {
                    rivalTotals.put(name, e.getValue());
                }
            }

            for (int ri = 0; ri < numRivals; ri++) {
                RivalProfile rival = rivals.get(ri);
                int total = rivalTotals.get(rival.name());
                for (int i = 0; i < M; i++) {
                    Score pred = RivalSimulator.predict(
                            rival, matrices[i],
                            matches.get(i).homeTeam(), matches.get(i).awayTeam(), rng);
                    Score actual = actuals[i];
                    boolean exact = pred.homeGoals() == actual.homeGoals()
                            && pred.awayGoals() == actual.awayGoals();
                    boolean result = Integer.compare(pred.homeGoals(), pred.awayGoals())
                            == Integer.compare(actual.homeGoals(), actual.awayGoals());
                    if (exact)       total += ptsExact;
                    else if (result) total += ptsResult;
                }
                rivalTotals.put(rival.name(), total);
            }

            // 3c. Ordenar puntos de rivales descendentemente
            int[] rp = rivalTotals.values().stream()
                    .mapToInt(Integer::intValue)
                    .boxed()
                    .sorted(Comparator.reverseOrder())
                    .mapToInt(Integer::intValue)
                    .toArray();
            System.arraycopy(rp, 0, rivalPoints[s], 0, numRivals);

            // 3d. Deltas por (match, candidato)
            for (int i = 0; i < M; i++) {
                for (int k = 0; k < K; k++) {
                    Score pick = candidates.get(i).get(k);
                    boolean exact = pick.homeGoals() == actuals[i].homeGoals()
                            && pick.awayGoals() == actuals[i].awayGoals();
                    boolean result = Integer.compare(pick.homeGoals(), pick.awayGoals())
                            == Integer.compare(actuals[i].homeGoals(), actuals[i].awayGoals());
                    int pts = exact ? ptsExact : result ? ptsResult : 0;
                    deltas[s][i][k] = (byte) (pts | (exact ? 0x10 : 0));
                }
            }
        }

        // ── 4. Evaluar combinaciones ────────────────────────────────────────
        int totalCombos = 1;
        for (int i = 0; i < M; i++) totalCombos *= K;

        // Potencias de K para descomposición rápida de comboIdx
        int[] pow = new int[M];
        pow[M - 1] = 1;
        for (int i = M - 2; i >= 0; i--) {
            pow[i] = pow[i + 1] * K;
        }

        OptimizationResult best = null;
        double bestScore = -Double.MAX_VALUE;

        int[] choices = new int[M];
        for (int comboIdx = 0; comboIdx < totalCombos; comboIdx++) {
            // Descomponer comboIdx en base K
            int tmp = comboIdx;
            for (int i = 0; i < M; i++) {
                choices[i] = tmp / pow[i];
                tmp %= pow[i];
            }

            int cnt1 = 0, cnt2 = 0, cnt3 = 0;
            long posSum = 0;

            for (int s = 0; s < S; s++) {
                int ourPts = basePts;
                for (int i = 0; i < M; i++) {
                    ourPts += (deltas[s][i][choices[i]] & 0x0F);
                }

                // Posición: contar rivales con estrictamente más puntos
                int ahead = 0;
                for (int ri = 0; ri < numRivals; ri++) {
                    if (rivalPoints[s][ri] > ourPts) ahead++;
                    else break; // orden descendente
                }
                int pos = ahead + 1;

                if (pos == 1)       cnt1++;
                else if (pos == 2)  cnt2++;
                else if (pos == 3)  cnt3++;
                posSum += pos;
            }

            double p1 = (double) cnt1 / S;
            double p2 = (double) cnt2 / S;
            double p3 = (double) cnt3 / S;
            double pPodio = p1 + p2 + p3;
            double expectedPos = (double) posSum / S;
            double payout = PRIZE_1ST * p1 + PRIZE_2ND * p2 + PRIZE_3RD * p3;

            double score = switch (objective) {
                case P1_FIRST       -> p1;
                case P2_SECOND      -> p2;
                case P3_THIRD       -> p3;
                case PODIUM         -> pPodio;
                case EXPECTED_PAYOUT -> payout;
            };

            if (score > bestScore) {
                bestScore = score;
                best = new OptimizationResult(
                        buildPredictions(candidates, choices),
                        pPodio, p1, p2, p3, expectedPos, payout,
                        standings.size(), totalCombos);
            }
        }

        return best;
    }

    /**
     * Versión ALL-IN: maximiza P(1°) con más candidatos por partido.
     */
    public static OptimizationResult optimizeAllIn(
            List<StrategyMatch> matches,
            Map<String, Integer> standings,
            List<RivalProfile> rivals,
            Stage stage,
            int topK, int sims, long seed) {
        int allInTopK = Math.max(5, (int) Math.ceil(topK * 1.5));
        return optimize(matches, standings, rivals, stage,
                allInTopK, sims, seed, Objective.P1_FIRST);
    }

    /**
     * Analiza los thresholds (puntos necesarios para 1°, 2°, 3°) sin
     * evaluar combinaciones.
     *
     * @return reporte con la mediana de puntos adicionales necesarios y la
     *         probabilidad actual de cada puesto (basada en rivales, sin
     *         optimización de picks)
     */
    public static ThresholdReport analyzeThresholds(
            List<StrategyMatch> matches,
            Map<String, Integer> standings,
            List<RivalProfile> rivals,
            Stage stage,
            int sims, long seed) {

        final int M = matches.size();
        final int S = sims;
        final int basePts = standings.getOrDefault(StandingsSimulator.US, 0);
        final int ptsResult = QuinielaScorer.pointsResult(stage);
        final int ptsExact  = QuinielaScorer.pointsExact(stage);
        final int numRivals = rivals.size();

        double[][][] matrices = new double[M][][];
        for (int i = 0; i < M; i++) {
            StrategyMatch m = matches.get(i);
            matrices[i] = PoissonPredictor.scoreMatrixTournament(
                    m.homeTeam(), m.home(), m.awayTeam(), m.away(), m.homeBonus(), stage);
        }

        Random rng = new Random(seed);

        int[] need1 = new int[S];
        int[] need2 = new int[S];
        int[] need3 = new int[S];
        int count1 = 0, count2 = 0, count3 = 0;

        for (int s = 0; s < S; s++) {
            Score[] actuals = new Score[M];
            for (int i = 0; i < M; i++) {
                actuals[i] = GroupSimulator.sampleScore(matrices[i], rng);
            }

            Map<String, Integer> rivalTotals = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : standings.entrySet()) {
                String name = e.getKey();
                if (!name.equals(StandingsSimulator.US)) {
                    rivalTotals.put(name, e.getValue());
                }
            }

            for (int ri = 0; ri < numRivals; ri++) {
                RivalProfile rival = rivals.get(ri);
                int total = rivalTotals.get(rival.name());
                for (int i = 0; i < M; i++) {
                    Score pred = RivalSimulator.predict(
                            rival, matrices[i],
                            matches.get(i).homeTeam(), matches.get(i).awayTeam(), rng);
                    Score actual = actuals[i];
                    boolean exact = pred.homeGoals() == actual.homeGoals()
                            && pred.awayGoals() == actual.awayGoals();
                    boolean result = Integer.compare(pred.homeGoals(), pred.awayGoals())
                            == Integer.compare(actual.homeGoals(), actual.awayGoals());
                    if (exact)       total += ptsExact;
                    else if (result) total += ptsResult;
                }
                rivalTotals.put(rival.name(), total);
            }

            int[] sorted = rivalTotals.values().stream()
                    .mapToInt(Integer::intValue)
                    .boxed()
                    .sorted(Comparator.reverseOrder())
                    .mapToInt(Integer::intValue)
                    .toArray();

            // Puntos adicionales necesarios: topRivalPts - basePts + 1
            if (sorted.length > 0) {
                int n1 = sorted[0] - basePts + 1;
                need1[s] = Math.max(0, n1);
                if (n1 <= 0) count1++;
            }
            if (sorted.length > 1) {
                int n2 = sorted[1] - basePts + 1;
                need2[s] = Math.max(0, n2);
                if (n2 <= 0) count2++;
            }
            if (sorted.length > 2) {
                int n3 = sorted[2] - basePts + 1;
                need3[s] = Math.max(0, n3);
                if (n3 <= 0) count3++;
            }
        }

        // Mediana de puntos necesarios
        Arrays.sort(need1); int med1 = need1[S / 2];
        Arrays.sort(need2); int med2 = need2[S / 2];
        Arrays.sort(need3); int med3 = need3[S / 2];

        return new ThresholdReport(
                med1, med2, med3,
                (double) count1 / S, (double) count2 / S, (double) count3 / S);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<Score> buildPredictions(
            List<List<Score>> candidates, int[] choices) {
        List<Score> result = new ArrayList<>(choices.length);
        for (int i = 0; i < choices.length; i++) {
            result.add(candidates.get(i).get(choices[i]));
        }
        return result;
    }
}

