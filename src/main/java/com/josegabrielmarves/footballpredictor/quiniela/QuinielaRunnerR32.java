package com.josegabrielmarves.footballpredictor.quiniela;

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

import java.time.LocalDate;
import java.util.*;

/**
 * 🏆 RUNNER DEDICADO PARA ELIMINATORIAS — DIECISEISAVOS DE FINAL (R32)
 * <p>
 * ════════════════════════════════════════════════════════════════════
 * CÓMO USAR (cuando se anuncien los cruces):
 * <p>
 * 1. Abre este archivo
 * 2. Ve a la sección "✏️ EDITAR AQUÍ: PARTIDOS DE LA JORNADA"
 * 3. Reemplaza los placeholders con los cruces R32 reales
 * 4. Guarda y ejecuta {@link #main(String[])}
 * 5. El mensaje se copia al portapapeles — pégalo en WhatsApp
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
    // ✏️  EDITAR AQUÍ: PARTIDOS DE LA JORNADA
    // ──────────────────────────────────────────────────────────────────────────
    //
    //  INSTRUCCIONES:
    //   1. Cuando se anuncien los 16 cruces de R32,
    //      reemplaza los strings de abajo con el formato:
    //      "EquipoLocal vs EquipoVisitante"
    //
    //   2. Usa los nombres EXACTOS en inglés
    //      (ej: "Netherlands" no "Holanda", "South Korea" no "Corea del Sur")
    //
    //   3. Los nombres deben coincidir con CalibratedEloRatings
    //
    //  ⚠️  MANTENER EL ORDEN CRONOLÓGICO si es posible
    //      (primer partido de R32 primero)
    //
    // ──────────────────────────────────────────────────────────────────────────
    private static final List<String> R32_MATCHES = List.of(
            // ═══ PARTIDOS R32 — reemplazar cuando se anuncien ═══
            // "Spain vs Morocco"    // ← ejemplo real
            // "Germany vs Brazil"   // ← ejemplo real
            //
            // ═══ PARTIDOS JORNADA 3 (placeholder temporal) ═══
            "Czech Republic vs Mexico",
            "South Africa vs South Korea",
            "Bosnia & Herzegovina vs Switzerland",
            "Qatar vs Canada"
    );
    //
    // ═══ FIN de la sección editable ═══
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
            ║  🏆  FOOTBALL PREDICTOR v2 — %s  ║
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

        // ── 2. Historial WC 2026 para GLM ─────────────────────────────────────
        System.out.println("[2/7] Inicializando historial WC 2026...");
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

        // ── 3. LiveMatchUpdater ────────────────────────────────────────────────
        System.out.println("[3/7] Inicializando LiveMatchUpdater...");
        LiveMatchUpdater updater = new LiveMatchUpdater(ratings, wcHistory);
        System.out.printf("  → %d partidos en historial, GLM calibrado%n",
                updater.matchesRecorded());

        // ── 4. CLASIFICACIÓN ACTUAL (24-jun, jornada 3 parcial) ───────────────
        System.out.println("[4/7] Cargando clasificación actual...");
        Map<String, Integer> standings = new LinkedHashMap<>();
        standings.put(StandingsSimulator.US,  19);   // Gabriel Marves (P17)
        standings.put("Rodrigo Lopez",        38);
        standings.put("Jason Avila",          36);
        standings.put("Ruben Figueroa",       33);
        standings.put("Nissy Rodriguez",      31);
        standings.put("Daniel Ortiz",         31);
        standings.put("Cristhian Brito",      28);
        standings.put("Carlos Guevara",       28);
        standings.put("Hector Cerrato",       27);
        standings.put("Alfredo Funez",        27);
        standings.put("Jose Pozadas",         27);
        standings.put("Carlos Davis",         26);
        standings.put("Daniel Rivera",        25);
        standings.put("Moises Chavarria",     25);
        standings.put("Luis Flores",          24);
        standings.put("Manuel Molina",        24);
        standings.put("Jorge Brand",          22);

        // ── 5. PERFILES DE RIVALES ─────────────────────────────────────────────
        System.out.println("[5/7] Cargando perfiles de rivales...");
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

        // ── 6. CONVERTIR STRINGS DE PARTIDOS A MatchInput ──────────────────────
        List<MatchdayEngine.MatchInput> matchday = parseMatches(R32_MATCHES);

        if (matchday.isEmpty()) {
            System.err.printf("""
                ╔══════════════════════════════════════════════════╗
                ║  ❌  ERROR: No hay partidos configurados          ║
                ║                                                 ║
                ║  Edita la lista R32_MATCHES en este archivo      ║
                ║  con los 16 cruces de Dieciseisavos de Final.    ║
                ║                                                 ║
                ║  Formato: "EquipoLocal vs EquipoVisitante"       ║
                ║  Ejemplo: "Spain vs Morocco"                     ║
                ╚══════════════════════════════════════════════════╝%n""");
            return;
        }

        System.out.printf("[6/7] Procesando %d partidos de %s...%n",
                matchday.size(), STAGE_NAME);

        // ── 7. ScoreMatrix 500k por partido ────────────────────────────────────
        MatchdayEngine.preMatchday(JORNADA, matchday, ratings, LocalDate.now());

        // ── 8. P(podio) con MetaSimulator ──────────────────────────────────────
        System.out.println("\n[📊] Calculando P(podio) con MetaSimulator...");
        List<Match> remaining = allMatches.stream().filter(m -> m.score == null).toList();

        Map<String, int[]> ourPredictions = new HashMap<>();
        for (MatchdayEngine.MatchInput m : matchday) {
            EloRating h = ratings.getOrDefault(m.team1(), EloRating.initial(m.team1()));
            EloRating a = ratings.getOrDefault(m.team2(), EloRating.initial(m.team2()));
            var score = PoissonPredictor.mostLikelyScore(h, a, MatchdayEngine.hostBonus(m.team1()));
            ourPredictions.put(m.team1() + " vs " + m.team2(),
                    new int[]{score.homeGoals(), score.awayGoals()});
        }

        MetaSimulator.MetaResult meta = MetaSimulator.run(
                remaining, ratings, ourPredictions, standings, rivals, 10_000, 2026L);
        meta.print();

        // ── 9. StrategyOptimizer (ALL-IN) ──────────────────────────────────────
        System.out.println("\n[🎯] Optimizando estrategia ALL-IN (max P(1°))...");
        List<StrategyOptimizer.StrategyMatch> strategyMatches = new ArrayList<>();
        for (MatchdayEngine.MatchInput m : matchday) {
            strategyMatches.add(new StrategyOptimizer.StrategyMatch(
                    m.team1(),
                    ratings.getOrDefault(m.team1(), EloRating.initial(m.team1())),
                    m.team2(),
                    ratings.getOrDefault(m.team2(), EloRating.initial(m.team2())),
                    MatchdayEngine.hostBonus(m.team1())
            ));
        }

        long t0 = System.currentTimeMillis();
        // ALL-IN: más candidatos, solo P(1°), +50% variantes
        StrategyOptimizer.OptimizationResult opt = StrategyOptimizer.optimizeAllIn(
                strategyMatches, standings, rivals, STAGE, 3, 5_000, 2026L);
        System.out.printf("[✓] Listo en %.1fs%n", (System.currentTimeMillis()-t0)/1000.0);

        // ── 10. IMPRIMIR RESULTADOS ────────────────────────────────────────────
        opt.print(strategyMatches);

        int n = opt.participants();
        System.out.printf("""
            
            ╔══════════════════════════════════════════════════════════╗
            ║  🏆  PREDICCIONES %s  ║
            ║  📌  Puntos: %d resultado · %d exacto · −10L fallo      ║
            ║  🎯  Estrategia: ALL-IN (max P(1°))                      ║
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
        String waMsg = buildWhatsAppMessage(JORNADA, matchday, opt, strategyMatches);
        WhatsAppMessenger.send(waMsg);

        System.out.println();
        System.out.println("✅ LISTO. El mensaje está en tu portapapeles.");
        System.out.println("👉 Pégalo en el grupo 'Quiniela Mundial 2026' de WhatsApp.");
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

    /**
     * Construye mensaje optimizado para WhatsApp con énfasis en eliminatorias.
     */
    private static String buildWhatsAppMessage(
            int jornada,
            List<MatchdayEngine.MatchInput> matchday,
            StrategyOptimizer.OptimizationResult opt,
            List<StrategyOptimizer.StrategyMatch> strategyMatches) {

        StringBuilder sb = new StringBuilder();
        sb.append("⚽ *PREDICCIONES ").append(STAGE_NAME).append("*\n");
        sb.append("📅 ").append(java.time.LocalDate.now()).append("\n");
        sb.append("👤 Gabriel Marves\n");
        sb.append("📊 P(podio)=").append(String.format("%.1f%%", opt.pPodio()*100));
        sb.append(" · P(1°)=").append(String.format("%.1f%%", opt.p1st()*100));
        sb.append(" · Esp.").append(String.format("%.2f", opt.expectedPosition()));
        sb.append("/").append(opt.participants()).append("\n");
        sb.append("💡 Pts: ").append(PTS_RESULT).append("R / ").append(PTS_EXACT).append("E · −10L fallo\n");
        sb.append("─".repeat(25)).append("\n\n");

        for (int i = 0; i < matchday.size(); i++) {
            MatchdayEngine.MatchInput m = matchday.get(i);
            StrategyOptimizer.StrategyMatch sm = strategyMatches.get(i);
            Score p = opt.predictions().get(i);
            MatchEV.Risk riesgo = MatchEV.risk(sm.home(), sm.away(), MatchdayEngine.hostBonus(m.team1()));

            double pHome = PoissonPredictor.matchProbabilities(sm.home(), sm.away(), MatchdayEngine.hostBonus(m.team1())).homeWin();
            double pDraw = PoissonPredictor.matchProbabilities(sm.home(), sm.away(), MatchdayEngine.hostBonus(m.team1())).draw();
            double pAway = PoissonPredictor.matchProbabilities(sm.home(), sm.away(), MatchdayEngine.hostBonus(m.team1())).awayWin();

            sb.append("🏟️ *").append(m.team1()).append("* vs *").append(m.team2()).append("*\n");
            sb.append("   📊 ").append(String.format("1=%.0f%% X=%.0f%% 2=%.0f%%", pHome*100, pDraw*100, pAway*100)).append("\n");
            sb.append("   🎯 *").append(p.homeGoals()).append("-").append(p.awayGoals()).append("*  (").append(riesgo.label).append(")\n");
            sb.append("\n");
        }

        sb.append("─".repeat(25)).append("\n");
        sb.append("⚡ FootballPredictor v2 — ALL-IN · Triple Blend + xG\n");
        sb.append("🎯 Estrategia ALL-IN (máx P(1°))\n");
        sb.append("📱 ").append(java.time.LocalDateTime.now()).append("\n");

        return sb.toString();
    }
}
