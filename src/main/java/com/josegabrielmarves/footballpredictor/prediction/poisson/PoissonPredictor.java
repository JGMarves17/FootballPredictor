package com.josegabrielmarves.footballpredictor.prediction.poisson;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.AltitudeFactor;
import com.josegabrielmarves.footballpredictor.prediction.EnsemblePredictor;
import com.josegabrielmarves.footballpredictor.prediction.FIFAFormCalculator;
import com.josegabrielmarves.footballpredictor.prediction.TournamentConditioner;
import com.josegabrielmarves.footballpredictor.prediction.ProbabilityCalibrator;
import com.josegabrielmarves.footballpredictor.prediction.TournamentGLM;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;
import org.apache.commons.math3.distribution.PoissonDistribution;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;

/**
 * Motor de predicción v2 — Triple blending:
 *
 *   λ_final = α_elo  × λ_elo          (fortaleza histórica)
 *           + α_form × λ_form          (FIFAFormCalculator — últimos 50 partidos)
 *           + α_glm  × λ_glm           (TournamentGLM — calibrado en WC 2026)
 *
 * Luego ajustado por TournamentConditioner (xG real vs goles del torneo).
 *
 * Pesos cuando el GLM tiene datos suficientes (≥8 partidos):
 *   α_elo=0.40, α_form=0.25, α_glm=0.35
 *
 * Pesos al inicio del torneo (pocos datos GLM):
 *   α_elo=0.60, α_form=0.25, α_glm=0.15
 *
 * Dixon-Coles ρ calibrado para WC 2026 (más empates que histórico):
 *   DC_RHO_TOURNAMENT = -0.09
 */
public final class PoissonPredictor {

    public static final double BASE_GOALS        = 1.35;
    public static final double MIN_LAMBDA        = 0.20;
    public static final double MAX_LAMBDA        = 5.00;
    public static final int    MAX_GOALS         = 9;
    public static final double DC_RHO            = -0.13; // histórico (backtest multi-mundial)
    public static final double DC_RHO_TOURNAMENT = -0.09; // calibrado WC 2026
    public static final double ELO_GOAL_SCALE    = 740.0; // escala log-link Elo→goles

    private static Path     dataFile = Path.of("data/results.json");
    private static LocalDate refDate = null;
    private static TournamentGLM glm = null;
    private static ProbabilityCalibrator calibrator = null;

    private PoissonPredictor() {}

    public static void setDataFile(Path path) { dataFile = path; }
    public static void setRefDate(LocalDate d) { refDate = d; }
    public static void setGLM(TournamentGLM g) { glm = g; }
    public static void setCalibrator(ProbabilityCalibrator cal) { calibrator = cal; }

    private static LocalDate today() {
        return refDate != null ? refDate : LocalDate.now();
    }

    // ── Lambdas ───────────────────────────────────────────────────────────────

    /** Lambda solo-Elo con log-link (compatible con backtest y tests existentes). */
    public static double expectedGoalsElo(double rating, double oppRating, double homeBonus) {
        double diff = (rating + homeBonus) - oppRating;
        double logLambda = Math.log(BASE_GOALS) + diff / ELO_GOAL_SCALE;
        return clamp(Math.exp(logLambda));
    }

    /**
     * Lambda triple-blend: Elo + FIFAForm + GLM.
     * Ajustado por TournamentConditioner (xG real del torneo).
     */
    public static double[] expectedGoalsBlended(String homeTeam, EloRating home,
                                                String awayTeam,  EloRating away,
                                                double homeBonus) {
        return expectedGoalsBlended(homeTeam, home, awayTeam, away, homeBonus, null);
    }

