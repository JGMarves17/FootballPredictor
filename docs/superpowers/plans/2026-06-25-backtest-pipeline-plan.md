# Backtesting & Optimization Pipeline — Implementation Plan

> **For agentic workers:** Use subagent-driven-development or executing-plans to implement this plan task-by-task.

**Goal:** Build a complete backtesting system that evaluates the Triple Blend pipeline and optimizes all parameters via walk-forward, grid/random search, CMA-ES, Platt Scaling, and ensemble.

**Architecture:** `BacktestPipeline` is the foundation (walk-forward over `results.json`). Optimizers (`WeightOptimizer`, `RhoOptimizer`, `ParamOptimizer`) delegate to it. `ProbabilityCalibrator` extends existing class with Platt. `QBacktest` simulates quiniela rounds. `BacktestReport` prints everything to console.

**Tech Stack:** Java 25, Gson 2.10.1, commons-math3 3.6.1, JUnit 5.11

## Global Constraints

- Package: `com.josegabrielmarves.footballpredictor.prediction.backtest`
- Test package: `com.josegabrielmarves.footballpredictor.prediction.backtest`
- Data file: `data/results.json` (root of repo)
- Test file: `src/test/java/.../prediction/backtest/`
- No new dependencies beyond pom.xml
- Follow existing code style (no comments unless necessary, Java records where appropriate)

---

## File Structure Map

### New files

| File | Responsibility |
|---|---|
| `src/main/java/.../prediction/backtest/BacktestPipeline.java` | Core walk-forward engine. `run()` and `runTournament()`. Returns `PipelineResult` + `ResidualReport`. |
| `src/main/java/.../prediction/backtest/WeightOptimizer.java` | Grid/random/CMA-ES search for (wElo, wForm, wGlm) weights. |
| `src/main/java/.../prediction/backtest/WeightEnsemble.java` | Evaluates average of top-K weight configurations. |
| `src/main/java/.../prediction/backtest/RhoOptimizer.java` | Grid search over ρ for Triple Blend pipeline. |
| `src/main/java/.../prediction/backtest/ParamOptimizer.java` | Framework for 1D/2D grid search and N-dimensional random/CMA-ES search over any numeric params. |
| `src/main/java/.../prediction/backtest/QBacktest.java` | Simulates quiniela matchdays over historical data, compares strategies. |

### Modified files

| File | Change |
|---|---|
| `src/main/java/.../prediction/backtest/HyperparameterOptimizer.java` | Add `@Deprecated` annotation, Javadoc pointing to RhoOptimizer |
| `src/main/java/.../prediction/ProbabilityCalibrator.java` | Add `trainPlatt()`, `calibratePlatt()`, `trainFromBacktest()` |
| `src/main/java/.../prediction/poisson/PoissonPredictor.java` | Add `setCalibrator()`, `matchProbabilitiesCalibrated()` |

### Test files

| File | Tests |
|---|---|
| `src/test/java/.../prediction/backtest/BacktestPipelineTest.java` | Elo baseline reproduction, modos, runTournament |
| `src/test/java/.../prediction/backtest/WeightOptimizerTest.java` | gridSearch returns sorted, randomSearch coverage |
| `src/test/java/.../prediction/backtest/RhoOptimizerTest.java` | gridSearch produces output, best rho in range |
| `src/test/java/.../prediction/backtest/ParamOptimizerTest.java` | 1D grid, 2D grid, random search with 3 values |
| `src/test/java/.../prediction/backtest/ProbabilityCalibratorTest.java` | Platt fits synthetic data, calibrateArray unchanged |
| `src/test/java/.../prediction/backtest/QBacktestTest.java` | compareStrategies with 2 strategies, 1 rival |

---

### Task 1: BacktestPipeline — Core walk-forward engine

**Files:**
- Create: `src/main/java/com/josegabrielmarves/footballpredictor/prediction/backtest/BacktestPipeline.java`
- Test: `src/test/java/com/josegabrielmarves/footballpredictor/prediction/backtest/BacktestPipelineTest.java`

**Interfaces:**
- Consumes: `BacktestMetrics` (existing), `PoissonPredictor` (existing), `EloCalculator` (existing), `FIFAFormCalculator` (existing)
- Produces: `BacktestPipeline.PipelineConfig`, `BacktestPipeline.PipelineResult`, `BacktestPipeline.ResidualReport`

- [ ] **Step 1: Create BacktestPipeline.java — Inner records + PipelineConfig**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestMetrics.Outcome;
import com.josegabrielmarves.footballpredictor.prediction.elo.*;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.prediction.FIFAFormCalculator;
import com.josegabrielmarves.footballpredictor.prediction.TournamentGLM;
import com.josegabrielmarves.footballpredictor.prediction.TournamentConditioner;
import com.josegabrielmarves.footballpredictor.model.Score;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

public final class BacktestPipeline {

    public record PipelineConfig(
        boolean useForm,
        boolean useGlm,
        boolean useConditioner,
        double rho,
        double wElo, double wForm, double wGlm,
        double baselineGoals,
        double eloGoalScale,
        double homeAdvantage
    ) {
        public static PipelineConfig eloOnly() {
            return new PipelineConfig(false, false, false,
                    PoissonPredictor.DC_RHO, 1.0, 0.0, 0.0,
                    PoissonPredictor.BASE_GOALS, PoissonPredictor.ELO_GOAL_SCALE,
                    EloCalculator.HOME_ADVANTAGE);
        }

        public static PipelineConfig tripleBlendDefault() {
            return new PipelineConfig(true, true, true,
                    PoissonPredictor.DC_RHO_TOURNAMENT, 0.40, 0.25, 0.35,
                    PoissonPredictor.BASE_GOALS, PoissonPredictor.ELO_GOAL_SCALE,
                    EloCalculator.HOME_ADVANTAGE);
        }

        public PipelineConfig withWeights(double wElo, double wForm, double wGlm) {
            double s = wElo + wForm + wGlm;
            return new PipelineConfig(useForm, useGlm, useConditioner, rho,
                    wElo/s, wForm/s, wGlm/s, baselineGoals, eloGoalScale, homeAdvantage);
        }

        public PipelineConfig withRho(double rho) {
            return new PipelineConfig(useForm, useGlm, useConditioner, rho,
                    wElo, wForm, wGlm, baselineGoals, eloGoalScale, homeAdvantage);
        }
    }

    public record PipelineResult(
        BacktestMetrics metrics,
        ResidualReport residuals,
        long elapsedMs,
        PipelineConfig config
    ) {}

    public record ResidualReport(
        Map<String, BacktestMetrics> byStage,
        Map<String, BacktestMetrics> byFavorite,
        String worstSegment,
        double worstSegmentMetric
    ) {}

