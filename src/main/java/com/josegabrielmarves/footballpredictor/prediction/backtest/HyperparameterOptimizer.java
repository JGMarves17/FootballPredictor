package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * @deprecated Usar {@link RhoOptimizer} + {@link BacktestPipeline} en su lugar.
 *             Esta clase solo evalúa Elo puro (no Triple Blend) y no soporta
 *             PipelineConfig ni análisis por segmento.
 */
@Deprecated
public final class HyperparameterOptimizer {

    private HyperparameterOptimizer() {}

    /**
     * NOTA: El backtest histórico solo usa Elo (no hay datos de forma ni GLM
     * en el archivo de resultados históricos). Por tanto wElo/wForm/wGlm
     * no afectan el resultado (siempre usan lambda Elo). El grid search
     * se centra en rho, que es el único parámetro que impacta.
     */
    public record Hyperparameters(
            double rho
    ) {
        @Override public String toString() {
            return String.format("rho=%.3f", rho);
        }
    }

    public record Result(Hyperparameters params, BacktestMetrics metrics) {
        public double score() {
            return metrics.accuracy() - metrics.brier() * 0.2;
        }

        @Override public String toString() {
            return String.format("%s → acc=%.1f%% brier=%.4f logLoss=%.4f rps=%.4f score=%.4f",
                    params, metrics.accuracy() * 100, metrics.brier(),
                    metrics.logLoss(), metrics.rps(), score());
        }
    }

