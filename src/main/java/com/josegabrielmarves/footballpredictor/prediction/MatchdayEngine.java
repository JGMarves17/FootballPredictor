package com.josegabrielmarves.footballpredictor.prediction;

import com.google.gson.*;
import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.MatchEV;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Motor de ciclo de vida de jornada.
 *
 * ANTES del día  → preMatchday():  500k sims por partido, genera JSON de predicciones
 * DESPUÉS del día → postMatchday(): actualiza Elo, guarda reporte de accuracy
 */
public final class MatchdayEngine {

    private static final int SIMS       = ScoreMatrix.DEFAULT_SIMS;
    private static final Path DATA_FILE = Path.of("data/results.json");
    private static final Path PRED_DIR  = Path.of("data/predictions");

    private MatchdayEngine() {}

    // ── Entrada de datos ──────────────────────────────────────────────────────
    //
    // ⚠️  En un Mundial NO hay "local" y "visitante" fijos (salvo sedes).
    //      Usamos team1/team2 como nombres neutrales. El bonus de sede
    //      se calcula automáticamente con hostBonus() según el equipo.
    //
    public record MatchInput(String team1, String team2, QuinielaScorer.Stage stage) {}
    public record MatchResult(String team1, String team2, int goals1, int goals2) {}

    /** ¿Este equipo juega como sede (México/USA/Canada)? */
    public static boolean isHost(String team) {
        return team.equals("Mexico") || team.equals("USA")
            || team.equals("United States") || team.equals("Canada");
    }

    /** Bonus de localía si el equipo es sede. */
    public static double hostBonus(String team) {
        return isHost(team) ? EloCalculator.HOME_ADVANTAGE : 0.0;
    }

    // ── PRE-JORNADA ───────────────────────────────────────────────────────────

    /**
     * Corre 500k simulaciones por cada partido del día y guarda predicciones.
     * También imprime el reporte en consola.
     *
     * @param jornada número de jornada
     * @param matches partidos del día
     * @param ratings ratings Elo actualizados con resultados previos
     * @param today   fecha de referencia
     */
    public static void preMatchday(int jornada, List<MatchInput> matches,
                                   Map<String, EloRating> ratings, LocalDate today) {
        PoissonPredictor.setRefDate(today);

        System.out.printf("%n╔══════════════════════════════════════════════════╗%n");
        System.out.printf("║  PRE-JORNADA %d — %s  (%,d simulaciones)  ║%n",
                jornada, today, SIMS);
        System.out.printf("╚══════════════════════════════════════════════════╝%n");

        JsonArray jsonMatches = new JsonArray();
        long seed = today.toEpochDay();

        for (MatchInput m : matches) {
            double bonus = hostBonus(m.team1()); // team1 es local simbólico en el fixture
            EloRating home = ratings.getOrDefault(m.team1(),
                    CalibratedEloRatings.getRating(m.team1()));
            EloRating away = ratings.getOrDefault(m.team2(),
                    CalibratedEloRatings.getRating(m.team2()));

            ScoreMatrix matrix = ScoreMatrix.compute(
                    m.team1(), home, m.team2(), away,
                    bonus, seed++, ScoreMatrix.DEFAULT_SIMS, m.stage());
            matrix.print();

            // Predicción óptima para quiniela
            MatchEV.Candidate optimal = MatchEV.best(home, away, bonus, m.stage());
            MatchEV.Risk risk = MatchEV.risk(home, away, bonus);

            // JSON
            JsonObject jm = new JsonObject();
            jm.addProperty("home", m.team1());
            jm.addProperty("away", m.team2());
            jm.addProperty("pHomeWin",  Math.round(matrix.pHomeWin()  * 1000) / 1000.0);
            jm.addProperty("pDraw",     Math.round(matrix.pDraw()     * 1000) / 1000.0);
            jm.addProperty("pAwayWin",  Math.round(matrix.pAwayWin()  * 1000) / 1000.0);

            JsonArray topScores = new JsonArray();
            for (ScoreMatrix.ScoredPrediction sp : matrix.topN(3)) {
                JsonObject js = new JsonObject();
                js.addProperty("score", sp.score().homeGoals() + "-" + sp.score().awayGoals());
                js.addProperty("probability", Math.round(sp.probability() * 1000) / 1000.0);
                topScores.add(js);
            }
            jm.add("topScores", topScores);

            JsonObject quinielaJson = new JsonObject();
            Score modal = matrix.mostLikelyScore();
            quinielaJson.addProperty("honest",  modal.homeGoals() + "-" + modal.awayGoals());
            quinielaJson.addProperty("optimal", optimal.score().homeGoals() + "-" + optimal.score().awayGoals());
            quinielaJson.addProperty("risk",    risk.name());
            quinielaJson.addProperty("evPoints", Math.round(optimal.expectedPoints() * 1000) / 1000.0);
            jm.add("quiniela", quinielaJson);

            jsonMatches.add(jm);
        }

        // Guardar JSON
        savePredictions(jornada, today, jsonMatches);
    }

