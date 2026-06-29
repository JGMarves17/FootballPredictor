package com.josegabrielmarves.footballpredictor.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
// NOTA: BracketApiClient es un cliente de datos puro.
// La conversión a MatchInput se hace en QuinielaRunnerR32.java
// para evitar que api → quiniela.

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *  Cliente autónomo del bracket de la Copa del Mundo 2026.
 *  <p>
 *  Lee el JSON público de openfootball/worldcup.json y extrae:
 *  <ul>
 *    <li>Los 16 partidos de R32 con equipos REALES</li>
 *    <li>Las rondas posteriores con placeholders (W73, L101, etc.)</li>
 *    <li>Resolución automática de placeholders según resultados reales</li>
 *  </ul>
 *  <p>
 *  Sin API key, sin rate limits, actualizado automáticamente por GitHub Actions.
 *  Ideal para auto-poblar {@code QuinielaRunnerR32.java} y el bracket visual.
 */
public class BracketApiClient {

    private static final String BASE_URL =
            "https://raw.githubusercontent.com/openfootball/worldcup.json/master/%d/worldcup.json";

    private static final String[] KNOCKOUT_ROUNDS = {
            "Round of 32", "Round of 16", "Quarter-final",
            "Semi-final", "Match for third place", "Final"
    };

    private final Gson gson = new Gson();
    private JsonObject cachedRoot;
    private String cachedJson;
    private int currentYear;

    /**
     * Información de un partido dentro del bracket (con número de partido FIFA).
     */
    public record BracketMatch(
            int matchNumber,
            String round,
            String date,
            String time,
            String ground,
            String team1,
            String team2,
            Integer homeGoals,
            Integer awayGoals,
            boolean isPlaceholder  // true si algún equipo es "W73", "L101", etc.
    ) {
        /** True si el partido ya se jugó (tiene marcador). */
        public boolean isPlayed() { return homeGoals != null && awayGoals != null; }

        /** Devuelve el ganador si ya se jugó, null si no. */
        public String winner() {
            if (!isPlayed()) return null;
            if (homeGoals > awayGoals) return team1;
            if (awayGoals > homeGoals) return team2;
            return "DRAW"; // no debería pasar en eliminatorias, pero cubre
        }
    }

    /** Constructor por defecto (año 2026). */
    public BracketApiClient() {
        this(2026);
    }

    public BracketApiClient(int year) {
        this.currentYear = year;
    }

    /**
     * Fuerza una recarga desde la red (sin caché).
     */
    public void refresh() throws Exception {
        String url = String.format(BASE_URL, currentYear);
        String json = fetchJson(url);
        this.cachedJson = json;
        this.cachedRoot = gson.fromJson(json, JsonObject.class);
    }

    /**
     * Obtiene TODOS los partidos de Ronda de 32 (16 partidos).
     * Los equipos ya están resueltos (no placeholders) porque el grupo terminó.
     */
    public List<BracketMatch> getRoundOf32() throws Exception {
        ensureLoaded();
        return getMatchesByRound("Round of 32");
    }

    /**
     * Obtiene TODOS los partidos de una ronda específica.
     */
    public List<BracketMatch> getMatchesByRound(String roundName) throws Exception {
        ensureLoaded();
        List<BracketMatch> result = new ArrayList<>();
        JsonArray matches = cachedRoot.getAsJsonArray("matches");
        for (JsonElement el : matches) {
            JsonObject m = el.getAsJsonObject();
            String round = getString(m, "round");
            if (roundName.equals(round)) {
                result.add(parseMatch(m));
            }
        }
        return result;
    }

    /**
     * Resuelve un placeholder como "W73" o "L101" mirando los partidos anteriores.
     * "W73" = ganador del partido 73, "L101" = perdedor del partido 101.
     */
    public String resolvePlaceholder(String placeholder) throws Exception {
        ensureLoaded();
        if (placeholder == null || placeholder.isEmpty()) return null;
        if (!placeholder.startsWith("W") && !placeholder.startsWith("L")) {
            return placeholder; // ya es un equipo real
        }

        boolean takeWinner = placeholder.startsWith("W");
        int matchNumber = Integer.parseInt(placeholder.substring(1));

        JsonArray matches = cachedRoot.getAsJsonArray("matches");
        for (JsonElement el : matches) {
            JsonObject m = el.getAsJsonObject();
            if (!m.has("num")) continue;
            int num = m.get("num").getAsInt();
            if (num == matchNumber) {
                BracketMatch bm = parseMatch(m);
                if (!bm.isPlayed()) return null; // todavía no se jugó
                return takeWinner ? bm.winner() : (bm.winner().equals("DRAW") ? bm.team1() : bm.winner());
            }
        }
        return null; // no encontrado
    }

