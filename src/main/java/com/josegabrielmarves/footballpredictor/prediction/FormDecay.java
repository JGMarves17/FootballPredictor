package com.josegabrielmarves.footballpredictor.prediction;

import com.google.gson.*;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Decaimiento temporal de forma reciente (Fase 13+).
 *
 * Implementa la mejora del modelo del amigo: en vez de usar solo el rating
 * Elo histórico, ajusta el rating en función de la forma reciente del equipo
 * en los últimos N partidos, ponderando cada partido con e^(-λ × días_atrás).
 *
 * Fórmula de peso:   w_i = e^(-DECAY × days_ago_i)
 * Factor de ataque:  Σ(goles_anotados_i × w_i) / Σ(w_i) / BASE_GOALS
 * Factor de defensa: Σ(goles_recibidos_i × w_i) / Σ(w_i) / BASE_GOALS
 *
 * Ajuste Elo:
 *   formBonus = (attackFactor - 1) × ATK_WEIGHT + (1 - defenseFactor) × DEF_WEIGHT
 *   adjustedRating = clamp(baseRating + formBonus, 1200, 2500)
 *
 * Los factores >1 significan mejor rendimiento que el promedio histórico.
 */
public final class FormDecay {

    private static final double DECAY      = 0.02;  // e^(-0.02 × días) — igual al modelo del amigo
    private static final int    WINDOW     = 12;    // últimos N partidos
    private static final double BASE_GOALS = 1.35;  // media de goles (mismo que PoissonPredictor)
    private static final double ATK_WEIGHT = 150.0; // puntos Elo por unidad de mejora en ataque
    private static final double DEF_WEIGHT = 100.0; // puntos Elo por unidad de mejora en defensa
    private static final double MAX_BONUS  = 200.0; // cap máximo de ajuste

    private FormDecay() {}

    /** Factores de forma (ataque y defensa) para un equipo. */
    public record FormFactors(double attackFactor, double defenseFactor) {
        /** Bonus Elo equivalente a estos factores de forma. */
        public double eloBonus() {
            double bonus = (attackFactor - 1.0) * ATK_WEIGHT
                    + (1.0 - defenseFactor) * DEF_WEIGHT;
            return Math.max(-MAX_BONUS, Math.min(MAX_BONUS, bonus));
        }

        @Override public String toString() {
            return String.format("atk=%.2f def=%.2f → Elo %+.0f",
                    attackFactor, defenseFactor, eloBonus());
        }
    }

    /**
     * Ajusta el EloRating de un equipo incorporando su forma reciente.
     *
     * @param base      rating base (del modelo Elo calibrado + jornadas previas)
     * @param team      nombre del equipo (debe coincidir con homeName/awayName del JSON)
     * @param dataFile  ruta al results.json
     * @param today     fecha de referencia para calcular días atrás
     * @return rating ajustado — si no hay datos suficientes, devuelve el base sin cambios
     */
    public static EloRating adjust(EloRating base, String team,
                                   Path dataFile, LocalDate today) {
        try {
            FormFactors f = computeFormFactors(team, dataFile, today);
            double adjusted = Math.max(1200, Math.min(2500, base.rating() + f.eloBonus()));
            return base.withRating(adjusted);
        } catch (Exception e) {
            System.err.println("[FormDecay] No se pudo ajustar " + team + ": " + e.getMessage());
            return base;
        }
    }

    /**
     * Calcula los factores de forma de un equipo desde results.json.
     * Usa los últimos WINDOW partidos (combinando local y visitante).
     */
    public static FormFactors computeFormFactors(String team,
                                                 Path dataFile,
                                                 LocalDate today) throws IOException {
        List<MatchRecord> history = loadHistory(team, dataFile);
        if (history.isEmpty()) return new FormFactors(1.0, 1.0);

        // Ordenar por fecha desc, tomar ventana
        history.sort(Comparator.comparing(MatchRecord::date).reversed());
        List<MatchRecord> window = history.subList(0, Math.min(WINDOW, history.size()));

        double sumW = 0, sumAtk = 0, sumDef = 0;
        for (MatchRecord m : window) {
            long daysAgo = ChronoUnit.DAYS.between(m.date(), today);
            double w = Math.exp(-DECAY * Math.max(0, daysAgo));
            sumW   += w;
            sumAtk += m.goalsFor()     * w;
            sumDef += m.goalsAgainst() * w;
        }

        if (sumW == 0) return new FormFactors(1.0, 1.0);

        double avgAtk = sumAtk / sumW;
        double avgDef = sumDef / sumW;
        double atkFactor = avgAtk / BASE_GOALS;
        double defFactor = avgDef / BASE_GOALS;

        // Suavizar hacia 1.0 si hay pocos partidos (evitar extremos con <5 partidos)
        double smoothing = Math.min(1.0, window.size() / 5.0);
        atkFactor = 1.0 + (atkFactor - 1.0) * smoothing;
        defFactor = 1.0 + (defFactor - 1.0) * smoothing;

        return new FormFactors(atkFactor, defFactor);
    }

    // ── Parseo de results.json ────────────────────────────────────────────────

    private record MatchRecord(LocalDate date, int goalsFor, int goalsAgainst) {}

    private static List<MatchRecord> loadHistory(String team, Path dataFile) throws IOException {
        List<MatchRecord> result = new ArrayList<>();
        String slug = toSlug(team);

        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            JsonArray matches = root.getAsJsonArray("matches");

            for (JsonElement el : matches) {
                JsonObject m = el.getAsJsonObject();
                String hName  = getString(m, "homeName");
                String aName  = getString(m, "awayName");
                String hSlug  = getString(m, "homeSlug");
                String aSlug  = getString(m, "awaySlug");
                String dateStr = getString(m, "date");
                if (dateStr == null) continue;

                int hg = m.has("hg") ? m.get("hg").getAsInt() : -1;
                int ag = m.has("ag") ? m.get("ag").getAsInt() : -1;
                if (hg < 0 || ag < 0) continue;

                LocalDate date = LocalDate.parse(dateStr);

                boolean isHome = matches(team, slug, hName, hSlug);
                boolean isAway = !isHome && matches(team, slug, aName, aSlug);
                if (!isHome && !isAway) continue;

                result.add(isHome
                        ? new MatchRecord(date, hg, ag)
                        : new MatchRecord(date, ag, hg));
            }
        }
        return result;
    }

    private static boolean matches(String team, String slug, String name, String nameSlug) {
        if (name == null) return false;
        if (team.equalsIgnoreCase(name)) return true;
        if (nameSlug != null && nameSlug.equalsIgnoreCase(slug)) return true;
        // Matching flexible para variantes de nombre
        String teamLow = team.toLowerCase();
        String nameLow = name.toLowerCase();
        return teamLow.contains(nameLow) || nameLow.contains(teamLow);
    }

    private static String toSlug(String name) {
        return name.toLowerCase().trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : null;
    }
}