    // ── POST-JORNADA ──────────────────────────────────────────────────────────

    /**
     * Actualiza ratings Elo con los resultados del día y genera reporte de accuracy.
     *
     * @param results    resultados reales del día
     * @param ratings    mapa de ratings a actualizar (se modifica in-place)
     * @param jornada    número de jornada
     */
    public static void postMatchday(int jornada, List<MatchResult> results,
                                    Map<String, EloRating> ratings) {
        System.out.printf("%n╔══════════════════════════════════════════════════╗%n");
        System.out.printf("║  POST-JORNADA %d — Reporte de accuracy           ║%n", jornada);
        System.out.printf("╚══════════════════════════════════════════════════╝%n%n");

        // Cargar predicciones guardadas
        Path predFile = PRED_DIR.resolve("jornada_" + jornada + ".json");
        Map<String, JsonObject> savedPreds = loadPredictions(predFile);

        int total = 0, hitResult = 0, hitExact = 0;

        for (MatchResult r : results) {
            String key = r.team1() + ":" + r.team2();
            String actualResult = r.goals1() > r.goals2() ? "1" : r.goals1() < r.goals2() ? "2" : "X";
            String actualScore  = r.goals1() + "-" + r.goals2();

            boolean correctResult = false, correctExact = false;
            String predicted = "?", predictedResult = "?";

            if (savedPreds.containsKey(key)) {
                JsonObject pred = savedPreds.get(key);
                JsonObject q = pred.getAsJsonObject("quiniela");
                predicted = q.get("honest").getAsString();
                String[] parts = predicted.split("-");
                int ph = Integer.parseInt(parts[0]), pa = Integer.parseInt(parts[1]);
                predictedResult = ph > pa ? "1" : ph < pa ? "2" : "X";
                correctResult = predictedResult.equals(actualResult);
                correctExact  = predicted.equals(actualScore);
            }

            if (correctResult) hitResult++;
            if (correctExact)  hitExact++;
            total++;

            System.out.printf("  %s vs %s%n", r.team1(), r.team2());
            System.out.printf("    Real: %s  |  Predicho: %s  |  %s%n",
                    actualScore, predicted,
                    correctExact  ? "✅ EXACTO" :
                            correctResult ? "🟡 resultado" : "❌ fallo");

            // Actualizar Elo
            EloRating home = ratings.getOrDefault(r.team1(),
                    CalibratedEloRatings.getRating(r.team1()));
            EloRating away = ratings.getOrDefault(r.team2(),
                    CalibratedEloRatings.getRating(r.team2()));
            double bonus = hostBonus(r.team1()); // team1 es local simbólico
            var updated = EloCalculator.updateRatings(home, away,
                    r.goals1(), r.goals2(), EloCalculator.K_WORLD_CUP, bonus);
            ratings.put(r.team1(), updated.home());
            ratings.put(r.team2(), updated.away());
        }

        System.out.printf("%n  ── Jornada %d: %d/%d resultados (%.0f%%) | %d/%d exactos (%.0f%%) ──%n%n",
                jornada, hitResult, total, total > 0 ? 100.0*hitResult/total : 0,
                hitExact, total, total > 0 ? 100.0*hitExact/total : 0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void savePredictions(int jornada, LocalDate today, JsonArray matches) {
        try {
            Files.createDirectories(PRED_DIR);
            JsonObject root = new JsonObject();
            root.addProperty("jornada", jornada);
            root.addProperty("generated", LocalDateTime.now().toString());
            root.addProperty("date", today.toString());
            root.add("matches", matches);
            Path file = PRED_DIR.resolve("jornada_" + jornada + ".json");
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
            System.out.println("  → Predicciones guardadas en " + file);
        } catch (IOException e) {
            System.err.println("[MatchdayEngine] Error guardando: " + e.getMessage());
        }
    }

    private static Map<String, JsonObject> loadPredictions(Path file) {
        Map<String, JsonObject> result = new HashMap<>();
        if (!Files.exists(file)) return result;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(r, JsonObject.class);
            for (JsonElement el : root.getAsJsonArray("matches")) {
                JsonObject m = el.getAsJsonObject();
                String key = m.get("home").getAsString() + ":" + m.get("away").getAsString();
                result.put(key, m);
            }
        } catch (IOException e) {
            System.err.println("[MatchdayEngine] Error cargando: " + e.getMessage());
        }
        return result;
    }

}