package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.TournamentGLM;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

/**
 * Juez basado en el TournamentGLM (rendimiento en el torneo actual).
 * <p>
 * Utiliza el modelo Poisson GLM calibrado con los partidos del Mundial 2026
 * (ataque/defensa por equipo, regularización ridge hacia prior Elo).
 * <p>
 * Requiere una instancia de {@link TournamentGLM} calibrada; si es {@code null}
 * (no disponible), el juez emite un veredicto neutral.
 */
public final class GLMJudge implements MatchJudge {

    private static final String NAME = "GLM";

    private static final int MAX_GOALS = 5;
    private static final double MIN_LAMBDA = 0.20;
    private static final double MAX_LAMBDA = 5.00;

    private final TournamentGLM glm;

    /**
     * @param glm instancia calibrada del GLM del torneo, o {@code null}
     *            si no hay datos suficientes (emite veredicto neutral)
     */
    public GLMJudge(TournamentGLM glm) {
        this.glm = glm;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Verdict judge(String homeTeam, EloRating home,
                         String awayTeam, EloRating away,
                         double homeBonus, Stage stage) {
        if (glm == null || glm.matchesUsed() < 2) {
            return new Verdict(NAME, "X", new Score(1, 1), 0.0,
                    "GLM no disponible — pocos partidos del torneo registrados");
        }

        double lH = glm.lambdaHome(homeTeam, awayTeam, homeBonus > 0);
        double lA = glm.lambdaAway(homeTeam, awayTeam);

        lH = clamp(lH);
        lA = clamp(lA);

        double rho = PoissonPredictor.rhoForStage(stage);
        double[][] matrix = buildDCMatrix(lH, lA, rho);

        // Probabilidades 1X2 y marcador más probable
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

        // No tenemos acceso a los parámetros ataque/defensa específicos desde aquí
        // porque TournamentGLM no los expone. Usamos las lambdas como proxy.
        String summary = String.format(
                "Torneo: λ %s=%.2f λ %s=%.2f (%d partidos GLM) → %s: %.0f%%",
                homeTeam, lH, awayTeam, lA, glm.matchesUsed(),
                result.equals("1") ? homeTeam : result.equals("2") ? awayTeam : "Empate",
                confidence * 100);

        return new Verdict(NAME, result, new Score(bestH, bestA), confidence, summary);
    }

    @Override
    public String reasoning() {
        return "Análisis basado en el modelo Poisson GLM calibrado con los partidos "
                + "del Mundial 2026 (ataque/defensa por equipo, regularización ridge "
                + "hacia prior Elo). Captura el rendimiento observado en el torneo.";
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
