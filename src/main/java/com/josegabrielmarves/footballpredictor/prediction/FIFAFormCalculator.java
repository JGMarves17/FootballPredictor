package com.josegabrielmarves.footballpredictor.prediction;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Calcula los factores de ataque y defensa de cada equipo basándose
 * en sus últimos 50 partidos ponderados por tres señales:
 *
 *   weight_i = importance_i × w_time_i × w_rival_i
 *
 * 1. importance: peso FIFA por tipo de competición (amistoso=15 … Final WC=60)
 * 2. w_time:     decaimiento exponencial e^(-0.003 × días_atrás)
 * 3. w_rival:    1 + max(0, 100 - rank_rival) / 100
 *
 * Luego:
 *   attack_factor  = Σ(gf_i × w_i) / Σ(w_i) / BASELINE_GOALS
 *   defense_factor = Σ(gc_i × w_i) / Σ(w_i) / BASELINE_GOALS
 */
public final class FIFAFormCalculator {

    private static final double BASELINE_GOALS = 1.35;
    private static final double TIME_LAMBDA    = 0.003;  // e^(-0.003×días), vida media ~231d
    private static final int    WINDOW         = 50;     // últimos N partidos

    private FIFAFormCalculator() {}

    // ── Resultado de la forma ─────────────────────────────────────────────────

    public record FormResult(
            double attackFactor,
            double defenseFactor,
            int matchesUsed,
            double avgImportance
    ) {
        public static FormResult neutral() {
            return new FormResult(1.0, 1.0, 0, 0.0);
        }

        /** λ de goles esperados anotados por este equipo. */
        public double lambdaAttack() { return BASELINE_GOALS * attackFactor; }

        /** λ de goles esperados recibidos por este equipo. */
        public double lambdaDefense() { return BASELINE_GOALS * defenseFactor; }

        @Override public String toString() {
            return String.format(
                    "atk=%.3f(λ=%.2f) def=%.3f(λ=%.2f) partidos=%d avgImp=%.1f",
                    attackFactor, lambdaAttack(),
                    defenseFactor, lambdaDefense(),
                    matchesUsed, avgImportance);
        }
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Calcula la forma reciente de un equipo desde results.json.
     *
     * @param team     nombre del equipo (coincide con homeName/awayName del JSON)
     * @param dataFile ruta a results.json
     * @param today    fecha de referencia
     * @return FormResult con factores de ataque y defensa
     */
    public static FormResult getForm(String team, Path dataFile, LocalDate today) {
        try {
            List<MatchRecord> history = loadHistory(team, dataFile);
            if (history.isEmpty()) return FormResult.neutral();

            // Ordenar por fecha desc, tomar ventana
            history.sort(Comparator.comparing(MatchRecord::date).reversed());
            List<MatchRecord> window = history.subList(0, Math.min(WINDOW, history.size()));

            double sumW = 0, sumAtk = 0, sumDef = 0, sumImp = 0;
            int used = 0;

            for (MatchRecord m : window) {
                long daysAgo = ChronoUnit.DAYS.between(m.date(), today);
                double wTime   = Math.exp(-TIME_LAMBDA * Math.max(0, daysAgo));
                double wRival  = rivalWeight(m.rivalRank());
                double wImport = m.importance();
                double w = wImport * wTime * wRival;

                sumW   += w;
                sumAtk += m.goalsFor()     * w;
                sumDef += m.goalsAgainst() * w;
                sumImp += wImport;
                used++;
            }

            if (sumW == 0) return FormResult.neutral();

            double rawAtk = (sumAtk / sumW) / BASELINE_GOALS;
            double rawDef = (sumDef / sumW) / BASELINE_GOALS;

            // Suavizar si hay pocos partidos significativos
            double smooth = Math.min(1.0, used / 5.0);
            double atkFactor = 1.0 + (rawAtk - 1.0) * smooth;
            double defFactor = 1.0 + (rawDef - 1.0) * smooth;

            return new FormResult(atkFactor, defFactor, used, sumImp / used);

        } catch (Exception e) {
            System.err.println("[FIFAForm] Error para " + team + ": " + e.getMessage());
            return FormResult.neutral();
        }
    }

    // ── Parsing de results.json ───────────────────────────────────────────────

    private record MatchRecord(
            LocalDate date, int goalsFor, int goalsAgainst,
            double importance, int rivalRank) {}

    private static List<MatchRecord> loadHistory(String team, Path dataFile)
            throws IOException {
        List<MatchRecord> result = new ArrayList<>();
        String slug = toSlug(team);

        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            JsonArray matches = root.getAsJsonArray("matches");

            for (JsonElement el : matches) {
                JsonObject m = el.getAsJsonObject();

                String hName  = str(m, "homeName");
                String aName  = str(m, "awayName");
                String hSlug  = str(m, "homeSlug");
                String aSlug  = str(m, "awaySlug");
                String dateStr = str(m, "date");
                String league  = str(m, "leagueName");

                if (dateStr == null) continue;
                int hg = m.has("hg") ? m.get("hg").getAsInt() : -1;
                int ag = m.has("ag") ? m.get("ag").getAsInt() : -1;
                if (hg < 0 || ag < 0) continue;

                LocalDate date = LocalDate.parse(dateStr);
                double imp = leagueImportance(league);

                boolean isHome = teamMatches(team, slug, hName, hSlug);
                boolean isAway = !isHome && teamMatches(team, slug, aName, aSlug);
                if (!isHome && !isAway) continue;

                // Rank del rival (sin datos reales de ranking, usamos proxy por liga)
                int rivalRank = isHome ? estimateRank(aName) : estimateRank(hName);

                result.add(isHome
                        ? new MatchRecord(date, hg, ag, imp, rivalRank)
                        : new MatchRecord(date, ag, hg, imp, rivalRank));
            }
        }
        return result;
    }