    public static List<Result> gridSearch(Path dataFile, int burnIn) {
        List<Result> results = new ArrayList<>();

        double[] rhoValues = {-0.17, -0.15, -0.13, -0.11, -0.09, -0.07, -0.05, -0.03};

        for (double rho : rhoValues) {
            Hyperparameters hp = new Hyperparameters(rho);
            BacktestMetrics metrics = runWithParams(dataFile, burnIn, hp);
            results.add(new Result(hp, metrics));
        }

        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    private static BacktestMetrics runWithParams(Path dataFile, int burnIn, Hyperparameters hp) {
        List<HistoricalMatch> matches = load(dataFile);
        Map<String, Double> ratings = new HashMap<>();
        BacktestMetrics metrics = new BacktestMetrics();
        int i = 0;

        for (HistoricalMatch m : matches) {
            double ra = getRating(ratings, m.homeSlug, m.homeName);
            double rb = getRating(ratings, m.awaySlug, m.awayName);
            EloRating home = EloRating.initial(m.homeName).withRating(ra);
            EloRating away = EloRating.initial(m.awayName).withRating(rb);

            if (i >= burnIn) {
                double[][] matrix = buildMatrixCustom(home, away,
                        EloCalculator.HOME_ADVANTAGE, hp);

                PoissonPredictor.MatchProbabilities probs = aggregateProbs(matrix);
                metrics.add(probs, BacktestMetrics.Outcome.of(new Score(m.hg, m.ag)));
            }

            EloCalculator.UpdatedRatings updated =
                    EloCalculator.updateRatings(home, away, m.hg, m.ag,
                            leagueToK(m.leagueName), EloCalculator.HOME_ADVANTAGE);
            setRating(ratings, m.homeSlug, m.homeName, updated.home().rating());
            setRating(ratings, m.awaySlug, m.awayName, updated.away().rating());
            i++;
        }
        return metrics;
    }

    private static double[][] buildMatrixCustom(EloRating home, EloRating away,
                                                double homeBonus, Hyperparameters hp) {
        double lH = PoissonPredictor.expectedGoalsElo(home.rating(), away.rating(), homeBonus);
        double lA = PoissonPredictor.expectedGoalsElo(away.rating(), home.rating(), -homeBonus / 2.0);
        return buildMatrix(lH, lA, hp.rho());
    }

    private static double[][] buildMatrix(double lH, double lA, double rho) {
        int maxGoals = 9;
        double[][] m = new double[maxGoals + 1][maxGoals + 1];
        double total = 0.0;
        for (int h = 0; h <= maxGoals; h++) {
            double pH = poissonPmf(h, lH);
            for (int a = 0; a <= maxGoals; a++) {
                double p = pH * poissonPmf(a, lA) * dcTau(h, a, lH, lA, rho);
                m[h][a] = p; total += p;
            }
        }
        if (total > 0)
            for (int h = 0; h <= maxGoals; h++)
                for (int a = 0; a <= maxGoals; a++)
                    m[h][a] /= total;
        return m;
    }

    private static double poissonPmf(int k, double lambda) {
        if (k < 0) return 0.0;
        if (lambda <= 0) return k == 0 ? 1.0 : 0.0;
        return new org.apache.commons.math3.distribution.PoissonDistribution(lambda).probability(k);
    }

    private static double dcTau(int h, int a, double lH, double lA, double rho) {
        if (h == 0 && a == 0) return 1 - lH * lA * rho;
        if (h == 0 && a == 1) return 1 + lH * rho;
        if (h == 1 && a == 0) return 1 + lA * rho;
        if (h == 1 && a == 1) return 1 - rho;
        return 1.0;
    }

    private static PoissonPredictor.MatchProbabilities aggregateProbs(double[][] m) {
        double win = 0, draw = 0, loss = 0;
        for (int h = 0; h < m.length; h++)
            for (int a = 0; a < m[h].length; a++) {
                if (h > a) win += m[h][a];
                else if (h == a) draw += m[h][a];
                else loss += m[h][a];
            }
        return new PoissonPredictor.MatchProbabilities(win, draw, loss);
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        int burnIn = args.length > 0 ? Integer.parseInt(args[0]) : 150;

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  HYPERPARAMETER GRID SEARCH                 ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println("Burn-in: " + burnIn);
        System.out.println();

        long t0 = System.currentTimeMillis();
        List<Result> results = gridSearch(dataFile, burnIn);
        long elapsed = (System.currentTimeMillis() - t0) / 1000;

        System.out.println("Resultados ordenados por score (acc - brier*0.2):");
        System.out.println("=".repeat(80));
        System.out.printf("%-4s %s%n", "#", "Configuración");
        System.out.println("-".repeat(80));
        for (int i = 0; i < Math.min(20, results.size()); i++) {
            System.out.printf("%-4d %s%n", i + 1, results.get(i));
        }
        System.out.println("=".repeat(80));

        System.out.printf("Grid search completo en %ds (%d combinaciones)%n",
                elapsed, results.size());

        if (!results.isEmpty()) {
            Result best = results.get(0);
            Result worst = results.get(results.size() - 1);
            System.out.printf("%nMejor:  %s%n", best);
            System.out.printf("Peor:   %s%n", worst);
            System.out.printf("Mejora: acc %+.1f%%  brier %+.4f%n",
                    (best.metrics.accuracy() - worst.metrics.accuracy()) * 100,
                    best.metrics.brier() - worst.metrics.brier());
        }
    }

    // ── Reuse from BacktestEngine ──────────────────────────────────────────────

    private record HistoricalMatch(String homeName, String awayName,
                                   String homeSlug, String awaySlug,
                                   int hg, int ag, String leagueName) {}

    private static final class ResultsWrapper {
        List<HistoricalMatch> matches;
        private ResultsWrapper() {}
    }

    private static List<HistoricalMatch> load(Path dataFile) {
        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, ResultsWrapper.class).matches;
        } catch (IOException e) {
            throw new RuntimeException("No se pudo leer: " + dataFile, e);
        }
    }

    private static double getRating(Map<String, Double> ratings,
                                    String slug, String name) {
        return ratings.computeIfAbsent(key(slug, name),
                k -> CalibratedEloRatings.getRating(name).rating());
    }

    private static void setRating(Map<String, Double> ratings,
                                  String slug, String name, double rating) {
        ratings.put(key(slug, name), rating);
    }

    private static String key(String slug, String name) {
        return slug != null ? slug : "ghost:" + name;
    }

    static double leagueToK(String leagueName) {
        if (leagueName == null) return EloCalculator.K_DEFAULT;
        String l = leagueName.toLowerCase();
        if (l.contains("world cup") && !l.contains("qual")) return EloCalculator.K_WORLD_CUP;
        if (l.contains("qual")) return EloCalculator.K_QUALIFIER;
        if (l.contains("copa america") || l.contains("euro championship")
                || l.contains("asian cup") || l.contains("africa cup")
                || l.contains("gold cup")) return EloCalculator.K_CONTINENTAL;
        if (l.contains("nations league") || l.contains("nations cup"))
            return EloCalculator.K_NATIONS_LEAGUE;
        if (l.contains("friendl")) return EloCalculator.K_FRIENDLY;
        return EloCalculator.K_DEFAULT;
    }
}
