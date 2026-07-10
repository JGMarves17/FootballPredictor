package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.messaging.WhatsAppMessenger;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.*;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import com.josegabrielmarves.footballpredictor.rivals.*;
import com.josegabrielmarves.footballpredictor.quiniela.StandingsLoader;

import java.nio.file.Path;
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
            ║    FOOTBALL PREDICTOR — Triple-Blend + xG        ║
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

        // ── 4. CLASIFICACIÓN ACTUAL (REAL — 29-jun, R32 completado) ──────
        Map<String, Integer> standings = StandingsLoader.load();

        // ── 5. PERFILES DE RIVALES (desde JSON, no hardcodeados) ──────────────────
        List<RivalProfile> rivals = RivalLoader.load();

        // ── 6. PARTIDOS DE LA JORNADA ─────────────────────────────────────────
        // ⚠️  Sin bonus hardcodeado — MatchdayEngine.hostBonus() lo calcula solo
        int jornada = 4; // R32 (Dieciseisavos)
        List<MatchdayEngine.MatchInput> matchday = List.of(
                new MatchdayEngine.MatchInput("Brazil",       "Qatar",       Stage.DIECISEISAVOS),
                new MatchdayEngine.MatchInput("Argentina",    "South Korea", Stage.DIECISEISAVOS),
                new MatchdayEngine.MatchInput("Germany",      "Canada",      Stage.DIECISEISAVOS),
                new MatchdayEngine.MatchInput("France",       "Mexico",      Stage.DIECISEISAVOS)
        );

        // ── 7. ScoreMatrix 500k por partido ──────────────────────────────────
        System.out.printf("%n[3/6] Generando matrices 500k simulaciones — jornada %d...%n", jornada);
        MatchdayEngine.preMatchday(jornada, matchday, ratings, LocalDate.now());

        // ── 8. P(podio) con MetaSimulator ────────────────────────────────────
        System.out.println("[4/6] Calculando P(podio) con MetaSimulator...");
        List<Match> remaining = allMatches.stream().filter(m -> m.score == null).toList();

        Map<String, int[]> ourPredictions = new HashMap<>();
        for (MatchdayEngine.MatchInput m : matchday) {
            double bonus = MatchdayEngine.hostBonus(m.team1());
            EloRating h = ratings.getOrDefault(m.team1(), EloRating.initial(m.team1()));
            EloRating a = ratings.getOrDefault(m.team2(), EloRating.initial(m.team2()));
            // Use tournament model with correct stage
            var score = PoissonPredictor.mostLikelyScoreTournament(m.team1(), h, m.team2(), a, bonus, m.stage());
            ourPredictions.put(m.team1() + " vs " + m.team2(),
                    new int[]{score.homeGoals(), score.awayGoals()});
        }

        MetaSimulator.MetaResult meta = MetaSimulator.run(
                remaining, ratings, ourPredictions, standings, rivals, 10_000, 2026L);
        meta.print();

        // ── 9. FastStrategyOptimizer (CRN, rápido) ──────────────────────────────
        System.out.println("[5/6] Optimizando estrategia...");
        List<FastStrategyOptimizer.StrategyMatch> strategyMatches = new ArrayList<>();
        for (MatchdayEngine.MatchInput m : matchday) {
            double bonus = MatchdayEngine.hostBonus(m.team1());
            strategyMatches.add(new FastStrategyOptimizer.StrategyMatch(
                    m.team1(),
                    ratings.getOrDefault(m.team1(), EloRating.initial(m.team1())),
                    m.team2(),
                    ratings.getOrDefault(m.team2(), EloRating.initial(m.team2())),
                    bonus
            ));
        }

        long t0 = System.currentTimeMillis();
        FastStrategyOptimizer.OptimizationResult opt = FastStrategyOptimizer.optimize(
                strategyMatches, standings, rivals, Stage.DIECISEISAVOS, 3, 5_000, 2026L,
                FastStrategyOptimizer.Objective.EXPECTED_PAYOUT);
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

        // ── 10. Mensaje WhatsApp ────────────────────────────────────────────────
        System.out.println("\n[📱] Generando mensaje para WhatsApp...");
        String waMsg = WhatsAppMessenger.buildMessage(jornada, matchday, opt, strategyMatches);
        // AUTO: intenta CallMeBot API, si falla → portapapeles (nunca pierdes el mensaje)
        WhatsAppMessenger.sendWithBot(waMsg);
    }
}