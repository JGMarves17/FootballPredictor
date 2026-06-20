package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

import java.util.*;

/**
 * Actualización en tiempo real después de cada partido jugado.
 *
 * Cuando un partido termina:
 *   1. Agrega el xG real al TournamentConditioner
 *   2. Actualiza los ratings Elo con el resultado
 *   3. Re-calibra el TournamentGLM con todos los datos acumulados
 *   4. El próximo ScoreMatrix.compute() usa automáticamente los datos actualizados
 *
 * Uso:
 *   LiveMatchUpdater updater = new LiveMatchUpdater(ratings, wcMatches);
 *   updater.matchPlayed("Spain", "Uruguay", 2, 1, 1.85, 0.72);
 *   // → siguiente ScoreMatrix ya usa xG de España vs Uruguay
 */
public final class LiveMatchUpdater {

    private final Map<String, EloRating> ratings;
    private final List<TournamentGLM.MatchData> wcMatches;
    private final TournamentConditioner conditioner;

    public LiveMatchUpdater(Map<String, EloRating> ratings,
                            List<TournamentGLM.MatchData> initialMatches) {
        this.ratings     = ratings;
        this.wcMatches   = new ArrayList<>(initialMatches);
        this.conditioner = TournamentConditioner.getInstance();
    }

    /**
     * Registra un partido jugado y actualiza todos los modelos.
     *
     * @param home       equipo local
     * @param away       equipo visitante
     * @param homeGoals  goles locales
     * @param awayGoals  goles visitantes
     * @param homeXG     xG del local (buscar en RealGM tracker)
     * @param awayXG     xG del visitante
     * @param homeAdv    true si el local es anfitrión oficial del torneo
     */
    public void matchPlayed(String home, String away,
                            int homeGoals, int awayGoals,
                            double homeXG, double awayXG,
                            boolean homeAdv) {

        System.out.printf("[LiveUpdater] %s %d-%d %s (xG: %.2f-%.2f)%n",
                home, homeGoals, awayGoals, away, homeXG, awayXG);

        // 1. Actualizar xG en TournamentConditioner
        conditioner.addMatch(home, away, homeXG, awayXG, homeGoals, awayGoals);

        // 2. Actualizar ratings Elo
        double bonus = homeAdv ? EloCalculator.HOME_ADVANTAGE : 0.0;
        EloRating h = ratings.getOrDefault(home, EloRating.initial(home));
        EloRating a = ratings.getOrDefault(away, EloRating.initial(away));
        var updated = EloCalculator.updateRatings(
                h, a, homeGoals, awayGoals, EloCalculator.K_WORLD_CUP, bonus);
        ratings.put(home, updated.home());
        ratings.put(away, updated.away());

        // 3. Agregar al historial del GLM
        wcMatches.add(new TournamentGLM.MatchData(home, away, homeGoals, awayGoals, homeAdv));

        // 4. Re-calibrar TournamentGLM con todos los datos acumulados
        TournamentGLM newGlm = TournamentGLM.fit(wcMatches, ratings);
        PoissonPredictor.setGLM(newGlm);

        System.out.printf("  → GLM re-calibrado con %d partidos | " +
                        "Elo %s: %.0f → %.0f | Elo %s: %.0f → %.0f%n",
                newGlm.matchesUsed(),
                home, h.rating(), updated.home().rating(),
                away, a.rating(), updated.away().rating());
    }

    /**
     * Versión simplificada cuando no tienes xG disponible todavía.
     * Usa estimación de xG basada en el marcador (proxy temporal).
     */
    public void matchPlayed(String home, String away,
                            int homeGoals, int awayGoals, boolean homeAdv) {
        // Proxy xG: goles reales + ajuste pequeño hacia el expected
        double homeXG = homeGoals * 0.85 + 0.5;  // subestimamos un poco
        double awayXG = awayGoals * 0.85 + 0.5;
        matchPlayed(home, away, homeGoals, awayGoals, homeXG, awayXG, homeAdv);
    }

    /** Cuántos partidos lleva registrados. */
    public int matchesRecorded() { return wcMatches.size(); }
}