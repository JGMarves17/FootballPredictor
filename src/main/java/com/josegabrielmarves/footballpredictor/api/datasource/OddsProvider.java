package com.josegabrielmarves.footballpredictor.api.datasource;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Proveedor de odds del mercado para el Mundial 2026.
 * Fuente: The Odds API (the-odds-api.com) — plan FREE, 500 créditos/mes.
 * Cada llamada consume 1 crédito.
 *
 * Endpoint: GET /v4/sports/soccer_fifa_world_cup/odds/
 * Formato: decimal odds EU, mercado h2h (1X2).
 *
 * Las odds se convierten a probabilidades implícitas normalizadas:
 *   P(home) = (1/oddHome) / (1/oddHome + 1/oddDraw + 1/oddAway)
 */
public final class OddsProvider {

    private static final String ENDPOINT =
            "https://api.the-odds-api.com/v4/sports/soccer_fifa_world_cup/odds/" +
                    "?regions=eu&markets=h2h&oddsFormat=decimal&apiKey=";

    private final String apiKey;
    private final Gson gson = new Gson();

    public OddsProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Probabilidades implícitas de mercado para un partido (1X2 normalizadas).
     *
     * @param homeTeam nombre del equipo local (en inglés, como en el fixture)
     * @param awayTeam nombre del visitante
     * @return probabilidades [homeWin, draw, awayWin] o null si no se encuentran odds
     */
    public double[] getImpliedProbabilities(String homeTeam, String awayTeam) {
        try {
            String json = fetchJson(ENDPOINT + apiKey);
            if (json == null) return null;

            JsonArray games = gson.fromJson(json, JsonArray.class);
            for (JsonElement el : games) {
                JsonObject game = el.getAsJsonObject();
                String home = game.get("home_team").getAsString();
                String away = game.get("away_team").getAsString();

                if (!matchesTeam(home, homeTeam) || !matchesTeam(away, awayTeam)) continue;

                JsonArray bookmakers = game.getAsJsonArray("bookmakers");
                if (bookmakers == null || bookmakers.isEmpty()) return null;

                // Usar el primer bookmaker disponible (todos son similares)
                JsonObject bk = bookmakers.get(0).getAsJsonObject();
                JsonArray markets = bk.getAsJsonArray("markets");

                for (JsonElement mkt : markets) {
                    JsonObject market = mkt.getAsJsonObject();
                    if (!"h2h".equals(market.get("key").getAsString())) continue;

                    JsonArray outcomes = market.getAsJsonArray("outcomes");
                    double oddHome = 0, oddDraw = 0, oddAway = 0;

                    for (JsonElement out : outcomes) {
                        JsonObject o = out.getAsJsonObject();
                        String name = o.get("name").getAsString();
                        double price = o.get("price").getAsDouble();
                        if (matchesTeam(name, homeTeam))      oddHome = price;
                        else if ("Draw".equals(name))          oddDraw = price;
                        else if (matchesTeam(name, awayTeam)) oddAway = price;
                    }

                    if (oddHome == 0 || oddDraw == 0 || oddAway == 0) return null;

                    // Convertir a probabilidades implícitas y normalizar
                    double pH = 1.0 / oddHome;
                    double pD = 1.0 / oddDraw;
                    double pA = 1.0 / oddAway;
                    double total = pH + pD + pA;
                    return new double[]{pH / total, pD / total, pA / total};
                }
            }
        } catch (Exception e) {
            System.err.println("[OddsProvider] Error: " + e.getMessage());
        }
        return null;
    }

    /** Obtiene todos los partidos con odds disponibles. */
    public List<OddsMatch> getAllMatches() {
        List<OddsMatch> result = new ArrayList<>();
        try {
            String json = fetchJson(ENDPOINT + apiKey);
            if (json == null) return result;

            JsonArray games = gson.fromJson(json, JsonArray.class);
            for (JsonElement el : games) {
                JsonObject game = el.getAsJsonObject();
                String home = game.get("home_team").getAsString();
                String away = game.get("away_team").getAsString();
                String time = game.get("commence_time").getAsString();

                double[] probs = getImpliedProbabilities(home, away);
                if (probs != null) {
                    result.add(new OddsMatch(home, away, time, probs[0], probs[1], probs[2]));
                }
            }
        } catch (Exception e) {
            System.err.println("[OddsProvider] Error: " + e.getMessage());
        }
        return result;
    }

    /** Partido con probabilidades implícitas del mercado. */
    public record OddsMatch(
            String homeTeam, String awayTeam, String commenceTime,
            double pHome, double pDraw, double pAway) {}

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Comparación flexible de nombres (ignora mayúsculas y acentos menores). */
    private boolean matchesTeam(String a, String b) {
        return a.equalsIgnoreCase(b)
                || a.toLowerCase().contains(b.toLowerCase())
                || b.toLowerCase().contains(a.toLowerCase());
    }

    private String fetchJson(String urlStr) throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] c, String a) {}
                    public void checkServerTrusted(X509Certificate[] c, String a) {}
                }
        };
        SSLContext sc = SSLContext.getInstance("SSL");
        sc.init(null, trustAll, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);

        var url  = URI.create(urlStr).toURL();
        var conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (conn.getResponseCode() != 200) return null;

        var reader   = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        var response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        return response.toString();
    }
}