    // ── Importancia por liga (sistema FIFA) ───────────────────────────────────

    static double leagueImportance(String league) {
        if (league == null) return 15.0;
        String l = league.toLowerCase();

        // World Cup
        if (l.contains("world cup") || l.contains("mundial")) {
            if (l.contains("final") && !l.contains("semi") && !l.contains("quarter")) return 60.0;
            if (l.contains("semi"))     return 50.0;
            if (l.contains("quarter")) return 50.0;
            if (l.contains("round") || l.contains("group")) return 45.0;
            return 45.0;
        }
        // Qualification / Eliminatorias
        if (l.contains("qualification") || l.contains("qualifier") || l.contains("eliminatoria")) {
            if (l.contains("world cup") || l.contains("mundial")) return 35.0;
            return 25.0;
        }
        // Continental (EURO, Copa América, AFCON, etc.)
        if (l.contains("euro") || l.contains("european championship")
                || l.contains("copa america") || l.contains("africa cup")
                || l.contains("asian cup") || l.contains("gold cup")
                || l.contains("continental")) {
            if (l.contains("final"))   return 40.0;
            if (l.contains("semi"))    return 35.0;
            if (l.contains("quarter")) return 35.0;
            return 30.0;
        }
        // Nations League / Confederation
        if (l.contains("nations league") || l.contains("nations cup")
                || l.contains("concacaf nations") || l.contains("uefa nations")) {
            return 25.0;
        }
        // Friendly
        if (l.contains("friendly") || l.contains("amistoso")) return 15.0;

        // Default: amistoso
        return 15.0;
    }

    // ── Peso del rival ────────────────────────────────────────────────────────

    private static double rivalWeight(int rank) {
        return 1.0 + Math.max(0, 100 - rank) / 100.0;
    }

    /**
     * Estimación proxy del rank FIFA a partir del nombre del equipo.
     * Sin datos reales de ranking descargados, usamos Elo calibrado como proxy.
     * Rank estimado: los ~10 equipos top tienen rank < 20; el resto ~50-150.
     * Para equipos desconocidos: rank 100 (peso neutral).
     */
    private static int estimateRank(String team) {
        if (team == null) return 100;
        String t = team.toLowerCase();
        // Top 10 aproximados según ranking FIFA jun-2026
        if (t.contains("argentina") || t.contains("france") || t.contains("spain")
                || t.contains("england") || t.contains("brazil") || t.contains("belgium")
                || t.contains("portugal") || t.contains("netherlands") || t.contains("italy")
                || t.contains("germany"))    return 10;
        if (t.contains("uruguay") || t.contains("croatia") || t.contains("morocco")
                || t.contains("colombia") || t.contains("japan") || t.contains("usa")
                || t.contains("united states") || t.contains("mexico") || t.contains("denmark")
                || t.contains("austria") || t.contains("switzerland") || t.contains("senegal"))
            return 25;
        if (t.contains("australia") || t.contains("south korea") || t.contains("iran")
                || t.contains("egypt") || t.contains("nigeria") || t.contains("chile")
                || t.contains("ecuador") || t.contains("norway") || t.contains("sweden")
                || t.contains("canada") || t.contains("peru") || t.contains("turkey"))
            return 45;
        return 80; // equipos menos conocidos
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean teamMatches(String team, String slug, String name, String nameSlug) {
        if (name == null) return false;
        if (team.equalsIgnoreCase(name)) return true;
        if (nameSlug != null && nameSlug.equalsIgnoreCase(slug)) return true;
        String a = team.toLowerCase(), b = name.toLowerCase();
        return a.contains(b) || b.contains(a);
    }

    private static String toSlug(String name) {
        return name.toLowerCase().trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String str(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString() : null;
    }
}