    /**
     * Lambda triple-blend con conciencia de etapa del torneo.
     * En grupos → más peso a GLM (datos frescos del torneo).
     * En eliminatorias → más peso a Elo (fortaleza histórica).
     * En final → más peso a forma reciente.
     */
    public static double[] expectedGoalsBlended(String homeTeam, EloRating home,
                                                String awayTeam,  EloRating away,
                                                double homeBonus, Stage stage) {
        // 1. Lambda Elo base
        double lEloH = expectedGoalsElo(home.rating(), away.rating(),  homeBonus);
        double lEloA = expectedGoalsElo(away.rating(), home.rating(), -homeBonus / 2.0);

        // 2. Lambda FIFAFormCalculator
        FIFAFormCalculator.FormResult fH = FIFAFormCalculator.getForm(homeTeam, dataFile, today());
        FIFAFormCalculator.FormResult fA = FIFAFormCalculator.getForm(awayTeam, dataFile, today());
        double lFormH = fH.matchesUsed() > 0
                ? clamp(BASE_GOALS * fH.attackFactor() / Math.max(0.5, fA.defenseFactor()))
                : lEloH;
        double lFormA = fA.matchesUsed() > 0
                ? clamp(BASE_GOALS * fA.attackFactor() / Math.max(0.5, fH.defenseFactor()))
                : lEloA;

        // 3. Lambda GLM in-tournament
        double lGlmH = lEloH, lGlmA = lEloA;
        int glmMatches = 0;
        if (glm != null) {
            lGlmH = glm.lambdaHome(homeTeam, awayTeam, homeBonus > 0);
            lGlmA = glm.lambdaAway(homeTeam, awayTeam);
            glmMatches = glm.matchesUsed();
        }

        // 4. Pesos dinámicos según etapa y cuántos partidos tiene el GLM
        double[] stageWeights = stageWeights(stage, glmMatches, fH.matchesUsed() > 0);
        double wElo  = stageWeights[0];
        double wForm = stageWeights[1];
        double wGlm  = stageWeights[2];

        double lambdaH = clamp(wElo * lEloH + wForm * lFormH + wGlm * lGlmH);
        double lambdaA = clamp(wElo * lEloA + wForm * lFormA + wGlm * lGlmA);

        // 5. Ajuste por xG real del torneo (TournamentConditioner)
        TournamentConditioner cond = TournamentConditioner.getInstance();
        double[] adjusted = cond.adjustLambdas(homeTeam, lambdaH, awayTeam, lambdaA);

        // 6. Ajuste por altitud de la sede (México ~1,800m)
        adjusted = AltitudeFactor.adjustLambdas(adjusted[0], adjusted[1], homeTeam, awayTeam);

        return adjusted;
    }

    public static double[] stageWeights(Stage stage, int glmMatches, boolean hasForm) {
        double baseGlm = glmMatches >= 16 ? 0.35 : glmMatches >= 8 ? 0.25 : 0.15;
        double baseForm = hasForm ? 0.25 : 0.0;
        double baseElo = 1.0 - baseGlm - baseForm;

        if (stage == null) return new double[]{baseElo, baseForm, baseGlm};

        return switch (stage) {
            case GRUPOS -> {
                double wGlm  = Math.min(0.40, baseGlm + 0.05);
                double wForm = baseForm;
                double wElo  = 1.0 - wGlm - wForm;
                yield new double[]{wElo, wForm, wGlm};
            }
            case DIECISEISAVOS, OCTAVOS -> {
                double wElo  = Math.min(0.55, baseElo + 0.05);
                double wForm = baseForm;
                double wGlm  = 1.0 - wElo - wForm;
                yield new double[]{wElo, wForm, wGlm};
            }
            case CUARTOS, SEMIFINAL -> {
                double wElo  = Math.min(0.60, baseElo + 0.10);
                double wForm = baseForm;
                double wGlm  = 1.0 - wElo - wForm;
                yield new double[]{wElo, wForm, wGlm};
            }
            case FINAL -> {
                double wForm = Math.min(0.35, baseForm + 0.10);
                double wElo  = Math.min(0.55, baseElo - 0.05);
                double wGlm  = 1.0 - wElo - wForm;
                yield new double[]{wElo, wForm, wGlm};
            }
        };
    }

    // ── PMF ───────────────────────────────────────────────────────────────────

    public static double poissonPmf(int k, double lambda) {
        if (k < 0)      return 0.0;
        if (lambda <= 0) return k == 0 ? 1.0 : 0.0;
        return new PoissonDistribution(lambda).probability(k);
    }

    // ── Matrices ──────────────────────────────────────────────────────────────

    /** Matriz solo-Elo (backtest, tests existentes — sin cambiar). */
    public static double[][] scoreMatrix(EloRating home, EloRating away, double homeBonus) {
        double lH = expectedGoalsElo(home.rating(), away.rating(),  homeBonus);
        double lA = expectedGoalsElo(away.rating(), home.rating(), -homeBonus / 2.0);
        return buildMatrix(lH, lA, DC_RHO);
    }

