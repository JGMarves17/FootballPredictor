package com.josegabrielmarves.footballpredictor.prediction;

/**
 * Ajuste por altitud de sede para el Mundial 2026.
 *
 * Sedes de alta altitud:
 *   - Mexico City (Estadio Azteca):     ~2,250m
 *   - Guadalajara (Estadio Akron):      ~1,566m
 *   - Monterrey (Estadio BBVA):         ~540m
 *   - USA/Canadá (17 sedes restantes):  <200m (sin efecto)
 *
 * ⚽ EFECTO DEMOSTRADO en la literatura FIFA:
 *   - A >1,500m, el VO₂max se reduce ~8-12% en no aclimatados
 *   - El balón viaja ~5% más rápido (menos densidad del aire)
 *   - Los arqueros tienen menos tiempo de reacción (efecto "burbuja")
 *   - Los equipos locales aclimatados tienen una ventaja de ~0.35 goles
 *     extra sobre la ventaja de localía normal (HOME_ADVANTAGE)
 *
 * Referencia: "Football at Altitude" — FIFA Medical & Science Department,
 * adaptado para estimaciones del Mundial 2026 en Norteamérica.
 *
 * Uso:
 *   double[] adj = AltitudeFactor.adjustLambdas(λ1, λ2, "Mexico", "Spain");
 *   // → λ₁*0.94, λ₂*0.85  (México aclimatado, España no, CDMX ~1,800m)
 */
public final class AltitudeFactor {

    private AltitudeFactor() {}

    // ── Constantes ─────────────────────────────────────────────────────────────

    /** Umbral para considerar efecto de altitud significativo. */
    public static final double MODERATE_THRESHOLD = 500;    // metros

    /** Umbral para efecto severo (fatiga, rendimiento reducido). */
    public static final double HIGH_THRESHOLD = 1500;       // metros

    /** Altitud promedio de las sedes mexicanas (CDMX 2250 + GDL 1566 + MTY 540). */
    public static final double ALT_MEXICO_AVG = 1800;       // metros

    // ── Detección de altitud ───────────────────────────────────────────────────

    /**
     * Determina la altitud de la sede según los equipos que juegan.
     *
     * Para el Mundial 2026, México es la única sede con altitud significativa.
     * Cuando México juega como local (o en el fixture), el partido es en
     * territorio mexicano → aplicamos altitud promedio de sus sedes.
     */
    public static double venueAltitude(String team1, String team2) {
        if ("Mexico".equals(team1) || "Mexico".equals(team2)) {
            return ALT_MEXICO_AVG;
        }
        return 0; // USA y Canadá están cerca del nivel del mar
    }

    /** ¿Este equipo está aclimatado fisiológicamente a la altura? */
    public static boolean isAcclimatized(String team) {
        return "Mexico".equals(team);
    }

    // ── Factor de ajuste ───────────────────────────────────────────────────────

    /**
     * Factor de ajuste de λ (goles esperados) según altitud y aclimatación.
     *
     *   Altitud     | Aclimatado  | Factor  | Efecto
     *   ------------|-------------|---------|--------------------
     *   <500m       | cualquiera  | 1.00    | Sin efecto
     *   500-1500m   | sí          | 0.97    | -3% (mínimo)
     *   500-1500m   | no          | 0.93    | -7% (moderado)
     *   >1500m      | sí          | 0.94    | -6% (México en casa)
     *   >1500m      | no          | 0.85    | -15% (significativo)
     *
     * @param altitude metros sobre el nivel del mar
     * @param acclimatized si el equipo está aclimatado
     * @return factor multiplicativo para λ (1.0 = sin cambio)
     */
    public static double lambdaFactor(double altitude, boolean acclimatized) {
        if (altitude < MODERATE_THRESHOLD) return 1.0;

        if (altitude < HIGH_THRESHOLD) {
            return acclimatized ? 0.97 : 0.93;
        }

        // Alta altitud (>1500m)
        return acclimatized ? 0.94 : 0.85;
    }

    /**
     * Ajusta los λ esperados de ambos equipos según la altitud de la sede.
     *
     * @param lambda1 λ base del equipo 1 (local simbólico)
     * @param lambda2 λ base del equipo 2 (visitante simbólico)
     * @param team1   nombre del equipo 1
     * @param team2   nombre del equipo 2
     * @return array [λ₁_ajustado, λ₂_ajustado]
     */
    public static double[] adjustLambdas(double lambda1, double lambda2,
                                         String team1, String team2) {
        double altitude = venueAltitude(team1, team2);
        if (altitude < MODERATE_THRESHOLD) {
            return new double[]{lambda1, lambda2};
        }

        double factor1 = lambdaFactor(altitude, isAcclimatized(team1));
        double factor2 = lambdaFactor(altitude, isAcclimatized(team2));

        double adj1 = lambda1 * factor1;
        double adj2 = lambda2 * factor2;

        // COMENTADO PARA PRODUCCIÓN: System.out.printf("  🏔️ [Altitud] %s: λ %.3f→%.3f (×%.2f) | %s: λ %.3f→%.3f (×%.2f) @ %.0fm%n",
        //         team1, lambda1, adj1, factor1,
        //         team2, lambda2, adj2, factor2,
        //         altitude);

        return new double[]{adj1, adj2};
    }
}