package com.josegabrielmarves.footballpredictor.prediction.elo;

/**
 * Cálculo de ratings Elo para fútbol internacional.
 *
 * Fórmulas y constantes portadas del modelo de referencia open-source (MIT)
 * github.com/Hicruben/world-cup-2026-prediction-model, backtesteado
 * walk-forward sobre 920 partidos internacionales (2023-2026):
 * ~61% de acierto en resultado, Brier ~0.54.
 *
 * Sin estado: todos los métodos son puros. Los K-factor y la ventaja de
 * local se pasan como parámetros usando las constantes de esta clase.
 */
public final class EloCalculator {

    // ── K-factor por importancia del partido (calibrados por backtest) ──
    /** Partidos de Mundial (fase de grupos y eliminatorias). */
    public static final double K_WORLD_CUP = 55.0;
    /** Eliminatorias mundialistas / clasificación. */
    public static final double K_QUALIFIER = 40.0;
    /** Torneos continentales (Copa América, Euro, etc.). */
    public static final double K_CONTINENTAL = 50.0;
    /** Nations League y similares. */
    public static final double K_NATIONS_LEAGUE = 32.0;
    /** Amistosos. */
    public static final double K_FRIENDLY = 18.0;
    /** Cualquier otra competición. */
    public static final double K_DEFAULT = 28.0;

    // ── Ventaja de local ──
    /**
     * Bonus Elo por jugar de local (75 pts, calibrado).
     * En el Mundial 2026 solo aplica a México, USA y Canadá;
     * para cancha neutral pasar 0.
     */
    public static final double HOME_ADVANTAGE = 75.0;

    private EloCalculator() {
        // Clase utilitaria: no instanciable.
    }

    /**
     * Probabilidad esperada de victoria del equipo A (logística sobre la
     * diferencia de ratings): P(A) = 1 / (1 + 10^((Rb - Ra) / 400)).
     */
    public static double calculateExpectedScore(double ratingA, double ratingB) {
        return calculateExpectedScore(ratingA, ratingB, 0.0);
    }

    /**
     * Variante con bonus de local sumado al rating de A.
     *
     * @param homeBonusA puntos Elo de ventaja para A (0 si cancha neutral)
     */
    public static double calculateExpectedScore(double ratingA, double ratingB, double homeBonusA) {
        return 1.0 / (1.0 + Math.pow(10.0, (ratingB - (ratingA + homeBonusA)) / 400.0));
    }

    /** Diferencia de rating: a - b. */
    public static double getRatingDifference(EloRating a, EloRating b) {
        return a.rating() - b.rating();
    }

    /**
     * Multiplicador de K por margen de goles (estándar World Football Elo):
     * x1.0 si la diferencia es 0 o 1 gol, x1.5 si es 2, (11+d)/8 si es 3+.
     */
    public static double goalDifferenceMultiplier(double homeGoals, double awayGoals) {
        double d = Math.abs(homeGoals - awayGoals);
        if (d <= 1) return 1.0;
        if (d == 2) return 1.5;
        return (11.0 + d) / 8.0;
    }

    /**
     * Actualiza ambos ratings tras un partido en cancha neutral.
     * El intercambio es de suma cero: lo que gana uno lo pierde el otro.
     *
     * @param k K-factor base según competición (usar las constantes K_*)
     * @return par de ratings actualizados (home, away)
     */
    public static UpdatedRatings updateRatings(EloRating home, EloRating away,
                                               double homeGoals, double awayGoals,
                                               double k) {
        return updateRatings(home, away, homeGoals, awayGoals, k, 0.0);
    }

    /**
     * Actualiza ambos ratings tras un partido, con ventaja de local.
     *
     * @param homeBonus puntos Elo de ventaja del local (0 = cancha neutral,
     *                  HOME_ADVANTAGE para anfitriones reales)
     */
    public static UpdatedRatings updateRatings(EloRating home, EloRating away,
                                               double homeGoals, double awayGoals,
                                               double k, double homeBonus) {
        double expected = calculateExpectedScore(home.rating(), away.rating(), homeBonus);
        double actual = homeGoals > awayGoals ? 1.0 : homeGoals < awayGoals ? 0.0 : 0.5;
        double delta = k * goalDifferenceMultiplier(homeGoals, awayGoals) * (actual - expected);
        return new UpdatedRatings(
                home.withRating(home.rating() + delta),
                away.withRating(away.rating() - delta)
        );
    }

    /** Resultado de una actualización: los dos ratings nuevos. */
    public record UpdatedRatings(EloRating home, EloRating away) { }
}
