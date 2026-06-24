package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.*;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import com.josegabrielmarves.footballpredictor.rivals.*;

import java.time.LocalDate;
import java.util.*;

/**
 * PUNTO DE ENTRADA PRINCIPAL v2.
 *
 * La lógica vive en run(), que puede llamarse desde:
 *   - main() (consola)
 *   - el dashboard (botón "Correr Pipeline") → la salida va a la pestaña Consola
 *
 * Cada partido jugado alimenta automáticamente las 500k del siguiente
 * mediante LiveMatchUpdater.
 */
public final class QuinielaRunnerV2 {

    private QuinielaRunnerV2() {}

    public static void main(String[] args) throws Exception {
        run();
    }

    /** Ejecuta el pipeline completo. Toda la salida va por System.out. */
    public static void run() throws Exception {

        System.out.println("""
            ╔══════════════════════════════════════════════════╗
            ║    FOOTBALL PREDICTOR v2 — Triple-Blend + xG     ║
            ║    Elo 40% + FIFAForm 25% + GLM 35% + xG real    ║
            ╚══════════════════════════════════════════════════╝""");

        // ── 1. Ratings base ───────────────────────────────────────────────────
        System.out.println("\n[1/6] Cargando fixture y ratings calibrados...");
        List<Match> allMatches = new OpenFootballProvider().getWorldCupMatches(2026);
        Map<String, EloRating> ratings = new HashMap<>();
        for (Match m : allMatches) {
            ratings.putIfAbsent(m.homeTeam, CalibratedEloRatings.getRating(m.homeTeam));
            ratings.putIfAbsent(m.awayTeam, CalibratedEloRatings.getRating(m.awayTeam));
        }

        // ── 2. Historial WC 2026 para el GLM ──────────────────────────────────
        List<TournamentGLM.MatchData> wcHistory = new ArrayList<>(List.of(
                new TournamentGLM.MatchData("Mexico",       "South Africa",         2,0,true),
                new TournamentGLM.MatchData("South Korea",  "Czech Republic",       2,1,false),
                new TournamentGLM.MatchData("Canada",       "Bosnia & Herzegovina", 1,1,true),
                new TournamentGLM.MatchData("USA",          "Paraguay",             4,1,true),
                new TournamentGLM.MatchData("Qatar",        "Switzerland",          1,1,false),
                new TournamentGLM.MatchData("Brazil",       "Morocco",              1,1,false),
                new TournamentGLM.MatchData("Haiti",        "Scotland",             0,1,false),
                new TournamentGLM.MatchData("Australia",    "Turkey",               2,0,false),
                new TournamentGLM.MatchData("Germany",      "Curaçao",              7,1,false),
                new TournamentGLM.MatchData("Ivory Coast",  "Ecuador",              1,0,false),
                new TournamentGLM.MatchData("Netherlands",  "Japan",                2,2,false),
                new TournamentGLM.MatchData("Sweden",       "Tunisia",              5,1,false),
                new TournamentGLM.MatchData("Spain",        "Cape Verde",           0,0,false),
                new TournamentGLM.MatchData("Belgium",      "Egypt",                1,1,false),
                new TournamentGLM.MatchData("Saudi Arabia", "Uruguay",              1,1,false),
                new TournamentGLM.MatchData("Iran",         "New Zealand",          2,2,false),
                new TournamentGLM.MatchData("France",       "Senegal",              3,1,false),
                new TournamentGLM.MatchData("Iraq",         "Norway",               1,4,false),
                new TournamentGLM.MatchData("Argentina",    "Algeria",              3,0,false),
                new TournamentGLM.MatchData("Austria",      "Jordan",               3,1,false),
                new TournamentGLM.MatchData("Portugal",     "DR Congo",             1,1,false),
                new TournamentGLM.MatchData("Uzbekistan",   "Colombia",             1,3,false),
                new TournamentGLM.MatchData("England",      "Croatia",              4,2,false),
                new TournamentGLM.MatchData("Ghana",        "Panama",               1,0,false)
        ));

        // ── 3. LiveMatchUpdater ───────────────────────────────────────────────
        System.out.println("[2/6] Inicializando LiveMatchUpdater...");
        LiveMatchUpdater updater = new LiveMatchUpdater(ratings, wcHistory);
        System.out.printf("  → %d partidos en historial, GLM calibrado%n",
                updater.matchesRecorded());

        // ── 4. CLASIFICACIÓN ACTUAL ───────────────────────────────────────────
        Map<String, Integer> standings = new LinkedHashMap<>();
        standings.put(StandingsSimulator.US,  17);
        standings.put("Rodrigo Lopez",        28);
        standings.put("Daniel Ortiz",         24);
        standings.put("Nissy Rodriguez",      23);
        standings.put("Ruben Figueroa",       22);
        standings.put("Jason Avila",          22);
        standings.put("Cristhian Brito",      20);
        standings.put("Carlos Guevara",       20);
        standings.put("Luis Flores",          17);
        standings.put("Manuel Molina",        17);
        standings.put("Alfredo Funez",        16);
        standings.put("Carlos Davis",         16);
        standings.put("Jose Pozadas",         15);
        standings.put("Daniel Rivera",        14);
        standings.put("Moises Chavarria",     14);
        standings.put("Hector Cerrato",       13);
        standings.put("Jorge Brand",          11);

        // ── 5. PERFILES DE RIVALES ────────────────────────────────────────────
        List<RivalProfile> rivals = List.of(
                new RivalProfile("Rodrigo Lopez",     RivalProfile.Type.FAVORITE),
                new RivalProfile("Daniel Ortiz",      RivalProfile.Type.RANDOM),
                new RivalProfile("Nissy Rodriguez",   RivalProfile.Type.RANDOM),
                new RivalProfile("Ruben Figueroa",    RivalProfile.Type.CONSERVATIVE),
                new RivalProfile("Jason Avila",       RivalProfile.Type.FAVORITE),
                new RivalProfile("Cristhian Brito",   RivalProfile.Type.FAVORITE),
                new RivalProfile("Carlos Guevara",    RivalProfile.Type.CONSERVATIVE),
                new RivalProfile("Luis Flores",       RivalProfile.Type.FAVORITE),
                new RivalProfile("Manuel Molina",     RivalProfile.Type.CONSERVATIVE),
                new RivalProfile("Alfredo Funez",     RivalProfile.Type.RANDOM),
                new RivalProfile("Carlos Davis",      RivalProfile.Type.CONSERVATIVE),
                new RivalProfile("Jose Pozadas",      RivalProfile.Type.CONSERVATIVE),
                new RivalProfile("Daniel Rivera",     RivalProfile.Type.FAVORITE),
                new RivalProfile("Moises Chavarria",  RivalProfile.Type.RANDOM),
                new RivalProfile("Hector Cerrato",    RivalProfile.Type.CONSERVATIVE),
                new RivalProfile("Jorge Brand",       RivalProfile.Type.RANDOM)
        );

        // ── 6. PARTIDOS DE LA JORNADA ─────────────────────────────────────────
        int jornada = 3;
        List<MatchdayEngine.MatchInput> matchday = List.of(
                new MatchdayEngine.MatchInput("Czech Republic",       "Mexico",       0.0, Stage.GRUPOS),
                new MatchdayEngine.MatchInput("South Africa",         "South Korea",  0.0, Stage.GRUPOS),
                new MatchdayEngine.MatchInput("Bosnia & Herzegovina", "Switzerland",  0.0, Stage.GRUPOS),
                new MatchdayEngine.MatchInput("Qatar",                "Canada",       0.0, Stage.GRUPOS)
        );

        // ── 7. ScoreMatrix 500k por partido ──────────────────────────────────
        System.out.printf("%n[3/6] Generando matrices 500k simulaciones — jornada %d...%n", jornada);
        MatchdayEngine.preMatchday(jornada, matchday, ratings, LocalDate.now());

        // ── 8. P(podio) con MetaSimulator ────────────────────────────────────
        System.out.println("[4/6] Calculando P(podio) con MetaSimulator...");
        List<Match> remaining = allMatches.stream().filter(m -> m.score == null).toList();

        Map<String, int[]> ourPredictions = new HashMap<>();
        for (MatchdayEngine.MatchInput m : matchday) {
            EloRating h = ratings.getOrDefault(m.homeTeam(), EloRating.initial(m.homeTeam()));
            EloRating a = ratings.getOrDefault(m.awayTeam(), EloRating.initial(m.awayTeam()));
            var score = PoissonPredictor.mostLikelyScore(h, a, m.homeBonus());
            ourPredictions.put(m.homeTeam() + " vs " + m.awayTeam(),
                    new int[]{score.homeGoals(), score.awayGoals()});
        }

        MetaSimulator.MetaResult meta = MetaSimulator.run(
                remaining, ratings, ourPredictions, standings, rivals, 10_000, 2026L);
        meta.print();

        // ── 9. StrategyOptimizer ──────────────────────────────────────────────
        System.out.println("[5/6] Optimizando estrategia...");
        List<StrategyOptimizer.StrategyMatch> strategyMatches = new ArrayList<>();
        for (MatchdayEngine.MatchInput m : matchday) {
            strategyMatches.add(new StrategyOptimizer.StrategyMatch(
                    m.homeTeam(),
                    ratings.getOrDefault(m.homeTeam(), EloRating.initial(m.homeTeam())),
                    m.awayTeam(),
                    ratings.getOrDefault(m.awayTeam(), EloRating.initial(m.awayTeam())),
                    m.homeBonus()
            ));
        }

        long t0 = System.currentTimeMillis();
        StrategyOptimizer.OptimizationResult opt = StrategyOptimizer.optimize(
                strategyMatches, standings, rivals, Stage.GRUPOS, 3, 5_000, 2026L);
        System.out.printf("[6/6] Listo en %.1fs%n", (System.currentTimeMillis()-t0)/1000.0);

        opt.print(strategyMatches);

        int n = opt.participants();
        System.out.printf("""

  ╔═══════════════════════════════════════════════╗
  ║  PREDICCIONES ÓPTIMAS — COPIAR AL WHATSAPP    ║
  ║  ⚠️  ENVIAR ANTES DEL PRIMER PARTIDO DEL DÍA   ║
  ╠═══════════════════════════════════════════════╣
  ║  P(podio torneo completo) = %.1f%%            ║
  ║  Posición esperada        = %.2f / %d         ║
  ║  Ventaja vs base          = +%.1f%%           ║
  ╚═══════════════════════════════════════════════╝%n""",
                meta.pPodio()*100, meta.expectedPosition(), n,
                (meta.pPodio() - 3.0/n)*100);
    }
}