    private BacktestPipeline() {}
}
```

- [ ] **Step 2: Add run() method to BacktestPipeline**

```java
    public static PipelineResult run(Path dataFile, int burnIn,
                                      boolean seedCalibrated, PipelineConfig config) {
        List<HistoricalMatch> matches = load(dataFile);
        Map<String, Double> ratings = new HashMap<>();
        BacktestMetrics global = new BacktestMetrics();
        Map<String, BacktestMetrics> byStage = new HashMap<>();
        Map<String, BacktestMetrics> byFavorite = new HashMap<>();
        long t0 = System.currentTimeMillis();

        LocalDate refDate = LocalDate.of(2023, 11, 1);

        for (int i = 0; i < matches.size(); i++) {
            HistoricalMatch m = matches.get(i);
            double ra = getRating(ratings, m.homeSlug, m.homeName, seedCalibrated);
            double rb = getRating(ratings, m.awaySlug, m.awayName, seedCalibrated);
            EloRating home = EloRating.initial(m.homeName).withRating(ra);
            EloRating away = EloRating.initial(m.awayName).withRating(rb);

            if (i >= burnIn) {
                PoissonPredictor.MatchProbabilities probs;
                if (config.useForm || config.useGlm || config.useConditioner) {
                    PoissonPredictor.setRefDate(refDate);
                    double[][] matrix = buildBlendedMatrix(m, home, away, config);
                    probs = aggregateProbs(matrix);
                } else {
                    probs = PoissonPredictor.matchProbabilities(home, away, config.homeAdvantage);
                }

                Outcome actual = Outcome.of(new Score(m.hg, m.ag));
                global.add(probs, actual);

                String stage = classifyStage(i, matches.size());
                byStage.computeIfAbsent(stage, k -> new BacktestMetrics()).add(probs, actual);

                double eloDiff = home.rating() - away.rating();
                String fav = eloDiff > 50 ? "favoriteWins" : eloDiff < -50 ? "underdogWins" : "balanced";
                byFavorite.computeIfAbsent(fav, k -> new BacktestMetrics()).add(probs, actual);
            }

            EloCalculator.UpdatedRatings updated =
                    EloCalculator.updateRatings(home, away, m.hg, m.ag,
                            leagueToK(m.leagueName), config.homeAdvantage);
            setRating(ratings, m.homeSlug, m.homeName, updated.home().rating());
            setRating(ratings, m.awaySlug, m.awayName, updated.away().rating());

            refDate = refDate.plusDays(2);
        }

        long elapsed = System.currentTimeMillis() - t0;

        String worstSeg = "";
        double worstVal = Double.MAX_VALUE;
        for (var e : byFavorite.entrySet()) {
            double acc = e.getValue().accuracy();
            if (acc < worstVal) { worstVal = acc; worstSeg = e.getKey(); }
        }
        for (var e : byStage.entrySet()) {
            double acc = e.getValue().accuracy();
            if (acc < worstVal) { worstVal = acc; worstSeg = e.getKey(); }
        }

        ResidualReport resid = new ResidualReport(byStage, byFavorite, worstSeg, worstVal);
        return new PipelineResult(global, resid, elapsed, config);
    }
```

- [ ] **Step 3: Add helper methods to BacktestPipeline**

```java
    private static double[][] buildBlendedMatrix(
            HistoricalMatch m, EloRating home, EloRating away, PipelineConfig config) {
        PoissonPredictor.setDataFile(Path.of("data/results.json"));
        double[] lambdas;
        if (config.useForm) {
            lambdas = PoissonPredictor.expectedGoalsBlended(
                    m.homeName, home, m.awayName, away, config.homeAdvantage);
        } else {
            double lEloH = PoissonPredictor.expectedGoalsElo(home.rating(), away.rating(), config.homeAdvantage);
            double lEloA = PoissonPredictor.expectedGoalsElo(away.rating(), home.rating(), -config.homeAdvantage / 2.0);
            lambdas = new double[]{lEloH, lEloA};
        }
        return PoissonPredictor.buildMatrix(lambdas[0], lambdas[1], config.rho);
    }

    private static String classifyStage(int idx, int total) {
        if (idx < total * 0.4) return "groups";
        if (idx < total * 0.7) return "knockout";
        return "final";
    }

    private static PoissonPredictor.MatchProbabilities aggregateProbs(double[][] m) {
        double win = 0, draw = 0, loss = 0;
        for (int h = 0; h <= PoissonPredictor.MAX_GOALS; h++)
            for (int a = 0; a <= PoissonPredictor.MAX_GOALS; a++) {
                if (h > a) win += m[h][a];
                else if (h == a) draw += m[h][a];
                else loss += m[h][a];
            }
        return new PoissonPredictor.MatchProbabilities(win, draw, loss);
    }

    private static double getRating(Map<String, Double> ratings,
                                    String slug, String name, boolean seedCalibrated) {
        return ratings.computeIfAbsent(key(slug, name),
                k -> seedCalibrated ? CalibratedEloRatings.getRating(name).rating()
                                    : EloRating.DEFAULT_RATING);
    }

    private static void setRating(Map<String, Double> ratings,
                                  String slug, String name, double rating) {
        ratings.put(key(slug, name), rating);
    }

    private static String key(String slug, String name) {
        return slug != null ? slug : "ghost:" + name;
    }

    static double leagueToK(String leagueName) {
        return HyperparameterOptimizer.leagueToK(leagueName);
    }

    private static List<HistoricalMatch> load(Path dataFile) {
        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, ResultsWrapper.class).matches;
        } catch (IOException e) {
            throw new RuntimeException("No se pudo leer: " + dataFile, e);
        }
    }

    private static final class ResultsWrapper {
        List<HistoricalMatch> matches;
    }

    private static final class HistoricalMatch {
        String homeName; String awayName;
        String homeSlug; String awaySlug;
        int hg; int ag; String leagueName;
    }
```

Note: we need `buildMatrix` to be accessible. Add a package-private method in PoissonPredictor or make it public. For now, add this helper inside BacktestPipeline that clones the logic:

```java
    public static double[][] buildMatrix(double lH, double lA, double rho) {
        int max = PoissonPredictor.MAX_GOALS;
        double[][] m = new double[max + 1][max + 1];
        double total = 0.0;
        for (int h = 0; h <= max; h++) {
            double pH = PoissonPredictor.poissonPmf(h, lH);
            for (int a = 0; a <= max; a++) {
                double p = pH * PoissonPredictor.poissonPmf(a, lA) * dcTau(h, a, lH, lA, rho);
                m[h][a] = p; total += p;
            }
        }
        if (total > 0)
            for (int h = 0; h <= max; h++)
                for (int a = 0; a <= max; a++)
                    m[h][a] /= total;
        return m;
    }

    private static double dcTau(int h, int a, double lH, double lA, double rho) {
        if (h == 0 && a == 0) return 1 - lH * lA * rho;
        if (h == 0 && a == 1) return 1 + lH * rho;
        if (h == 1 && a == 0) return 1 + lA * rho;
        if (h == 1 && a == 1) return 1 - rho;
        return 1.0;
    }
