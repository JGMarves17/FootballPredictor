package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

import java.util.*;

public final class ProbabilityCalibrator {

    private final double[] correctionFactors;

    public ProbabilityCalibrator(double adjustHome, double adjustDraw, double adjustAway) {
        this.correctionFactors = new double[]{adjustHome, adjustDraw, adjustAway};
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
