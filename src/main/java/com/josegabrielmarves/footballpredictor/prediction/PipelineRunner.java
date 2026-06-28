package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.messaging.WhatsAppMessenger;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestEngine;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestMetrics;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

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
        System.out.println("\n[1/7] Backtest honesto...");
        Path dataFile = Path.of("data/results.json");
        BacktestMetrics honest = BacktestEngine.run(dataFile, 150, false);
        System.out.printf("  Accuracy: %.1f%%  Brier: %.4f  LogLoss: %.4f  RPS: %.4f  (%d partidos)%n",
                honest.accuracy() * 100, honest.brier(), honest.logLoss(), honest.rps(), honest.matches());

        // 2. Cargar fixture y ratings
        System.out.println("\n[2/7] Inicializando modelo...");
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
        System.out.println("[3/7] Calibrando GLM...");
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

        // 4. LiveResultFetcher — busca resultados desde internet
        System.out.println("\n[4/7] LiveResultFetcher — buscando resultados en vivo...");
        LiveResultFetcher fetcher = new LiveResultFetcher(ratings, wcHistory);
        int nuevos = fetcher.fetchAndUpdate();
        if (nuevos > 0) {
            var newGlm = com.josegabrielmarves.footballpredictor.prediction.TournamentGLM.fit(wcHistory, ratings);
            com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor.setGLM(newGlm);
        }

        // 5. Market comparison
        System.out.println("\n[5/7] Comparación modelo vs mercado (si hay odds)...");
        try {
            String apiKey = System.getenv("ODDS_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                apiKey = EnsemblePredictor.DEFAULT_API_KEY;
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

        // 6. Predecir jornada
        System.out.println("\n[6/7] Generando predicciones...");
        int jornada = 3;
        var matchday = List.of(
                new MatchdayEngine.MatchInput("Czech Republic", "Mexico", com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage.GRUPOS),
                new MatchdayEngine.MatchInput("South Africa", "South Korea", com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage.GRUPOS),
                new MatchdayEngine.MatchInput("Bosnia & Herzegovina", "Switzerland", com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage.GRUPOS),
                new MatchdayEngine.MatchInput("Qatar", "Canada", com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage.GRUPOS)
        );
        MatchdayEngine.preMatchday(jornada, matchday, ratings, today);

        // 7. WhatsApp — genera mensaje y copia al portapapeles
        System.out.println("\n[7/7] Generando mensaje de WhatsApp...");
        String msg = WhatsAppMessenger.buildMessage(jornada, matchday,
                matchday.stream().map(m -> ratings.getOrDefault(m.team1(),
                        com.josegabrielmarves.footballpredictor.prediction.elo.EloRating.initial(m.team1()))).toList(),
                matchday.stream().map(m -> ratings.getOrDefault(m.team2(),
                        com.josegabrielmarves.footballpredictor.prediction.elo.EloRating.initial(m.team2()))).toList());
        WhatsAppMessenger.send(msg, WhatsAppMessenger.Mode.COPY);

        // Resumen
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  RESUMEN EJECUTIVO");
        System.out.println("=".repeat(60));
        System.out.printf("  Fecha:         %s%n", dateStr);
        System.out.printf("  Backtest acc:  %.1f%% (%d partidos)%n",
                honest.accuracy() * 100, honest.matches());
        System.out.printf("  GLM partidos:  %d%n", glm.matchesUsed());
        System.out.printf("  Resultados nuevos: %d%n", nuevos);
        System.out.printf("  Equipos:       %d%n", ratings.size());
        System.out.printf("  Jornada:       %d%n", jornada);
        System.out.println("-".repeat(60));
        System.out.println();
        System.out.println("  ⚠️  ENVIAR PREDICCIONES POR WHATSAPP ANTES DEL PRIMER PARTIDO");
        System.out.println("  ⚠️  No enviar = -3 pts + 10L por partido");
        System.out.println();
    }
}
