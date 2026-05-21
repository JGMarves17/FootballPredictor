package com.josegabrielmarves.footballpredictor.api;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class FootballDataClient {

    private static final String BASE_URL = "https://api.football-data.org/v4/";
    private static final String API_KEY = "TU_API_KEY_AQUI";

    public String getMatches() {

        try {
            URL url = new URL(BASE_URL + "matches");

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Auth-Token", API_KEY);

            int responseCode = conn.getResponseCode();

            if (responseCode != 200) {
                return "Error: " + responseCode;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream())
            );

            StringBuilder response = new StringBuilder();
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