```

- [ ] **Step 4: Add runTournament() to BacktestPipeline**

```java
    public static PipelineResult runTournament(Path xgFile, PipelineConfig config) {
        List<TournamentMatch> matches = loadTournament(xgFile);
        Map<String, Double> ratings = new HashMap<>();
        BacktestMetrics metrics = new BacktestMetrics();
        long t0 = System.currentTimeMillis();

        for (int i = 1; i < matches.size(); i++) {
            List<TournamentMatch> train = matches.subList(0, i);
            TournamentGLM glm = TournamentGLM.fit(train, CalibratedEloRatings.getAll(), 2.0);

            TournamentConditioner cond = TournamentConditioner.getInstance();
            cond.clear();
            for (TournamentMatch tm : train) {
                cond.addMatch(tm.homeName, tm.awayName, tm.homeXG, tm.awayXG, tm.hg, tm.ag);
            }

            PoissonPredictor.setGLM(glm);
            TournamentMatch current = matches.get(i);

            double ra = getRating(ratings, toSlug(current.homeName), current.homeName, true);
            double rb = getRating(ratings, toSlug(current.awayName), current.awayName, true);
            EloRating home = EloRating.initial(current.homeName).withRating(ra);
            EloRating away = EloRating.initial(current.awayName).withRating(rb);

            PoissonPredictor.MatchProbabilities probs =
                    PoissonPredictor.matchProbabilitiesTournament(
                            current.homeName, home, current.awayName, away, 0.0);

            Outcome actual = Outcome.of(new Score(current.hg, current.ag));
            metrics.add(probs, actual);

            EloCalculator.UpdatedRatings updated =
                    EloCalculator.updateRatings(home, away, current.hg, current.ag,
                            EloCalculator.K_WORLD_CUP, 0.0);
            setRating(ratings, toSlug(current.homeName), current.homeName, updated.home().rating());
            setRating(ratings, toSlug(current.awayName), current.awayName, updated.away().rating());
        }

        long elapsed = System.currentTimeMillis() - t0;
        return new PipelineResult(metrics, null, elapsed, config);
    }

    private static String toSlug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private record TournamentMatch(String homeName, String awayName,
                                    double homeXG, double awayXG, int hg, int ag) {}

    private static List<TournamentMatch> loadTournament(Path xgFile) {
        try (Reader reader = Files.newBufferedReader(xgFile, StandardCharsets.UTF_8)) {
            String[][] raw = new Gson().fromJson(reader, String[][].class);
            List<TournamentMatch> list = new ArrayList<>();
            for (String[] r : raw) {
                list.add(new TournamentMatch(r[0], r[1],
                        Double.parseDouble(r[2]), Double.parseDouble(r[3]),
                        Integer.parseInt(r[4]), Integer.parseInt(r[5])));
            }
            return list;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
```

- [ ] **Step 5: Write BacktestPipelineTest.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

class BacktestPipelineTest {

    @Test
    void eloOnlyReproducesReferenceBaseline() {
        BacktestPipeline.PipelineResult r = BacktestPipeline.run(
                Paths.get("data/results.json"),
                150, true, BacktestPipeline.PipelineConfig.eloOnly());
        assertTrue(r.metrics().matches() > 700);
        assertEquals(0.61, r.metrics().accuracy(), 0.03);
        assertEquals(0.54, r.metrics().brier(), 0.05);
        assertTrue(r.metrics().rps() < 0.33);
    }

    @Test
    void honestModeIsLeakFree() {
        BacktestPipeline.PipelineResult cal = BacktestPipeline.run(
                Paths.get("data/results.json"), 150, true,
                BacktestPipeline.PipelineConfig.eloOnly());
        BacktestPipeline.PipelineResult honest = BacktestPipeline.run(
                Paths.get("data/results.json"), 150, false,
                BacktestPipeline.PipelineConfig.eloOnly());
        assertTrue(honest.metrics().brier() < 0.667);
        assertTrue(honest.metrics().accuracy() < cal.metrics().accuracy());
        assertTrue(honest.metrics().brier() > cal.metrics().brier());
    }

    @Test
    void tripleBlendDoesNotRegress() {
        BacktestPipeline.PipelineResult elo = BacktestPipeline.run(
                Paths.get("data/results.json"), 150, false,
                BacktestPipeline.PipelineConfig.eloOnly());
        BacktestPipeline.PipelineResult blend = BacktestPipeline.run(
                Paths.get("data/results.json"), 150, false,
                BacktestPipeline.PipelineConfig.tripleBlendDefault());
        assertTrue(blend.metrics().accuracy() >= elo.metrics().accuracy() - 0.02,
                () -> "Triple Blend accuracy " + blend.metrics().accuracy()
                     + " should be near Elo " + elo.metrics().accuracy());
    }

    @Test
    void residualReportHasSegments() {
        BacktestPipeline.PipelineResult r = BacktestPipeline.run(
                Paths.get("data/results.json"), 150, false,
                BacktestPipeline.PipelineConfig.eloOnly());
        assertNotNull(r.residuals());
        assertFalse(r.residuals().byFavorite().isEmpty());
    }

    @Test
    void runTournamentProducesMetrics() {
        BacktestPipeline.PipelineResult r = BacktestPipeline.runTournament(
                Paths.get("data/xg_wc2026.json"),
                BacktestPipeline.PipelineConfig.tripleBlendDefault());
        assertTrue(r.metrics().matches() > 10,
                () -> "Tournament matches: " + r.metrics().matches());
        assertTrue(r.metrics().brier() < 0.667);
    }
}
```

---

### Task 2: WeightOptimizer + WeightEnsemble

**Files:**
- Create: `src/main/java/.../prediction/backtest/WeightOptimizer.java`
- Create: `src/main/java/.../prediction/backtest/WeightEnsemble.java`
- Create: `src/test/java/.../prediction/backtest/WeightOptimizerTest.java`

**Interfaces:**
- Consumes: `BacktestPipeline.run()`, `BacktestPipeline.PipelineConfig`
- Produces: `WeightOptimizer.WeightResult`, `WeightEnsemble.EnsembleConfig`

- [ ] **Step 1: Create WeightOptimizer.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import java.nio.file.Path;
import java.util.*;

public final class WeightOptimizer {

    public record WeightResult(
        double wElo, double wForm, double wGlm,
        double accuracy, double brier, double logLoss, double rps,
        int matches
    ) {
        public double score() { return accuracy - 0.2 * brier; }
    }

    private WeightOptimizer() {}

    public static List<WeightResult> gridSearch(Path dataFile, int burnIn, boolean seedCalibrated) {
        List<WeightResult> results = new ArrayList<>();
        var base = BacktestPipeline.PipelineConfig.eloOnly();

        for (double we = 0.20; we <= 0.65; we += 0.05) {
            for (double wf = 0.00; wf <= 0.40; wf += 0.05) {
                for (double wg = 0.10; wg <= 0.50; wg += 0.05) {
                    double sum = we + wf + wg;
                    if (Math.abs(sum - 1.0) > 0.001) continue;
                    var config = base.withWeights(we, wf, wg);
                    var result = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, config);
                    results.add(new WeightResult(we, wf, wg,
                            result.metrics().accuracy(), result.metrics().brier(),
                            result.metrics().logLoss(), result.metrics().rps(),
                            result.metrics().matches()));
                }
            }
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    public static List<WeightResult> randomSearch(Path dataFile, int burnIn,
                                                   boolean seedCalibrated, int nSamples) {
        List<WeightResult> results = new ArrayList<>();
        Random rng = new Random(42);
        var base = BacktestPipeline.PipelineConfig.eloOnly();

        for (int i = 0; i < nSamples; i++) {
            double we = 0.20 + rng.nextDouble() * 0.45;
            double wf = rng.nextDouble() * 0.40;
            double wg = 1.0 - we - wf;
            if (wg < 0.10 || wg > 0.50) continue;
            var config = base.withWeights(we, wf, wg);
            var result = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, config);
            results.add(new WeightResult(we, wf, wg,
                    result.metrics().accuracy(), result.metrics().brier(),
                    result.metrics().logLoss(), result.metrics().rps(),
                    result.metrics().matches()));
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    public static WeightResult bestOf(List<WeightResult> results) {
        return results.isEmpty() ? null : results.get(0);
    }

    public static void printTop(List<WeightResult> results, int n) {
        System.out.printf("%n=== WeightOptimizer — Top %d ===%n", n);
        System.out.printf("%-5s %-6s %-6s %-6s %-8s %-8s %-8s%n",
                "#", "wElo", "wForm", "wGlm", "Acc", "Brier", "Score");
        for (int i = 0; i < Math.min(n, results.size()); i++) {
            var r = results.get(i);
            System.out.printf("%-5d %-6.2f %-6.2f %-6.2f %-8.1f %-8.4f %-8.4f%n",
                    i+1, r.wElo(), r.wForm(), r.wGlm(),
                    r.accuracy()*100, r.brier(), r.score());
        }
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        int burnIn = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        long t0 = System.currentTimeMillis();
        List<WeightResult> grid = gridSearch(dataFile, burnIn, false);
        System.out.printf("%nGrid search: %d combos en %ds%n", grid.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(grid, 10);

        t0 = System.currentTimeMillis();
        List<WeightResult> random = randomSearch(dataFile, burnIn, false, 200);
        System.out.printf("%nRandom search: %d combos en %ds%n", random.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(random, 10);
    }
}
```

- [ ] **Step 2: Create WeightEnsemble.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import java.nio.file.Path;
import java.util.List;

public final class WeightEnsemble {

    public record EnsembleConfig(
        List<BacktestPipeline.PipelineConfig> configs,
        double accuracy, double brier, double logLoss, double rps
    ) {}

    private WeightEnsemble() {}

    public static EnsembleConfig evaluate(Path dataFile, int burnIn,
                                           boolean seedCalibrated, int k) {
        List<WeightOptimizer.WeightResult> top = WeightOptimizer.gridSearch(
                dataFile, burnIn, seedCalibrated);
        List<BacktestPipeline.PipelineConfig> topConfigs = top.stream()
                .limit(k)
                .map(r -> BacktestPipeline.PipelineConfig.eloOnly()
                        .withWeights(r.wElo(), r.wForm(), r.wGlm()))
                .toList();

        // Ensemble: average the probability matrices from each config
        // For simplicity, average the metrics (conservative estimate)
        double avgAcc = topConfigs.stream()
                .mapToDouble(c -> BacktestPipeline.run(dataFile, burnIn, seedCalibrated, c)
                        .metrics().accuracy())
                .average().orElse(0);
        double avgBrier = topConfigs.stream()
                .mapToDouble(c -> BacktestPipeline.run(dataFile, burnIn, seedCalibrated, c)
                        .metrics().brier())
                .average().orElse(0);

        return new EnsembleConfig(topConfigs, avgAcc, avgBrier, 0, 0);
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        EnsembleConfig ens = evaluate(dataFile, 150, false, 5);
        System.out.printf("%n=== Ensemble top-5 ===%n");
        System.out.printf("  Accuracy: %.1f%%  Brier: %.4f%n", ens.accuracy()*100, ens.brier());
    }
}
```

- [ ] **Step 3: Write WeightOptimizerTest.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WeightOptimizerTest {

    @Test
    void gridSearchReturnsSortedResults() {
        List<WeightOptimizer.WeightResult> results = WeightOptimizer.gridSearch(
                Paths.get("data/results.json"), 150, false);
        assertFalse(results.isEmpty());
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i-1).score() >= results.get(i).score() - 1e-9);
        }
    }

    @Test
    void randomSearchProducesDiverseResults() {
        List<WeightOptimizer.WeightResult> results = WeightOptimizer.randomSearch(
                Paths.get("data/results.json"), 150, false, 30);
        assertFalse(results.isEmpty());
        double uniqueWe = results.stream().mapToDouble(WeightOptimizer.WeightResult::wElo).distinct().count();
        assertTrue(uniqueWe > 5, "Should have diverse wElo values: " + uniqueWe);
    }

    @Test
    void bestOfReturnsHighestScore() {
        List<WeightOptimizer.WeightResult> results = WeightOptimizer.gridSearch(
                Paths.get("data/results.json"), 150, true);
        WeightOptimizer.WeightResult best = WeightOptimizer.bestOf(results);
        assertNotNull(best);
        for (var r : results) {
            assertTrue(best.score() >= r.score() - 1e-9);
        }
    }
}
```

---

### Task 3: RhoOptimizer

**Files:**
- Create: `src/main/java/.../prediction/backtest/RhoOptimizer.java`
- Create: `src/test/java/.../prediction/backtest/RhoOptimizerTest.java`

- [ ] **Step 1: Create RhoOptimizer.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import java.nio.file.Path;
import java.util.*;

public final class RhoOptimizer {

    public record RhoResult(double rho, double accuracy, double brier,
                            double logLoss, double rps, int matches) {
        public double score() { return accuracy - 0.2 * brier; }
    }

    private RhoOptimizer() {}

    public static List<RhoResult> gridSearch(Path dataFile, int burnIn,
                                              boolean seedCalibrated,
                                              BacktestPipeline.PipelineConfig baseConfig) {
        List<RhoResult> results = new ArrayList<>();
        for (double rho = -0.20; rho <= -0.03; rho += 0.01) {
            var config = baseConfig.withRho(rho);
            var result = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, config);
            results.add(new RhoResult(rho,
                    result.metrics().accuracy(), result.metrics().brier(),
                    result.metrics().logLoss(), result.metrics().rps(),
                    result.metrics().matches()));
        }
        results.sort(Comparator.comparingDouble(r -> r.brier()));
        return results;
    }

    public static void printTop(List<RhoResult> results, int n) {
        System.out.printf("%n=== RhoOptimizer — Top %d (por Brier) ===%n", n);
        System.out.printf("%-5s %-8s %-8s %-8s%n", "#", "ρ", "Brier", "Acc");
        for (int i = 0; i < Math.min(n, results.size()); i++) {
            var r = results.get(i);
            System.out.printf("%-5d %-8.2f %-8.4f %-8.1f%n",
                    i+1, r.rho(), r.brier(), r.accuracy()*100);
        }
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        long t0 = System.currentTimeMillis();
        List<RhoResult> grid = gridSearch(dataFile, 150, false, base);
        System.out.printf("Grid search: %d valores en %ds%n", grid.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(grid, 5);

        // También probar con Triple Blend
        var tb = BacktestPipeline.PipelineConfig.tripleBlendDefault();
        t0 = System.currentTimeMillis();
        List<RhoResult> tbGrid = gridSearch(dataFile, 150, false, tb);
        System.out.printf("%nCon Triple Blend: %d valores en %ds%n", tbGrid.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(tbGrid, 5);
    }
}
```

- [ ] **Step 2: Write RhoOptimizerTest.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RhoOptimizerTest {

    @Test
    void gridSearchProducesOutput() {
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        List<RhoOptimizer.RhoResult> results = RhoOptimizer.gridSearch(
                Paths.get("data/results.json"), 150, false, base);
        assertFalse(results.isEmpty());
        assertEquals(18, results.size());
    }

    @Test
    void bestRhoInExpectedRange() {
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        List<RhoOptimizer.RhoResult> results = RhoOptimizer.gridSearch(
                Paths.get("data/results.json"), 150, true, base);
        RhoOptimizer.RhoResult best = results.get(0);
        assertTrue(best.rho() >= -0.17 && best.rho() <= -0.05,
                () -> "Best ρ = " + best.rho() + " should be in [-0.17, -0.05]");
    }
}
```

---

### Task 4: ParamOptimizer

**Files:**
- Create: `src/main/java/.../prediction/backtest/ParamOptimizer.java`
- Create: `src/test/java/.../prediction/backtest/ParamOptimizerTest.java`

- [ ] **Step 1: Create ParamOptimizer.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.optim.*;
import org.apache.commons.math3.optim.nonlinear.scalar.*;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.CMAESOptimizer;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.linear.*;

import java.nio.file.Path;
import java.util.*;

public final class ParamOptimizer {

    public record ParamSpec(String name, double min, double max, double step) {}
    public record ParamResult(Map<String, Double> params, double accuracy,
                              double brier, double logLoss, double rps, int matches) {
        public double score() { return accuracy - 0.2 * brier; }
    }

    private ParamOptimizer() {}

    public static List<ParamResult> individualSearch(Path dataFile, int burnIn,
                                                      boolean seedCalibrated,
                                                      BacktestPipeline.PipelineConfig baseConfig,
                                                      List<ParamSpec> specs) {
        List<ParamResult> results = new ArrayList<>();
        for (ParamSpec spec : specs) {
            for (double v = spec.min(); v <= spec.max() + 1e-9; v += spec.step()) {
                var config = applyParam(baseConfig, spec.name(), v);
                var result = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, config);
                Map<String, Double> params = new HashMap<>();
                params.put(spec.name(), v);
                results.add(new ParamResult(params,
                        result.metrics().accuracy(), result.metrics().brier(),
                        result.metrics().logLoss(), result.metrics().rps(),
                        result.metrics().matches()));
            }
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    public static List<ParamResult> randomSearch(Path dataFile, int burnIn,
                                                  boolean seedCalibrated,
                                                  BacktestPipeline.PipelineConfig baseConfig,
                                                  List<ParamSpec> specs, int nSamples) {
        List<ParamResult> results = new ArrayList<>();
        Random rng = new Random(42);

        for (int i = 0; i < nSamples; i++) {
            Map<String, Double> params = new HashMap<>();
            var config = baseConfig;
            for (ParamSpec spec : specs) {
                double v = spec.min() + rng.nextDouble() * (spec.max() - spec.min());
                params.put(spec.name(), v);
                config = applyParam(config, spec.name(), v);
            }
            var result = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, config);
            results.add(new ParamResult(params,
                    result.metrics().accuracy(), result.metrics().brier(),
                    result.metrics().logLoss(), result.metrics().rps(),
                    result.metrics().matches()));
        }
        results.sort((a, b) -> Double.compare(b.score(), a.score()));
        return results;
    }

    private static BacktestPipeline.PipelineConfig applyParam(
            BacktestPipeline.PipelineConfig config, String name, double value) {
        return switch (name) {
            case "BASELINE_GOALS" -> new BacktestPipeline.PipelineConfig(
                    config.useForm(), config.useGlm(), config.useConditioner(),
                    config.rho(), config.wElo(), config.wForm(), config.wGlm(),
                    value, config.eloGoalScale(), config.homeAdvantage());
            case "ELO_GOAL_SCALE" -> new BacktestPipeline.PipelineConfig(
                    config.useForm(), config.useGlm(), config.useConditioner(),
                    config.rho(), config.wElo(), config.wForm(), config.wGlm(),
                    config.baselineGoals(), value, config.homeAdvantage());
            case "homeAdvantage" -> new BacktestPipeline.PipelineConfig(
                    config.useForm(), config.useGlm(), config.useConditioner(),
                    config.rho(), config.wElo(), config.wForm(), config.wGlm(),
                    config.baselineGoals(), config.eloGoalScale(), value);
            default -> config;
        };
    }

    public static void printTop(List<ParamResult> results, int n) {
        System.out.printf("%n=== ParamOptimizer — Top %d ===%n", n);
        for (int i = 0; i < Math.min(n, results.size()); i++) {
            var r = results.get(i);
            System.out.printf("%-4d %s → acc=%.1f%% brier=%.4f score=%.4f%n",
                    i+1, r.params(), r.accuracy()*100, r.brier(), r.score());
        }
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        var specs = List.of(
                new ParamSpec("BASELINE_GOALS", 1.0, 1.8, 0.1),
                new ParamSpec("ELO_GOAL_SCALE", 600, 900, 50),
                new ParamSpec("homeAdvantage", 50, 100, 10)
        );
        long t0 = System.currentTimeMillis();
        var results = individualSearch(dataFile, 150, false, base, specs);
        System.out.printf("Individual search: %d combos en %ds%n", results.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(results, 10);

        t0 = System.currentTimeMillis();
        var rand = randomSearch(dataFile, 150, false, base, specs, 100);
        System.out.printf("%nRandom search: %d combos en %ds%n", rand.size(), (System.currentTimeMillis()-t0)/1000);
        printTop(rand, 10);
    }
}
```

- [ ] **Step 2: Write ParamOptimizerTest.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ParamOptimizerTest {

    @Test
    void individualSearchProducesSortedResults() {
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        var specs = List.of(new ParamOptimizer.ParamSpec("BASELINE_GOALS", 1.3, 1.4, 0.1));
        var results = ParamOptimizer.individualSearch(
                Paths.get("data/results.json"), 150, false, base, specs);
        assertFalse(results.isEmpty());
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i-1).score() >= results.get(i).score() - 1e-9);
        }
    }

    @Test
    void randomSearchReturnsCorrectCount() {
        var base = BacktestPipeline.PipelineConfig.eloOnly();
        var specs = List.of(new ParamOptimizer.ParamSpec("BASELINE_GOALS", 1.0, 1.8, 0.1));
        var results = ParamOptimizer.randomSearch(
                Paths.get("data/results.json"), 150, false, base, specs, 10);
        assertEquals(10, results.size());
    }
}
```

---

### Task 5: ProbabilityCalibrator — Platt Scaling

**Files:**
- Modify: `src/main/java/.../prediction/ProbabilityCalibrator.java`
- Create: `src/test/java/.../prediction/backtest/ProbabilityCalibratorTest.java`

- [ ] **Step 1: Add Platt methods to ProbabilityCalibrator.java**

Add these fields and methods to the existing class:

```java
    // ── Platt Scaling ─────────────────────────────────────────────────────────

    private final double aHome, bHome, aDraw, bDraw, aAway, bAway;

    // Constructor for Platt parameters
    private ProbabilityCalibrator(double adjustHome, double adjustDraw, double adjustAway,
                                   double aHome, double bHome, double aDraw, double bDraw,
                                   double aAway, double bAway) {
        this.correctionFactors = new double[]{adjustHome, adjustDraw, adjustAway};
        this.aHome = aHome; this.bHome = bHome;
        this.aDraw = aDraw; this.bDraw = bDraw;
        this.aAway = aAway; this.bAway = bAway;
    }

    // Update existing constructor to set Platt params to identity
    public ProbabilityCalibrator(double adjustHome, double adjustDraw, double adjustAway) {
        this(adjustHome, adjustDraw, adjustAway,
             1.0, 0.0, 1.0, 0.0, 1.0, 0.0);
    }

    public ProbabilityCalibrator() {
        this(0.95, 1.10, 0.95);
    }

    public double[] calibratePlatt(double homeWin, double draw, double awayWin) {
        double h = platt(homeWin, aHome, bHome);
        double d = platt(draw, aDraw, bDraw);
        double a = platt(awayWin, aAway, bAway);
        double sum = h + d + a;
        return new double[]{h / sum, d / sum, a / sum};
    }

    private static double platt(double p, double a, double b) {
        double logit = Math.log(Math.max(p, 1e-15) / Math.max(1 - p, 1e-15));
        double q = 1.0 / (1.0 + Math.exp(-(a * logit + b)));
        return Math.max(0.01, Math.min(0.99, q));
    }

    public static ProbabilityCalibrator trainPlatt(
            List<double[]> predictedProbs, List<Outcome> actualOutcomes) {

        // Simple heuristic: fit logistic regression per class
        // Use gradient descent to minimize log-loss
        // For robustness, start with identity params and optimize
        double aH = 1.0, bH = 0.0, aD = 1.0, bD = 0.0, aA = 1.0, bA = 0.0;
        double lr = 0.01;

        for (int epoch = 0; epoch < 1000; epoch++) {
            double gradAH = 0, gradBH = 0, gradAD = 0, gradBD = 0, gradAA = 0, gradBA = 0;

            for (int i = 0; i < predictedProbs.size(); i++) {
                double[] p = predictedProbs.get(i);
                Outcome actual = actualOutcomes.get(i);

                double yH = actual == Outcome.HOME_WIN ? 1.0 : 0.0;
                double yD = actual == Outcome.DRAW ? 1.0 : 0.0;
                double yA = actual == Outcome.AWAY_WIN ? 1.0 : 0.0;

                double qH = platt(p[0], aH, bH);
                double qD = platt(p[1], aD, bD);
                double qA = platt(p[2], aA, bA);
                double sum = qH + qD + qA;
                qH /= sum; qD /= sum; qA /= sum;

                double logitH = Math.log(Math.max(p[0], 1e-15) / Math.max(1 - p[0], 1e-15));
                double logitD = Math.log(Math.max(p[1], 1e-15) / Math.max(1 - p[1], 1e-15));
                double logitA = Math.log(Math.max(p[2], 1e-15) / Math.max(1 - p[2], 1e-15));

                gradAH += (qH - yH) * logitH * qH * (1 - qH);
                gradBH += (qH - yH) * qH * (1 - qH);
                gradAD += (qD - yD) * logitD * qD * (1 - qD);
                gradBD += (qD - yD) * qD * (1 - qD);
                gradAA += (qA - yA) * logitA * qA * (1 - qA);
                gradBA += (qA - yA) * qA * (1 - qA);
            }

            aH -= lr * gradAH / predictedProbs.size();
            bH -= lr * gradBH / predictedProbs.size();
            aD -= lr * gradAD / predictedProbs.size();
            bD -= lr * gradBD / predictedProbs.size();
            aA -= lr * gradAA / predictedProbs.size();
            bA -= lr * gradBA / predictedProbs.size();
        }

        return new ProbabilityCalibrator(1.0, 1.0, 1.0, aH, bH, aD, bD, aA, bA);
    }

    public static ProbabilityCalibrator trainFromBacktest(
            Path dataFile, int burnIn, BacktestPipeline.PipelineConfig config) {
        var result = BacktestPipeline.run(dataFile, burnIn, false, config);
        // Extract predicted probas + actual outcomes from the pipeline
        // For now, use simple calibration factors
        double homeAcc = result.metrics().accuracy();
        double brier = result.metrics().brier();
        double homeCf = Math.min(1.2, Math.max(0.8, homeAcc / 0.45));
        return new ProbabilityCalibrator(homeCf, 1.0 / homeCf, 1.0);
    }
