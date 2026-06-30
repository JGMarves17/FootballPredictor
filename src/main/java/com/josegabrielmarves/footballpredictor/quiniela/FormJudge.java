package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.FIFAFormCalculator;
import com.josegabrielmarves.footballpredictor.prediction.FIFAFormCalculator.FormResult;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Juez basado en la forma reciente (FIFAFormCalculator).
 * <p>
 * Analiza los últimos 50 partidos de cada equipo ponderados por
 * importancia FIFA × decaimiento temporal × calidad del rival.
 * <p>
 * Construye una matriz Poisson-DC propia a partir de los factores
 * de ataque/defensa de la forma reciente, sin contaminación Elo o GLM.
 */
public final class FormJudge implements MatchJudge {

    private static final String NAME = "Forma";
    private static final Path DATA_FILE = Path.of("data/results.json");

    private static final int MAX_GOALS = 5;
    private static final double MIN_LAMBDA = 0.20;
    private static final double MAX_LAMBDA = 5.00;
    private static final double BASE_GOALS = 1.35;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Verdict judge(String homeTeam, EloRating home,
                         String awayTeam, EloRating away,
                         double homeBonus, Stage stage) {
        LocalDate today = LocalDate.now();

        FormResult fH = FIFAFormCalculator.getForm(homeTeam, DATA_FILE, today);
        FormResult fA = FIFAFormCalculator.getForm(awayTeam, DATA_FILE, today);

        boolean hasHomeForm = fH.matchesUsed() > 0;
        boolean hasAwayForm = fA.matchesUsed() > 0;

        if (!hasHomeForm && !hasAwayForm) {
            return new Verdict(NAME, "X", new Score(1, 1), 0.0,
                    "Sin datos de forma reciente para ninguno de los dos equipos");
        }

        // Construir lambdas desde factores de forma
        double lH = hasHomeForm
                ? clamp(BASE_GOALS * fH.attackFactor() / Math.max(0.5, fA.defenseFactor()))
                : PoissonPredictor.expectedGoalsElo(home.rating(), away.rating(), homeBonus);
        double lA = hasAwayForm
                ? clamp(BASE_GOALS * fA.attackFactor() / Math.max(0.5, fH.defenseFactor()))
                : PoissonPredictor.expectedGoalsElo(away.rating(), home.rating(), -homeBonus / 2.0);

        // Construir matriz DC propia
        double rho = PoissonPredictor.rhoForStage(stage);
        double[][] matrix = buildDCMatrix(lH, lA, rho);

        // Probabilidades 1X2 desde la matriz
        double pH = 0, pD = 0, pA = 0;
        int bestH = 0, bestA = 0;
        double bestP = -1;
        for (int h = 0; h <= MAX_GOALS; h++) {
            for (int a = 0; a <= MAX_GOALS; a++) {
                double p = matrix[h][a];
                if (p > bestP) { bestP = p; bestH = h; bestA = a; }
                if (h > a)      pH += p;
                else if (h == a) pD += p;
                else             pA += p;
            }
        }

        String result;
        double confidence;
        if (pH >= pD && pH >= pA) {
            result = "1";
            confidence = pH;
        } else if (pA >= pD) {
            result = "2";
            confidence = pA;
        } else {
            result = "X";
            confidence = pD;
        }

        // Summary con datos reales
        String homeFormStr = hasHomeForm
                ? String.format("%s: atq %.2fx · def %.2fx (%d partidos)",
                homeTeam, fH.attackFactor(), fH.defenseFactor(), fH.matchesUsed())
                : homeTeam + ": sin datos";
        String awayFormStr = hasAwayForm
                ? String.format("%s: atq %.2fx · def %.2fx (%d partidos)",
                awayTeam, fA.attackFactor(), fA.defenseFactor(), fA.matchesUsed())
                : awayTeam + ": sin datos";

        String summary = String.format("Forma reciente → %s | %s | %s: %.0f%%",
                homeFormStr, awayFormStr,
                result.equals("1") ? homeTeam : result.equals("2") ? awayTeam : "Empate",
                confidence * 100);

        return new Verdict(NAME, result, new Score(bestH, bestA), confidence, summary);
    }

    @Override
    public String reasoning() {
        return "Análisis basado en los últimos 50 partidos de cada equipo "
                + "ponderados por importancia FIFA × decaimiento temporal "
                + "× calidad del rival. Matriz Poisson-DC independiente.";
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static double[][] buildDCMatrix(double lH, double lA, double rho) {
        double[][] m = new double[MAX_GOALS + 1][MAX_GOALS + 1];
        double total = 0.0;
        for (int h = 0; h <= MAX_GOALS; h++) {
            double pH = PoissonPredictor.poissonPmf(h, lH);
            for (int a = 0; a <= MAX_GOALS; a++) {
                double p = pH * PoissonPredictor.poissonPmf(a, lA) * dcTau(h, a, lH, lA, rho);
                m[h][a] = p;
                total += p;
            }
        }
        if (total > 0) {
            for (int h = 0; h <= MAX_GOALS; h++)
                for (int a = 0; a <= MAX_GOALS; a++)
                    m[h][a] /= total;
        }
        return m;
    }

    private static double dcTau(int h, int a, double lH, double lA, double rho) {
        if (h == 0 && a == 0) return 1 - lH * lA * rho;
        if (h == 0 && a == 1) return 1 + lH * rho;
        if (h == 1 && a == 0) return 1 + lA * rho;
        if (h == 1 && a == 1) return 1 - rho;
        return 1.0;
    }

    private static double clamp(double v) {
        return Math.max(MIN_LAMBDA, Math.min(MAX_LAMBDA, v));
    }
}
