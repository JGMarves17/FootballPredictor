package com.josegabrielmarves.footballpredictor.api.datasource;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.model.Score;

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
 * Proveedor principal — openfootball/worldcup.json
 * Sin API Key, sin rate limits. Fuente crítica del sistema.
 */
public class OpenFootballProvider implements DataProvider {

    private static final String BASE_URL =
            "https://raw.githubusercontent.com/openfootball/worldcup.json/master/%d/worldcup.json";

    private final Gson gson = new Gson();

    @Override
    public List<Match> getWorldCupMatches(int year) {
        List<Match> matches = new ArrayList<>();
        try {
            String json = fetchJson(String.format(BASE_URL, year));
            if (json == null) return matches;

            JsonObject root = gson.fromJson(json, JsonObject.class);
            JsonArray jsonMatches = root.getAsJsonArray("matches");

            int id = 1;
            for (JsonElement el : jsonMatches) {
                JsonObject m = el.getAsJsonObject();

                String team1 = getString(m, "team1");
                String team2 = getString(m, "team2");
                String date  = getString(m, "date");
                String round = getString(m, "round");

                Score score = null;
                String winner = null;

                if (m.has("score") && !m.get("score").isJsonNull()) {
                    JsonObject s = m.getAsJsonObject("score");
                    if (s.has("ft")) {
                        JsonArray ft = s.getAsJsonArray("ft");
                        int home = ft.get(0).getAsInt();
                        int away = ft.get(1).getAsInt();
                        score = new Score(home, away);
                        if (home > away)      winner = "HOME_TEAM";
                        else if (away > home) winner = "AWAY_TEAM";
                        else                  winner = "DRAW";
                    }
                }

                matches.add(new Match(id++, team1, team2, date, round, score, winner));
            }

        } catch (Exception e) {
            System.err.println("[OpenFootballProvider] Error: " + e.getMessage());
        }
        return matches;
    }

    @Override
    public boolean isAvailable() {
        try {
            return fetchJson(String.format(BASE_URL, 2026)) != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getName() {
        return "OpenFootball";
    }

    // TODO: remover trust-all en producción — solo para desarrollo local
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

    private String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull()
                ? obj.get(key).getAsString()
                : "Desconocido";
    }
}