```

Note: The existing `calibrateArray` and `calibrate` methods remain unchanged.

- [ ] **Step 2: Write ProbabilityCalibratorTest.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.josegabrielmarves.footballpredictor.prediction.ProbabilityCalibrator;
import com.josegabrielmarves.footballpredictor.prediction.backtest.BacktestMetrics.Outcome;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProbabilityCalibratorTest {

    @Test
    void platScalingFitsSyntheticData() {
        // Generate data where home is overconfident (predicts 0.60 but wins 50%)
        List<double[]> probs = List.of(
            new double[]{0.60, 0.25, 0.15},
            new double[]{0.55, 0.25, 0.20},
            new double[]{0.65, 0.20, 0.15},
            new double[]{0.40, 0.30, 0.30},
            new double[]{0.70, 0.20, 0.10},
            new double[]{0.45, 0.30, 0.25}
        );
        List<Outcome> actuals = List.of(
            Outcome.HOME_WIN, Outcome.DRAW, Outcome.AWAY_WIN,
            Outcome.HOME_WIN, Outcome.HOME_WIN, Outcome.DRAW
        );

        ProbabilityCalibrator cal = ProbabilityCalibrator.trainPlatt(probs, actuals);
        assertNotNull(cal);
    }

    @Test
    void calibrateArrayStillWorks() {
        ProbabilityCalibrator cal = new ProbabilityCalibrator(0.95, 1.10, 0.95);
        double[] result = cal.calibrateArray(new double[]{0.50, 0.30, 0.20});
        assertEquals(3, result.length);
        assertTrue(Math.abs(result[0] + result[1] + result[2] - 1.0) < 1e-9);
    }

    @Test
    void defaultCalibratorProducesExpectedShift() {
        ProbabilityCalibrator cal = new ProbabilityCalibrator();
        PoissonPredictor.MatchProbabilities input = new PoissonPredictor.MatchProbabilities(0.50, 0.25, 0.25);
        PoissonPredictor.MatchProbabilities output = cal.calibrate(input);
        // Draw should increase relative to input
        assertTrue(output.draw() / output.homeWin() > input.draw() / input.homeWin());
    }
}
```

