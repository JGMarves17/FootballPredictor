package com.josegabrielmarves.footballpredictor.prediction.backtest;

import java.nio.file.Path;
import java.util.List;

public final class WeightEnsemble {

    public record EnsembleConfig(
        List<BacktestPipeline.PipelineConfig> configs,
        double accuracy, double brier, double logLoss, double rps
    ) {}

    private WeightEnsemble() {}

    public static EnsembleConfig evaluate(Path dataFile, int burnIn,
                                           boolean seedCalibrated, int k) {
        List<WeightOptimizer.WeightResult> top = WeightOptimizer.gridSearch(
                dataFile, burnIn, seedCalibrated);
        List<BacktestPipeline.PipelineConfig> topConfigs = top.stream()
                .limit(k)
                .map(r -> BacktestPipeline.PipelineConfig.eloOnly()
                        .withWeights(r.wElo(), r.wForm(), r.wGlm()))
                .toList();

        double avgAcc = topConfigs.stream()
                .mapToDouble(c -> BacktestPipeline.run(dataFile, burnIn, seedCalibrated, c)
                        .metrics().accuracy())
                .average().orElse(0);
        double avgBrier = topConfigs.stream()
                .mapToDouble(c -> BacktestPipeline.run(dataFile, burnIn, seedCalibrated, c)
                        .metrics().brier())
                .average().orElse(0);

        return new EnsembleConfig(topConfigs, avgAcc, avgBrier, 0, 0);
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        EnsembleConfig ens = evaluate(dataFile, 150, false, 5);
        System.out.printf("%n=== Ensemble top-5 ===%n");
        System.out.printf("  Accuracy: %.1f%%  Brier: %.4f%n", ens.accuracy()*100, ens.brier());
    }
}
