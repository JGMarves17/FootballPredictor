package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

/**
 * Juez basado en el rating Elo histórico.
 * <p>
 * Analiza la diferencia de rating entre ambos equipos usando
 * {@link PoissonPredictor#matchProbabilities(EloRating, EloRating, double)}
 * (matriz solo-Elo, sin ajuste por forma ni GLM).
 * <p>
 * Veredicto: el resultado más probable según la fortaleza histórica,
 * con el marcador exacto extraído de la matriz DC de Elo.
 */
public final class EloJudge implements MatchJudge {

    private static final String NAME = "Elo";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Verdict judge(String homeTeam, EloRating home,
                         String awayTeam, EloRating away,
                         double homeBonus, Stage stage) {
        // Probabilidades Elo puras
        PoissonPredictor.MatchProbabilities probs =
                PoissonPredictor.matchProbabilities(home, away, homeBonus);

        // Marcador más probable según Elo
        Score exact = PoissonPredictor.mostLikelyScore(home, away, homeBonus);

        // Resultado 1X2 dominante
        String result;
        double confidence;
        if (probs.homeWin() >= probs.draw() && probs.homeWin() >= probs.awayWin()) {
            result = "1";
            confidence = probs.homeWin();
        } else if (probs.awayWin() >= probs.draw()) {
            result = "2";
            confidence = probs.awayWin();
        } else {
            result = "X";
            confidence = probs.draw();
        }

        // Summary con datos reales
        double diff = home.rating() - away.rating();
        String diffDesc = diff > 0
                ? String.format("%s supera a %s por %.0f pts",
                homeTeam, awayTeam, Math.abs(diff))
                : String.format("%s supera a %s por %.0f pts",
                awayTeam, homeTeam, Math.abs(diff));
        String summary = String.format(
                "%s → %s: %.0f%% según rating histórico",
                diffDesc,
                result.equals("1") ? homeTeam : result.equals("2") ? awayTeam : "Empate",
                confidence * 100);

        return new Verdict(NAME, result, exact, confidence, summary);
    }

    @Override
    public String reasoning() {
        return "Análisis basado en el rating Elo histórico de ambos equipos "
                + "(calibrado sobre 920 partidos internacionales 2023-2026). "
                + "Usa matriz Poisson con corrección Dixon-Coles.";
    }
}
