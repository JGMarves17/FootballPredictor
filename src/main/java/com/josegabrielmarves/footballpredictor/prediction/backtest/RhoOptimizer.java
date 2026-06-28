package com.josegabrielmarves.footballpredictor.prediction.backtest;

import java.nio.file.Path;
import java.util.*;

public final class RhoOptimizer {

    public record RhoResult(double rho, double accuracy, double brier,
                            double logLoss, double rps, int matches) {
        public double score() { return accuracy - 0.2 * brier; }
    }

    private RhoOptimizer() {}

    public static List<RhoResult> gridSearch(Path dataFile, int burnIn,
                                              boolean seedCalibrated,
                                              BacktestPipeline.PipelineConfig baseConfig) {
        List<RhoResult> results = new ArrayList<>();
        for (int i = 0; i <= 17; i++) {
            double rho = -0.20 + i * 0.01;
            var config = baseConfig.withRho(rho);
            var result = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, config);
            results.add(new RhoResult(rho,
                    result.metrics().accuracy(), result.metrics().brier(),
                    result.metrics().logLoss(), result.metrics().rps(),
                    result.metrics().matches()));
        }
        results.sort(Comparator.comparingDouble(r -> r.brier()));
        return results;
    }

    public static void printTop(List<RhoResult> results, int n) {
        System.out.printf("%n=== RhoOptimizer — Top %d (por Brier) ===%n", n);
        System.out.printf("%-5s %-8s %-8s %-8s%n", "#", "\u03C1", "Brier", "Acc");
        for (int i = 0; i < Math.min(n, results.size()); i++) {
            var r = results.get(i);
            System.out.printf("%-5d %-8.2f %-8.4f %-8.1f%n",
                    i+1, r.rho(), r.brier(), r.accuracy()*100);
        }
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        long t0 = System.currentTimeMillis();
        List<RhoResult> grid = gridSearch(dataFile, 150, false, base);
        System.out.printf("Grid search: %d valores en %ds%n", grid.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(grid, 5);

        var tb = BacktestPipeline.PipelineConfig.tripleBlendDefault();
        t0 = System.currentTimeMillis();
        List<RhoResult> tbGrid = gridSearch(dataFile, 150, false, tb);
        System.out.printf("%nCon Triple Blend: %d valores en %ds%n", tbGrid.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(tbGrid, 5);
    }
}
