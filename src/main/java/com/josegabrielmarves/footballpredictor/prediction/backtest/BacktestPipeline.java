package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.TournamentConditioner;
import com.josegabrielmarves.footballpredictor.prediction.TournamentGLM;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestMetrics.Outcome;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

public final class BacktestPipeline {

    public record PipelineConfig(
        boolean useForm,
        boolean useGlm,
        boolean useConditioner,
        double rho,
        double wElo, double wForm, double wGlm,
        double baselineGoals,
        double eloGoalScale,
        double homeAdvantage
    ) {
        public static PipelineConfig eloOnly() {
            return new PipelineConfig(false, false, false,
                    PoissonPredictor.DC_RHO, 1.0, 0.0, 0.0,
                    PoissonPredictor.BASE_GOALS, PoissonPredictor.ELO_GOAL_SCALE,
                    EloCalculator.HOME_ADVANTAGE);
        }

        public static PipelineConfig tripleBlendDefault() {
            return new PipelineConfig(true, true, true,
                    PoissonPredictor.DC_RHO_TOURNAMENT, 0.40, 0.25, 0.35,
                    PoissonPredictor.BASE_GOALS, PoissonPredictor.ELO_GOAL_SCALE,
                    EloCalculator.HOME_ADVANTAGE);
        }

        public PipelineConfig withWeights(double wElo, double wForm, double wGlm) {
            double s = wElo + wForm + wGlm;
            return new PipelineConfig(useForm, useGlm, useConditioner, rho,
                    wElo/s, wForm/s, wGlm/s, baselineGoals, eloGoalScale, homeAdvantage);
        }

        public PipelineConfig withRho(double rho) {
            return new PipelineConfig(useForm, useGlm, useConditioner, rho,
                    wElo, wForm, wGlm, baselineGoals, eloGoalScale, homeAdvantage);
        }
    }

    public record PipelineResult(
        BacktestMetrics metrics,
        ResidualReport residuals,
        long elapsedMs,
        PipelineConfig config
    ) {}

    public record ResidualReport(
        Map<String, BacktestMetrics> byStage,
        Map<String, BacktestMetrics> byFavorite,
        String worstSegment,
        double worstSegmentMetric
    ) {}

    private BacktestPipeline() {}

