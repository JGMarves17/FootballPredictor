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
        // 76 partidos: 72 grupos (J1+J2+J3) + 16 R32 + 8 R16 + 4 QF (TODOS JUGADOS)
        List<TournamentGLM.MatchData> wcHistory = new ArrayList<>(List.of(
                // ── GRUPOS J1 (24 partidos) ─────────────────────────────────
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
                new TournamentGLM.MatchData("Ghana",        "Panama",               1,0,false),
                // ── GRUPOS J2 (24 partidos) ─────────────────────────────────
                new TournamentGLM.MatchData("Czech Republic",  "South Africa",      1,1,false),
                new TournamentGLM.MatchData("Mexico",          "South Korea",       1,0,true),
                new TournamentGLM.MatchData("Switzerland",     "Bosnia & Herzegovina", 4,1,false),
                new TournamentGLM.MatchData("Canada",          "Qatar",             6,0,true),
                new TournamentGLM.MatchData("Scotland",        "Morocco",           0,1,false),
                new TournamentGLM.MatchData("Brazil",          "Haiti",             3,0,false),
                new TournamentGLM.MatchData("USA",             "Australia",         2,0,true),
                new TournamentGLM.MatchData("Turkey",          "Paraguay",          0,1,false),
                new TournamentGLM.MatchData("Germany",         "Ivory Coast",       2,1,false),
                new TournamentGLM.MatchData("Ecuador",         "Curaçao",           0,0,false),
                new TournamentGLM.MatchData("Netherlands",     "Sweden",            5,1,false),
                new TournamentGLM.MatchData("Tunisia",         "Japan",             0,4,false),
                new TournamentGLM.MatchData("Belgium",         "Iran",              0,0,false),
                new TournamentGLM.MatchData("New Zealand",     "Egypt",             1,3,false),
                new TournamentGLM.MatchData("Spain",           "Saudi Arabia",      4,0,false),
                new TournamentGLM.MatchData("Uruguay",         "Cape Verde",        2,2,false),
                new TournamentGLM.MatchData("France",          "Iraq",              3,0,false),
                new TournamentGLM.MatchData("Norway",          "Senegal",           3,2,false),
                new TournamentGLM.MatchData("Argentina",       "Austria",           2,0,false),
                new TournamentGLM.MatchData("Jordan",          "Algeria",           1,2,false),
                new TournamentGLM.MatchData("Portugal",        "Uzbekistan",        5,0,false),
                new TournamentGLM.MatchData("Colombia",        "DR Congo",          1,0,false),
                new TournamentGLM.MatchData("England",         "Ghana",             0,0,false),
                new TournamentGLM.MatchData("Panama",          "Croatia",           0,1,false),
                // ── GRUPOS J3 (24 partidos) ─────────────────────────────────
                new TournamentGLM.MatchData("Czech Republic",  "Mexico",            0,3,false),
                new TournamentGLM.MatchData("South Africa",    "South Korea",       1,0,false),
                new TournamentGLM.MatchData("Switzerland",     "Canada",            2,1,false),
                new TournamentGLM.MatchData("Bosnia & Herzegovina", "Qatar",        3,1,false),
                new TournamentGLM.MatchData("Scotland",        "Brazil",            0,3,false),
                new TournamentGLM.MatchData("Morocco",         "Haiti",             4,2,false),
                new TournamentGLM.MatchData("Turkey",          "USA",               3,2,false),
                new TournamentGLM.MatchData("Paraguay",        "Australia",         0,0,false),
                new TournamentGLM.MatchData("Curaçao",         "Ivory Coast",       0,2,false),
                new TournamentGLM.MatchData("Ecuador",         "Germany",           2,1,false),
                new TournamentGLM.MatchData("Japan",           "Sweden",            1,1,false),
                new TournamentGLM.MatchData("Tunisia",         "Netherlands",       1,3,false),
                new TournamentGLM.MatchData("Egypt",           "Iran",              1,1,false),
                new TournamentGLM.MatchData("New Zealand",     "Belgium",           1,5,false),
                new TournamentGLM.MatchData("Cape Verde",      "Saudi Arabia",      0,0,false),
                new TournamentGLM.MatchData("Uruguay",         "Spain",             0,1,false),
                new TournamentGLM.MatchData("Norway",          "France",            1,4,false),
                new TournamentGLM.MatchData("Senegal",         "Iraq",              5,0,false),
                new TournamentGLM.MatchData("Algeria",         "Austria",           3,3,false),
                new TournamentGLM.MatchData("Jordan",          "Argentina",         1,3,false),
                new TournamentGLM.MatchData("Colombia",        "Portugal",          0,0,false),
                new TournamentGLM.MatchData("DR Congo",        "Uzbekistan",        3,1,false),
                new TournamentGLM.MatchData("Panama",          "England",           0,2,false),
                new TournamentGLM.MatchData("Croatia",         "Ghana",             2,1,false),
                // ── R32 — Dieciseisavos (16 partidos) ───────────────────────
                new TournamentGLM.MatchData("South Africa",    "Canada",            0,1,false),
                new TournamentGLM.MatchData("Germany",         "Paraguay",          1,1,false),  // pens 3-4
                new TournamentGLM.MatchData("Netherlands",     "Morocco",           1,1,false),  // pens 2-3
                new TournamentGLM.MatchData("Brazil",          "Japan",             2,1,false),
                new TournamentGLM.MatchData("France",          "Sweden",            3,0,false),
                new TournamentGLM.MatchData("Ivory Coast",     "Norway",            1,2,false),
                new TournamentGLM.MatchData("Mexico",          "Ecuador",           2,0,true),
                new TournamentGLM.MatchData("England",         "DR Congo",          2,1,false),
                new TournamentGLM.MatchData("USA",             "Bosnia & Herzegovina", 2,0,true),
                new TournamentGLM.MatchData("Belgium",         "Senegal",           2,2,false),  // ET 3-2
                new TournamentGLM.MatchData("Portugal",        "Croatia",           2,1,false),
                new TournamentGLM.MatchData("Spain",           "Austria",           3,0,false),
                new TournamentGLM.MatchData("Switzerland",     "Algeria",           2,0,false),
                new TournamentGLM.MatchData("Argentina",       "Cape Verde",        1,1,false),  // ET 3-2
                new TournamentGLM.MatchData("Colombia",        "Ghana",             1,0,false),
                new TournamentGLM.MatchData("Australia",       "Egypt",             1,1,false),  // pens 2-4
                // ── R16 — Octavos (8 partidos) ─────────────────────────────
                new TournamentGLM.MatchData("Paraguay",        "France",            0,1,false),
                new TournamentGLM.MatchData("Canada",          "Morocco",           0,3,true),
                new TournamentGLM.MatchData("Brazil",          "Norway",            1,2,false),
                new TournamentGLM.MatchData("Mexico",          "England",           2,3,true),
                new TournamentGLM.MatchData("Portugal",        "Spain",             0,1,false),
                new TournamentGLM.MatchData("USA",             "Belgium",           1,4,true),
                new TournamentGLM.MatchData("Argentina",       "Egypt",             3,2,false),
                new TournamentGLM.MatchData("Switzerland",     "Colombia",          0,0,false),
                // ── Cuartos de Final (4 partidos) ──────────────────────────
                new TournamentGLM.MatchData("France",          "Morocco",           2,0,false),
                new TournamentGLM.MatchData("Spain",           "Belgium",           2,1,false),
                new TournamentGLM.MatchData("Norway",          "England",           1,1,false),  // ET 1-2
                new TournamentGLM.MatchData("Argentina",       "Switzerland",       1,1,false)   // ET 3-1
        ));

        // ── 3. LiveMatchUpdater ───────────────────────────────────────────────
        System.out.println("[2/6] Inicializando LiveMatchUpdater...");
        LiveMatchUpdater updater = new LiveMatchUpdater(ratings, wcHistory);
        System.out.printf("  → %d partidos en historial, GLM calibrado%n",
                updater.matchesRecorded());

        // ── 4. CLASIFICACIÓN ACTUAL (REAL — 13-jul, SF próximo) ──────────────
        Map<String, Integer> standings = StandingsLoader.load();

        // ── 5. PERFILES DE RIVALES (desde JSON, no hardcodeados) ──────────────────
        List<RivalProfile> rivals = RivalLoader.load();

        // ── 6. PARTIDOS DE LA JORNADA — SEMIFINAL #101 ───────────────────────
        // ⚠️  Sin bonus hardcodeado — MatchdayEngine.hostBonus() lo calcula solo
        // Francia vs España — Ninguno es sede → homeAdv = false
        int jornada = 101; // Semifinal #101
        List<MatchdayEngine.MatchInput> matchday = List.of(
                new MatchdayEngine.MatchInput("France",       "Spain",       Stage.SEMIFINAL)
        );

        // EnsemblePredictor único para todo el run: ajusta el modelo hacia las
        // odds reales del mercado (The Odds API) cuando hay datos disponibles.
        // Se reutiliza la misma instancia en todo el pipeline para que el
        // caché de OddsProvider evite quemar créditos de la API.
        EnsemblePredictor ep = new EnsemblePredictor();

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
            // Modelo torneo + mercado (odds reales cuando están disponibles)
            var score = PoissonPredictor.mostLikelyScoreTournamentWithMarket(
                    m.team1(), h, m.team2(), a, bonus, m.stage(), ep);
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
                strategyMatches, standings, rivals, Stage.SEMIFINAL, 3, 5_000, 2026L,
                FastStrategyOptimizer.Objective.EXPECTED_PAYOUT, ep);
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
        String waMsg = WhatsAppMessenger.buildMessage(jornada, matchday, opt, strategyMatches, ep);
        // AUTO: intenta CallMeBot API, si falla → portapapeles (nunca pierdes el mensaje)
        WhatsAppMessenger.sendWithBot(waMsg);
    }
}