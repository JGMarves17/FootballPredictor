package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.HeadToHeadFactor;
import com.josegabrielmarves.footballpredictor.prediction.HeadToHeadFactor.H2HResult;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

/**
 * Juez basado en el historial de enfrentamientos directos (Head-to-Head).
 * <p>
 * Analiza los partidos previos entre ambos equipos desde {@code data/results.json}
 * usando {@link HeadToHeadFactor}. Incluye decaimiento temporal y smoothing
 * progresivo según el número de encuentros.
 * <p>
 * Veredicto: resultado más frecuente en el historial. Solo emite juicio
 * cuando hay al menos 3 enfrentamientos previos registrados.
 */
public final class H2HJudge implements MatchJudge {

    private static final String NAME = "H2H";
    private static final int MIN_MATCHES_FOR_CONFIDENT = 3;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Verdict judge(String homeTeam, EloRating home,
                         String awayTeam, EloRating away,
                         double homeBonus, Stage stage) {
        H2HResult h2h = HeadToHeadFactor.getH2H(homeTeam, awayTeam);

        if (h2h.matchesPlayed() < 1) {
            return new Verdict(NAME, "X", new Score(1, 1), 0.0,
                    "Sin enfrentamientos previos entre " + homeTeam + " y " + awayTeam);
        }

        // Determinar resultado según promedios históricos de goles
        double lH = h2h.homeAvgGoals();
        double lA = h2h.awayAvgGoals();

        String result;
        double confidence;

        if (lH > lA + 0.3) {
            result = "1";
            confidence = Math.min(0.85, 0.40 + h2h.matchesPlayed() * 0.05);
        } else if (lA > lH + 0.3) {
            result = "2";
            confidence = Math.min(0.85, 0.40 + h2h.matchesPlayed() * 0.05);
        } else {
            result = "X";
            confidence = 0.30 + h2h.matchesPlayed() * 0.03;
        }

        // Ajustar confianza por cantidad de partidos
        if (h2h.matchesPlayed() < MIN_MATCHES_FOR_CONFIDENT) {
            confidence *= 0.5; // poca confianza con < 3 encuentros
        }

        // Score exacto recomendado: redondear promedios
        int sh = (int) Math.round(lH);
        int sa = (int) Math.round(lA);
        // Mínimo 0 goles
        sh = Math.max(0, sh);
        sa = Math.max(0, sa);

        // Summary con datos reales
        String summary = String.format(
                "%d enfrentamientos: %s promedia %.1f goles, %s promedia %.1f goles → %s (%.0f%%)",
                h2h.matchesPlayed(), homeTeam, lH, awayTeam, lA,
                result.equals("1") ? homeTeam : result.equals("2") ? awayTeam : "Empate",
                confidence * 100);

        return new Verdict(NAME, result, new Score(sh, sa), confidence, summary);
    }

    @Override
    public String reasoning() {
        return "Análisis basado en el historial de enfrentamientos directos "
                + "entre ambos equipos con decaimiento temporal y suavizado "
                + "progresivo según el número de encuentros.";
    }
}
