package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.google.gson.Gson;
import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestMetrics.Outcome;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Motor de backtesting walk-forward out-of-sample (Fase 5b).
 *
 * <p>Protocolo: cada partido se predice con los ratings construidos SOLO
 * sobre partidos anteriores; Elo se actualiza después. Sin look-ahead.
 *
 * <p>Replica el protocolo de la referencia MIT
 * (Hicruben/world-cup-2026-prediction-model) sobre el mismo dataset
 * ({@code data/results.json}, 913 partidos nov-2023→jun-2026):
 * baseline esperado ~61% accuracy, Brier ~0.54.
 *
 * <p><b>Simplificación deliberada:</b> {@link #HOME_ADV} se aplica a TODOS
 * los partidos del dataset (igual que la referencia) para que la comparación
 * sea directa. En el motor de predicción del Mundial 2026 solo aplica a
 * México, USA y Canadá.
 */
public final class BacktestEngine {

    /** Partidos iniciales excluidos de métricas mientras los ratings calientan. */
    public static final int DEFAULT_BURN_IN = 150;

    /** Ventaja de local fija para todo el dataset (simplificación de la referencia). */
    private static final double HOME_ADV = EloCalculator.HOME_ADVANTAGE;

    private BacktestEngine() {}

    /** Ejecuta el backtest con burn-in por defecto y semilla CALIBRADA (replica la referencia). */
    public static BacktestMetrics run(Path dataFile) {
        return run(dataFile, DEFAULT_BURN_IN, true);
    }

    /** Ejecuta el backtest con semilla CALIBRADA (replica la referencia). */
    public static BacktestMetrics run(Path dataFile, int burnIn) {
        return run(dataFile, burnIn, true);
    }

    /**
     * Ejecuta el backtest walk-forward y devuelve las métricas acumuladas.
     *
     * @param dataFile       ruta a results.json (relativa a la raíz del repo)
     * @param burnIn         partidos iniciales excluidos de la evaluación
     * @param seedCalibrated semilla de los ratings:
     *   <ul>
     *     <li>{@code true}  = ratings calibrados (replica la referencia MIT).
     *         OJO: esos ratings se ajustaron sobre TODO el periodo del dataset,
     *         así que el arranque ya incluye información del futuro → infla el
     *         resultado (fuga parcial).</li>
     *     <li>{@code false} = arranque PLANO (todos en {@link EloRating#DEFAULT_RATING});
     *         el Elo se construye solo hacia adelante a partir de la secuencia
     *         in-sample, sin fuga → número honesto/causal. Es el modo a usar para
     *         evaluar de verdad el modelo y futuras mejoras.</li>
     *   </ul>
     */
    public static BacktestMetrics run(Path dataFile, int burnIn, boolean seedCalibrated) {
        List<HistoricalMatch> matches = load(dataFile);
        Map<String, Double> ratings = new HashMap<>();
        BacktestMetrics metrics = new BacktestMetrics();
        int i = 0;

        for (HistoricalMatch m : matches) {
            double ra = getRating(ratings, m.homeSlug, m.homeName, seedCalibrated);
            double rb = getRating(ratings, m.awaySlug, m.awayName, seedCalibrated);
            EloRating home = EloRating.initial(m.homeName).withRating(ra);
            EloRating away = EloRating.initial(m.awayName).withRating(rb);

            if (i >= burnIn) {
                PoissonPredictor.MatchProbabilities probs =
                        PoissonPredictor.matchProbabilities(home, away, HOME_ADV);
                metrics.add(probs, Outcome.of(new Score(m.hg, m.ag)));
            }

            EloCalculator.UpdatedRatings updated =
                    EloCalculator.updateRatings(home, away, m.hg, m.ag,
                            leagueToK(m.leagueName), HOME_ADV);
            setRating(ratings, m.homeSlug, m.homeName, updated.home().rating());
            setRating(ratings, m.awaySlug, m.awayName, updated.away().rating());
            i++;
        }
        return metrics;
    }

    // ── carga ────────────────────────────────────────────────────────────────

    private static List<HistoricalMatch> load(Path dataFile) {
        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, ResultsWrapper.class).matches;
        } catch (IOException e) {
            throw new RuntimeException("No se pudo leer: " + dataFile, e);
        }
    }

    /** Wrapper para la clave raíz "matches" del JSON. */
    private static final class ResultsWrapper {
        List<HistoricalMatch> matches;
    }

    /**
     * DTO de deserialización. Campos nombrados igual que el JSON (hg/ag)
     * para que Gson los mapee sin @SerializedName.
     */
    private static final class HistoricalMatch {
        String homeName;
        String awayName;
        String homeSlug;   // null en algunos registros del dataset
        String awaySlug;   // null en algunos registros del dataset
        int    hg;
        int    ag;
        String leagueName;
    }

    // ── ratings ──────────────────────────────────────────────────────────────

    private static double getRating(Map<String, Double> ratings,
                                    String slug, String name, boolean seedCalibrated) {
        return ratings.computeIfAbsent(key(slug, name),
                k -> seedCalibrated ? CalibratedEloRatings.getRating(name).rating()
                                    : EloRating.DEFAULT_RATING);
    }

    private static void setRating(Map<String, Double> ratings,
                                  String slug, String name, double rating) {
        ratings.put(key(slug, name), rating);
    }

    /** Clave del mapa: slug si disponible, "ghost:<name>" si no (igual que referencia). */
    private static String key(String slug, String name) {
        return slug != null ? slug : "ghost:" + name;
    }

    // ── liga → K ─────────────────────────────────────────────────────────────

    /**
     * Mapea leagueName del dataset al K-factor correcto.
     * Portado de los regex JS de la referencia a contains() lowercase.
     * Cubre los 26 valores distintos de leagueName en results.json.
     * Package-private para tests unitarios de esta lógica.
     */
    static double leagueToK(String leagueName) {
        if (leagueName == null) return EloCalculator.K_DEFAULT;
        String l = leagueName.toLowerCase();
        if (l.contains("world cup") && !l.contains("qual")) return EloCalculator.K_WORLD_CUP;
        if (l.contains("qual"))                              return EloCalculator.K_QUALIFIER;
        if (l.contains("copa america")      ||
                l.contains("euro championship") ||
                l.contains("asian cup")         ||
                l.contains("africa cup")        ||
                l.contains("gold cup"))                          return EloCalculator.K_CONTINENTAL;
        if (l.contains("nations league")    ||
                l.contains("nations cup"))                       return EloCalculator.K_NATIONS_LEAGUE;
        if (l.contains("friendl"))                           return EloCalculator.K_FRIENDLY;
        return EloCalculator.K_DEFAULT;
    }
}