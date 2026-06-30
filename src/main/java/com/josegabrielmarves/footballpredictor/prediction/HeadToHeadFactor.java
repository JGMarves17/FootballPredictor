package com.josegabrielmarves.footballpredictor.prediction;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Factor de enfrentamiento directo (Head-to-Head).
 *
 * Lee data/results.json y calcula, para cada par de equipos,
 * el rendimiento histórico en enfrentamientos directos.
 *
 * Devuelve un factor multiplicativo para los goles esperados:
 * - > 1.0 si el equipo históricamente le gana al rival
 * - < 1.0 si el equipo históricamente pierde contra el rival
 * - 1.0 si no hay datos suficientes (< 3 partidos)
 */
public final class HeadToHeadFactor {

    private static final int MIN_MATCHES = 1;  // 1 partido ya da señal (con smooth fuerte)
    private static final double TIME_DECAY = 0.001; // decaimiento suave para partidos viejos
    private static final Path RESULTS_FILE = Path.of("data/results.json");

    // Cache: Map<"homeTeam||awayTeam", H2HResult>
    private static final Map<String, H2HResult> cache = new HashMap<>();
    private static boolean loaded = false;
    private static List<H2HMatch> allMatches = new ArrayList<>();

    private HeadToHeadFactor() {}

    /**
     * Resultado del análisis H2H.
     */
    public record H2HResult(
            double homeAdvantage,  // >1 si home domina el H2H
            double awayAdvantage,  // >1 si away domina el H2H
            int matchesPlayed,
            double homeAvgGoals,
            double awayAvgGoals
    ) {
        public static H2HResult neutral() {
            return new H2HResult(1.0, 1.0, 0, 0, 0);
        }
    }

    private record H2HMatch(
            String home, String away,
            int homeGoals, int awayGoals,
            long daysAgo
    ) {}

    /**
     * Obtiene el factor H2H para un partido.
     */
    public static H2HResult getH2H(String homeTeam, String awayTeam) {
        ensureLoaded();

        String key = homeTeam.toLowerCase().trim() + "||" + awayTeam.toLowerCase().trim();
        H2HResult cached = cache.get(key);
        if (cached != null) return cached;

        // Buscar partidos donde estos dos equipos se enfrentaron
        List<H2HMatch> matches = new ArrayList<>();
        for (H2HMatch m : allMatches) {
            boolean homeIsHome = m.home().equalsIgnoreCase(homeTeam.trim())
                    && m.away().equalsIgnoreCase(awayTeam.trim());
            boolean awayIsHome = m.home().equalsIgnoreCase(awayTeam.trim())
                    && m.away().equalsIgnoreCase(homeTeam.trim());

            if (homeIsHome) {
                matches.add(new H2HMatch(
                        m.home(), m.away(),
                        m.homeGoals(), m.awayGoals(),
                        m.daysAgo()
                ));
            } else if (awayIsHome) {
                // Invertir: cuando away fue local, intercambiamos
                matches.add(new H2HMatch(
                        m.away(), m.home(),
                        m.awayGoals(), m.homeGoals(),
                        m.daysAgo()
                ));
            }
        }

        if (matches.size() < MIN_MATCHES) {
            H2HResult neutral = H2HResult.neutral();
            cache.put(key, neutral);
            return neutral;
        }

        // Calcular promedio ponderado por decaimiento temporal
        double totalWeight = 0;
        double weightedHomeGoals = 0;
        double weightedAwayGoals = 0;

        for (H2HMatch m : matches) {
            double w = Math.exp(-TIME_DECAY * m.daysAgo());
            totalWeight += w;
            weightedHomeGoals += w * m.homeGoals();
            weightedAwayGoals += w * m.awayGoals();
        }

        double avgHome = weightedHomeGoals / totalWeight;
        double avgAway = weightedAwayGoals / totalWeight;

        // El factor es la relación entre goles anotados y recibidos
        // normalizado por BASELINE_GOALS (1.35)
        double homeAdv = avgHome > 0 ? avgHome / 1.35 : 1.0;
        double awayAdv = avgAway > 0 ? avgAway / 1.35 : 1.0;

        // Suavizar: cuanto más partidos, más confianza
        // Con 1 partido → 10% del efecto; con 5+ → efecto completo
        double smoothFactor = Math.min(1.0, matches.size() / 5.0);
        homeAdv = 1.0 + (homeAdv - 1.0) * smoothFactor;
        awayAdv = 1.0 + (awayAdv - 1.0) * smoothFactor;

        // Clampear a rango razonable (máximo ±15% con 1 partido, ±20% con muchos)
        double maxAdj = 0.15 + smoothFactor * 0.05;
        homeAdv = clamp(homeAdv, 1.0 - maxAdj, 1.0 + maxAdj);
        awayAdv = clamp(awayAdv, 1.0 - maxAdj, 1.0 + maxAdj);

        H2HResult result = new H2HResult(homeAdv, awayAdv, matches.size(), avgHome, avgAway);
        cache.put(key, result);
        return result;
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        if (!Files.exists(RESULTS_FILE)) {
            System.err.println("[HeadToHeadFactor] No se encuentra " + RESULTS_FILE);
            return;
        }

        try (Reader reader = Files.newBufferedReader(RESULTS_FILE, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            JsonArray matchesArray = root.getAsJsonArray("matches");
            LocalDate now = LocalDate.now();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            for (JsonElement el : matchesArray) {
                JsonObject m = el.getAsJsonObject();
                String home = m.get("homeName").getAsString();
                String away = m.get("awayName").getAsString();
                int hg = m.get("hg").getAsInt();
                int ag = m.get("ag").getAsInt();
                String dateStr = m.get("date").getAsString();

                long daysAgo = 0;
                try {
                    LocalDate matchDate = LocalDate.parse(dateStr, fmt);
                    daysAgo = java.time.temporal.ChronoUnit.DAYS.between(matchDate, now);
                } catch (Exception e) {
                    daysAgo = 365;
                }

                allMatches.add(new H2HMatch(home, away, hg, ag, Math.max(0, daysAgo)));
            }

            System.out.printf("[HeadToHeadFactor] Cargados %d partidos históricos%n", allMatches.size());
        } catch (IOException e) {
            System.err.println("[HeadToHeadFactor] Error cargando: " + e.getMessage());
        }
    }

    /**
     * Reinicia la caché (útil para tests).
     */
    public static void reset() {
        cache.clear();
        allMatches.clear();
        loaded = false;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
