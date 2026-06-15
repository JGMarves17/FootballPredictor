package com.josegabrielmarves.footballpredictor.rivals;

import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.MatchEV;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer;
import com.josegabrielmarves.footballpredictor.quiniela.StageDetector;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.simulation.tournament.GroupSimulator;

import java.util.*;

/**
 * Meta Simulator (Fase 12) — proyecta la clasificación FINAL de la quiniela
 * al terminar el torneo completo.
 *
 * Para cada simulación:
 * 1. Recorre todas las jornadas restantes del torneo (matches sin resultado).
 * 2. Para cada partido: simula el resultado real (Poisson) y genera las
 *    predicciones de los 14 participantes (nosotros + 13 rivales).
 * 3. Acumula puntos durante todo el torneo.
 * 4. Devuelve la distribución de posición final para "Nosotros".
 *
 * Diferencia vs StandingsSimulator: ese simula UNA jornada.
 * MetaSimulator simula el TORNEO COMPLETO restante.
 */
public final class MetaSimulator {

    public static final int DEFAULT_SIMULATIONS = 10_000;

    private MetaSimulator() {}

    /**
     * Resultado de la meta-simulación.
     */
    public record MetaResult(
            double p1st, double p2nd, double p3rd, double pPodio,
            double expectedPosition, int simulations) {

        public void print() {
            System.out.printf(
                    "%n=== Meta Simulator — torneo completo (%,d simulaciones) ===%n" +
                            "  P(1°)    = %5.1f%%%n" +
                            "  P(2°)    = %5.1f%%%n" +
                            "  P(3°)    = %5.1f%%%n" +
                            "  ─────────────────────%n" +
                            "  P(podio) = %5.1f%%%n" +
                            "  Posición esperada = %.2f / 14%n" +
                            "  Base sin modelo  ≈ 21.4%% (3/14)%n" +
                            "  Ventaja del modelo: %+.1f%%%n",
                    simulations,
                    p1st*100, p2nd*100, p3rd*100, pPodio*100,
                    expectedPosition,
                    (pPodio - 3.0/14.0)*100);
        }
    }

    /**
     * Corre la meta-simulación del torneo completo.
     *
     * @param remainingMatches  partidos sin resultado (score == null)
     * @param ratings           ratings actualizados con resultados jugados
     * @param ourPredictions    nuestras predicciones para los partidos restantes
     *                          (clave: "homeTeam vs awayTeam", valor: Score)
     * @param currentStandings  clasificación actual (incluye "Nosotros")
     * @param rivalProfiles     13 perfiles de rivales
     * @param simulations       número de simulaciones
     * @param seed              semilla
     */
    public static MetaResult run(
            List<Match> remainingMatches,
            Map<String, EloRating> ratings,
            Map<String, int[]> ourPredictions,
            Map<String, Integer> currentStandings,
            List<RivalProfile> rivalProfiles,
            int simulations, long seed) {

        Random rng = new Random(seed);
        int[] posCount = new int[14];
        long posSum = 0;

        for (int sim = 0; sim < simulations; sim++) {
            Map<String, Integer> totals = new HashMap<>(currentStandings);

            for (Match m : remainingMatches) {
                // Stage del partido
                QuinielaScorer.Stage stage = StageDetector.detect(m);

                // Ratings
                EloRating home = ratings.getOrDefault(m.homeTeam, EloRating.initial(m.homeTeam));
                EloRating away = ratings.getOrDefault(m.awayTeam, EloRating.initial(m.awayTeam));
                boolean isHost = m.homeTeam.equals("Mexico") || m.homeTeam.equals("USA")
                        || m.homeTeam.equals("United States") || m.homeTeam.equals("Canada");
                double bonus = isHost ? 75.0 : 0.0;

                // Simular resultado real
                double[][] matrix = PoissonPredictor.scoreMatrix(home, away, bonus);
                var actual = GroupSimulator.sampleScore(matrix, rng);
                int actualSign = Integer.compare(actual.homeGoals(), actual.awayGoals());

                // Nuestra predicción
                String key = m.homeTeam + " vs " + m.awayTeam;
                int[] ourPred = ourPredictions.getOrDefault(key,
                        new int[]{MatchEV.honest(home, away, bonus).homeGoals(),
                                MatchEV.honest(home, away, bonus).awayGoals()});
                int ourSign = Integer.compare(ourPred[0], ourPred[1]);
                boolean ourExact  = ourPred[0] == actual.homeGoals() && ourPred[1] == actual.awayGoals();
                boolean ourResult = ourSign == actualSign;
                int ourPts = ourExact ? QuinielaScorer.pointsExact(stage)
                        : ourResult ? QuinielaScorer.pointsResult(stage) : 0;
                totals.merge(StandingsSimulator.US, ourPts, Integer::sum);

                // Predicciones de rivales
                for (RivalProfile rival : rivalProfiles) {
                    var pred = RivalSimulator.predict(rival, matrix, m.homeTeam, m.awayTeam, rng);
                    int rSign = Integer.compare(pred.homeGoals(), pred.awayGoals());
                    boolean rExact  = pred.homeGoals() == actual.homeGoals()
                            && pred.awayGoals() == actual.awayGoals();
                    boolean rResult = rSign == actualSign;
                    int rPts = rExact ? QuinielaScorer.pointsExact(stage)
                            : rResult ? QuinielaScorer.pointsResult(stage) : 0;
                    totals.merge(rival.name(), rPts, Integer::sum);
                }
            }

            // Posición final
            int ourScore = totals.getOrDefault(StandingsSimulator.US, 0);
            int pos = 1;
            for (Map.Entry<String, Integer> e : totals.entrySet()) {
                if (!e.getKey().equals(StandingsSimulator.US) && e.getValue() > ourScore) pos++;
            }
            posCount[pos - 1]++;
            posSum += pos;
        }

        double p1 = (double) posCount[0] / simulations;
        double p2 = (double) posCount[1] / simulations;
        double p3 = (double) posCount[2] / simulations;
        return new MetaResult(p1, p2, p3, p1+p2+p3,
                (double) posSum / simulations, simulations);
    }
}