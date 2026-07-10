package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.api.BracketApiClient;
import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.messaging.WhatsAppMessenger;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.*;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import com.josegabrielmarves.footballpredictor.rivals.*;
import com.josegabrielmarves.footballpredictor.quiniela.StandingsLoader;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 🏆 RUNNER DEDICADO PARA ELIMINATORIAS — DIECISEISAVOS DE FINAL (R32)
 * <p>
 * ════════════════════════════════════════════════════════════════════
 * 🔄 AUTOMÁTICO — Los cruces R32 se obtienen EN VIVO desde:
 *    openfootball/worldcup.json (sin API key, actualizado por GitHub Actions)
 * <p>
 * 📡 El sistema resuelve los cruces reales automáticamente.
 *    No necesitas editar nada. Solo ejecuta y envía.
 * ════════════════════════════════════════════════════════════════════
 * <p>
 * Puntos en juego:
 * - Resultado acertado (1X2): 2 pts
 * - Marcador exacto:          4 pts
 * - Multa por fallo:         10 Lempiras
 * <p>
 * Estrategia: ALL-IN (maximiza solo P(1°) porque vamos últimos)
 */
public final class QuinielaRunnerR32 {

    private QuinielaRunnerR32() {}

    // ──────────────────────────────────────────────────────────────────────────
    // 🌐  PARTIDOS AUTOMÁTICOS — se obtienen de openfootball/worldcup.json
    // ──────────────────────────────────────────────────────────────────────────
    //
    //  Los 16 partidos de R32 se cargan DESDE LA API en tiempo real.
    //  No necesitas editar nada aquí a menos que quieras OVERRIDE manual.
    //
    //  Para override manual, asigna MANUAL_MATCHES y pon AUTO_FETCH=false:
    //     private static final boolean AUTO_FETCH = false;
    //     private static final List<String> MANUAL_MATCHES = List.of(
    //         "Spain vs Morocco",
    //         "Germany vs Brazil"
    //     );
    //
    // ──────────────────────────────────────────────────────────────────────────
    private static final boolean AUTO_FETCH = true;

    /** Override manual — solo si AUTO_FETCH=false */
    private static final List<String> MANUAL_MATCHES = List.of();
    //
    // ═══ FIN de la sección de configuración ═══
    // ──────────────────────────────────────────────────────────────────────────

    /** Número de jornada (R32 = jornada 4 del torneo). */
    private static final int JORNADA = 4;

    /** Fase del torneo. */
    private static final Stage STAGE = Stage.DIECISEISAVOS;

    /** Título mostrado en consola. */
    private static final String STAGE_NAME = "DIECISEISAVOS DE FINAL (R32)";

    /** Puntos por resultado acertado. */
    private static final int PTS_RESULT = QuinielaScorer.pointsResult(STAGE);
    /** Puntos por marcador exacto. */
    private static final int PTS_EXACT  = QuinielaScorer.pointsExact(STAGE);

    public static void main(String[] args) throws Exception {
        run();
    }

