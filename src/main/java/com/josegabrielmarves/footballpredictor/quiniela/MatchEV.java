package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.EnsemblePredictor;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import com.josegabrielmarves.footballpredictor.simulation.montecarlo.MonteCarloSimulator;
import com.josegabrielmarves.footballpredictor.util.MatrixUtils;

import java.util.*;
import java.util.stream.Collectors;

import com.josegabrielmarves.footballpredictor.quiniela.FastStrategyOptimizer.Objective;

/**
 * Evalúa todos los marcadores candidatos para un partido y devuelve
 * el ranking por EV esperado de puntos de quiniela.
 *
 * Métodos clave:
 *   - best()           → marcador que maximiza EV (conservador)
 *   - honest()         → marcador modal de la matriz Poisson+DC
 *   - top3MC()         → Top 3 marcadores más frecuentes por Monte Carlo
 *   - risk()           → Clasificación de riesgo (Fijo/Fuerte/Doble/Triple)
 *   - bestResult()     → Resultado 1X2 más probable con su probabilidad
 *   - seguro()         → [v2] marcador de resultado más probable (juega seguro)
 *   - exactoArriesgado() → [v2] marcador exacto pico de la matriz de TORNEO (xG+GLM)
 */
public final class MatchEV {

    private static final int MAX_GOALS = 5;

    private MatchEV() {}

    // ── Tipos ─────────────────────────────────────────────────────────────────

    public enum Risk {
        FIJO   ("🔒 FIJO"),
        FUERTE ("💪 FUERTE"),
        DOBLE  ("⚖️  DOBLE"),
        TRIPLE ("🎲 TRIPLE");
        public final String label;
        Risk(String label) { this.label = label; }
    }

    public record Candidate(
            Score score, double pExact, double pResult,
            double expectedPoints, double expectedFine
    ) {
        @Override public String toString() {
            return String.format("%d-%d  pts=%.3f  multa=%.2fL  P(exacto)=%.1f%%  P(result)=%.1f%%",
                    score.homeGoals(), score.awayGoals(),
                    expectedPoints, expectedFine, pExact * 100, pResult * 100);
        }
    }

    public record MCScore(Score score, double frequency) {
        @Override public String toString() {
            return String.format("%d-%d (%.1f%%)",
                    score.homeGoals(), score.awayGoals(), frequency * 100);
        }
    }

    /**
     * [v2] Recomendación dual para la quiniela:
     *   seguro          → marcador del resultado más probable (alta P de acertar resultado)
     *   exacto          → marcador exacto con mayor probabilidad real (apunta a los 3 pts)
     *   pSeguro         → P del resultado dominante (1X2), NO del marcador específico
     *   pExacto         → P del marcador exacto pico
     *   risk            → nivel de confianza del resultado
     */
    public record DualPick(
            Score seguro, double pSeguro,
            Score exacto, double pExacto,
            Risk risk, String resultLabel
    ) {}

    // ── Métodos principales ───────────────────────────────────────────────────

    public static List<Candidate> rank(EloRating home, EloRating away,
                                       double homeBonus, Stage stage) {
        double[][] matrix = PoissonPredictor.scoreMatrix(home, away, homeBonus);
        PoissonPredictor.MatchProbabilities probs =
                PoissonPredictor.matchProbabilities(home, away, homeBonus);
        return buildCandidates(matrix, probs, stage);
    }

    /**
     * Ranking de candidatos usando el modelo de TORNEO (triple-blend + xG + GLM + H2H + Descanso + Altitud).
     * Este es el modelo que usa el pipeline principal. StrategyOptimizer debe usar ESTE método,
     * no {@link #rank(EloRating, EloRating, double, Stage)} que usa solo Elo.
     */
    public static List<Candidate> rankTournament(String homeTeam, EloRating home,
                                                  String awayTeam, EloRating away,
                                                  double homeBonus, Stage stage) {
        double[][] matrix = PoissonPredictor.scoreMatrixTournament(homeTeam, home, awayTeam, away, homeBonus, stage);
        PoissonPredictor.MatchProbabilities probs =
                PoissonPredictor.matchProbabilitiesTournament(homeTeam, home, awayTeam, away, homeBonus, stage);
        return buildCandidates(matrix, probs, stage);
    }

    private static List<Candidate> buildCandidates(double[][] matrix,
                                                    PoissonPredictor.MatchProbabilities probs,
                                                    Stage stage) {
        List<Candidate> candidates = new ArrayList<>();
        for (int h = 0; h <= MAX_GOALS; h++) {
            for (int a = 0; a <= MAX_GOALS; a++) {
                double pExact = (h < matrix.length && a < matrix[h].length) ? matrix[h][a] : 0.0;
                double pResult = h > a ? probs.homeWin() : h < a ? probs.awayWin() : probs.draw();
                candidates.add(new Candidate(new Score(h, a), pExact, pResult,
                        QuinielaScorer.expectedPoints(pExact, pResult, stage),
                        QuinielaScorer.expectedFine(pResult)));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::expectedPoints).reversed());
        return candidates;
    }

