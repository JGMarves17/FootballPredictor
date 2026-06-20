package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.api.datasource.OddsProvider;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

import java.util.*;

public final class MarketComparator {

    private final OddsProvider oddsProvider;
    private final double ensembleAlpha;

    public MarketComparator(OddsProvider oddsProvider, double ensembleAlpha) {
        this.oddsProvider = oddsProvider;
        this.ensembleAlpha = ensembleAlpha;
    }

    public MarketComparator(OddsProvider oddsProvider) {
        this(oddsProvider, 0.15);
    }

    public record ComparisonRow(
            String homeTeam, String awayTeam,
            double modelHome, double modelDraw, double modelAway,
            double marketHome, double marketDraw, double marketAway,
            double ensembleHome, double ensembleDraw, double ensembleAway,
            boolean hasOdds
    ) {
        public String modelResult() { return resultLabel(modelHome, modelDraw, modelAway); }
        public String marketResult() { return resultLabel(marketHome, marketDraw, marketAway); }
        public String ensembleResult() { return resultLabel(ensembleHome, ensembleDraw, ensembleAway); }

        public double modelConfidence() { return max3(modelHome, modelDraw, modelAway); }
        public double marketConfidence() { return max3(marketHome, marketDraw, marketAway); }

        public String valueBet() {
            if (!hasOdds) return "-";
            double modelBest = modelConfidence();
            double marketBest = marketConfidence();
            double diff = modelBest - marketBest;
            if (diff > 0.08) return "VALUE";
            if (diff > 0.04) return "leve";
            if (diff < -0.08) return "MERCADO";
            if (diff < -0.04) return "leve";
            return "~";
        }

        public double bestScore() { return max3(ensembleHome, ensembleDraw, ensembleAway); }
    }

    public List<ComparisonRow> compareMatches(
            List<MatchInfo> matches, Map<String, EloRating> ratings) {
        List<ComparisonRow> rows = new ArrayList<>();
        for (MatchInfo m : matches) {
            EloRating home = ratings.getOrDefault(m.homeTeam(),
                    com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings.getRating(m.homeTeam()));
            EloRating away = ratings.getOrDefault(m.awayTeam(),
                    com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings.getRating(m.awayTeam()));

            PoissonPredictor.MatchProbabilities modelProbs =
                    PoissonPredictor.matchProbabilitiesTournament(m.homeTeam(), home, m.awayTeam(), away, m.homeBonus());

            double[] marketProbs = oddsProvider.getImpliedProbabilities(m.homeTeam(), m.awayTeam());
            boolean hasOdds = marketProbs != null;

            double eH, eD, eA;
            if (hasOdds) {
                eH = ensembleAlpha * modelProbs.homeWin() + (1 - ensembleAlpha) * marketProbs[0];
                eD = ensembleAlpha * modelProbs.draw() + (1 - ensembleAlpha) * marketProbs[1];
                eA = ensembleAlpha * modelProbs.awayWin() + (1 - ensembleAlpha) * marketProbs[2];
            } else {
                eH = modelProbs.homeWin();
                eD = modelProbs.draw();
                eA = modelProbs.awayWin();
            }

            double tot = eH + eD + eA;
            rows.add(new ComparisonRow(
                    m.homeTeam(), m.awayTeam(),
                    modelProbs.homeWin(), modelProbs.draw(), modelProbs.awayWin(),
                    hasOdds ? marketProbs[0] : 0, hasOdds ? marketProbs[1] : 0, hasOdds ? marketProbs[2] : 0,
                    eH / tot, eD / tot, eA / tot,
                    hasOdds
            ));
        }
        return rows;
    }

    public void printComparison(List<ComparisonRow> rows) {
        System.out.println();
        System.out.println("=".repeat(130));
        System.out.printf("  COMPARACIÓN MODELO vs MERCADO  (α=%.2f)  %n", ensembleAlpha);
        System.out.println("=".repeat(130));
        System.out.printf("%-22s %-22s  %-16s  %-16s  %-16s  %-10s%n",
                "Local", "Visitante", "Modelo", "Mercado", "Ensemble", "Value");
        System.out.println("-".repeat(130));

        for (ComparisonRow r : rows) {
            String modelStr = !r.hasOdds() ? String.format("%s (%.0f%%)", r.modelResult(), r.modelConfidence() * 100)
                    : String.format("%s (%.0f%%)", r.modelResult(), r.modelConfidence() * 100);
            String marketStr = r.hasOdds()
                    ? String.format("%s (%.0f%%)", r.marketResult(), r.marketConfidence() * 100)
                    : "N/A";
            String ensembleStr = String.format("%s (%.0f%%)", r.ensembleResult(), r.bestScore() * 100);

            System.out.printf("%-22s %-22s  %-16s  %-16s  %-16s  %-10s%n",
                    r.homeTeam(), r.awayTeam(), modelStr, marketStr, ensembleStr, r.valueBet());
        }
        System.out.println("=".repeat(130));
    }

    public void printDetailed(List<ComparisonRow> rows) {
        for (ComparisonRow r : rows) {
            System.out.println();
            System.out.printf("  %s vs %s%n", r.homeTeam(), r.awayTeam());
            System.out.printf("  Modelo:   1=%.1f%%  X=%.1f%%  2=%.1f%%  → %s%n",
                    r.modelHome() * 100, r.modelDraw() * 100, r.modelAway() * 100,
                    r.modelResult());
            if (r.hasOdds()) {
                System.out.printf("  Mercado:  1=%.1f%%  X=%.1f%%  2=%.1f%%  → %s%n",
                        r.marketHome() * 100, r.marketDraw() * 100, r.marketAway() * 100,
                        r.marketResult());
                System.out.printf("  Dif:      Δ1=%+.1f%%  ΔX=%+.1f%%  Δ2=%+.1f%%%n",
                        (r.modelHome() - r.marketHome()) * 100,
                        (r.modelDraw() - r.marketDraw()) * 100,
                        (r.modelAway() - r.marketAway()) * 100);
            }
            System.out.printf("  Ensemble(α=%.2f):  1=%.1f%%  X=%.1f%%  2=%.1f%%  → %s  [%s]%n",
                    ensembleAlpha,
                    r.ensembleHome() * 100, r.ensembleDraw() * 100, r.ensembleAway() * 100,
                    r.ensembleResult(), r.valueBet());
        }
    }

    public record MatchInfo(String homeTeam, String awayTeam, double homeBonus) {}

    static String resultLabel(double pH, double pD, double pA) {
        double best = max3(pH, pD, pA);
        if (best == pH) return "1";
        if (best == pD) return "X";
        return "2";
    }

    static double max3(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }
}
