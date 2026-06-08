package com.josegabrielmarves.footballpredictor.api;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Cliente HTTP para football-data.org v4.
 */
public class FootballDataClient {

    private static final String BASE_URL = "https://api.football-data.org/v4/";
    private static final String API_KEY  = "TU_API_KEY_AQUI"; // reemplazar cuando tengas la key

    public String getWorldCupMatches() {
        try {
            var url  = URI.create(BASE_URL + "competitions/WC/matches").toURL();
            var conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Auth-Token", API_KEY);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return "Error HTTP: " + responseCode;
            }

            var reader   = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            var response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            reader.close();
            return response.toString();

        } catch (Exception e) {
            return "Exception: " + e.getMessage();
        }
    }
}