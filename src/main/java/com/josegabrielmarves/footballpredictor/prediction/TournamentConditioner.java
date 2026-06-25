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
import java.util.*;

public final class TournamentConditioner {

    private static final double PRIOR = 3.0;
    private static final Path XG_DATA_FILE = Path.of("data/xg_wc2026.json");

    private final Map<String, List<double[]>> teamData = new HashMap<>();
    private static TournamentConditioner INSTANCE;

    private TournamentConditioner() {}

    public static synchronized TournamentConditioner getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new TournamentConditioner();
            INSTANCE.loadFromJson();
        }
        return INSTANCE;
    }

    public static synchronized void resetInstance() {
        INSTANCE = null;
    }

    private void loadFromJson() {
        if (!Files.exists(XG_DATA_FILE)) {
            System.err.println("[TournamentConditioner] No se encuentra " + XG_DATA_FILE);
            return;
        }
        try (Reader reader = Files.newBufferedReader(XG_DATA_FILE, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            JsonArray matches = root.getAsJsonArray("matches");
            for (JsonElement el : matches) {
                JsonArray m = el.getAsJsonArray();
                String home = m.get(0).getAsString();
                String away = m.get(1).getAsString();
                double hXG = m.get(2).getAsDouble();
                double aXG = m.get(3).getAsDouble();
                int hG = m.get(4).getAsInt();
                int aG = m.get(5).getAsInt();
                add(home, away, hXG, aXG, hG, aG);
            }
            System.out.printf("[TournamentConditioner] Cargados %d partidos desde %s%n",
                    matches.size(), XG_DATA_FILE.getFileName());
        } catch (IOException e) {
            System.err.println("[TournamentConditioner] Error cargando xG: " + e.getMessage());
        }
    }

    public static void reloadFromJson() {
        INSTANCE = new TournamentConditioner();
        INSTANCE.loadFromJson();
    }

    public void addMatch(String home, String away,
                         double hXG, double aXG, int hG, int aG) {
        add(home, away, hXG, aXG, hG, aG);
    }

    private void add(String home, String away,
                     double hXG, double aXG, int hG, int aG) {
        teamData.computeIfAbsent(normalize(home), k -> new ArrayList<>())
                .add(new double[]{hXG, hG, aXG, aG});
        teamData.computeIfAbsent(normalize(away), k -> new ArrayList<>())
                .add(new double[]{aXG, aG, hXG, hG});
    }

    public double attackAdjustment(String team) {
        return adjustment(team, 0, 1);
    }

    public double defenseAdjustment(String team) {
        return adjustment(team, 2, 3);
    }

    private double adjustment(String team, int xgIdx, int goalIdx) {
        List<double[]> data = teamData.get(normalize(team));
        if (data == null || data.isEmpty()) return 1.0;

        double sumXG = PRIOR * 1.35;
        double sumGoals = PRIOR * 1.35;
        for (double[] d : data) {
            sumXG += d[xgIdx];
            sumGoals += d[goalIdx];
        }
        return sumGoals / sumXG;
    }

    public double[] adjustLambdas(String homeTeam, double lambdaHome,
                                  String awayTeam, double lambdaAway) {
        double atkH = attackAdjustment(homeTeam);
        double defH = defenseAdjustment(homeTeam);
        double atkA = attackAdjustment(awayTeam);
        double defA = defenseAdjustment(awayTeam);

        double adjH = lambdaHome * atkH / Math.max(0.4, defA);
        double adjA = lambdaAway * atkA / Math.max(0.4, defH);

        double alpha = dataAlpha(homeTeam, awayTeam);
        return new double[]{
                lambdaHome * (1 - alpha) + adjH * alpha,
                lambdaAway * (1 - alpha) + adjA * alpha
        };
    }

    /** Peso del xG: crece con más partidos disponibles (0.35→0.75). */
    private double dataAlpha(String home, String away) {
        int minN = Math.min(
                teamData.getOrDefault(normalize(home), Collections.emptyList()).size(),
                teamData.getOrDefault(normalize(away), Collections.emptyList()).size()
        );
        return Math.min(0.75, 0.35 + minN * 0.05);
    }

    private String normalize(String t) {
        if (t == null) return "";
        return switch (t.toLowerCase().trim()) {
            case "usa", "united states" -> "usa";
            case "ivory coast", "c\u00f4te d'ivoire", "cote d'ivoire" -> "ivory coast";
            case "south korea", "republic of korea" -> "south korea";
            case "iran", "ir iran" -> "iran";
            case "t\u00fcrkiye", "turkey" -> "turkey";
            default -> t.toLowerCase().trim();
        };
    }

    public void printAdjustments() {
        System.out.println("\n\u2500\u2500 Ajustes WC 2026 (xG vs goles reales) \u2500\u2500");
        System.out.printf("%-25s %8s %8s%n", "Equipo", "Ataque", "Defensa");
        teamData.keySet().stream().sorted().forEach(team -> {
            double atk = attackAdjustment(team);
            double def = defenseAdjustment(team);
            String flag = (atk < 0.80 || atk > 1.30 || def < 0.70 || def > 1.40) ? " ***" : "";
            System.out.printf("%-25s %7.2fx %7.2fx%s%n", team, atk, def, flag);
        });
        System.out.println();
    }

}
