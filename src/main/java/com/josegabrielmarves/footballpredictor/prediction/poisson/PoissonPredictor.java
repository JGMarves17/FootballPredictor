package com.josegabrielmarves.footballpredictor.prediction.poisson;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;

/**
 * Modelo Poisson con corrección Dixon-Coles: convierte ratings Elo en
 * distribuciones de marcadores.
 *
 * Puente Elo→goles esperados y parámetros portados del modelo de referencia
 * open-source (MIT) github.com/Hicruben/world-cup-2026-prediction-model,
 * validado por backtest walk-forward sobre 920 internacionales (2023-2026).
 *
 * Fase 4 (implementada): la corrección Dixon-Coles (DC_RHO = -0.13) ajusta
 * la dependencia entre marcadores bajos que el Poisson independiente modela
 * mal — sube 0-0 y 1-1, baja 1-0 y 0-1. Ver {@link #scoreMatrix}.
 *
 * Sin estado: todos los métodos son puros.
 */
public final class PoissonPredictor {

    /** Goles base de un equipo contra rival de rating idéntico. */
    public static final double BASE_GOALS = 1.35;
    /** Mínimo de goles esperados (evita lambdas degeneradas). */
    public static final double MIN_LAMBDA = 0.3;
    /** Máximo de goles esperados (evita goleadas irreales). */
    public static final double MAX_LAMBDA = 3.5;
    /**
     * Marcador máximo por lado. Cobertura P(X<=8): >99.7% para lambdas
     * reales (<=2.8 con los ratings actuales); 99.0% en el peor caso
     * (clamp 3.5). La matriz se renormaliza, así que el residuo solo
     * redistribuye masa (medido y verificado por test, 10-jun-2026).
     */
    public static final int MAX_GOALS = 8;
    /**
     * Corrección Dixon-Coles (rho calibrado por la referencia MIT):
     * ajusta la dependencia entre marcadores bajos que el Poisson
     * independiente modela mal (sube 0-0 y 1-1, baja 1-0 y 0-1).
     */
    public static final double DC_RHO = -0.13;

    private PoissonPredictor() {
        // Clase utilitaria: no instanciable.
    }

    /**
     * Goles esperados (lambda) de un equipo contra su rival:
     * lambda = 1.35 + diff/400, acotado en [0.3, 3.5],
     * donde diff = (rating + homeBonus) - opponentRating.
     */
    public static double expectedGoals(double rating, double opponentRating, double homeBonus) {
        double diff = (rating + homeBonus) - opponentRating;
        double lambda = BASE_GOALS + diff / 400.0;
        return Math.max(MIN_LAMBDA, Math.min(MAX_LAMBDA, lambda));
    }

    /**
     * P(X = k) para X ~ Poisson(lambda), calculada iterativamente
     * (sin factoriales: estable para k grandes).
     */
    public static double poissonPmf(int k, double lambda) {
        if (k < 0) return 0.0;
        if (lambda <= 0) return k == 0 ? 1.0 : 0.0;
        double p = Math.exp(-lambda);
        for (int i = 1; i <= k; i++) {
            p *= lambda / i;
        }
        return p;
    }

    /**
     * Matriz de probabilidades de marcadores [golesLocal][golesVisitante],
     * tamaño (MAX_GOALS+1) x (MAX_GOALS+1), con corrección Dixon-Coles
     * aplicada y normalizada para sumar 1.
     *
     * Convención de la referencia: el bonus del local entra completo a su
     * lambda y como -homeBonus/2 a la del visitante.
     *
     * IMPORTANTE: pasar ratings BASE (sin bonus pre-aplicado); el bonus se
     * aplica internamente una sola vez.
     *
     * @param homeBonus puntos Elo de ventaja del local
     *                  (0 = cancha neutral; EloCalculator.HOME_ADVANTAGE = 75
     *                  solo para México, USA y Canadá en 2026)
     */
    public static double[][] scoreMatrix(EloRating home, EloRating away, double homeBonus) {
        double lambdaHome = expectedGoals(home.rating(), away.rating(), homeBonus);
        double lambdaAway = expectedGoals(away.rating(), home.rating(), -homeBonus / 2.0);

        double[][] matrix = new double[MAX_GOALS + 1][MAX_GOALS + 1];
        double total = 0.0;
        for (int h = 0; h <= MAX_GOALS; h++) {
            double pH = poissonPmf(h, lambdaHome);
            for (int a = 0; a <= MAX_GOALS; a++) {
                double p = pH * poissonPmf(a, lambdaAway) * dcTau(h, a, lambdaHome, lambdaAway);
                matrix[h][a] = p;
                total += p;
            }
        }
        for (int h = 0; h <= MAX_GOALS; h++) {
            for (int a = 0; a <= MAX_GOALS; a++) {
                matrix[h][a] /= total;
            }
        }
        return matrix;
    }

    /** Marcador con mayor probabilidad individual (predicción honesta). */
    public static Score mostLikelyScore(EloRating home, EloRating away, double homeBonus) {
        double[][] m = scoreMatrix(home, away, homeBonus);
        int bestH = 0, bestA = 0;
        double best = -1.0;
        for (int h = 0; h <= MAX_GOALS; h++) {
            for (int a = 0; a <= MAX_GOALS; a++) {
                if (m[h][a] > best) {
                    best = m[h][a];
                    bestH = h;
                    bestA = a;
                }
            }
        }
        return new Score(bestH, bestA);
    }

    /** Probabilidades 1X2 agregadas desde la matriz de marcadores. */
    public static MatchProbabilities matchProbabilities(EloRating home, EloRating away, double homeBonus) {
        double[][] m = scoreMatrix(home, away, homeBonus);
        double win = 0.0, draw = 0.0, loss = 0.0;
        for (int h = 0; h <= MAX_GOALS; h++) {
            for (int a = 0; a <= MAX_GOALS; a++) {
                if (h > a) win += m[h][a];
                else if (h == a) draw += m[h][a];
                else loss += m[h][a];
            }
        }
        return new MatchProbabilities(win, draw, loss);
    }

    /**
     * Factor de corrección Dixon-Coles tau(h,a). Solo difiere de 1.0 en las
     * cuatro celdas de marcadores bajos. Seguro con los clamps de lambda:
     * con MAX_LAMBDA=3.5 y DC_RHO=-0.13, tau nunca baja de ~0.55.
     */
    private static double dcTau(int h, int a, double lambdaH, double lambdaA) {
        if (h == 0 && a == 0) return 1 - lambdaH * lambdaA * DC_RHO;
        if (h == 0 && a == 1) return 1 + lambdaH * DC_RHO;
        if (h == 1 && a == 0) return 1 + lambdaA * DC_RHO;
        if (h == 1 && a == 1) return 1 - DC_RHO;
        return 1.0;
    }

    /** Probabilidades de victoria local / empate / victoria visitante. */
    public record MatchProbabilities(double homeWin, double draw, double awayWin) {
        @Override
        public String toString() {
            return String.format("1: %.1f%%  X: %.1f%%  2: %.1f%%",
                    homeWin * 100, draw * 100, awayWin * 100);
        }
    }
}