    public static Candidate best(EloRating home, EloRating away, double homeBonus, Stage stage) {
        return rank(home, away, homeBonus, stage).get(0);
    }

    /**
     * Ranking de candidatos ajustado según el objetivo estratégico.
     * <p>
     * Para P1_FIRST: devuelve más candidatos (alta varianza) para explorar
     * opciones poco convencionales.
     * Para EXPECTED_PAYOUT/PODIUM: devuelve los topK estándar (balanceados).
     * Para P3_THIRD: devuelve los topK estándar (seguro).
     *
     * @param home     rating local
     * @param away     rating visitante
     * @param homeBonus ventaja local
     * @param stage    fase del torneo
     * @param objective objetivo estratégico
     * @param topK     base de candidatos a considerar
     */
    public static List<Candidate> rankWithObjective(EloRating home, EloRating away,
                                                     double homeBonus, Stage stage,
                                                     Objective objective, int topK) {
        List<Candidate> all = rank(home, away, homeBonus, stage);
        int adjustedK = switch (objective) {
            case P1_FIRST       -> Math.max(5, (int) Math.ceil(topK * 1.5));
            case P2_SECOND      -> Math.max(4, (int) Math.ceil(topK * 1.2));
            case P3_THIRD, PODIUM, EXPECTED_PAYOUT -> topK;
        };
        return all.stream().limit(adjustedK).collect(Collectors.toList());
    }

    public static Score honest(EloRating home, EloRating away, double homeBonus) {
        return PoissonPredictor.mostLikelyScore(home, away, homeBonus);
    }

    // ── [v2] Recomendación dual con matriz de TORNEO ──────────────────────────

    /**
     * Genera la recomendación dual (seguro + exacto arriesgado) usando la
     * matriz del TORNEO (triple-blend: Elo + xG + GLM), no la matriz solo-Elo.
     *
     * - seguro: dentro del resultado 1X2 más probable, el marcador más probable
     *   (típicamente conservador, alta chance de acertar el resultado → 1 pt)
     * - exacto: el marcador exacto con mayor probabilidad absoluta en la matriz
     *   (apunta al marcador exacto → 3 pts, es el que rompe empates en la tabla)
     *
     * Cuando el favorito es claro, ambos pueden coincidir. Cuando hay goleada
     * probable, el exacto será más agresivo (3-0, 3-1) que el seguro (2-0).
     */
    public static DualPick dualPick(String homeTeam, EloRating home,
                                    String awayTeam, EloRating away,
                                    double homeBonus) {
        double[][] m = PoissonPredictor.scoreMatrixTournament(
                homeTeam, home, awayTeam, away, homeBonus);
        PoissonPredictor.MatchProbabilities probs =
                PoissonPredictor.matchProbabilitiesTournament(
                        homeTeam, home, awayTeam, away, homeBonus);

        // Exacto: marcador con mayor probabilidad absoluta en toda la matriz
        MatrixUtils.ScoredCell exact = MatrixUtils.findMax(m);
        int eh = exact.row();
        int ea = exact.col();
        double pExacto = exact.value();

        // Resultado más probable (1, X, 2)
        char winner = probs.homeWin() >= probs.draw() && probs.homeWin() >= probs.awayWin() ? '1'
                : probs.awayWin() >= probs.draw() ? '2' : 'X';

        // Seguro: marcador más probable DENTRO del resultado dominante
        // pResult = P(resultado dominante) = suma de todos los marcadores de ese resultado
        int sh = 0, sa = 0; double pScoreSeguro = -1; double pResult = 0;
        for (int h = 0; h < m.length; h++)
            for (int a = 0; a < m[h].length; a++) {
                char r = h > a ? '1' : h < a ? '2' : 'X';
                if (r == winner) {
                    pResult += m[h][a];
                    if (m[h][a] > pScoreSeguro) { pScoreSeguro = m[h][a]; sh = h; sa = a; }
                }
            }

        double best = Math.max(probs.homeWin(), Math.max(probs.draw(), probs.awayWin()));
        Risk risk = best >= 0.65 ? Risk.FIJO : best >= 0.55 ? Risk.FUERTE
                                               : best >= 0.45 ? Risk.DOBLE : Risk.TRIPLE;

        String label = winner == '1' ? homeTeam : winner == '2' ? awayTeam : "Empate";
        label = String.format("%s (%.0f%%)", label, best * 100);

        return new DualPick(new Score(sh, sa), pResult,
                new Score(eh, ea), pExacto, risk, label);
    }

