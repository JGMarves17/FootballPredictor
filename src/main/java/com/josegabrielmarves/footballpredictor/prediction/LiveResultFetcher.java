package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.api.datasource.LiveStandingsProvider;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Busca resultados reales desde internet (worldcup26.ir) y actualiza
 * automáticamente el modelo (Elo, GLM, TournamentConditioner, xG).
 *
 * Uso:
 *   LiveResultFetcher fetcher = new LiveResultFetcher(ratings, wcHistory);
 *   int actualizados = fetcher.fetchAndUpdate();
 *
 * Si hay datos de xG disponibles en la fuente, también los aplica.
 * Proxy xG usado si no hay xG real (85% de los goles + 0.5).
 */
public final class LiveResultFetcher {

    private final Map<String, EloRating> ratings;
    private final List<TournamentGLM.MatchData> wcHistory;
    private final LiveMatchUpdater updater;

    public LiveResultFetcher(Map<String, EloRating> ratings,
                             List<TournamentGLM.MatchData> wcHistory) {
        this.ratings   = ratings;
        this.wcHistory = wcHistory;
        this.updater   = new LiveMatchUpdater(ratings, wcHistory);
    }

    /**
     * Fetch resultados desde worldcup26.ir y actualiza el modelo.
     * @return número de partidos nuevos aplicados
     */
    public int fetchAndUpdate() {
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("  LiveResultFetcher — buscando resultados...");
        System.out.println("═══════════════════════════════════════════");

        Map<String, int[]> scores = LiveStandingsProvider.fetchScores();
        if (scores.isEmpty()) {
            System.out.println("  No se encontraron resultados nuevos.");
            return 0;
        }

        int count = 0;
        for (Map.Entry<String, int[]> e : scores.entrySet()) {
            String key = e.getKey();
            int[] hgAg = e.getValue();

            String[] parts = key.split(":", 2);
            if (parts.length != 2) continue;

            String home = parts[0].trim();
            String away = parts[1].trim();
            int hg = hgAg[0];
            int ag = hgAg[1];

            // Verificar si ya está registrado en el historial
            if (yaRegistrado(home, away)) {
                System.out.printf("  ⏭️  %s vs %s (%d-%d) ya registrado%n", home, away, hg, ag);
                continue;
            }

            boolean homeAdv = esHost(home);
            updater.matchPlayed(home, away, hg, ag, homeAdv);
            count++;
        }

        System.out.printf("  → %d partidos nuevos aplicados%n", count);
        return count;
    }

    private boolean yaRegistrado(String home, String away) {
        return wcHistory.stream().anyMatch(m ->
                m.home().equalsIgnoreCase(home) && m.away().equalsIgnoreCase(away));
    }

    /**
     * Ejecuta fetch y luego corre el pipeline de predicciones.
     */
    public static void autoUpdate(LocalDate today,
                                  Map<String, EloRating> ratings,
                                  List<TournamentGLM.MatchData> wcHistory,
                                  List<MatchdayEngine.MatchInput> matchday,
                                  int jornada) {
        LiveResultFetcher fetcher = new LiveResultFetcher(ratings, wcHistory);
        int nuevos = fetcher.fetchAndUpdate();

        if (nuevos > 0) {
            System.out.printf("%n  GLM re-calibrado con %d partidos%n", wcHistory.size());
            TournamentGLM newGlm = TournamentGLM.fit(wcHistory, ratings);
            PoissonPredictor.setGLM(newGlm);
            newGlm.printStrengths(ratings.keySet().stream().sorted().toList());
            TournamentConditioner.getInstance().printAdjustments();
        }

        System.out.printf("%n  Generando predicciones jornada %d...%n", jornada);
        MatchdayEngine.preMatchday(jornada, matchday, ratings, today);
    }

    private static boolean esHost(String t) {
        return t.equals("Mexico") || t.equals("USA")
                || t.equals("United States") || t.equals("Canada");
    }
}
