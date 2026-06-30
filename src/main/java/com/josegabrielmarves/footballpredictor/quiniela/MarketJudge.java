package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.EnsemblePredictor;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

/**
 * Juez basado en las odds del mercado de apuestas (The Odds API).
 * <p>
 * Utiliza {@link EnsemblePredictor} para obtener las probabilidades blend
 * (modelo propio + mercado) con α=0.15 (85% peso al mercado).
 * <p>
 * Cuando no hay odds disponibles, el juez reporta que no hay datos de mercado
 * y emite un veredicto neutral.
 */
public final class MarketJudge implements MatchJudge {

    private static final String NAME = "Mercado";

    private static final int MAX_GOALS = 5;
    private static final double MIN_LAMBDA = 0.20;
    private static final double MAX_LAMBDA = 5.00;

    private final EnsemblePredictor ep;

    /**
     * @param ep instancia de EnsemblePredictor con acceso a la API de odds,
     *           o {@code null} (emite veredicto neutral)
     */
    public MarketJudge(EnsemblePredictor ep) {
        this.ep = ep;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Verdict judge(String homeTeam, EloRating home,
                         String awayTeam, EloRating away,
                         double homeBonus, Stage stage) {
        if (ep == null) {
            return new Verdict(NAME, "X", new Score(1, 1), 0.0,
                    "Mercado no disponible — EnsemblePredictor sin configurar");
        }

        // Obtener probabilidades blend (modelo + mercado)
        double[] blended;
        try {
            blended = ep.probabilitiesTournament(homeTeam, home, awayTeam, away, homeBonus, stage);
        } catch (Exception e) {
            return new Verdict(NAME, "X", new Score(1, 1), 0.0,
                    "Error consultando mercado: " + e.getMessage());
        }

        // Determinar si realmente hay señal de mercado comparando con el modelo puro
        var modelProbs = PoissonPredictor.matchProbabilitiesTournament(
                homeTeam, home, awayTeam, away, homeBonus, stage);
        boolean hasMarketSignal = Math.abs(blended[0] - modelProbs.homeWin()) > 0.002
                || Math.abs(blended[2] - modelProbs.awayWin()) > 0.002;

        if (!hasMarketSignal) {
            // Si no hay odds, las probabilidades blend = modelo puro (sin mercado)
            String result;
            double confidence;
            if (blended[0] >= blended[1] && blended[0] >= blended[2]) {
                result = "1";
                confidence = blended[0];
            } else if (blended[2] >= blended[1]) {
                result = "2";
                confidence = blended[2];
            } else {
                result = "X";
                confidence = blended[1];
            }
            return new Verdict(NAME, result,
                    PoissonPredictor.mostLikelyScore(home, away, homeBonus),
                    confidence,
                    "Sin odds de mercado disponibles — usando solo modelo propio");
        }

        // Construir matriz DC a partir de las probabilidades blend para obtener
        // el marcador exacto más probable del ensemble
        double[][] matrix = PoissonPredictor.scoreMatrixTournamentWithMarket(
                homeTeam, home, awayTeam, away, homeBonus, stage, ep);

        // Marcador exacto más probable
        int bestH = 0, bestA = 0;
        double bestP = -1;
        for (int h = 0; h < Math.min(matrix.length, MAX_GOALS + 1); h++) {
            for (int a = 0; a < Math.min(matrix[h].length, MAX_GOALS + 1); a++) {
                if (matrix[h][a] > bestP) {
                    bestP = matrix[h][a];
                    bestH = h;
                    bestA = a;
                }
            }
        }

        // Resultado 1X2 según blend
        String result;
        double confidence;
        if (blended[0] >= blended[1] && blended[0] >= blended[2]) {
            result = "1";
            confidence = blended[0];
        } else if (blended[2] >= blended[1]) {
            result = "2";
            confidence = blended[2];
        } else {
            result = "X";
            confidence = blended[1];
        }

        String teamLabel = result.equals("1") ? homeTeam
                : result.equals("2") ? awayTeam : "Empate";

        String summary = String.format(
                "Mercado: %s %.0f%% | Empate %.0f%% | %s %.0f%% → %s",
                homeTeam, blended[0] * 100, blended[1] * 100,
                awayTeam, blended[2] * 100,
                teamLabel);

        return new Verdict(NAME, result, new Score(bestH, bestA), confidence, summary);
    }

    @Override
    public String reasoning() {
        return "Análisis basado en las probabilidades blend del modelo propio "
                + "con el mercado de apuestas (The Odds API, α=0.15). El mercado "
                + "captura información no modelada (lesiones, alineaciones, clima).";
    }
}