    public static void run() throws Exception {
        System.out.printf("""
            ╔════════════════════════════════════════════════════════╗
            ║  🏆  FOOTBALL PREDICTOR — %s        ║
            ║  📌  Puntos: %d resultado · %d exacto · −10L fallo   ║
            ║  🎯  Estrategia: ALL-IN (maximiza P de 1er lugar)     ║
            ╚════════════════════════════════════════════════════════╝
            %n""", STAGE_NAME, PTS_RESULT, PTS_EXACT);

        // ── 1. Cargar fixture y ratings ────────────────────────────────────────
        System.out.println("[1/7] Cargando fixture y ratings calibrados...");
        List<Match> allMatches = new OpenFootballProvider().getWorldCupMatches(2026);
        Map<String, EloRating> ratings = new HashMap<>();
        for (Match m : allMatches) {
            ratings.putIfAbsent(m.homeTeam, CalibratedEloRatings.getRating(m.homeTeam));
            ratings.putIfAbsent(m.awayTeam, CalibratedEloRatings.getRating(m.awayTeam));
        }

        // ── 2. CARGAR CRUCES R32 DESDE LA API ────────────────────────────────
        System.out.println("[2/7] Obteniendo cruces R32 desde openfootball API...");
        List<MatchdayEngine.MatchInput> matchday;
        try {
            BracketApiClient bracket = new BracketApiClient();
            bracket.refresh();
            List<BracketApiClient.BracketMatch> r32Matches = bracket.getRoundOf32();

            if (r32Matches.isEmpty()) {
                System.err.println("  ⚠️  No se encontraron partidos R32 en la API.");
                System.err.println("  Usando override manual si está configurado...");
                matchday = parseMatches(MANUAL_MATCHES);
            } else {
                // Separar jugados vs próximos
                List<BracketApiClient.BracketMatch> upcoming = r32Matches.stream()
                        .filter(bm -> !bm.isPlayed())
                        .toList();
                List<BracketApiClient.BracketMatch> played = r32Matches.stream()
                        .filter(BracketApiClient.BracketMatch::isPlayed)
                        .toList();

                if (!played.isEmpty()) {
                    System.out.println("\n  📋 RESULTADOS RECIENTES:");
                    for (BracketApiClient.BracketMatch bm : played) {
                        System.out.printf("    ✅ #%d: %s %d-%d %s [%s]%n",
                                bm.matchNumber(), bm.team1(), bm.homeGoals(),
                                bm.awayGoals(), bm.team2(), bm.date());
                    }
                }

                System.out.printf("\n  → %d partidos de R32 (%d jugados, %d próximos)%n",
                        r32Matches.size(), played.size(), upcoming.size());
                for (BracketApiClient.BracketMatch bm : upcoming) {
                    System.out.printf("    🏟️  #%d: %s vs %s [%s]%n",
                            bm.matchNumber(), bm.team1(), bm.team2(), bm.date());
                }
                // Convertir BracketMatch → MatchInput para el motor (con fecha real del partido)
                DateTimeFormatter dtFmt = DateTimeFormatter.ISO_LOCAL_DATE;
                matchday = new ArrayList<>();
                for (BracketApiClient.BracketMatch bm : upcoming) {
                    if (bm.isPlaceholder()) continue;
                    LocalDate matchDate;
                    try {
                        matchDate = LocalDate.parse(bm.date(), dtFmt);
                    } catch (Exception e) {
                        // Fallback: usar hoy si la fecha no se puede parsear
                        matchDate = LocalDate.now();
                        System.out.printf("  ⚠️  No se pudo parsear fecha \"%s\" para %s vs %s, usando hoy%n",
                                bm.date(), bm.team1(), bm.team2());
                    }
                    matchday.add(new MatchdayEngine.MatchInput(
                            bm.team1(), bm.team2(), Stage.DIECISEISAVOS, matchDate));
                }
            }
        } catch (Exception e) {
            System.err.println("  ⚠️  Error al consultar API: " + e.getMessage());
            System.err.println("  Usando override manual...");
            matchday = parseMatches(MANUAL_MATCHES);
        }

        if (matchday.isEmpty()) {
            System.err.printf("""
                ╔══════════════════════════════════════════════════╗
                ║  ❌  ERROR: No hay partidos para predecir        ║
                ║                                                 ║
                ║  La API no devolvió datos y no hay override.     ║
                ║  Verifica tu conexión a Internet o configura     ║
                ║  MANUAL_MATCHES con AUTO_FETCH=false.            ║
                ╚══════════════════════════════════════════════════╝%n""");
            return;
        }

        // ── 3. Historial WC 2026 para GLM (estático para calibración) ─────────────────────────────────────
        System.out.println("[3/7] Inicializando historial WC 2026...");
        List<TournamentGLM.MatchData> wcHistory = new ArrayList<>(List.of(
                // Jornada 1 (24 partidos)
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

        // ── 4. LiveMatchUpdater ────────────────────────────────────────────────
        System.out.println("[4/7] Inicializando LiveMatchUpdater...");
        LiveMatchUpdater updater = new LiveMatchUpdater(ratings, wcHistory);
        System.out.printf("  → %d partidos en historial, GLM calibrado%n",
                updater.matchesRecorded());

        // ── 5. CLASIFICACIÓN ACTUAL (29-jun, R32 completado) ────────────────
        System.out.println("[5/7] Cargando clasificación actual...");
        Map<String, Integer> standings = StandingsLoader.load();

        // ── 6. PERFILES DE RIVALES (desde JSON, no hardcodeados) ──────────────────
        System.out.println("[6/7] Cargando perfiles de rivales...");
        List<RivalProfile> rivals = RivalLoader.load();

        // matchday ya cargado desde API en paso [2/7]

        System.out.printf("[6/7] Procesando %d partidos de %s...%n",
                matchday.size(), STAGE_NAME);

        // ── 7. ScoreMatrix 500k por partido ────────────────────────────────────
        MatchdayEngine.preMatchday(JORNADA, matchday, ratings, LocalDate.now());

        // ── 8. P(podio) con MetaSimulator ──────────────────────────────────────
        System.out.println("\n[7/7] Calculando P(podio) con MetaSimulator...");
        System.out.println("\n[📊] Calculando P(podio) con MetaSimulator...");
        List<Match> remaining = allMatches.stream().filter(m -> m.score == null).toList();

        Map<String, int[]> ourPredictions = new HashMap<>();
        for (MatchdayEngine.MatchInput m : matchday) {
            EloRating h = ratings.getOrDefault(m.team1(), EloRating.initial(m.team1()));
            EloRating a = ratings.getOrDefault(m.team2(), EloRating.initial(m.team2()));
            var score = PoissonPredictor.mostLikelyScoreTournament(m.team1(), h, m.team2(), a, MatchdayEngine.hostBonus(m.team1()), STAGE);
            ourPredictions.put(m.team1() + " vs " + m.team2(),
                    new int[]{score.homeGoals(), score.awayGoals()});
        }

        MetaSimulator.MetaResult meta = MetaSimulator.run(
                remaining, ratings, ourPredictions, standings, rivals, 10_000, 2026L);
        meta.print();

        // ── 9. FastStrategyOptimizer (ALL-IN) ──────────────────────────────────
        System.out.println("\n[🎯] Optimizando estrategia ALL-IN (max P(1°))...");
        List<FastStrategyOptimizer.StrategyMatch> strategyMatches = new ArrayList<>();
        for (MatchdayEngine.MatchInput m : matchday) {
            strategyMatches.add(new FastStrategyOptimizer.StrategyMatch(
                    m.team1(),
                    ratings.getOrDefault(m.team1(), EloRating.initial(m.team1())),
                    m.team2(),
                    ratings.getOrDefault(m.team2(), EloRating.initial(m.team2())),
                    MatchdayEngine.hostBonus(m.team1())
            ));
        }

        // Análisis de thresholds (rápido, solo simula rivales)
        System.out.println("\n[📊] Analizando thresholds...");
        FastStrategyOptimizer.ThresholdReport tr = FastStrategyOptimizer.analyzeThresholds(
                strategyMatches, standings, rivals, STAGE, 10_000, 2026L);
        tr.print(standings.getOrDefault(StandingsSimulator.US, 0));

        long t0 = System.currentTimeMillis();
        FastStrategyOptimizer.OptimizationResult opt = FastStrategyOptimizer.optimize(
                strategyMatches, standings, rivals, STAGE, 3, 5_000, 2026L,
                FastStrategyOptimizer.Objective.EXPECTED_PAYOUT);
        System.out.printf("[✓] Listo en %.1fs%n", (System.currentTimeMillis()-t0)/1000.0);

        // ── 10. IMPRIMIR RESULTADOS ────────────────────────────────────────────
        opt.print(strategyMatches);

        int n = opt.participants();
        System.out.printf("""
            
            ╔══════════════════════════════════════════════════════════╗
            ║  🏆  PREDICCIONES %s  ║
            ║  📌  Puntos: %d resultado · %d exacto · −10L fallo      ║
            ║  🎯  Estrategia: EXPECTED_PAYOUT (FastStrategyOptimizer)        ║
            ╠══════════════════════════════════════════════════════════╣
            ║  P(podio torneo completo) = %.1f%%                       ║
            ║  Posición esperada        = %.2f / %d                    ║
            ║                                                          ║
            ║  ⚠️  ENVIAR ANTES DEL PRIMER PARTIDO DEL DÍA              ║
            ║  ⚠️  No enviar = −3 pts + 10L/partido                    ║
            ╚══════════════════════════════════════════════════════════╝%n""",
                STAGE_NAME, PTS_RESULT, PTS_EXACT,
                meta.pPodio()*100, meta.expectedPosition(), n);

        // ── 11. WHATSAPP ──────────────────────────────────────────────────────
        System.out.println("\n[📱] Generando mensaje para WhatsApp...");
        String waMsg = WhatsAppMessageBuilder.build(
                STAGE_NAME, PTS_RESULT, PTS_EXACT,
                matchday, strategyMatches, opt);
        // AUTO: intenta CallMeBot API, si falla → portapapeles
        WhatsAppMessenger.sendWithBot(waMsg);

        System.out.println();
        System.out.println("✅ LISTO. Mensaje generado y enviado.");
        System.out.println("👉 Si el envío automático falló, pégalo en el grupo.");
        System.out.println("👉 NO olvides enviarlo ANTES del primer partido de R32.");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Convierte strings "Local vs Visitante" a objetos MatchInput.
     * Si un equipo no se encuentra en ratings, lanza advertencia.
     */
    private static List<MatchdayEngine.MatchInput> parseMatches(List<String> rawMatches) {
        List<MatchdayEngine.MatchInput> result = new ArrayList<>();
        for (String raw : rawMatches) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;

            // Dividir por " vs " (case-insensitive)
            String[] parts = trimmed.split("\\s+vs\\s+|\\s+VS\\s+", 2);
            if (parts.length < 2) {
                System.err.printf("  ⚠️  Saltando línea mal formada: \"%s\"%n", trimmed);
                System.out.println("     Formato esperado: \"EquipoLocal vs EquipoVisitante\"");
                continue;
            }

            String home = parts[0].trim();
            String away = parts[1].trim();

            if (home.equalsIgnoreCase("EquipoLocal") || home.contains("??")) {
                System.err.printf("  ⚠️  Saltando placeholder: \"%s\"%n", trimmed);
                continue;
            }

                // Verificar que los nombres existen (al menos en ratings)
                boolean homeExists = CalibratedEloRatings.hasCalibratedRating(home);
                boolean awayExists = CalibratedEloRatings.hasCalibratedRating(away);
                if (!homeExists || !awayExists) {
                    System.err.printf("  ⚠️  Equipo no encontrado en ratings:%s%s%n",
                            homeExists ? "" : "  ❌ " + home,
                            awayExists ? "" : "  ❌ " + away);
                    if (!homeExists && !awayExists) {
                        System.out.println("     Posiblemente es un placeholder. Saltando.");
                        continue;
                    }
                }

            result.add(new MatchdayEngine.MatchInput(home, away, STAGE));
            System.out.printf("  ✓ %s vs %s [%s]%n", home, away, STAGE_NAME);
        }
        return result;
    }

}
