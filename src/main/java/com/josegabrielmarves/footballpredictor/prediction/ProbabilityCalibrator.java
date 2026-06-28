package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestMetrics;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestMetrics.Outcome;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestPipeline;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

import java.nio.file.Path;
import java.util.*;

public final class ProbabilityCalibrator {

    private final double[] correctionFactors;

    private final double aHome, bHome, aDraw, bDraw, aAway, bAway;

    private ProbabilityCalibrator(double adjustHome, double adjustDraw, double adjustAway,
                                   double aHome, double bHome, double aDraw, double bDraw,
                                   double aAway, double bAway) {
        this.correctionFactors = new double[]{adjustHome, adjustDraw, adjustAway};
        this.aHome = aHome; this.bHome = bHome;
        this.aDraw = aDraw; this.bDraw = bDraw;
        this.aAway = aAway; this.bAway = bAway;
    }

    public ProbabilityCalibrator(double adjustHome, double adjustDraw, double adjustAway) {
        this(adjustHome, adjustDraw, adjustAway,
             1.0, 0.0, 1.0, 0.0, 1.0, 0.0);
    }

    public ProbabilityCalibrator() {
        this(0.95, 1.10, 0.95);
    }

    public PoissonPredictor.MatchProbabilities calibrate(PoissonPredictor.MatchProbabilities probs) {
        double h = clamp(probs.homeWin() * correctionFactors[0]);
        double d = clamp(probs.draw() * correctionFactors[1]);
        double a = clamp(probs.awayWin() * correctionFactors[2]);
        double sum = h + d + a;
        return new PoissonPredictor.MatchProbabilities(h / sum, d / sum, a / sum);
    }

    public double[] calibrateArray(double[] probs) {
        double h = clamp(probs[0] * correctionFactors[0]);
        double d = clamp(probs[1] * correctionFactors[1]);
        double a = clamp(probs[2] * correctionFactors[2]);
        double sum = h + d + a;
        return new double[]{h / sum, d / sum, a / sum};
    }

    private static double clamp(double p) {
        return Math.max(0.01, Math.min(0.99, p));
    }

    @Override public String toString() {
        return String.format("ProbabilityCalibrator(h=%.2f, d=%.2f, a=%.2f)",
                correctionFactors[0], correctionFactors[1], correctionFactors[2]);
    }

    public double[] calibratePlatt(double homeWin, double draw, double awayWin) {
        double h = platt(homeWin, aHome, bHome);
        double d = platt(draw, aDraw, bDraw);
        double a = platt(awayWin, aAway, bAway);
        double sum = h + d + a;
        return new double[]{h / sum, d / sum, a / sum};
    }

    private static double platt(double p, double a, double b) {
        double logit = Math.log(Math.max(p, 1e-15) / Math.max(1 - p, 1e-15));
        double q = 1.0 / (1.0 + Math.exp(-(a * logit + b)));
        return Math.max(0.01, Math.min(0.99, q));
    }

    public static ProbabilityCalibrator trainPlatt(
            List<double[]> predictedProbs, List<Outcome> actualOutcomes) {

        double aH = 1.0, bH = 0.0, aD = 1.0, bD = 0.0, aA = 1.0, bA = 0.0;
        double lr = 0.01;

        for (int epoch = 0; epoch < 1000; epoch++) {
            double gradAH = 0, gradBH = 0, gradAD = 0, gradBD = 0, gradAA = 0, gradBA = 0;

            for (int i = 0; i < predictedProbs.size(); i++) {
                double[] p = predictedProbs.get(i);
                Outcome actual = actualOutcomes.get(i);

                double yH = actual == Outcome.HOME_WIN ? 1.0 : 0.0;
                double yD = actual == Outcome.DRAW ? 1.0 : 0.0;
                double yA = actual == Outcome.AWAY_WIN ? 1.0 : 0.0;

                double qH = platt(p[0], aH, bH);
                double qD = platt(p[1], aD, bD);
                double qA = platt(p[2], aA, bA);
                double sum = qH + qD + qA;
                qH /= sum; qD /= sum; qA /= sum;

                double logitH = Math.log(Math.max(p[0], 1e-15) / Math.max(1 - p[0], 1e-15));
                double logitD = Math.log(Math.max(p[1], 1e-15) / Math.max(1 - p[1], 1e-15));
                double logitA = Math.log(Math.max(p[2], 1e-15) / Math.max(1 - p[2], 1e-15));

                gradAH += (qH - yH) * logitH * qH * (1 - qH);
                gradBH += (qH - yH) * qH * (1 - qH);
                gradAD += (qD - yD) * logitD * qD * (1 - qD);
                gradBD += (qD - yD) * qD * (1 - qD);
                gradAA += (qA - yA) * logitA * qA * (1 - qA);
                gradBA += (qA - yA) * qA * (1 - qA);
            }

            aH -= lr * gradAH / predictedProbs.size();
            bH -= lr * gradBH / predictedProbs.size();
            aD -= lr * gradAD / predictedProbs.size();
            bD -= lr * gradBD / predictedProbs.size();
            aA -= lr * gradAA / predictedProbs.size();
            bA -= lr * gradBA / predictedProbs.size();
        }

        return new ProbabilityCalibrator(1.0, 1.0, 1.0, aH, bH, aD, bD, aA, bA);
    }

    public static ProbabilityCalibrator trainFromBacktest(
            Path dataFile, int burnIn, BacktestPipeline.PipelineConfig config) {
        var result = BacktestPipeline.run(dataFile, burnIn, false, config);
        double homeAcc = result.metrics().accuracy();
        double brier = result.metrics().brier();
        double homeCf = Math.min(1.2, Math.max(0.8, homeAcc / 0.45));
        return new ProbabilityCalibrator(homeCf, 1.0 / homeCf, 1.0);
    }

    public static void main(String[] args) {
        ProbabilityCalibrator cal = new ProbabilityCalibrator();
        PoissonPredictor.MatchProbabilities test =
                new PoissonPredictor.MatchProbabilities(0.55, 0.25, 0.20);
        PoissonPredictor.MatchProbabilities calib = cal.calibrate(test);
        System.out.printf("Original: %.1f%% / %.1f%% / %.1f%%%n",
                test.homeWin() * 100, test.draw() * 100, test.awayWin() * 100);
        System.out.printf("Calibrado: %.1f%% / %.1f%% / %.1f%%%n",
                calib.homeWin() * 100, calib.draw() * 100, calib.awayWin() * 100);
    }
}
