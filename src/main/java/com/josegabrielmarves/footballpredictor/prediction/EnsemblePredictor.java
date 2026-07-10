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

    /** API key de The Odds — se lee desde variable de entorno ODDS_API_KEY. */
    public static final String DEFAULT_API_KEY = getApiKey();

    private final OddsProvider oddsProvider;
    private final double alpha;

    /**
     * Obtiene la API key desde la variable de entorno ODDS_API_KEY.
     * Si no está definida, devuelve la clave por defecto (hardcodeada como fallback).
     */
    public static String getApiKey() {
        String envKey = System.getenv("ODDS_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }
        // Fallback a la clave hardcodeada (solo para desarrollo)
        return "a1d46a53187e24d4f564000bb9319181";
    }

    /** Crea un EnsemblePredictor con la API key por defecto y alpha=0.15 (mercado pesado). */
    public EnsemblePredictor() {
        this(DEFAULT_API_KEY, ALPHA_MARKET_HEAVY);
    }

    public EnsemblePredictor(String apiKey) {
        this(apiKey, DEFAULT_ALPHA);
    }

    public EnsemblePredictor(String apiKey, double alpha) {
        if (alpha < 0 || alpha > 1) throw new IllegalArgumentException("alpha debe estar en [0,1]");
        this.alpha = alpha;
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[EnsemblePredictor] ⚠️ ODDS_API_KEY no configurada — mercado deshabilitado");
            this.oddsProvider = null;
        } else {
            this.oddsProvider = new OddsProvider(apiKey);
        }
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
     * Probabilidades combinadas modelo (Elo-simple) + mercado.
     * Útil para backtest y comparaciones rápidas.
     *
     * @return array [pHomeWin, pDraw, pAwayWin] normalizado.
     *         Si no hay odds disponibles, devuelve solo el modelo (α=1).
     */
    public double[] probabilities(String homeTeam, EloRating home,
                                  String awayTeam, EloRating away,
                                  double homeBonus) {
        return probabilitiesImpl(homeTeam, home, awayTeam, away, homeBonus, false, null);
    }

    /**
     * Probabilidades combinadas modelo (TORNEO: triple-blend + xG + altitud) + mercado.
     * Usa el mismo modelo que el pipeline principal (PoissonPredictor versión torneo).
     * Preferir este método para predicciones reales de quiniela.
     *
     * @return array [pHomeWin, pDraw, pAwayWin]
     */
    public double[] probabilitiesTournament(String homeTeam, EloRating home,
                                            String awayTeam, EloRating away,
                                            double homeBonus) {
        return probabilitiesImpl(homeTeam, home, awayTeam, away, homeBonus, true, null);
    }

    public double[] probabilitiesTournament(String homeTeam, EloRating home,
                                            String awayTeam, EloRating away,
                                            double homeBonus,
                                            com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage stage) {
        return probabilitiesImpl(homeTeam, home, awayTeam, away, homeBonus, true, stage);
    }

    /**
     * Implementación unificada para ambos modos (simple y torneo).
     */
    private double[] probabilitiesImpl(String homeTeam, EloRating home,
                                       String awayTeam, EloRating away,
                                       double homeBonus, boolean tournament,
                                       com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage stage) {
        // Probabilidades del modelo
        PoissonPredictor.MatchProbabilities model = tournament
                ? PoissonPredictor.matchProbabilitiesTournament(homeTeam, home, awayTeam, away, homeBonus, stage)
                : PoissonPredictor.matchProbabilities(home, away, homeBonus);
        double[] modelProbs = {model.homeWin(), model.draw(), model.awayWin()};

        // Probabilidades del mercado
        double[] marketProbs = null;
        if (oddsProvider != null) {
            marketProbs = oddsProvider.getImpliedProbabilities(homeTeam, awayTeam);
        }

        if (marketProbs == null) {
            // COMENTADO PARA PRODUCCIÓN: System.out.printf("[Ensemble] Sin odds para %s vs %s — usando solo modelo%n",
            //         homeTeam, awayTeam);
            return modelProbs;
        }

        // Mezcla ponderada
        double[] ensemble = new double[3];
        for (int i = 0; i < 3; i++) {
            ensemble[i] = alpha * modelProbs[i] + (1 - alpha) * marketProbs[i];
        }

        // COMENTADO PARA PRODUCCIÓN: System.out.printf("[Ensemble] %s vs %s%n" +
        //         "  Modelo:  1=%.1f%% X=%.1f%% 2=%.1f%%%n" +
        //         "  Mercado: 1=%.1f%% X=%.1f%% 2=%.1f%%%n" +
        //         "  Ensemble(α=%.1f): 1=%.1f%% X=%.1f%% 2=%.1f%%%n",
        //         homeTeam, awayTeam,
        //         modelProbs[0]*100,  modelProbs[1]*100,  modelProbs[2]*100,
        //         marketProbs[0]*100, marketProbs[1]*100, marketProbs[2]*100,
        //         alpha,
        //         ensemble[0]*100, ensemble[1]*100, ensemble[2]*100);

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