    /**
     * [v2 + Mercado] Recomendación dual usando probabilidades ajustadas
     * por mercado de apuestas (The Odds API).
     *
     * Cuando hay odds disponibles, los picks reflejan información que
     * el modelo puro no captura (lesiones, alineaciones, clima).
     * Cuando no hay odds, se comporta igual que dualPick().
     */
    public static DualPick dualPickWithMarket(String homeTeam, EloRating home,
                                               String awayTeam, EloRating away,
                                               double homeBonus,
                                               QuinielaScorer.Stage stage,
                                               EnsemblePredictor ep) {
        double[][] m = PoissonPredictor.scoreMatrixTournamentWithMarket(
                homeTeam, home, awayTeam, away, homeBonus, stage, ep);
        PoissonPredictor.MatchProbabilities probs =
                PoissonPredictor.matchProbabilitiesTournamentWithMarket(
                        homeTeam, home, awayTeam, away, homeBonus, stage, ep);

        // Exacto: marcador con mayor probabilidad absoluta
        MatrixUtils.ScoredCell exact = MatrixUtils.findMax(m);
        int eh = exact.row();
        int ea = exact.col();
        double pExacto = exact.value();

        // Resultado más probable
        char winner = probs.homeWin() >= probs.draw() && probs.homeWin() >= probs.awayWin() ? '1'
                : probs.awayWin() >= probs.draw() ? '2' : 'X';

        // Seguro: marcador más probable dentro del resultado dominante
        // pResult = P(resultado dominante) = suma de todos los marcadores de ese resultado
        int sh = 0, sa = 0; double pScoreSeguro = -1; double pResult = 0;
        for (int h = 0; h < m.length; h++)
            for (int a = 0; a < m[h].length; a++) {
                char r = h > a ? '1' : h < a ? '2' : 'X';
                if (r == winner) {
                    pResult += m[h][a];
                    if (m[h][a] > pScoreSeguro) { pScoreSeguro = m[h][a]; sh = h; sa = a; }
                }
            }

        double best = Math.max(probs.homeWin(), Math.max(probs.draw(), probs.awayWin()));
        Risk risk = best >= 0.65 ? Risk.FIJO : best >= 0.55 ? Risk.FUERTE
                                               : best >= 0.45 ? Risk.DOBLE : Risk.TRIPLE;

        String label = winner == '1' ? homeTeam : winner == '2' ? awayTeam : "Empate";
        label = String.format("%s (%.0f%%)", label, best * 100);

        return new DualPick(new Score(sh, sa), pResult,
                new Score(eh, ea), pExacto, risk, label);
    }

    // ── Métodos existentes ────────────────────────────────────────────────────

    public static List<MCScore> top3MC(EloRating home, EloRating away,
                                       double homeBonus, int n, long seed) {
        double[][] matrix = PoissonPredictor.scoreMatrix(home, away, homeBonus);
        Random rng = new Random(seed);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Score s = MonteCarloSimulator.sample(matrix, rng);
            counts.merge(s.homeGoals() + "-" + s.awayGoals(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(3)
                .map(e -> {
                    String[] p = e.getKey().split("-");
                    return new MCScore(new Score(Integer.parseInt(p[0]), Integer.parseInt(p[1])),
                            (double) e.getValue() / n);
                })
                .collect(Collectors.toList());
    }

    public static Score secure(String homeTeam, EloRating home,
                               String awayTeam, EloRating away,
                               double homeBonus) {
        return dualPick(homeTeam, home, awayTeam, away, homeBonus).seguro();
    }

    public static Risk risk(EloRating home, EloRating away, double homeBonus) {
        PoissonPredictor.MatchProbabilities probs =
                PoissonPredictor.matchProbabilities(home, away, homeBonus);
        double best = Math.max(probs.homeWin(), Math.max(probs.draw(), probs.awayWin()));
        if (best >= 0.65) return Risk.FIJO;
        if (best >= 0.55) return Risk.FUERTE;
        if (best >= 0.45) return Risk.DOBLE;
        return Risk.TRIPLE;
    }

    public static String bestResult(EloRating home, EloRating away,
                                    double homeBonus, String homeTeam, String awayTeam) {
        PoissonPredictor.MatchProbabilities p =
                PoissonPredictor.matchProbabilities(home, away, homeBonus);
        if (p.homeWin() >= p.draw() && p.homeWin() >= p.awayWin())
            return String.format("%s (%.0f%%)", homeTeam, p.homeWin() * 100);
        if (p.awayWin() >= p.draw())
            return String.format("%s (%.0f%%)", awayTeam, p.awayWin() * 100);
        return String.format("Empate (%.0f%%)", p.draw() * 100);
    }
}