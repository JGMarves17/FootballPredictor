package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.optim.*;
import org.apache.commons.math3.optim.nonlinear.scalar.*;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.CMAESOptimizer;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.linear.*;

import java.nio.file.Path;
import java.util.*;

public final class ParamOptimizer {

    public record ParamSpec(String name, double min, double max, double step) {}
    public record ParamResult(Map<String, Double> params, double accuracy,
                              double brier, double logLoss, double rps, int matches) {
        public double score() { return accuracy - 0.2 * brier; }
    }

    private ParamOptimizer() {}

    public static List<ParamResult> individualSearch(Path dataFile, int burnIn,
                                                      boolean seedCalibrated,
                                                      BacktestPipeline.PipelineConfig baseConfig,
                                                      List<ParamSpec> specs) {
        List<ParamResult> results = new ArrayList<>();
        for (ParamSpec spec : specs) {
            for (double v = spec.min(); v <= spec.max() + 1e-9; v += spec.step()) {
                var config = applyParam(baseConfig, spec.name(), v);
                var result = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, config);
                Map<String, Double> params = new HashMap<>();
                params.put(spec.name(), v);
                results.add(new ParamResult(params,
                        result.metrics().accuracy(), result.metrics().brier(),
                        result.metrics().logLoss(), result.metrics().rps(),
                        result.metrics().matches()));
            }
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    public static List<ParamResult> randomSearch(Path dataFile, int burnIn,
                                                  boolean seedCalibrated,
                                                  BacktestPipeline.PipelineConfig baseConfig,
                                                  List<ParamSpec> specs, int nSamples) {
        List<ParamResult> results = new ArrayList<>();
        Random rng = new Random(42);

        for (int i = 0; i < nSamples; i++) {
            Map<String, Double> params = new HashMap<>();
            var config = baseConfig;
            for (ParamSpec spec : specs) {
                double v = spec.min() + rng.nextDouble() * (spec.max() - spec.min());
                params.put(spec.name(), v);
                config = applyParam(config, spec.name(), v);
            }
            var result = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, config);
            results.add(new ParamResult(params,
                    result.metrics().accuracy(), result.metrics().brier(),
                    result.metrics().logLoss(), result.metrics().rps(),
                    result.metrics().matches()));
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    private static BacktestPipeline.PipelineConfig applyParam(
            BacktestPipeline.PipelineConfig config, String name, double value) {
        return switch (name) {
            case "BASELINE_GOALS" -> new BacktestPipeline.PipelineConfig(
                    config.useForm(), config.useGlm(), config.useConditioner(),
                    config.rho(), config.wElo(), config.wForm(), config.wGlm(),
                    value, config.eloGoalScale(), config.homeAdvantage());
            case "ELO_GOAL_SCALE" -> new BacktestPipeline.PipelineConfig(
                    config.useForm(), config.useGlm(), config.useConditioner(),
                    config.rho(), config.wElo(), config.wForm(), config.wGlm(),
                    config.baselineGoals(), value, config.homeAdvantage());
            case "homeAdvantage" -> new BacktestPipeline.PipelineConfig(
                    config.useForm(), config.useGlm(), config.useConditioner(),
                    config.rho(), config.wElo(), config.wForm(), config.wGlm(),
                    config.baselineGoals(), config.eloGoalScale(), value);
            default -> config;
        };
    }

    public static void printTop(List<ParamResult> results, int n) {
        System.out.printf("%n=== ParamOptimizer — Top %d ===%n", n);
        for (int i = 0; i < Math.min(n, results.size()); i++) {
            var r = results.get(i);
            System.out.printf("%-4d %s → acc=%.1f%% brier=%.4f score=%.4f%n",
                    i+1, r.params(), r.accuracy()*100, r.brier(), r.score());
        }
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        var specs = List.of(
                new ParamSpec("BASELINE_GOALS", 1.0, 1.8, 0.1),
                new ParamSpec("ELO_GOAL_SCALE", 600, 900, 50),
                new ParamSpec("homeAdvantage", 50, 100, 10)
        );
        long t0 = System.currentTimeMillis();
        var results = individualSearch(dataFile, 150, false, base, specs);
        System.out.printf("Individual search: %d combos en %ds%n", results.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(results, 10);

        t0 = System.currentTimeMillis();
        var rand = randomSearch(dataFile, 150, false, base, specs, 100);
        System.out.printf("%nRandom search: %d combos en %ds%n", rand.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(rand, 10);
    }
}