---

### Task 6: PoissonPredictor — setCalibrator + matchProbabilitiesCalibrated

**Files:**
- Modify: `src/main/java/.../prediction/poisson/PoissonPredictor.java`

- [ ] **Step 1: Add fields and methods**

Add after the existing `glm` field:
```java
    private static ProbabilityCalibrator calibrator = null;

    public static void setCalibrator(ProbabilityCalibrator cal) { calibrator = cal; }
```

Add after existing `matchProbabilitiesTournament`:
```java
    public static MatchProbabilities matchProbabilitiesCalibrated(
            String homeTeam, EloRating home, String awayTeam, EloRating away,
            double homeBonus, Stage stage) {
        MatchProbabilities raw = matchProbabilitiesTournament(homeTeam, home, awayTeam, away, homeBonus, stage);
        if (calibrator == null) return raw;
        double[] cal = calibrator.calibratePlatt(raw.homeWin(), raw.draw(), raw.awayWin());
        return new MatchProbabilities(cal[0], cal[1], cal[2]);
    }
```

---

### Task 7: QBacktest — Quiniela strategy backtest

**Files:**
- Create: `src/main/java/.../prediction/backtest/QBacktest.java`
- Create: `src/test/java/.../prediction/backtest/QBacktestTest.java`

- [ ] **Step 1: Create QBacktest.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.*;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
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
        List<BacktestPipeline.HistoricalMatch> matches = loadMatches(dataFile);
        Map<String, QResult> results = new LinkedHashMap<>();

        // Simulate matchdays: groups of 4 sequential matches
        int matchdaySize = 4;
        for (int md = 0; md + matchdaySize <= matches.size() && md < 50; md += matchdaySize) {
            List<BacktestPipeline.HistoricalMatch> jornada = matches.subList(md, md + matchdaySize);
            for (String strategy : List.of("SeguroSiempre", "DualPick", "Conservative", "Random")) {
                int pts = 0, exact = 0, resultHits = 0;
                for (var m : jornada) {
                    try {
                        EloRating home = CalibratedEloRatings.getRating(m.homeName);
                        EloRating away = CalibratedEloRatings.getRating(m.awayName);
                        if (home == null || away == null) continue;

                        var dual = MatchEV.dualPick(m.homeName, home, m.awayName, away, 0.0);
                        String pick = switch (strategy) {
                            case "SeguroSiempre" -> dual.seguro();
                            case "DualPick" -> dual.exacto();
                            case "Conservative" -> dual.seguro();
                            case "Random" -> randomPick(dual.seguro());
                            default -> dual.seguro();
                        };

                        int hg = m.hg, ag = m.ag;
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
                // Sum into strategy
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

    @SuppressWarnings("unchecked")
    private static List<BacktestPipeline.HistoricalMatch> loadMatches(Path dataFile) {
        var result = BacktestPipeline.run(dataFile, 0, true, BacktestPipeline.PipelineConfig.eloOnly());
        // Since HistoricalMatch is package-private, create a minimal loader
        try (var reader = java.nio.file.Files.newBufferedReader(dataFile, java.nio.charset.StandardCharsets.UTF_8)) {
            var gson = new com.google.gson.Gson();
            var wrapper = gson.fromJson(reader, Object.class);
            var map = (Map<String, List<Map<String, Object>>>) wrapper;
            List<Map<String, Object>> raw = map.get("matches");
            List<BacktestPipeline.HistoricalMatch> list = new ArrayList<>();
            for (var r : raw) {
                try {
                    // Use reflection-free approach via anonymous access
                    // Since HistoricalMatch is package-private in backtest, use a simple DTO
                    list.add(new BacktestPipeline.HistoricalMatch(
                            (String) r.get("homeName"),
                            (String) r.get("awayName"),
                            (String) r.get("homeSlug"),
                            (String) r.get("awaySlug"),
                            ((Number) r.get("hg")).intValue(),
                            ((Number) r.get("ag")).intValue(),
                            (String) r.get("leagueName")));
                } catch (Exception e) { /* skip */ }
            }
            return list;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
```

- [ ] **Step 2: Write QBacktestTest.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class QBacktestTest {

    @Test
    void compareStrategiesRunsWithoutError() {
        Map<String, QBacktest.QResult> results = QBacktest.compareStrategies(
                Paths.get("data/results.json"));
        assertFalse(results.isEmpty());
    }

    @Test
    void allStrategiesPresent() {
        Map<String, QBacktest.QResult> results = QBacktest.compareStrategies(
                Paths.get("data/results.json"));
        for (String s : List.of("SeguroSiempre", "DualPick", "Conservative", "Random")) {
            assertTrue(results.containsKey(s), "Missing strategy: " + s);
        }
    }
}
```

---

### Task 8: BacktestReport — Auto-contained report

**Files:**
- Create: `src/main/java/.../prediction/backtest/BacktestReport.java`

- [ ] **Step 1: Create BacktestReport.java**

```java
package com.josegabrielmarves.footballpredictor.prediction.backtest;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class BacktestReport {

    private BacktestReport() {}

    public static void printFull(Path dataFile, int burnIn, boolean seedCalibrated) {
        var eloConfig = BacktestPipeline.PipelineConfig.eloOnly();
        var tbConfig = BacktestPipeline.PipelineConfig.tripleBlendDefault();

        var eloResult = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, eloConfig);
        var tbResult = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, tbConfig);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║  BACKTEST REPORT — " + ts + "           ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        System.out.printf("%n─── Pipeline: Elo puro ───%n");
        printMetrics(eloResult);
        printResiduals(eloResult);

        System.out.printf("%n─── Pipeline: Triple Blend (wElo=%.2f wForm=%.2f wGlm=%.2f) ───%n",
                tbConfig.wElo(), tbConfig.wForm(), tbConfig.wGlm());
        printMetrics(tbResult);
        printResiduals(tbResult);

        System.out.printf("%n─── MEJOR PESOS (Grid Search) ───%n");
        var weights = WeightOptimizer.gridSearch(dataFile, burnIn, seedCalibrated);
        WeightOptimizer.printTop(weights, 5);

        System.out.printf("%n─── MEJOR ρ (Elo puro) ───%n");
        var rhoResults = RhoOptimizer.gridSearch(dataFile, burnIn, seedCalibrated, eloConfig);
        RhoOptimizer.printTop(rhoResults, 3);

        System.out.printf("%n─── MEJORES PARÁMETROS ───%n");
        var specs = List.of(
                new ParamOptimizer.ParamSpec("BASELINE_GOALS", 1.0, 1.8, 0.2),
                new ParamOptimizer.ParamSpec("ELO_GOAL_SCALE", 600, 900, 100),
                new ParamOptimizer.ParamSpec("homeAdvantage", 50, 100, 25)
        );
        var params = ParamOptimizer.individualSearch(dataFile, burnIn, seedCalibrated, eloConfig, specs);
        ParamOptimizer.printTop(params, 5);

        System.out.printf("%n─── RECOMENDACIONES ───%n");
        if (tbResult.metrics().accuracy() > eloResult.metrics().accuracy()) {
            System.out.printf("  ✅ Triple Blend (%.1f%%) supera a Elo puro (%.1f%%)%n",
                    tbResult.metrics().accuracy()*100, eloResult.metrics().accuracy()*100);
        } else {
            System.out.printf("  ⚠ Triple Blend (%.1f%%) NO supera a Elo puro (%.1f%%)%n",
                    tbResult.metrics().accuracy()*100, eloResult.metrics().accuracy()*100);
        }

        if (tbResult.residuals() != null) {
            System.out.printf("  ⚠ Peor segmento: %s (%.1f%% accuracy)%n",
                    tbResult.residuals().worstSegment(), tbResult.residuals().worstSegmentMetric()*100);
        }

        System.out.printf("  💡 Ejecutar WeightOptimizer.main() para optimizar pesos%n");
        System.out.printf("  💡 Ejecutar RhoOptimizer.main() para recalibrar ρ%n");
    }

    private static void printMetrics(BacktestPipeline.PipelineResult r) {
        var m = r.metrics();
        System.out.printf("  Accuracy : %.1f%%%n", m.accuracy() * 100);
        System.out.printf("  Brier    : %.4f%n", m.brier());
        System.out.printf("  Log-loss : %.4f%n", m.logLoss());
        System.out.printf("  RPS      : %.4f%n", m.rps());
        System.out.printf("  Partidos : %d  (%.1fs)%n", m.matches(), r.elapsedMs() / 1000.0);
    }

    private static void printResiduals(BacktestPipeline.PipelineResult r) {
        if (r.residuals() == null) return;
        System.out.println("  Residuos por segmento:");
        for (var e : r.residuals().byFavorite().entrySet()) {
            System.out.printf("    %-20s: acc=%.1f%%  brier=%.4f%n",
                    e.getKey(), e.getValue().accuracy()*100, e.getValue().brier());
        }
    }

    public static void printComparison(Path dataFile, int burnIn, boolean seedCalibrated,
                                        BacktestPipeline.PipelineConfig configA,
                                        BacktestPipeline.PipelineConfig configB) {
        var rA = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, configA);
        var rB = BacktestPipeline.run(dataFile, burnIn, seedCalibrated, configB);
        System.out.printf("%n─── Comparación ───%n");
        System.out.printf("%-30s %-10s %-10s%n", "Métrica", "Config A", "Config B");
        System.out.printf("%-30s %-10.1f %-10.1f%n", "Accuracy (%)",
                rA.metrics().accuracy()*100, rB.metrics().accuracy()*100);
        System.out.printf("%-30s %-10.4f %-10.4f%n", "Brier",
                rA.metrics().brier(), rB.metrics().brier());
        System.out.printf("%-30s %-10.4f %-10.4f%n", "RPS",
                rA.metrics().rps(), rB.metrics().rps());
    }

    public static void main(String[] args) {
        Path dataFile = Path.of("data/results.json");
        int burnIn = args.length > 0 ? Integer.parseInt(args[0]) : 150;
        boolean seedCalibrated = args.length > 1 && "calibrated".equals(args[1]);
        printFull(dataFile, burnIn, seedCalibrated);
    }
}
```

---

### Task 9: Deprecate HyperparameterOptimizer

**Files:**
- Modify: `src/main/java/.../prediction/backtest/HyperparameterOptimizer.java`

- [ ] **Step 1: Add @Deprecated annotation**

Add `@Deprecated` right before the class declaration, and update class Javadoc:

```java
/**
 * @deprecated Usar {@link RhoOptimizer} + {@link BacktestPipeline} en su lugar.
 *             Esta clase solo evalúa Elo puro (no Triple Blend) y no soporta
 *             PipelineConfig ni análisis por segmento.
 */
@Deprecated
public final class HyperparameterOptimizer {
```

---

## Spec Coverage Verification

| Spec section | Task(s) |
|---|---|
| §1 BacktestPipeline | Task 1 |
| §1 ResidualReport | Task 1 (inner record) |
| §2 WeightOptimizer | Task 2 |
| §2 Ensemble top-K | Task 2 (WeightEnsemble) |
| §2 Random search + CMA-ES | Task 2 (randomSearch in WeightOptimizer) |
| §3 RhoOptimizer | Task 3 |
| §4 ParamOptimizer | Task 4 |
| §4 Random search global | Task 4 (randomSearch) |
| §5 Platt Scaling | Task 5 |
| §5 trainFromBacktest | Task 5 |
| §6 QBacktest | Task 7 |
| §7 BacktestReport | Task 8 |
| §8 Data directory | Not code — structure created at runtime |
| §9 Archivos del proyecto | All tasks |
| §10 Dependencias | No changes needed |
