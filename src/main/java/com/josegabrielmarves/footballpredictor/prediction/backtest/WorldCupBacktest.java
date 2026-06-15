package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Backtest del motor sobre Mundiales reales (dataset A).
 * Usa los fixtures worldcup2018.json y worldcup2022.json de openfootball.
 * Formato: score.ft = [homeGoals, awayGoals]
 *
 * A diferencia del BacktestEngine (dataset B, walk-forward con update Elo),
 * este backtest usa los ratings calibrados estáticos — es in-sample pero
 * útil para comparar predicciones del modelo vs resultados reales del Mundial.
 */
public final class WorldCupBacktest {

    private WorldCupBacktest() {}

    private record WCMatch(String home, String away, int hg, int ag) {}

    /**
     * Corre el backtest sobre un archivo de fixture openfootball.
     * Solo evalúa partidos con resultado (ft != null).
     *
     * @param dataFile ruta al JSON (ej: Paths.get("data/worldcup2022.json"))
     */
    public static BacktestMetrics run(Path dataFile) {
        List<WCMatch> matches = load(dataFile);
        Map<String, EloRating> ratings = new HashMap<>();
        BacktestMetrics metrics = new BacktestMetrics();

        for (WCMatch m : matches) {
            EloRating home = ratings.computeIfAbsent(m.home(),
                    k -> CalibratedEloRatings.getRating(k));
            EloRating away = ratings.computeIfAbsent(m.away(),
                    k -> CalibratedEloRatings.getRating(k));

            // Detectar si es local real (solo anfitriones del torneo)
            // Para 2022: Qatar; para 2018: Rusia; para 2026: México/USA/Canadá
            double homeBonus = 0.0; // cancha neutral para todos

            PoissonPredictor.MatchProbabilities probs =
                    PoissonPredictor.matchProbabilities(home, away, homeBonus);

            BacktestMetrics.Outcome actual = m.hg() > m.ag()
                    ? BacktestMetrics.Outcome.HOME_WIN
                    : m.hg() < m.ag()
                      ? BacktestMetrics.Outcome.AWAY_WIN
                      : BacktestMetrics.Outcome.DRAW;

            metrics.add(probs, actual);

            // Actualizar Elo después de predecir (evita look-ahead)
            EloCalculator.UpdatedRatings updated = EloCalculator.updateRatings(
                    home, away, m.hg(), m.ag(), EloCalculator.K_WORLD_CUP, homeBonus);
            ratings.put(m.home(), updated.home());
            ratings.put(m.away(), updated.away());
        }

        return metrics;
    }

    private static List<WCMatch> load(Path dataFile) {
        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            JsonArray matches = root.getAsJsonArray("matches");
            List<WCMatch> result = new ArrayList<>();

            for (var el : matches) {
                JsonObject m = el.getAsJsonObject();
                String home = m.get("team1").getAsString();
                String away = m.get("team2").getAsString();

                if (!m.has("score") || m.get("score").isJsonNull()) continue;
                JsonObject score = m.getAsJsonObject("score");
                if (!score.has("ft") || score.get("ft").isJsonNull()) continue;

                JsonArray ft = score.getAsJsonArray("ft");
                int hg = ft.get(0).getAsInt();
                int ag = ft.get(1).getAsInt();
                result.add(new WCMatch(home, away, hg, ag));
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("No se pudo leer: " + dataFile, e);
        }
    }

    /** Punto de entrada: corre backtest sobre 2018 y 2022 e imprime resultados. */
    public static void main(String[] args) {
        for (String year : new String[]{"2018", "2022"}) {
            Path file = Path.of("data/worldcup" + year + ".json");
            BacktestMetrics m = run(file);
            System.out.printf("%n=== Mundial %s — %d partidos ===%n", year, m.matches());
            System.out.printf("  Accuracy : %.1f%%%n", m.accuracy() * 100);
            System.out.printf("  Brier    : %.3f%n", m.brier());
            System.out.printf("  Log-loss : %.3f%n", m.logLoss());
            System.out.printf("  RPS      : %.4f%n", m.rps());
        }
        System.out.println("\n  Referencia dataset B: 62.8% / Brier 0.508 / RPS 0.1685");
    }
}