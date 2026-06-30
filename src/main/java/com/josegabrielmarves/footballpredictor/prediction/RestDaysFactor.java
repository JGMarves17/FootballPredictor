package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.model.Match;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Factor de días de descanso entre partidos.
 *
 * Cuantos más días de descanso tiene un equipo respecto a su rival,
 * mejor rendimiento se espera.
 *
 * Fórmula: factor = 1.0 + 0.03 * (restDaysHome - restDaysAway)
 * El factor se clamp entre [0.88, 1.12] (±12% máximo).
 */
public final class RestDaysFactor {

    private static final double REST_COEFFICIENT = 0.03;  // 3% por día de diferencia
    private static final double MAX_ADJUSTMENT = 0.12;     // máx ±12%
    private static final int DEFAULT_REST = 4;              // descanso por defecto si no hay datos

    // Cache de últimos partidos por equipo
    private static final Map<String, LocalDate> lastMatchDate = new HashMap<>();
    private static boolean initialized = false;

    private RestDaysFactor() {}

    /**
     * Inicializa el factor con los partidos del fixture.
     * Debe llamarse antes de getFactor(), típicamente al cargar el fixture.
     */
    public static void initialize(List<Match> allMatches) {
        lastMatchDate.clear();

        // Solo nos interesan partidos ya jugados para el descanso
        List<Match> played = allMatches.stream()
                .filter(m -> m.score != null)
                .toList();

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        for (Match m : played) {
            try {
                LocalDate date = LocalDate.parse(m.date, fmt);
                // Usar isAfter() para conservar la fecha MÁS RECIENTE por equipo
                LocalDate prevHome = lastMatchDate.get(m.homeTeam);
                if (prevHome == null || date.isAfter(prevHome)) {
                    lastMatchDate.put(m.homeTeam, date);
                }
                LocalDate prevAway = lastMatchDate.get(m.awayTeam);
                if (prevAway == null || date.isAfter(prevAway)) {
                    lastMatchDate.put(m.awayTeam, date);
                }
            } catch (Exception e) {
                // ignorar fechas mal formadas
            }
        }

        initialized = true;
        System.out.printf("[RestDaysFactor] Inicializado con %d equipos con partidos jugados%n",
                lastMatchDate.size());
    }

    /**
     * Inicializa manualmente con un mapa de fechas.
     */
    public static void initialize(Map<String, LocalDate> lastDates) {
        lastMatchDate.clear();
        lastMatchDate.putAll(lastDates);
        initialized = true;
    }

    /**
     * Obtiene el factor de descanso para un partido.
     *
     * @param homeTeam equipo local
     * @param awayTeam equipo visitante
     * @param matchDate fecha del partido
     * @return factor multiplicativo para λ del home (el factor del away es 1/factor)
     */
    public static double getHomeRestFactor(String homeTeam, String awayTeam, LocalDate matchDate) {
        if (!initialized) return 1.0;

        int restHome = getRestDays(homeTeam, matchDate);
        int restAway = getRestDays(awayTeam, matchDate);

        int diff = restHome - restAway;
        double factor = 1.0 + REST_COEFFICIENT * diff;

        return clamp(factor, 1.0 - MAX_ADJUSTMENT, 1.0 + MAX_ADJUSTMENT);
    }

    /**
     * Obtiene los días de descanso de un equipo hasta una fecha.
     */
    private static int getRestDays(String team, LocalDate matchDate) {
        LocalDate last = lastMatchDate.get(team);
        if (last == null) return DEFAULT_REST;
        return (int) java.time.temporal.ChronoUnit.DAYS.between(last, matchDate);
    }

    public static void reset() {
        lastMatchDate.clear();
        initialized = false;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