    /**
     * Construye el bracket completo desde R32 hasta la Final,
     * resolviendo placeholders recursivamente hasta donde los resultados lo permitan.
     * @return Mapa: roundName → lista de BracketMatch con equipos resueltos
     */
    public Map<String, List<BracketMatch>> getFullBracket() throws Exception {
        ensureLoaded();
        Map<String, List<BracketMatch>> bracket = new HashMap<>();
        for (String round : KNOCKOUT_ROUNDS) {
            List<BracketMatch> matches = getMatchesByRound(round);
            // Resolver placeholders
            for (int i = 0; i < matches.size(); i++) {
                BracketMatch bm = matches.get(i);
                String t1 = bm.team1();
                String t2 = bm.team2();
                String resolvedT1 = resolvePlaceholder(t1);
                String resolvedT2 = resolvePlaceholder(t2);
                if (resolvedT1 != null) t1 = resolvedT1;
                if (resolvedT2 != null) t2 = resolvedT2;
                boolean stillPlaceholder = (resolvedT1 == null || resolvedT2 == null);
                matches.set(i, new BracketMatch(
                        bm.matchNumber(), bm.round(), bm.date(), bm.time(), bm.ground(),
                        t1, t2, bm.homeGoals(), bm.awayGoals(), stillPlaceholder
                ));
            }
            bracket.put(round, matches);
        }
        return bracket;
    }

    /**
     * Obtiene el partido de la FINAL con equipos resueltos (si es posible).
     */
    public BracketMatch getFinal() throws Exception {
        List<BracketMatch> finals = getMatchesByRound("Final");
        if (finals.isEmpty()) return null;
        BracketMatch f = finals.get(0);
        String t1 = resolvePlaceholder(f.team1());
        String t2 = resolvePlaceholder(f.team2());
        return new BracketMatch(
                f.matchNumber(), f.round(), f.date(), f.time(), f.ground(),
                t1 != null ? t1 : f.team1(),
                t2 != null ? t2 : f.team2(),
                f.homeGoals(), f.awayGoals(),
                t1 == null || t2 == null
        );
    }

    /**
     * Obtiene resultados RECIENTES (últimos N partidos con marcador).
     */
    public List<BracketMatch> getRecentResults(int limit) throws Exception {
        ensureLoaded();
        List<BracketMatch> all = new ArrayList<>();
        JsonArray matches = cachedRoot.getAsJsonArray("matches");
        for (JsonElement el : matches) {
            BracketMatch bm = parseMatch(el.getAsJsonObject());
            if (bm.isPlayed()) all.add(bm);
        }
        // Últimos N (los del final del array son más recientes)
        int start = Math.max(0, all.size() - limit);
        return all.subList(start, all.size());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void ensureLoaded() throws Exception {
        if (cachedRoot == null) refresh();
    }

    private BracketMatch parseMatch(JsonObject m) {
        int num = m.has("num") ? m.get("num").getAsInt() : 0;
        String round = getString(m, "round");
        String date = getString(m, "date");
        String time = getString(m, "time");
        String ground = getString(m, "ground");
        String team1 = getString(m, "team1");
        String team2 = getString(m, "team2");

        // Detectar placeholders
        boolean isPH = team1.matches("^[WL]\\d+$") || team2.matches("^[WL]\\d+$");

        Integer hg = null, ag = null;
        if (m.has("score") && !m.get("score").isJsonNull()) {
            JsonObject s = m.getAsJsonObject("score");
            if (s.has("ft")) {
                JsonArray ft = s.getAsJsonArray("ft");
                hg = ft.get(0).getAsInt();
                ag = ft.get(1).getAsInt();
            }
        }

        return new BracketMatch(num, round, date, time, ground, team1, team2, hg, ag, isPH);
    }

    private String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString()
                : "";
    }

    private String fetchJson(String urlStr) throws Exception {
        var url = URI.create(urlStr).toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "FootballPredictor/2.0");

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("HTTP " + conn.getResponseCode() + " al consultar " + urlStr);
        }

        var reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        var response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        return response.toString();
    }
}
