package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.MatchEV;

import java.nio.file.Path;
import java.util.*;

public final class QBacktest {

    public record QResult(
        String strategy,
        int totalPts, int exactHits, int resultHits,
        double avgRisk,
        Map<String, Integer> riskDistribution
    ) {}

    private QBacktest() {}

    public static Map<String, QResult> compareStrategies(Path dataFile) {
        List<BacktestPipeline.HistoricalMatch> matches = BacktestPipeline.load(dataFile);
        Map<String, QResult> results = new LinkedHashMap<>();

        int matchdaySize = 4;
        for (int md = 0; md + matchdaySize <= matches.size() && md < 50; md += matchdaySize) {
            List<BacktestPipeline.HistoricalMatch> jornada = matches.subList(md, md + matchdaySize);
            for (String strategy : List.of("SeguroSiempre", "DualPick", "Conservative", "Random")) {
                int pts = 0, exact = 0, resultHits = 0;
                for (var m : jornada) {
                    try {
                        EloRating home = CalibratedEloRatings.getRating(m.homeName());
                        EloRating away = CalibratedEloRatings.getRating(m.awayName());
                        if (home == null || away == null) continue;

                        var dual = MatchEV.dualPick(m.homeName(), home, m.awayName(), away, 0.0);
                        String pick = switch (strategy) {
                            case "SeguroSiempre" -> dual.seguro().toString();
                            case "DualPick" -> dual.exacto().toString();
                            case "Conservative" -> dual.seguro().toString();
                            case "Random" -> randomPick(dual.seguro().toString());
                            default -> dual.seguro().toString();
                        };

                        int hg = m.hg(), ag = m.ag();
                        String[] parts = pick.split("-");
                        int pH = Integer.parseInt(parts[0].trim());
                        int pA = Integer.parseInt(parts[1].trim());

                        boolean resultCorrect = (pH > pA && hg > ag) || (pH < pA && hg < ag) || (pH == pA && hg == ag);
                        boolean exactCorrect = pH == hg && pA == ag;

                        if (exactCorrect) { pts += 3; exact++; }
                        else if (resultCorrect) { pts += 1; resultHits++; }
                    } catch (Exception e) {
                        // skip problematic matches
                    }
                }
                var existing = results.get(strategy);
                if (existing == null) {
                    results.put(strategy, new QResult(strategy, pts, exact, resultHits, 0, Map.of()));
                } else {
                    results.put(strategy, new QResult(strategy,
                            existing.totalPts() + pts, existing.exactHits() + exact,
                            existing.resultHits() + resultHits, 0, Map.of()));
                }
            }
        }
        return results;
    }

    private static String randomPick(String base) {
        String[] parts = base.split("-");
        int h = Integer.parseInt(parts[0].trim());
        int a = Integer.parseInt(parts[1].trim());
        Random rng = new Random();
        h += rng.nextInt(3) - 1;
        a += rng.nextInt(3) - 1;
        h = Math.max(0, Math.min(5, h));
        a = Math.max(0, Math.min(5, a));
        return h + " - " + a;
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        Map<String, QResult> results = compareStrategies(dataFile);
        System.out.printf("%n=== QBacktest — Comparación de Estrategias ===%n");
        System.out.printf("%-20s %-10s %-10s %-10s%n", "Estrategia", "Puntos", "Exactos", "Resultados");
        for (var e : results.entrySet()) {
            QResult r = e.getValue();
            System.out.printf("%-20s %-10d %-10d %-10d%n",
                    r.strategy(), r.totalPts(), r.exactHits(), r.resultHits());
        }
    }
}