    /**
     * Matriz triple-blend para predicciones del torneo.
     * Usa DC_RHO_TOURNAMENT (-0.09) y ajuste por xG real.
     */
    public static double[][] scoreMatrixTournament(String homeTeam, EloRating home,
                                                   String awayTeam,  EloRating away,
                                                   double homeBonus) {
        return scoreMatrixTournament(homeTeam, home, awayTeam, away, homeBonus, null);
    }

    /**
     * Matriz triple-blend con conciencia de etapa.
     */
    public static double[][] scoreMatrixTournament(String homeTeam, EloRating home,
                                                   String awayTeam,  EloRating away,
                                                   double homeBonus, Stage stage) {
        double[] lambdas = expectedGoalsBlended(homeTeam, home, awayTeam, away, homeBonus, stage);
        return buildMatrix(lambdas[0], lambdas[1], DC_RHO_TOURNAMENT);
    }

    /**
     * Matriz triple-blend + mercado de apuestas.
     *
     * Combina el modelo propio con las probabilidades del mercado (The Odds API).
     * Usa optimización para ajustar los λ (goles esperados) de forma que la
     * matriz DC resultante coincida con las probabilidades blend.
     *
     * Cuando no hay odds disponibles, devuelve la matriz estándar de torneo.
     */
    public static double[][] scoreMatrixTournamentWithMarket(String homeTeam, EloRating home,
                                                              String awayTeam,  EloRating away,
                                                              double homeBonus, Stage stage,
                                                              EnsemblePredictor ep) {
        // 1. Obtener λ base del modelo (ya incluye altitud)
        double[] lambdas = expectedGoalsBlended(homeTeam, home, awayTeam, away, homeBonus, stage);

        // 2. Obtener probabilidades blend del EnsemblePredictor
        double[] blended = ep.probabilitiesTournament(homeTeam, home, awayTeam, away, homeBonus, stage);

        // 3. Verificar si realmente hay datos de mercado
        boolean hasMarket = false;
        try {
            MatchProbabilities modelProbs = matchProbabilitiesTournament(homeTeam, home, awayTeam, away, homeBonus, stage);
            hasMarket = Math.abs(blended[0] - modelProbs.homeWin()) > 0.002
                    || Math.abs(blended[2] - modelProbs.awayWin()) > 0.002;
        } catch (Exception e) {
            hasMarket = false;
        }

        if (!hasMarket) {
            return buildMatrix(lambdas[0], lambdas[1], DC_RHO_TOURNAMENT);
        }

        // 4. Buscar λ ajustados que produzcan las probabilidades blend
        double[] adjusted = findLambdasToMatchProbs(
                blended[0], blended[1], blended[2],
                lambdas[0], lambdas[1], DC_RHO_TOURNAMENT);

        System.out.printf("  📈 [Mercado] %s vs %s — λ: %.3f→%.3f , %.3f→%.3f%n",
                homeTeam, awayTeam,
                lambdas[0], adjusted[0], lambdas[1], adjusted[1]);

        return buildMatrix(adjusted[0], adjusted[1], DC_RHO_TOURNAMENT);
    }

    /**
     * Probabilidades 1X2 con blend de mercado.
     */
    public static MatchProbabilities matchProbabilitiesTournamentWithMarket(
            String homeTeam, EloRating home,
            String awayTeam, EloRating away,
            double homeBonus, Stage stage, EnsemblePredictor ep) {
        return aggregateProbs(scoreMatrixTournamentWithMarket(
                homeTeam, home, awayTeam, away, homeBonus, stage, ep));
    }

