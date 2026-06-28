package com.josegabrielmarves.footballpredictor.prediction.backtest;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class BacktestReport {

    private BacktestReport() {}

    public static void printFull(Path dataFile, int burnIn, boolean seedCalibrated) {
        var eloConfig = BacktestPipeline.PipelineConfig.eloOnly();
        var tbConfig = BacktestPipeline.PipelineConfig.tripleBlendDefault();

        var eloResult = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, eloConfig);
        var tbResult = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, tbConfig);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║  BACKTEST REPORT — " + ts + "           ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        System.out.printf("%n─── Pipeline: Elo puro ───%n");
        printMetrics(eloResult);
        printResiduals(eloResult);

        System.out.printf("%n─── Pipeline: Triple Blend (wElo=%.2f wForm=%.2f wGlm=%.2f) ───%n",
                tbConfig.wElo(), tbConfig.wForm(), tbConfig.wGlm());
        printMetrics(tbResult);
        printResiduals(tbResult);

        System.out.printf("%n─── MEJOR PESOS (Grid Search) ───%n");
        var weights = WeightOptimizer.gridSearch(dataFile, burnIn, seedCalibrated);
        WeightOptimizer.printTop(weights, 5);

        System.out.printf("%n─── MEJOR ρ (Elo puro) ───%n");
        var rhoResults = RhoOptimizer.gridSearch(dataFile, burnIn, seedCalibrated, eloConfig);
        RhoOptimizer.printTop(rhoResults, 3);

        System.out.printf("%n─── MEJORES PARÁMETROS ───%n");
        var specs = List.of(
                new ParamOptimizer.ParamSpec("BASELINE_GOALS", 1.0, 1.8, 0.2),
                new ParamOptimizer.ParamSpec("ELO_GOAL_SCALE", 600, 900, 100),
                new ParamOptimizer.ParamSpec("homeAdvantage", 50, 100, 25)
        );
        var params = ParamOptimizer.individualSearch(dataFile, burnIn, seedCalibrated, eloConfig, specs);
        ParamOptimizer.printTop(params, 5);

        System.out.printf("%n─── RECOMENDACIONES ───%n");
        if (tbResult.metrics().accuracy() > eloResult.metrics().accuracy()) {
            System.out.printf("  ✅ Triple Blend (%.1f%%) supera a Elo puro (%.1f%%)%n",
                    tbResult.metrics().accuracy()*100, eloResult.metrics().accuracy()*100);
        } else {
            System.out.printf("  ⚠ Triple Blend (%.1f%%) NO supera a Elo puro (%.1f%%)%n",
                    tbResult.metrics().accuracy()*100, eloResult.metrics().accuracy()*100);
        }

        if (tbResult.residuals() != null) {
            System.out.printf("  ⚠ Peor segmento: %s (%.1f%% accuracy)%n",
                    tbResult.residuals().worstSegment(), tbResult.residuals().worstSegmentMetric()*100);
        }

        System.out.printf("  💡 Ejecutar WeightOptimizer.main() para optimizar pesos%n");
        System.out.printf("  💡 Ejecutar RhoOptimizer.main() para recalibrar ρ%n");
    }

    private static void printMetrics(BacktestPipeline.PipelineResult r) {
        var m = r.metrics();
        System.out.printf("  Accuracy : %.1f%%%n", m.accuracy() * 100);
        System.out.printf("  Brier    : %.4f%n", m.brier());
        System.out.printf("  Log-loss : %.4f%n", m.logLoss());
        System.out.printf("  RPS      : %.4f%n", m.rps());
        System.out.printf("  Partidos : %d  (%.1fs)%n", m.matches(), r.elapsedMs() / 1000.0);
    }

    private static void printResiduals(BacktestPipeline.PipelineResult r) {
        if (r.residuals() == null) return;
        System.out.println("  Residuos por segmento:");
        for (var e : r.residuals().byFavorite().entrySet()) {
            System.out.printf("    %-20s: acc=%.1f%%  brier=%.4f%n",
                    e.getKey(), e.getValue().accuracy()*100, e.getValue().brier());
        }
    }

    public static void printComparison(Path dataFile, int burnIn, boolean seedCalibrated,
                                        BacktestPipeline.PipelineConfig configA,
                                        BacktestPipeline.PipelineConfig configB) {
        var rA = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, configA);
        var rB = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, configB);
        System.out.printf("%n─── Comparación ───%n");
        System.out.printf("%-30s %-10s %-10s%n", "Métrica", "Config A", "Config B");
        System.out.printf("%-30s %-10.1f %-10.1f%n", "Accuracy (%)",
                rA.metrics().accuracy()*100, rB.metrics().accuracy()*100);
        System.out.printf("%-30s %-10.4f %-10.4f%n", "Brier",
                rA.metrics().brier(), rB.metrics().brier());
        System.out.printf("%-30s %-10.4f %-10.4f%n", "RPS",
                rA.metrics().rps(), rB.metrics().rps());
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        int burnIn = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        boolean seedCalibrated = args.length > 1 && "calibrated".equals(args[1]);
        printFull(dataFile, burnIn, seedCalibrated);
    }
}
