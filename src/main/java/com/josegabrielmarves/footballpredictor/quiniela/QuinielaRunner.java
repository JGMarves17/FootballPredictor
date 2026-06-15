package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import com.josegabrielmarves.footballpredictor.rivals.RivalProfile;
import com.josegabrielmarves.footballpredictor.rivals.RivalProfile.Type;
import com.josegabrielmarves.footballpredictor.rivals.StandingsSimulator;

import java.util.*;

/**
 * Punto de entrada principal de la quiniela (Fase 10).
 *
 * Conecta todo el sistema:
 *   Fixture 2026 → Ratings calibrados → Jornada → StrategyOptimizer → Recomendación
 *
 * CÓMO USAR CADA JORNADA:
 * 1. Actualizar la sección "CLASIFICACIÓN ACTUAL" con los puntos reales.
 * 2. Actualizar la sección "PARTIDOS DE LA JORNADA" con los matches reales.
 * 3. Agregar el resultado de la jornada anterior en "RESULTADOS APLICADOS".
 * 4. Correr main() y copiar las predicciones óptimas al WhatsApp.
 */
public final class QuinielaRunner {

    private QuinielaRunner() {}

    public static void main(String[] args) throws Exception {

        // ── 1. Cargar ratings base ────────────────────────────────────────────
        System.out.println("Cargando ratings...");
        var provider = new OpenFootballProvider();
        List<Match> allMatches = provider.getWorldCupMatches(2026);

        Map<String, EloRating> ratings = new HashMap<>();
        for (Match m : allMatches) {
            ratings.putIfAbsent(m.homeTeam, CalibratedEloRatings.getRating(m.homeTeam));
            ratings.putIfAbsent(m.awayTeam, CalibratedEloRatings.getRating(m.awayTeam));
        }

        // ── 2. RESULTADOS APLICADOS (actualizar cada jornada) ─────────────────
        // Jornada 1:
        applyResult(ratings, "Mexico",     "South Africa",  2, 1, EloCalculator.HOME_ADVANTAGE);
        applyResult(ratings, "South Korea","Czech Republic", 1, 1, 0.0);
        // Jornada 2+ → agregar aquí:
        // applyResult(ratings, "TeamA", "TeamB", golesA, golesB, homeBonus);

        // ── 3. CLASIFICACIÓN ACTUAL (actualizar cada jornada) ─────────────────
        // Nombre clave: StandingsSimulator.US = "Nosotros"
        Map<String, Integer> standings = new LinkedHashMap<>();
        standings.put(StandingsSimulator.US, 1);  // ← nuestros puntos reales
        // 13 rivales — actualizar nombres y puntos con los del grupo real:
        for (int i = 1; i <= 13; i++) standings.put("Rival" + i, 0);

        // ── 4. PERFILES DE RIVALES ────────────────────────────────────────────
        // Ajustar cuando tengas datos reales de cómo predicen
        List<RivalProfile> rivals = List.of(
                new RivalProfile("Rival1",  Type.CONSERVATIVE),
                new RivalProfile("Rival2",  Type.CONSERVATIVE),
                new RivalProfile("Rival3",  Type.CONSERVATIVE),
                new RivalProfile("Rival4",  Type.CONSERVATIVE),
                new RivalProfile("Rival5",  Type.CONSERVATIVE),
                new RivalProfile("Rival6",  Type.CONSERVATIVE),
                new RivalProfile("Rival7",  Type.FAVORITE),
                new RivalProfile("Rival8",  Type.FAVORITE),
                new RivalProfile("Rival9",  Type.FAVORITE),
                new RivalProfile("Rival10", Type.FAVORITE),
                new RivalProfile("Rival11", Type.RANDOM),
                new RivalProfile("Rival12", Type.RANDOM),
                new RivalProfile("Rival13", Type.FAN, "Mexico") // ← ajustar favorito
        );

        // ── 5. PARTIDOS DE LA JORNADA (actualizar cada jornada) ───────────────
        // Cambiar por los partidos reales de la jornada a predecir
        // homeBonus: EloCalculator.HOME_ADVANTAGE si es México/USA/Canadá como local, 0 si neutral
        List<StrategyOptimizer.StrategyMatch> matches = List.of(
                new StrategyOptimizer.StrategyMatch(
                        "Mexico",    ratings.getOrDefault("Mexico",    EloRating.initial("Mexico")),
                        "South Africa", ratings.getOrDefault("South Africa", EloRating.initial("South Africa")),
                        EloCalculator.HOME_ADVANTAGE   // México es local
                ),
                new StrategyOptimizer.StrategyMatch(
                        "South Korea",   ratings.getOrDefault("South Korea",   EloRating.initial("South Korea")),
                        "Czech Republic",ratings.getOrDefault("Czech Republic",EloRating.initial("Czech Republic")),
                        0.0   // cancha neutral
                )
                // → agregar más partidos de la jornada aquí
        );

        // ── 6. Fase 8: reporte partido a partido ──────────────────────────────
        System.out.println("\n--- Reporte MatchEV (Fase 8) ---");
        JornadaOptimizer jornadaOpt = new JornadaOptimizer(Stage.GRUPOS);
        for (StrategyOptimizer.StrategyMatch m : matches) {
            jornadaOpt.addMatch(m.homeTeam(), m.home(), m.awayTeam(), m.away(), m.homeBonus());
        }
        jornadaOpt.printReport();

        // ── 7. Fase 10: optimización de estrategia ────────────────────────────
        System.out.println("--- Strategy Optimizer (Fase 10) ---");
        System.out.println("Evaluando combinaciones...");
        long t0 = System.currentTimeMillis();

        StrategyOptimizer.OptimizationResult optimal = StrategyOptimizer.optimize(
                matches, standings, rivals, Stage.GRUPOS,
                3,      // top-K candidatos por partido
                3_000,  // simulaciones por combinación
                2026L   // semilla
        );

        System.out.printf("Completado en %.1fs%n", (System.currentTimeMillis()-t0)/1000.0);
        optimal.print(matches);

        // ── 8. Clasificación proyectada ───────────────────────────────────────
        System.out.println("\n--- Clasificación proyectada con predicciones óptimas ---");
        int n = optimal.participants();
        System.out.printf("  EV de premio = %.1f%% del pozo  |  P(podio) = %.1f%%  |  " +
                        "Posición esperada = %.2f / %d%n",
                optimal.expectedPayout()*100, optimal.pPodio()*100, optimal.expectedPosition(), n);
        System.out.printf("  Base sin modelo ≈ %.1f%% (3/%d)  |  Ventaja del modelo: +%.1f%%%n",
                100.0*3.0/n, n, (optimal.pPodio() - 3.0/n)*100);
    }

    /** Aplica un resultado real al mapa de ratings. */
    private static void applyResult(Map<String, EloRating> ratings,
                                    String home, String away,
                                    double hg, double ag, double homeBonus) {
        EloRating h = ratings.getOrDefault(home, EloRating.initial(home));
        EloRating a = ratings.getOrDefault(away, EloRating.initial(away));
        EloCalculator.UpdatedRatings u = EloCalculator.updateRatings(h, a, hg, ag,
                EloCalculator.K_WORLD_CUP, homeBonus);
        ratings.put(home, u.home());
        ratings.put(away, u.away());
    }
}