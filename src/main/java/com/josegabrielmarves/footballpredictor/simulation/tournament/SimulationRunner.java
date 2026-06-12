package com.josegabrielmarves.footballpredictor.simulation.tournament;

import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Punto de entrada para correr la simulación completa del Mundial 2026.
 *
 * <p>Flujo:
 * <ol>
 *   <li>Carga el fixture 2026 desde openfootball via OpenFootballProvider.</li>
 *   <li>Extrae los 12 grupos (partidos con campo "group" no nulo).</li>
 *   <li>Construye el mapa de ratings desde CalibratedEloRatings.</li>
 *   <li>Aplica los resultados reales ya jugados (actualización Elo post-jornada).</li>
 *   <li>Corre TournamentSimulator con 50k iteraciones e imprime el ranking.</li>
 * </ol>
 *
 * <p><b>Nota de nombres:</b> el fixture openfootball usa nombres propios
 * ("South Korea", "Czech Republic", "Ivory Coast"). Si CalibratedEloRatings
 * los tiene bajo otro nombre ("Korea Republic", "Côte d'Ivoire"), el equipo
 * parte con rating 1500 de fallback. Los equipos favoritos (España, Argentina,
 * Francia…) tienen nombres estándar y se resuelven correctamente.
 */
public final class SimulationRunner {

    private SimulationRunner() {}

    public static void main(String[] args) throws Exception {
        System.out.println("Cargando fixture 2026...");
        var provider = new OpenFootballProvider();
        List<Match> allMatches = provider.getWorldCupMatches(2026);
        System.out.printf("  %d partidos cargados%n", allMatches.size());

        // Extraer grupos (partidos con group != null)
        Map<String, List<Match>> groups = GroupExtractor.extractGroups(allMatches);
        System.out.printf("  %d grupos extraídos%n%n", groups.size());

        if (groups.isEmpty()) {
            System.err.println("ERROR: no se encontraron partidos con campo 'group'.");
            System.err.println("Verifica que OpenFootballProvider popule Match.group desde el JSON.");
            return;
        }

        // ── Construir ratings desde semillas calibradas ───────────────────────
        Map<String, EloRating> ratings = new HashMap<>();
        for (List<Match> groupMatches : groups.values()) {
            for (Match m : groupMatches) {
                ratings.putIfAbsent(m.homeTeam, CalibratedEloRatings.getRating(m.homeTeam));
                ratings.putIfAbsent(m.awayTeam, CalibratedEloRatings.getRating(m.awayTeam));
            }
        }

        // ── Aplicar resultados reales ya jugados (jornada 1) ─────────────────
        // México 2-1 Sudáfrica — México local en Azteca (HOME_ADVANTAGE)
        applyResult(ratings, "Mexico", "South Africa", 2, 1, EloCalculator.HOME_ADVANTAGE);

        // Corea del Sur 1-1 República Checa — cancha neutral
        applyResult(ratings, "South Korea", "Czech Republic", 1, 1, 0.0);

        // ── Agregar aquí los resultados de jornadas posteriores ───────────────
        // applyResult(ratings, "TeamA", "TeamB", golesA, golesB, homeBonus);

        System.out.println("Ratings inicializados y jornada 1 aplicada.");
        System.out.println("Corriendo simulación (50.000 iteraciones)...");
        long t0 = System.currentTimeMillis();

        TournamentSimulator.TournamentResult result =
                TournamentSimulator.run(groups, ratings, 50_000, 2026L);

        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("Completado en %.1f s%n", elapsed / 1000.0);

        result.printSummary();
    }

    /**
     * Aplica un resultado real al mapa de ratings (actualización Elo in-place).
     *
     * @param homeBonus EloCalculator.HOME_ADVANTAGE si es local real; 0 si cancha neutral
     */
    private static void applyResult(Map<String, EloRating> ratings,
                                    String homeTeam, String awayTeam,
                                    double homeGoals, double awayGoals,
                                    double homeBonus) {
        EloRating home = ratings.getOrDefault(homeTeam, EloRating.initial(homeTeam));
        EloRating away = ratings.getOrDefault(awayTeam, EloRating.initial(awayTeam));
        EloCalculator.UpdatedRatings updated =
                EloCalculator.updateRatings(home, away, homeGoals, awayGoals,
                        EloCalculator.K_WORLD_CUP, homeBonus);
        ratings.put(homeTeam, updated.home());
        ratings.put(awayTeam, updated.away());
    }
}