    public static PipelineResult run(Path dataFile, int burnIn,
                                      boolean seedCalibrated, PipelineConfig config) {
        List<HistoricalMatch> matches = load(dataFile);
        Map<String, Double> ratings = new HashMap<>();
        BacktestMetrics global = new BacktestMetrics();
        Map<String, BacktestMetrics> byStage = new HashMap<>();
        Map<String, BacktestMetrics> byFavorite = new HashMap<>();
        long t0 = System.currentTimeMillis();

        LocalDate refDate = LocalDate.of(2023, 11, 1);

        for (int i = 0; i < matches.size(); i++) {
            HistoricalMatch m = matches.get(i);
            double ra = getRating(ratings, m.homeSlug, m.homeName, seedCalibrated);
            double rb = getRating(ratings, m.awaySlug, m.awayName, seedCalibrated);
            EloRating home = EloRating.initial(m.homeName).withRating(ra);
            EloRating away = EloRating.initial(m.awayName).withRating(rb);

            if (i >= burnIn) {
                PoissonPredictor.MatchProbabilities probs;
                if (config.useForm || config.useGlm || config.useConditioner) {
                    PoissonPredictor.setRefDate(refDate);
                    double[][] matrix = buildBlendedMatrix(m, home, away, config);
                    probs = aggregateProbs(matrix);
                } else {
                    probs = PoissonPredictor.matchProbabilities(home, away, config.homeAdvantage);
                }

                Outcome actual = Outcome.of(new Score(m.hg, m.ag));
                global.add(probs, actual);

                String stage = classifyStage(i, matches.size());
                byStage.computeIfAbsent(stage, k -> new BacktestMetrics()).add(probs, actual);

                double eloDiff = home.rating() - away.rating();
                String fav = eloDiff > 50 ? "favoriteWins" : eloDiff < -50 ? "underdogWins" : "balanced";
                byFavorite.computeIfAbsent(fav, k -> new BacktestMetrics()).add(probs, actual);
            }

            EloCalculator.UpdatedRatings updated =
                    EloCalculator.updateRatings(home, away, m.hg, m.ag,
                            leagueToK(m.leagueName), config.homeAdvantage);
            setRating(ratings, m.homeSlug, m.homeName, updated.home().rating());
            setRating(ratings, m.awaySlug, m.awayName, updated.away().rating());

            refDate = refDate.plusDays(2);
        }

        long elapsed = System.currentTimeMillis() - t0;

        String worstSeg = "";
        double worstVal = Double.MAX_VALUE;
        for (var e : byFavorite.entrySet()) {
            double acc = e.getValue().accuracy();
            if (acc < worstVal) { worstVal = acc; worstSeg = e.getKey(); }
        }
        for (var e : byStage.entrySet()) {
            double acc = e.getValue().accuracy();
            if (acc < worstVal) { worstVal = acc; worstSeg = e.getKey(); }
        }

        ResidualReport resid = new ResidualReport(byStage, byFavorite, worstSeg, worstVal);
        return new PipelineResult(global, resid, elapsed, config);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double[][] buildBlendedMatrix(
            HistoricalMatch m, EloRating home, EloRating away, PipelineConfig config) {
        PoissonPredictor.setDataFile(Path.of("data/results.json"));
        double[] lambdas;
        if (config.useForm) {
            lambdas = PoissonPredictor.expectedGoalsBlended(
                    m.homeName, home, m.awayName, away, config.homeAdvantage);
        } else {
            double lEloH = PoissonPredictor.expectedGoalsElo(home.rating(), away.rating(), config.homeAdvantage);
            double lEloA = PoissonPredictor.expectedGoalsElo(away.rating(), home.rating(), -config.homeAdvantage / 2.0);
            lambdas = new double[]{lEloH, lEloA};
        }
        return buildMatrix(lambdas[0], lambdas[1], config.rho);
    }

    private static String classifyStage(int idx, int total) {
        if (idx < total * 0.4) return "groups";
        if (idx < total * 0.7) return "knockout";
        return "final";
    }

    private static PoissonPredictor.MatchProbabilities aggregateProbs(double[][] m) {
        double win = 0, draw = 0, loss = 0;
        for (int h = 0; h <= PoissonPredictor.MAX_GOALS; h++)
            for (int a = 0; a <= PoissonPredictor.MAX_GOALS; a++) {
                if (h > a) win += m[h][a];
                else if (h == a) draw += m[h][a];
                else loss += m[h][a];
            }
        return new PoissonPredictor.MatchProbabilities(win, draw, loss);
    }

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

    private static String key(String slug, String name) {
        return slug != null ? slug : "ghost:" + name;
    }

    static double leagueToK(String leagueName) {
        return HyperparameterOptimizer.leagueToK(leagueName);
    }

    static List<HistoricalMatch> load(Path dataFile) {
        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, ResultsWrapper.class).matches;
        } catch (IOException e) {
            throw new RuntimeException("No se pudo leer: " + dataFile, e);
        }
    }

    public static double[][] buildMatrix(double lH, double lA, double rho) {
        int max = PoissonPredictor.MAX_GOALS;
        double[][] m = new double[max + 1][max + 1];
        double total = 0.0;
        for (int h = 0; h <= max; h++) {
            double pH = PoissonPredictor.poissonPmf(h, lH);
            for (int a = 0; a <= max; a++) {
                double p = pH * PoissonPredictor.poissonPmf(a, lA) * dcTau(h, a, lH, lA, rho);
                m[h][a] = p; total += p;
            }
        }
        if (total > 0)
            for (int h = 0; h <= max; h++)
                for (int a = 0; a <= max; a++)
                    m[h][a] /= total;
        return m;
    }

    private static double dcTau(int h, int a, double lH, double lA, double rho) {
        if (h == 0 && a == 0) return 1 - lH * lA * rho;
        if (h == 0 && a == 1) return 1 + lH * rho;
        if (h == 1 && a == 0) return 1 + lA * rho;
        if (h == 1 && a == 1) return 1 - rho;
        return 1.0;
    }

    // ── Tournament mode ───────────────────────────────────────────────────────

    public static PipelineResult runTournament(Path xgFile, PipelineConfig config) {
        List<TournamentMatch> matches = loadTournament(xgFile);
        Map<String, Double> ratings = new HashMap<>();
        BacktestMetrics metrics = new BacktestMetrics();
        long t0 = System.currentTimeMillis();

        Map<String, EloRating> eloRatings = new HashMap<>();
        for (TournamentMatch m : matches) {
            eloRatings.put(m.homeName, CalibratedEloRatings.getRating(m.homeName));
            eloRatings.put(m.awayName, CalibratedEloRatings.getRating(m.awayName));
        }

        for (int i = 1; i < matches.size(); i++) {
            List<TournamentMatch> train = matches.subList(0, i);

            List<TournamentGLM.MatchData> matchDataList = new ArrayList<>();
            for (TournamentMatch tm : train) {
                matchDataList.add(new TournamentGLM.MatchData(
                        tm.homeName, tm.awayName, tm.hg, tm.ag, false));
            }

            TournamentGLM glm = TournamentGLM.fit(matchDataList, eloRatings);

            TournamentConditioner cond = TournamentConditioner.getInstance();
            cond.clear();
            for (TournamentMatch tm : train) {
                cond.addMatch(tm.homeName, tm.awayName, tm.homeXG, tm.awayXG, tm.hg, tm.ag);
            }

            PoissonPredictor.setGLM(glm);
            TournamentMatch current = matches.get(i);

            double ra = getRating(ratings, toSlug(current.homeName), current.homeName, true);
            double rb = getRating(ratings, toSlug(current.awayName), current.awayName, true);
            EloRating home = EloRating.initial(current.homeName).withRating(ra);
            EloRating away = EloRating.initial(current.awayName).withRating(rb);

            PoissonPredictor.MatchProbabilities probs =
                    PoissonPredictor.matchProbabilitiesTournament(
                            current.homeName, home, current.awayName, away, 0.0);

            Outcome actual = Outcome.of(new Score(current.hg, current.ag));
            metrics.add(probs, actual);

            EloCalculator.UpdatedRatings updated =
                    EloCalculator.updateRatings(home, away, current.hg, current.ag,
                            EloCalculator.K_WORLD_CUP, 0.0);
            setRating(ratings, toSlug(current.homeName), current.homeName, updated.home().rating());
            setRating(ratings, toSlug(current.awayName), current.awayName, updated.away().rating());
        }

        long elapsed = System.currentTimeMillis() - t0;
        return new PipelineResult(metrics, null, elapsed, config);
    }

    private static String toSlug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private record TournamentMatch(String homeName, String awayName,
                                    double homeXG, double awayXG, int hg, int ag) {}

    private static List<TournamentMatch> loadTournament(Path xgFile) {
        try (Reader reader = Files.newBufferedReader(xgFile, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            JsonArray arr = root.getAsJsonArray("matches");
            List<TournamentMatch> list = new ArrayList<>();
            for (JsonElement el : arr) {
                JsonArray m = el.getAsJsonArray();
                list.add(new TournamentMatch(
                        m.get(0).getAsString(), m.get(1).getAsString(),
                        m.get(2).getAsDouble(), m.get(3).getAsDouble(),
                        m.get(4).getAsInt(), m.get(5).getAsInt()));
            }
            return list;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    record ResultsWrapper(List<HistoricalMatch> matches) {}

    public record HistoricalMatch(String homeName, String awayName,
                                   String homeSlug, String awaySlug,
                                   int hg, int ag, String leagueName) {}
}
