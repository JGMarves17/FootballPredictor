package com.josegabrielmarves.footballpredictor.prediction.backtest;

import java.nio.file.Path;
import java.util.*;

public final class WeightOptimizer {

    public record WeightResult(
        double wElo, double wForm, double wGlm,
        double accuracy, double brier, double logLoss, double rps,
        int matches
    ) {
        public double score() { return accuracy - 0.2 * brier; }
    }

    private WeightOptimizer() {}

    public static List<WeightResult> gridSearch(Path dataFile, int burnIn, boolean seedCalibrated) {
        List<WeightResult> results = new ArrayList<>();
        var base = BacktestPipeline.PipelineConfig.eloOnly();

        for (double we = 0.20; we <= 0.65; we += 0.05) {
            for (double wf = 0.00; wf <= 0.40; wf += 0.05) {
                for (double wg = 0.10; wg <= 0.50; wg += 0.05) {
                    double sum = we + wf + wg;
                    if (Math.abs(sum - 1.0) > 0.001) continue;
                    var config = base.withWeights(we, wf, wg);
                    var result = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, config);
                    results.add(new WeightResult(we, wf, wg,
                            result.metrics().accuracy(), result.metrics().brier(),
                            result.metrics().logLoss(), result.metrics().rps(),
                            result.metrics().matches()));
                }
            }
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    public static List<WeightResult> randomSearch(Path dataFile, int burnIn,
                                                   boolean seedCalibrated, int nSamples) {
        List<WeightResult> results = new ArrayList<>();
        Random rng = new Random(42);
        var base = BacktestPipeline.PipelineConfig.eloOnly();

        for (int i = 0; i < nSamples; i++) {
            double we = 0.20 + rng.nextDouble() * 0.45;
            double wf = rng.nextDouble() * 0.40;
            double wg = 1.0 - we - wf;
            if (wg < 0.10 || wg > 0.50) continue;
            var config = base.withWeights(we, wf, wg);
            var result = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, config);
            results.add(new WeightResult(we, wf, wg,
                    result.metrics().accuracy(), result.metrics().brier(),
                    result.metrics().logLoss(), result.metrics().rps(),
                    result.metrics().matches()));
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    public static WeightResult bestOf(List<WeightResult> results) {
        return results.isEmpty() ? null : results.get(0);
    }

    public static void printTop(List<WeightResult> results, int n) {
        System.out.printf("%n=== WeightOptimizer — Top %d ===%n", n);
        System.out.printf("%-5s %-6s %-6s %-6s %-8s %-8s %-8s%n",
                "#", "wElo", "wForm", "wGlm", "Acc", "Brier", "Score");
        for (int i = 0; i < Math.min(n, results.size()); i++) {
            var r = results.get(i);
            System.out.printf("%-5d %-6.2f %-6.2f %-6.2f %-8.1f %-8.4f %-8.4f%n",
                    i+1, r.wElo(), r.wForm(), r.wGlm(),
                    r.accuracy()*100, r.brier(), r.score());
        }
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        int burnIn = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        long t0 = System.currentTimeMillis();
        List<WeightResult> grid = gridSearch(dataFile, burnIn, false);
        System.out.printf("%nGrid search: %d combos en %ds%n", grid.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(grid, 10);

        t0 = System.currentTimeMillis();
        List<WeightResult> random = randomSearch(dataFile, burnIn, false, 200);
        System.out.printf("%nRandom search: %d combos en %ds%n", random.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(random, 10);
    }
}
