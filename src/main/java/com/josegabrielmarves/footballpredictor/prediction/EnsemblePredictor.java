package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.api.datasource.OddsProvider;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

/**
 * Ensemble Predictor (Fase 11) — combina nuestro modelo Poisson/Elo
 * con las probabilidades implícitas del mercado de apuestas.
 *
 * Fórmula: P_ensemble = α·P_modelo + (1−α)·P_mercado
 *
 * El mercado incorpora información que nuestro modelo no tiene
 * (lesiones, alineaciones, clima, ánimo del vestuario).
 * α = 0.5 por defecto: peso igual a modelo y mercado.
 * α = 1.0: solo modelo. α = 0.0: solo mercado.
 *
 * Uso:
 *   EnsemblePredictor ep = new EnsemblePredictor(new OddsProvider(API_KEY));
 *   double[] probs = ep.probabilities("Spain", spainRating, "Morocco", moroccoRating, 0.0);
 *   // probs = [P(homeWin), P(draw), P(awayWin)]
 */
public final class EnsemblePredictor {

    /** Peso del modelo propio vs mercado. 0.5 = promedio simple. */
    public static final double DEFAULT_ALPHA = 0.5;

    /** Alpha usado en PipelineRunner para comparación modelo vs mercado (15% modelo, 85% mercado). */
    public static final double ALPHA_MARKET_HEAVY = 0.15;

    /** API key de The Odds — activada para todo el pipeline. */
    public static final String DEFAULT_API_KEY = "a1d46a53187e24d4f564000bb9319181";

    private final OddsProvider oddsProvider;
    private final double alpha;

    /** Crea un EnsemblePredictor con la API key por defecto y alpha=0.15 (mercado pesado). */
    public EnsemblePredictor() {
        this(new OddsProvider(DEFAULT_API_KEY), ALPHA_MARKET_HEAVY);
    }

    public EnsemblePredictor(OddsProvider oddsProvider) {
        this(oddsProvider, DEFAULT_ALPHA);
    }

    public EnsemblePredictor(OddsProvider oddsProvider, double alpha) {
        if (alpha < 0 || alpha > 1) throw new IllegalArgumentException("alpha debe estar en [0,1]");
        this.oddsProvider = oddsProvider;
        this.alpha = alpha;
    }

    /**
     * Probabilidades combinadas modelo + mercado.
     *
     * @return array [pHomeWin, pDraw, pAwayWin] normalizado.
     *         Si no hay odds disponibles, devuelve solo el modelo (α=1).
     */
    public double[] probabilities(String homeTeam, EloRating home,
                                  String awayTeam, EloRating away,
                                  double homeBonus) {
        // Probabilidades del modelo
        PoissonPredictor.MatchProbabilities model =
                PoissonPredictor.matchProbabilities(home, away, homeBonus);
        double[] modelProbs = {model.homeWin(), model.draw(), model.awayWin()};

        // Probabilidades del mercado
        double[] marketProbs = oddsProvider.getImpliedProbabilities(homeTeam, awayTeam);

        if (marketProbs == null) {
            System.out.printf("[Ensemble] Sin odds para %s vs %s — usando solo modelo%n",
                    homeTeam, awayTeam);
            return modelProbs;
        }

        // Mezcla ponderada
        double[] ensemble = new double[3];
        for (int i = 0; i < 3; i++) {
            ensemble[i] = alpha * modelProbs[i] + (1 - alpha) * marketProbs[i];
        }

        System.out.printf("[Ensemble] %s vs %s%n" +
                        "  Modelo:  1=%.1f%% X=%.1f%% 2=%.1f%%%n" +
                        "  Mercado: 1=%.1f%% X=%.1f%% 2=%.1f%%%n" +
                        "  Ensemble(α=%.1f): 1=%.1f%% X=%.1f%% 2=%.1f%%%n",
                homeTeam, awayTeam,
                modelProbs[0]*100,  modelProbs[1]*100,  modelProbs[2]*100,
                marketProbs[0]*100, marketProbs[1]*100, marketProbs[2]*100,
                alpha,
                ensemble[0]*100, ensemble[1]*100, ensemble[2]*100);

        return ensemble;
    }

    /**
     * Punto de entrada: muestra los partidos disponibles con odds
     * y sus probabilidades ensemble.
     * API_KEY se pasa como argumento o se hardcodea aquí.
     */
    public static void main(String[] args) {
        String apiKey = args.length > 0 ? args[0]
                : System.getenv("ODDS_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = DEFAULT_API_KEY;
        }
        System.out.println("[EnsemblePredictor] API key activada desde " +
                (args.length > 0 ? "argumento" :
                 System.getenv("ODDS_API_KEY") != null ? "env ODDS_API_KEY" :
                 "constante DEFAULT_API_KEY"));

        OddsProvider odds = new OddsProvider(apiKey);
        System.out.println("Obteniendo odds del Mundial 2026...");

        var matches = odds.getAllMatches();
        if (matches.isEmpty()) {
            System.out.println("Sin partidos disponibles en este momento.");
            return;
        }

        System.out.printf("%n%d partidos con odds disponibles:%n", matches.size());
        for (var m : matches) {
            System.out.printf("  %s vs %s — 1=%.1f%% X=%.1f%% 2=%.1f%%%n",
                    m.homeTeam(), m.awayTeam(),
                    m.pHome()*100, m.pDraw()*100, m.pAway()*100);
        }
    }
}