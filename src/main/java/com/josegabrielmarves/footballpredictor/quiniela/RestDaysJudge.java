package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.RestDaysFactor;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

import java.time.LocalDate;

/**
 * Juez basado en la diferencia de días de descanso entre equipos.
 * <p>
 * Utiliza {@link RestDaysFactor#getHomeRestFactor(String, String, LocalDate)}
 * para cuantificar la ventaja/desventaja por descanso.
 * <p>
 * El factor es ±3% por día de diferencia, clamp a ±12% máximo.
 * Solo emite juicio modificador cuando la diferencia es significativa (>2 días).
 */
public final class RestDaysJudge implements MatchJudge {

    private static final String NAME = "Descanso";

    /** Diferencia mínima en días para que el juez emita un veredicto no neutral. */
    private static final int MIN_DAYS_DIFF = 2;

    /** Umbral a partir del cual el factor es significativo (>2% de cambio). */
    private static final double FACTOR_THRESHOLD = 0.02;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Verdict judge(String homeTeam, EloRating home,
                         String awayTeam, EloRating away,
                         double homeBonus, Stage stage) {
        LocalDate today = LocalDate.now();

        double factor = RestDaysFactor.getHomeRestFactor(homeTeam, awayTeam, today);

        // Si el factor está cerca de 1.0 → descanso similar → no hay señal
        if (Math.abs(factor - 1.0) <= FACTOR_THRESHOLD) {
            return new Verdict(NAME, "X", new Score(1, 1), 0.0,
                    "Descanso similar entre " + homeTeam + " y " + awayTeam
                            + " — sin ventaja significativa");
        }

        // Determinar dirección y magnitud
        boolean homeFavored = factor > 1.0;
        double absDelta = Math.abs(factor - 1.0) * 100; // en %

        // Estimar días de diferencia: factor = 1 + 0.03 * diff
        // diff = (factor - 1) / 0.03
        double rawDiff = (factor - 1.0) / 0.03;
        int approxDaysDiff = (int) Math.round(Math.abs(rawDiff));

        if (approxDaysDiff < MIN_DAYS_DIFF) {
            return new Verdict(NAME, "X", new Score(1, 1), 0.0,
                    "Diferencia de descanso pequeña (" + approxDaysDiff
                            + " días) — impacto marginal");
        }

        // El descanso modifica ligeramente la probabilidad
        String result = homeFavored ? "1" : "2";
        // La confianza es proporcional a la diferencia de días, max 0.40
        double confidence = Math.min(0.40, 0.10 + approxDaysDiff * 0.04);

        String favoredTeam = homeFavored ? homeTeam : awayTeam;
        String unfavoredTeam = homeFavored ? awayTeam : homeTeam;

        String summary = String.format(
                "%s: ~%d días descanso | %s: ~%d días descanso → "
                        + "factor %.2fx para %s (favorece a %s)",
                homeTeam,
                homeFavored ? (4 + approxDaysDiff) : Math.max(1, 4 - approxDaysDiff),
                awayTeam,
                homeFavored ? Math.max(1, 4 - approxDaysDiff) : (4 + approxDaysDiff),
                factor, homeFavored ? homeTeam : awayTeam, favoredTeam);

        return new Verdict(NAME, result,
                new Score(homeFavored ? 2 : 0, homeFavored ? 0 : 2),
                confidence, summary);
    }

    @Override
    public String reasoning() {
        return "Análisis de la diferencia de días de descanso entre equipos. "
                + "Cuantifica la ventaja física de tener más recuperación. "
                + "Factor: ±3% por día de diferencia, máx ±12%.";
    }
}
