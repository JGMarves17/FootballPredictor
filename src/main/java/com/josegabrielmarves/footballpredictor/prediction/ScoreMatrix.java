package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import com.josegabrielmarves.footballpredictor.simulation.tournament.GroupSimulator;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Matriz completa de probabilidades de marcadores generada con 500,000
 * simulaciones de Monte Carlo usando el modelo PoissonPredictor v2.
 *
 * Usa el método de torneo (blend Elo+forma + ρ calibrado -0.09).
 */
public record ScoreMatrix(
        double[][] matrix,     // matrix[homeGoals][awayGoals]
        double pHomeWin,
        double pDraw,
        double pAwayWin,
        List<ScoredPrediction> top5,
        int simulations,
        String homeTeam,
        String awayTeam
) {
    public static final int DEFAULT_SIMS = 500_000;

    public record ScoredPrediction(Score score, double probability) {}

    // ── Construcción ─────────────────────────────────────────────────────────

    /**
     * Genera la ScoreMatrix desde 500k simulaciones de Monte Carlo.
     * Usa PoissonPredictor.scoreMatrixTournament (Elo+forma, ρ=-0.09).
     */
    public static ScoreMatrix compute(String homeTeam, EloRating home,
                                      String awayTeam,  EloRating away,
                                      double homeBonus, long seed) {
        return compute(homeTeam, home, awayTeam, away, homeBonus, seed, DEFAULT_SIMS, null);
    }

    public static ScoreMatrix compute(String homeTeam, EloRating home,
                                      String awayTeam,  EloRating away,
                                      double homeBonus, long seed, int n) {
        return compute(homeTeam, home, awayTeam, away, homeBonus, seed, n, null);
    }

    public static ScoreMatrix compute(String homeTeam, EloRating home,
                                       String awayTeam,  EloRating away,
                                       double homeBonus, long seed, int n, Stage stage) {
        double[][] dcMatrix = PoissonPredictor.scoreMatrixTournament(
                homeTeam, home, awayTeam, away, homeBonus, stage);
        return runMc(dcMatrix, homeTeam, awayTeam, seed, n);
    }

    // ── Factoría con mercado ───────────────────────────────────────────────────

    /**
     * Genera la ScoreMatrix con ajuste de mercado de apuestas.
     *
     * Combina el modelo propio con las odds de The Odds API usando
     * EnsemblePredictor. Cuando no hay odds disponibles, se comporta
     * igual que compute() normal.
     */
    public static ScoreMatrix computeWithMarket(String homeTeam, EloRating home,
                                                 String awayTeam,  EloRating away,
                                                 double homeBonus, long seed, int n,
                                                 Stage stage, EnsemblePredictor ep) {
        double[][] dcMatrix = PoissonPredictor.scoreMatrixTournamentWithMarket(
                homeTeam, home, awayTeam, away, homeBonus, stage, ep);

        return runMc(dcMatrix, homeTeam, awayTeam, seed, n);
    }

    /** Versión con DEFAULT_SIMS. */
    public static ScoreMatrix computeWithMarket(String homeTeam, EloRating home,
                                                 String awayTeam,  EloRating away,
                                                 double homeBonus, long seed,
                                                 Stage stage, EnsemblePredictor ep) {
        return computeWithMarket(homeTeam, home, awayTeam, away,
                homeBonus, seed, DEFAULT_SIMS, stage, ep);
    }

    /** MC interno compartido entre compute y computeWithMarket. */
    private static ScoreMatrix runMc(double[][] dcMatrix, String homeTeam,
                                      String awayTeam, long seed, int n) {
        Random rng = new Random(seed);
        int size = dcMatrix.length;
        int[][] counts = new int[size][size];
        double win = 0, draw = 0, loss = 0;

        for (int i = 0; i < n; i++) {
            Score s = GroupSimulator.sampleScore(dcMatrix, rng);
            int h = Math.min(s.homeGoals(), size - 1);
            int a = Math.min(s.awayGoals(), size - 1);
            counts[h][a]++;
            if (h > a) win++; else if (h == a) draw++; else loss++;
        }

        double[][] prob = new double[size][size];
        for (int h = 0; h < size; h++)
            for (int a = 0; a < size; a++)
                prob[h][a] = (double) counts[h][a] / n;

        List<ScoredPrediction> top5 = new ArrayList<>();
        for (int h = 0; h < size; h++)
            for (int a = 0; a < size; a++)
                if (prob[h][a] > 0)
                    top5.add(new ScoredPrediction(new Score(h, a), prob[h][a]));
        top5.sort((a, b) -> Double.compare(b.probability(), a.probability()));
        List<ScoredPrediction> top = top5.subList(0, Math.min(5, top5.size()));

        return new ScoreMatrix(prob, win/n, draw/n, loss/n,
                new ArrayList<>(top), n, homeTeam, awayTeam);
    }

    // ── Consultas ─────────────────────────────────────────────────────────────

    public List<ScoredPrediction> topN(int n) {
        return top5().subList(0, Math.min(n, top5().size()));
    }

    public Score mostLikelyScore() {
        return top5().isEmpty() ? new Score(1, 1) : top5().get(0).score();
    }

    public double probability(int h, int a) {
        if (h < 0 || a < 0 || h >= matrix.length || a >= matrix[0].length) return 0.0;
        return matrix[h][a];
    }

    // ── Impresión visual ──────────────────────────────────────────────────────

    /**
     * Imprime la matriz 7×7 con el marcador modal marcado.
     */
    public void print() {
        int display = 7; // mostrar hasta 6-6+
        Score modal = mostLikelyScore();

        System.out.println();
        System.out.println("═".repeat(65));
        System.out.printf("  %s vs %s  —  %,d simulaciones%n",
                homeTeam, awayTeam, simulations);
        System.out.println("═".repeat(65));

        // Header columnas (goles visitante)
        System.out.print("  Local↓ Vis→ ");
        for (int a = 0; a < display; a++)
            System.out.printf("  %-6s", a < display-1 ? String.valueOf(a) : "6+");
        System.out.println();
        System.out.println("  " + "─".repeat(63));

        for (int h = 0; h < display; h++) {
            String rowLabel = h < display-1 ? String.valueOf(h) : "6+";
            System.out.printf("  %-4s         ", rowLabel);
            for (int a = 0; a < display; a++) {
                double p = 0;
                if (h < display-1 && a < display-1) p = probability(h, a);
                else if (h == display-1 && a < display-1) // fila 6+
                    for (int x = display-1; x < matrix.length; x++) p += probability(x, a);
                else if (h < display-1) // col 6+
                    for (int y = display-1; y < matrix[0].length; y++) p += probability(h, y);
                else // esquina 6+,6+
                    for (int x = display-1; x < matrix.length; x++)
                        for (int y = display-1; y < matrix[0].length; y++) p += probability(x, y);

                boolean isModal = (h == modal.homeGoals() && a == modal.awayGoals()
                        && h < display-1 && a < display-1);
                String cell = String.format("%.1f%%", p * 100);
                if (isModal) System.out.printf("[%-5s]", cell);
                else         System.out.printf(" %-5s ", cell);
            }
            if (h == modal.homeGoals() && modal.homeGoals() < display-1)
                System.out.print("  ← más probable");
            System.out.println();
        }

        System.out.println("  " + "─".repeat(63));
        System.out.printf("  P(local)=%5.1f%%  P(empate)=%5.1f%%  P(visit.)=%5.1f%%%n",
                pHomeWin*100, pDraw*100, pAwayWin*100);

        System.out.print("  Top marcadores: ");
        for (int i = 0; i < Math.min(3, top5.size()); i++) {
            ScoredPrediction sp = top5.get(i);
            System.out.printf("%d-%d (%.1f%%)",
                    sp.score().homeGoals(), sp.score().awayGoals(), sp.probability()*100);
            if (i < 2) System.out.print("  ·  ");
        }
        System.out.println();
        System.out.println("═".repeat(65));
        System.out.println();
    }
}