    /**
     * Encuentra λ_home, λ_away que minimizan la distancia a las
     * probabilidades objetivo [p1, pX, p2] usando búsqueda en grid.
     *
     * Busca escalares s₁,s₂ ∈ [0.7, 1.3] alrededor de los λ base.
     * Esto ajusta la forma de la matriz DC para que sus marginales
     * 1X2 se alineen con las del mercado.
     */
    private static double[] findLambdasToMatchProbs(
            double targetP1, double targetPX, double targetP2,
            double lambdaH, double lambdaA, double rho) {
        double bestDist = Double.MAX_VALUE;
        double bestLH = lambdaH, bestLA = lambdaA;

        // Grid: ±30% alrededor de λ base, paso 2%
        for (double s1 = 0.70; s1 <= 1.30; s1 += 0.02) {
            for (double s2 = 0.70; s2 <= 1.30; s2 += 0.02) {
                double tH = clamp(lambdaH * s1);
                double tA = clamp(lambdaA * s2);
                var probs = aggregateProbs(buildMatrix(tH, tA, rho));

                double dist = Math.abs(probs.homeWin() - targetP1)
                            + Math.abs(probs.draw()   - targetPX)
                            + Math.abs(probs.awayWin() - targetP2);

                if (dist < bestDist) {
                    bestDist = dist;
                    bestLH = tH;
                    bestLA = tA;
                }
            }
        }
        return new double[]{bestLH, bestLA};
    }

    private static double[][] buildMatrix(double lH, double lA, double rho) {
        double[][] m = new double[MAX_GOALS + 1][MAX_GOALS + 1];
        double total = 0.0;
        for (int h = 0; h <= MAX_GOALS; h++) {
            double pH = poissonPmf(h, lH);
            for (int a = 0; a <= MAX_GOALS; a++) {
                double p = pH * poissonPmf(a, lA) * dcTau(h, a, lH, lA, rho);
                m[h][a] = p; total += p;
            }
        }
        if (total > 0)
            for (int h = 0; h <= MAX_GOALS; h++)
                for (int a = 0; a <= MAX_GOALS; a++)
                    m[h][a] /= total;
        return m;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static Score mostLikelyScore(EloRating home, EloRating away, double homeBonus) {
        double[][] m = scoreMatrix(home, away, homeBonus);
        int bH = 0, bA = 0; double best = -1;
        for (int h = 0; h <= MAX_GOALS; h++)
            for (int a = 0; a <= MAX_GOALS; a++)
                if (m[h][a] > best) { best = m[h][a]; bH = h; bA = a; }
        return new Score(bH, bA);
    }

    public static MatchProbabilities matchProbabilities(EloRating home, EloRating away,
                                                        double homeBonus) {
        return aggregateProbs(scoreMatrix(home, away, homeBonus));
    }

    public static MatchProbabilities matchProbabilitiesTournament(String homeTeam, EloRating home,
                                                                   String awayTeam, EloRating away,
                                                                   double homeBonus) {
        return matchProbabilitiesTournament(homeTeam, home, awayTeam, away, homeBonus, null);
    }

    public static MatchProbabilities matchProbabilitiesTournament(String homeTeam, EloRating home,
                                                                    String awayTeam, EloRating away,
                                                                    double homeBonus, Stage stage) {
        return aggregateProbs(scoreMatrixTournament(homeTeam, home, awayTeam, away, homeBonus, stage));
    }

    public static MatchProbabilities matchProbabilitiesCalibrated(
            String homeTeam, EloRating home, String awayTeam, EloRating away,
            double homeBonus, Stage stage) {
        MatchProbabilities raw = matchProbabilitiesTournament(homeTeam, home, awayTeam, away, homeBonus, stage);
        if (calibrator == null) return raw;
        double[] cal = calibrator.calibratePlatt(raw.homeWin(), raw.draw(), raw.awayWin());
        return new MatchProbabilities(cal[0], cal[1], cal[2]);
    }

    private static MatchProbabilities aggregateProbs(double[][] m) {
        double win = 0, draw = 0, loss = 0;
        for (int h = 0; h <= MAX_GOALS; h++)
            for (int a = 0; a <= MAX_GOALS; a++) {
                if      (h > a) win  += m[h][a];
                else if (h == a) draw += m[h][a];
                else             loss += m[h][a];
            }
        return new MatchProbabilities(win, draw, loss);
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

    public record MatchProbabilities(double homeWin, double draw, double awayWin) {
        @Override public String toString() {
            return String.format("1: %.1f%%  X: %.1f%%  2: %.1f%%",
                    homeWin*100, draw*100, awayWin*100);
        }
    }

    // ── Alias backward-compat para tests existentes ───────────────────────────
    /** @deprecated Usar expectedGoalsElo(). */
    @Deprecated
    public static double expectedGoals(double rating, double oppRating, double homeBonus) {
        return expectedGoalsElo(rating, oppRating, homeBonus);
    }
}