package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;


/**
 * Acumulador de métricas de calibración para backtesting (Fase 5a).
 *
 * <p>Patrón acumulador con estado mínimo (O(1) de memoria): el futuro
 * BacktestEngine walk-forward alimenta {@code add(...)} partido a partido
 * mientras actualiza Elo, sin construir listas intermedias.
 *
 * <p><b>Convención del Brier — multiclase:</b> por partido se calcula
 * Σ(pᵢ − oᵢ)² sobre las 3 clases (homeWin, draw, awayWin) y se promedia
 * sobre partidos. Rango [0, 2]. El predictor uniforme ⅓-⅓-⅓ produce
 * exactamente 2/3 ≈ 0.667. El valor de la referencia
 * (Hicruben/world-cup-2026-prediction-model, ~0.54) usa esta misma escala;
 * NO comparar contra Brier binario [0, 1].
 */
public final class BacktestMetrics {

    /**
     * Resultado 1X2 de un partido. Anidado: hoy solo el backtest necesita
     * este concepto; si una fase futura lo requiere, se promueve entonces.
     */
    public enum Outcome {
        HOME_WIN, DRAW, AWAY_WIN;

        /** Deriva el resultado 1X2 de un marcador real. */
        public static Outcome of(Score score) {
            if (score.homeGoals() > score.awayGoals()) return HOME_WIN;
            if (score.homeGoals() < score.awayGoals()) return AWAY_WIN;
            return DRAW;
        }
    }

    /** Clamp para evitar log(0) en el log-loss. */
    private static final double EPSILON = 1e-15;

    private int matches;
    private int correct;
    private double brierSum;
    private double logLossSum;

    /**
     * Registra un partido: probabilidades predichas + resultado real.
     * Las probabilidades deben venir normalizadas (suman 1), como las
     * produce {@code PoissonPredictor.matchProbabilities}.
     */
    public void add(double homeWin, double draw, double awayWin, Outcome actual) {
        double pActual = switch (actual) {
            case HOME_WIN -> homeWin;
            case DRAW -> draw;
            case AWAY_WIN -> awayWin;
        };

        if (predicted(homeWin, draw, awayWin) == actual) {
            correct++;
        }

        double oHome = actual == Outcome.HOME_WIN ? 1.0 : 0.0;
        double oDraw = actual == Outcome.DRAW ? 1.0 : 0.0;
        double oAway = actual == Outcome.AWAY_WIN ? 1.0 : 0.0;
        brierSum += sq(homeWin - oHome) + sq(draw - oDraw) + sq(awayWin - oAway);

        logLossSum += -Math.log(Math.max(pActual, EPSILON));

        matches++;
    }

    /** Sobrecarga de conveniencia: reutiliza el record del predictor. */
    public void add(PoissonPredictor.MatchProbabilities probs, Outcome actual) {
        add(probs.homeWin(), probs.draw(), probs.awayWin(), actual);
    }

    /**
     * Proporción de partidos donde argmax(probabilidades) == resultado real.
     * Empates de argmax: prioridad HOME &gt; DRAW &gt; AWAY (determinista;
     * irrelevante en la práctica con probabilidades continuas).
     */
    public double accuracy() {
        requireMatches();
        return (double) correct / matches;
    }

    /** Brier multiclase promedio. Rango [0, 2]; uniforme = 2/3; referencia ≈ 0.54. */
    public double brier() {
        requireMatches();
        return brierSum / matches;
    }

    /** Log-loss promedio: −ln(p_real). Uniforme = ln(3) ≈ 1.0986. */
    public double logLoss() {
        requireMatches();
        return logLossSum / matches;
    }

    /** Número de partidos registrados. */
    public int matches() {
        return matches;
    }

    private static Outcome predicted(double homeWin, double draw, double awayWin) {
        if (homeWin >= draw && homeWin >= awayWin) return Outcome.HOME_WIN;
        if (draw >= awayWin) return Outcome.DRAW;
        return Outcome.AWAY_WIN;
    }

    private static double sq(double x) {
        return x * x;
    }

    private void requireMatches() {
        if (matches == 0) {
            throw new IllegalStateException("No hay partidos registrados");
        }
    }
}
