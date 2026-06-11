package com.josegabrielmarves.footballpredictor.simulation.montecarlo;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

import java.util.random.RandomGenerator;

/**
 * Simulación Monte Carlo de partidos.
 *
 * DECISIÓN DE DISEÑO (10-jun-2026): se muestrea directamente de la
 * scoreMatrix corregida por Dixon-Coles, NO con muestreo de Knuth de dos
 * Poisson independientes (como hace la referencia MIT). Knuth ignoraría la
 * corrección de la Fase 4 y el simulador produciría menos empates 0-0/1-1
 * que nuestra propia matriz. Muestrear de la matriz garantiza consistencia
 * total entre predicción y simulación.
 *
 * Sin estado: el generador aleatorio se pasa como parámetro (semilla
 * explícita = reproducibilidad para tests y backtesting).
 */
public final class MonteCarloSimulator {

    private MonteCarloSimulator() {
        // Clase utilitaria: no instanciable.
    }

    /**
     * Sortea UN marcador desde una matriz de probabilidades (debe venir de
     * {@link PoissonPredictor#scoreMatrix} y por tanto sumar 1).
     *
     * Recorrido acumulativo de las celdas: O(celdas) por sorteo, suficiente
     * para los volúmenes del proyecto (100k sorteos < 100 ms).
     */
    public static Score sample(double[][] matrix, RandomGenerator rng) {
        double u = rng.nextDouble();
        double cumulative = 0.0;
        int lastH = matrix.length - 1;
        int lastA = matrix[lastH].length - 1;
        for (int h = 0; h <= lastH; h++) {
            for (int a = 0; a <= lastA; a++) {
                cumulative += matrix[h][a];
                if (u < cumulative) {
                    return new Score(h, a);
                }
            }
        }
        // Residuo de redondeo flotante (u ~ 1.0): devolver la última celda.
        return new Score(lastH, lastA);
    }

    /**
     * Simula n partidos entre dos equipos y agrega los resultados.
     *
     * @param homeBonus puntos Elo de ventaja del local (0 = cancha neutral)
     * @param n         número de simulaciones
     * @param seed      semilla del generador (misma semilla = mismos resultados)
     */
    public static SimResult simulateMatch(EloRating home, EloRating away,
                                          double homeBonus, int n, long seed) {
        double[][] matrix = PoissonPredictor.scoreMatrix(home, away, homeBonus);
        RandomGenerator rng = new java.util.Random(seed);
        int homeWins = 0, draws = 0, awayWins = 0;
        for (int i = 0; i < n; i++) {
            Score s = sample(matrix, rng);
            if (s.homeGoals() > s.awayGoals()) homeWins++;
            else if (s.homeGoals() == s.awayGoals()) draws++;
            else awayWins++;
        }
        return new SimResult(homeWins, draws, awayWins, n);
    }

    /** Resultados agregados de una simulación de n partidos. */
    public record SimResult(int homeWins, int draws, int awayWins, int n) {
        public double homeWinRate() { return (double) homeWins / n; }
        public double drawRate()    { return (double) draws / n; }
        public double awayWinRate() { return (double) awayWins / n; }

        @Override
        public String toString() {
            return String.format("n=%d  1: %.1f%%  X: %.1f%%  2: %.1f%%",
                    n, homeWinRate() * 100, drawRate() * 100, awayWinRate() * 100);
        }
    }
}
