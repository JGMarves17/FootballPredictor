package com.josegabrielmarves.footballpredictor.api.datasource;

import com.google.gson.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Proveedor de clasificaciones en tiempo real del Mundial 2026.
 * Fuente: worldcup26.ir — gratuita, sin API key.
 *
 * Endpoints:
 *   GET /get/groups  → clasificación de grupos (puntos, goles, partidos)
 *   GET /get/games   → resultados de partidos
 */
public final class LiveStandingsProvider {

    private static final String BASE = "https://worldcup26.ir";
    private static final int TIMEOUT = 6_000;
    private static final Gson GSON = new Gson();

    private LiveStandingsProvider() {}

    // ── Clasificación de grupos ───────────────────────────────────────────────

    /**
     * Descarga la clasificación actual de todos los grupos.
     *
     * @return Map< "Group A", List<teamName ordenado 1°→4°> > o vacío si hay error
     */
    public static Map<String, List<String>> fetchGroupStandings() {
        try {
            String json = fetch(BASE + "/get/groups");
            if (json == null) return Map.of();
            return parseGroups(json);
        } catch (Exception e) {
            System.err.println("[LiveStandings] grupos: " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * Descarga los resultados de todos los partidos jugados.
     *
     * @return Map< "HomeTeam:AwayTeam", int[]{hg,ag} > para partidos finalizados
     */
    public static Map<String, int[]> fetchScores() {
        try {
            String json = fetch(BASE + "/get/games");
            if (json == null) return Map.of();
            return parseScores(json);
        } catch (Exception e) {
            System.err.println("[LiveStandings] partidos: " + e.getMessage());
            return Map.of();
        }
    }

    // ── Parsers flexibles ─────────────────────────────────────────────────────

    private static Map<String, List<String>> parseGroups(String json) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        JsonElement root = GSON.fromJson(json, JsonElement.class);

        // Intentar varios formatos comunes de la API
        JsonArray groups = null;
        if (root.isJsonArray()) {
            groups = root.getAsJsonArray();
        } else if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            // buscar "groups", "data", "group_standings", etc.
            for (String key : List.of("groups", "data", "group_standings", "standings")) {
                if (obj.has(key) && obj.get(key).isJsonArray()) {
                    groups = obj.getAsJsonArray(key);
                    break;
                }
            }
        }

        if (groups == null) {
            System.err.println("[LiveStandings] Formato de grupos no reconocido, json=" + json.substring(0, Math.min(200, json.length())));
            return result;
        }

        for (JsonElement ge : groups) {
            if (!ge.isJsonObject()) continue;
            JsonObject g = ge.getAsJsonObject();

            // Nombre del grupo
            String groupName = getString(g, "name", "group", "group_name", "title");
            if (groupName == null) continue;
            // Normalizar: "A" → "Group A", "Group A" → "Group A"
            if (!groupName.startsWith("Group")) groupName = "Group " + groupName.trim();

            // Lista de equipos dentro del grupo
            JsonArray teams = null;
            for (String key : List.of("teams", "standings", "table", "team_standings")) {
                if (g.has(key) && g.get(key).isJsonArray()) {
                    teams = g.getAsJsonArray(key);
                    break;
                }
            }
            if (teams == null) continue;

            // Construir lista de equipos ya ordenada por posición (la API los devuelve en orden)
            List<String> teamNames = new ArrayList<>();
            for (JsonElement te : teams) {
                if (!te.isJsonObject()) continue;
                JsonObject t = te.getAsJsonObject();
                String name = getString(t, "name", "team", "team_name", "country", "country_name");
                if (name != null && !name.isBlank()) teamNames.add(name.trim());
            }

            if (!teamNames.isEmpty()) {
                result.put(groupName, teamNames);
                System.out.printf("[LiveStandings] %s: %s%n", groupName, teamNames);
            }
        }
        return result;
    }

    private static Map<String, int[]> parseScores(String json) {
        Map<String, int[]> result = new LinkedHashMap<>();
        JsonElement root = GSON.fromJson(json, JsonElement.class);

        JsonArray games = null;
        if (root.isJsonArray()) {
            games = root.getAsJsonArray();
        } else if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            for (String key : List.of("games", "matches", "data", "results")) {
                if (obj.has(key) && obj.get(key).isJsonArray()) {
                    games = obj.getAsJsonArray(key);
                    break;
                }
            }
        }
        if (games == null) return result;

        for (JsonElement ge : games) {
            if (!ge.isJsonObject()) continue;
            JsonObject g = ge.getAsJsonObject();

            String home = getString(g, "home", "home_team", "team1", "home_name");
            String away = getString(g, "away", "away_team", "team2", "away_name");
            if (home == null || away == null) continue;

            // Intentar leer marcador
            int hg = -1, ag = -1;
            // Formato: score_home / score_away
            if (g.has("score_home") && g.has("score_away")) {
                try { hg = g.get("score_home").getAsInt(); ag = g.get("score_away").getAsInt(); } catch (Exception ignored) {}
            }
            // Formato: home_score / away_score
            if (hg < 0 && g.has("home_score") && g.has("away_score")) {
                try { hg = g.get("home_score").getAsInt(); ag = g.get("away_score").getAsInt(); } catch (Exception ignored) {}
            }
            // Formato: result como "2-1"
            if (hg < 0 && g.has("result")) {
                try {
                    String[] parts = g.get("result").getAsString().split("[-:]");
                    hg = Integer.parseInt(parts[0].trim());
                    ag = Integer.parseInt(parts[1].trim());
                } catch (Exception ignored) {}
            }
            // Formato: score como objeto {"home":2,"away":1}
            if (hg < 0 && g.has("score") && g.get("score").isJsonObject()) {
                JsonObject sc = g.getAsJsonObject("score");
                try {
                    hg = sc.has("home") ? sc.get("home").getAsInt() : sc.get("ft_home").getAsInt();
                    ag = sc.has("away") ? sc.get("away").getAsInt() : sc.get("ft_away").getAsInt();
                } catch (Exception ignored) {}
            }

            if (hg >= 0 && ag >= 0) {
                result.put(home.trim() + ":" + away.trim(), new int[]{hg, ag});
            }
        }
        return result;
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private static String fetch(String urlStr) throws Exception {
        // Trust-all SSL (solo desarrollo)
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }}, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);

        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT);
        conn.setReadTimeout(TIMEOUT);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "FootballPredictor/1.0");

        int code = conn.getResponseCode();
        if (code != 200) {
            System.err.println("[LiveStandings] HTTP " + code + " para " + urlStr);
            return null;
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String getString(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key)) {
                JsonElement el = obj.get(key);
                if (el.isJsonPrimitive()) return el.getAsString();
            }
        }
        return null;
    }
}