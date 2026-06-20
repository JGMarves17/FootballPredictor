package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestEngine;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestMetrics;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

public final class PipelineRunner {

    private PipelineRunner() {}

    public static void main(String[] args) throws Exception {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        String dateStr = today + " " + now.withNano(0);

        System.out.println("=".repeat(60));
        System.out.println("  PIPELINE RUNNER — " + dateStr);
        System.out.println("=".repeat(60));

        // 1. Backtest honesto
        System.out.println("\n[1/5] Backtest honesto...");
        Path dataFile = Path.of("data/results.json");
        BacktestMetrics honest = BacktestEngine.run(dataFile, 150, false);
        System.out.printf("  Accuracy: %.1f%%  Brier: %.4f  LogLoss: %.4f  RPS: %.4f  (%d partidos)%n",
                honest.accuracy() * 100, honest.brier(), honest.logLoss(), honest.rps(), honest.matches());

        // 2. Cargar fixture y ratings
        System.out.println("\n[2/5] Inicializando modelo...");
        var matches = new com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider()
                .getWorldCupMatches(2026);
        var ratings = new java.util.HashMap<String,
                com.josegabrielmarves.footballpredictor.prediction.elo.EloRating>();
        for (var m : matches) {
            ratings.putIfAbsent(m.homeTeam,
                    com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings.getRating(m.homeTeam));
            ratings.putIfAbsent(m.awayTeam,
                    com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings.getRating(m.awayTeam));
        }

        // 3. Calibrar GLM con datos del torneo
        System.out.println("[3/5] Calibrando GLM...");
        var wcHistory = new java.util.ArrayList<com.josegabrielmarves.footballpredictor.prediction.TournamentGLM.MatchData>();
        String[][] j1results = {
                {"Mexico","South Africa","2","0"},{"South Korea","Czech Republic","2","1"},
                {"Canada","Bosnia & Herzegovina","1","1"},{"USA","Paraguay","4","1"},
                {"Qatar","Switzerland","1","1"},{"Brazil","Morocco","1","1"},
                {"Haiti","Scotland","0","1"},{"Australia","Turkey","2","0"},
                {"Germany","Curacao","7","1"},{"Ivory Coast","Ecuador","1","0"},
                {"Netherlands","Japan","2","2"},{"Sweden","Tunisia","5","1"},
                {"Spain","Cape Verde","0","0"},{"Belgium","Egypt","1","1"},
                {"Saudi Arabia","Uruguay","1","1"},{"Iran","New Zealand","2","2"},
                {"France","Senegal","3","1"},{"Iraq","Norway","1","4"},
                {"Argentina","Algeria","3","0"},{"Austria","Jordan","3","1"},
                {"Portugal","DR Congo","1","1"},{"Uzbekistan","Colombia","1","3"},
                {"England","Croatia","4","2"},{"Ghana","Panama","1","0"}
        };
        for (String[] r : j1results) {
            boolean isHost = r[0].equals("Mexico") || r[0].equals("USA") || r[0].equals("Canada");
            wcHistory.add(new com.josegabrielmarves.footballpredictor.prediction.TournamentGLM.MatchData(
                    r[0], r[1], Integer.parseInt(r[2]), Integer.parseInt(r[3]), isHost));
        }
        var glm = com.josegabrielmarves.footballpredictor.prediction.TournamentGLM.fit(wcHistory, ratings);
        com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor.setGLM(glm);
        System.out.printf("  GLM calibrado con %d partidos%n", glm.matchesUsed());

        // 4. Market comparison
        System.out.println("\n[4/5] Comparación modelo vs mercado (si hay odds)...");
        try {
            String apiKey = System.getenv("ODDS_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = "a1d46a53187e24d4f564000bb9319181";
            }
            var oddsProvider = new com.josegabrielmarves.footballpredictor.api.datasource.OddsProvider(apiKey);
            MarketComparator comparator = new MarketComparator(oddsProvider, 0.15);

            var matchday = java.util.List.of(
                    new MarketComparator.MatchInfo("Czech Republic", "Mexico", 0.0),
                    new MarketComparator.MatchInfo("South Africa", "South Korea", 0.0),
                    new MarketComparator.MatchInfo("Bosnia & Herzegovina", "Switzerland", 0.0),
                    new MarketComparator.MatchInfo("Qatar", "Canada", 0.0)
            );

            var rows = comparator.compareMatches(matchday, ratings);
            comparator.printComparison(rows);
        } catch (Exception e) {
            System.out.println("  (sin odds disponibles: " + e.getMessage() + ")");
        }

        // 5. Resumen
        System.out.println("\n[5/5] Resumen ejecutivo");
        System.out.println("-".repeat(60));
        System.out.printf("  Fecha:         %s%n", dateStr);
        System.out.printf("  Backtest acc:  %.1f%% (%d partidos)%n",
                honest.accuracy() * 100, honest.matches());
        System.out.printf("  GLM partidos:  %d%n", glm.matchesUsed());
        System.out.printf("  Equipos:       %d%n", ratings.size());
        System.out.printf("  Partidos WC:   %d%n", matches.size());
        System.out.println("-".repeat(60));
        System.out.println();
        System.out.println("  ⚠️  ENVIAR PREDICCIONES POR WHATSAPP ANTES DEL PRIMER PARTIDO");
        System.out.println("  ⚠️  No enviar = -3 pts + 10L por partido");
        System.out.println();
    }
}
