# Pipeline de Backtesting y Optimización — Spec de Diseño

> **Objetivo:** Construir un sistema de backtesting que evalúe el MISMO pipeline que se ejecuta en producción (Triple Blend completo) y optimice todos los parámetros mediante walk-forward, grid search, random search y optimización bayesiana. Con ensemble de top-K configs, Platt Scaling, análisis de residuos por segmento y reporte auto-contenido. Java 25, sin dependencias externas nuevas.

---

## 1. BacktestPipeline — Backtest del pipeline real

### Problema

`BacktestEngine` y `WorldCupBacktest` solo prueban `PoissonPredictor.matchProbabilities()` (Elo puro). El pipeline de producción usa `PoissonPredictor.expectedGoalsBlended()` (Triple Blend). No hay métricas del modelo que se envía al WhatsApp.

### Solución

Nueva clase `BacktestPipeline` en `prediction/backtest/`:

```java
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
    );

    public record PipelineResult(
        BacktestMetrics metrics,
        long elapsedMs,
        PipelineConfig config
    );

    /** Backtest walk-forward sobre results.json.
     * @param seedCalibrated true → CalibratedEloRatings, false → arranque 1500 (causal) */
    public static PipelineResult run(Path dataFile, int burnIn,
                                     boolean seedCalibrated, PipelineConfig config);

    /** Modo torneo: GLM + Conditioner walk-forward sobre xg_wc2026.json. */
    public static PipelineResult runTournament(Path xgFile, PipelineConfig config);
}
```

### Pipeline interno

1. Obtener ratings Elo del mapa (seed calibrado o plano)
2. `useForm=true`: `FIFAFormCalculator.getForm()` con fecha estimada por posición en el dataset
3. `useGlm/useConditioner=false` en histórico, `true` en `runTournament()`
4. Blend: `wElo×lElo + wForm×lForm + wGlm×(lGlm??lElo)`
5. Matriz con `config.rho` + Dixon-Coles
6. `BacktestMetrics.add()`
7. `EloCalculator.updateRatings()`

### Análisis de residuos por segmento (NUEVO)

`BacktestPipeline` además devuelve un `ResidualReport`:

```java
public record ResidualReport(
    Map<String, BacktestMetrics> byStage,         // grupos / eliminatorias
    Map<String, BacktestMetrics> byLeagueType,    // WC / qualifiers / friendlies
    Map<String, BacktestMetrics> byScoreRange,    // goleada / ajustado / empate
    Map<String, BacktestMetrics> byFavorite,      // favorito gana / underdog / draw
    SegmentSummary worstSegment
);
```

Identifica DÓNDE falla el modelo: ¿empates? ¿goleadas? ¿underdogs? ¿friendlies? Así se sabe qué mejorar.

---

## 2. WeightOptimizer — Optimización de pesos del Triple Blend

```java
public final class WeightOptimizer {

    public record WeightResult(
        double wElo, double wForm, double wGlm,
        double accuracy, double brier, double logLoss, double rps,
        int matches
    );

    /** Grid search: wElo∈[0.20,0.65], wForm∈[0.00,0.40], wGlm∈[0.10,0.50], paso 0.05 */
    public static List<WeightResult> gridSearch(
            Path dataFile, int burnIn, boolean seedCalibrated);

    /** Random search: N combinaciones aleatorias uniformes. Más eficiente que grid. */
    public static List<WeightResult> randomSearch(
            Path dataFile, int burnIn, boolean seedCalibrated, int nSamples);

    /** CMA-ES (Covariance Matrix Adaptation Evolution Strategy) vía commons-math3.
     *  Optimiza pesos para maximizar accuracy - 0.2×brier. */
    public static WeightResult bayesianOptimize(
            Path dataFile, int burnIn, boolean seedCalibrated, int maxEvals);
}
```

### Ensemble de top-K (NUEVO)

```java
public final class WeightEnsemble {

    public record EnsembleConfig(
        List<WeightResult> topK,
        double ensembleAccuracy, double ensembleBrier
    );

    /** Evalúa promediar las probabilidades de las top-K configuraciones.
     *  El ensemble SIEMPRE gana al mejor individual. */
    public static EnsembleConfig evaluate(
            Path dataFile, int burnIn, boolean seedCalibrated, int k);
}
```

Output: top-10 individual + ensemble + `data/backtest/weights_<timestamp>.json`

---

## 3. RhoOptimizer — Recalibrar ρ para Triple Blend

Clase nueva independiente (HyperparameterOptimizer queda @Deprecated):

```java
public final class RhoOptimizer {

    public record RhoResult(double rho, double accuracy, double brier,
                            double logLoss, double rps);

    /** Grid: ρ∈[-0.20, -0.03] paso 0.01, criterio=min Brier */
    public static List<RhoResult> gridSearch(
            Path dataFile, int burnIn, boolean seedCalibrated,
            PipelineConfig baseConfig);
}
```

---

## 4. ParamOptimizer — Optimización global

```java
public final class ParamOptimizer {

    public record ParamSpec(String name, double min, double max, double step);
    public record ParamResult(Map<String, Double> params, BacktestMetrics metrics);

    /** Grid 1D: varía un parámetro a la vez */
    public static List<ParamResult> individualSearch(
            Path dataFile, int burnIn, boolean seedCalibrated,
            PipelineConfig baseConfig, List<ParamSpec> specs);

    /** Grid 2D: combina 2 parámetros */
    public static List<ParamResult> grid2DSearch(
            Path dataFile, int burnIn, boolean seedCalibrated,
            PipelineConfig baseConfig, ParamSpec s1, ParamSpec s2);

    /** Random search sobre TODOS los parámetros simultáneamente */
    public static List<ParamResult> randomSearch(
            Path dataFile, int burnIn, boolean seedCalibrated,
            PipelineConfig baseConfig, List<ParamSpec> specs, int nSamples);

    /** CMA-ES sobre subconjunto de parámetros numéricos */
    public static ParamResult bayesianOptimize(
            Path dataFile, int burnIn, boolean seedCalibrated,
            PipelineConfig baseConfig, List<ParamSpec> specs, int maxEvals);
}
```

### Parámetros

| Parámetro | Rango | Default | Búsqueda |
|---|---|---|---|
| `REG_LAMBDA` | [0.5, 5.0] | 2.0 | grid+random |
| `α_TC_base` | [0.10, 0.50] | 0.35 | grid+random |
| `α_TC_step` | [0.02, 0.10] | 0.05 | grid+random |
| `BASELINE_GOALS` | [1.0, 1.8] | 1.35 | grid+random |
| `Form.decay` | [0.001, 0.005] | 0.003 | random+CMA-ES |
| `Form.maxMatches` | [20, 80] | 50 | grid+random |
| `Elo.k_worldCup` | [40, 70] | 55 | grid+random |
| `Elo.homeAdvantage` | [50, 100] | 75 | grid+random |
| `ELO_GOAL_SCALE` | [500, 1000] | 740 | random+CMA-ES |

Output: `data/backtest/params_<timestamp>.csv`

---

## 5. ProbabilityCalibrator — Platt Scaling (NUEVO)

La clase existe en `prediction/ProbabilityCalibrator.java` (55 líneas, factores fijos 0.95/1.10/0.95). Se extiende con **Platt Scaling** en vez de factores multiplicativos.

### Platt Scaling

```java
public final class ProbabilityCalibrator {

    // Constructores existentes (sin cambios)
    public ProbabilityCalibrator(double adjustHome, double adjustDraw, double adjustAway);
    public ProbabilityCalibrator();

    // Métodos existentes (sin cambios)
    public PoissonPredictor.MatchProbabilities calibrate(PoissonPredictor.MatchProbabilities probs);
    public double[] calibrateArray(double[] probs);

    // NUEVO: Platt Scaling — entrena sigmoide 1/(1+exp(a*logit(p)+b)) por clase
    // Usa PowellOptimizer de commons-math3 para minimizar log-loss en validación
    public static ProbabilityCalibrator trainPlatt(
            List<double[]> predictedProbs,
            List<Outcome> actualOutcomes);
    // Retorna calibrator con parámetros (aHome, bHome, aDraw, bDraw, aAway, bAway)

    // NUEVO: Platt calibrate
    public double[] calibratePlatt(double homeWin, double draw, double awayWin);

    // NUEVO: entrenar desde backtest
    public static ProbabilityCalibrator trainFromBacktest(
            Path dataFile, int burnIn, PipelineConfig config);
}
```

### Integración

```java
// En PoissonPredictor
public static void setCalibrator(ProbabilityCalibrator cal);
public static MatchProbabilities matchProbabilitiesCalibrated(
        String homeTeam, EloRating home, String awayTeam, EloRating away,
        double homeBonus, Stage stage);
```

Platt Scaling da mejor calibración que factores multiplicativos porque:
- No asume relación lineal entre prob predicha y real
- Se optimiza directamente contra log-loss (la métrica correcta para probabilidades)
- Maneja colas (probs extremas) correctamente

---

## 6. QBacktest — Backtest de estrategia quiniela

```java
public final class QBacktest {

    public record QResult(
        String strategy,
        int totalPts, int exactHits, int resultHits,
        double avgRisk,
        Map<String, Integer> riskDistribution
    );

    public static Map<String, QResult> compareStrategies(Path dataFile);
}
```

Estrategias: SeguroSiempre, DualPickActual, StrategyOptimizer, Conservative, Random.

---

## 7. Reporte auto-contenido (NUEVO)

`BacktestReport` que imprime TODO a consola con formato claro:

```
╔══════════════════════════════════════════════════╗
║  BACKTEST REPORT — 2026-06-25 12:00:00           ║
╚══════════════════════════════════════════════════╝

─── Pipeline: Triple Blend (wElo=0.40 wForm=0.25 wGlm=0.35) ───
Accuracy : 61.2%    Brier : 0.538    LogLoss : 0.892    RPS : 0.318
Partidos: 763      Burn-in: 150      Modo: Honesto (seed=1500)

─── MEJOR PESOS (Grid Search) ───
  1. wElo=0.35 wForm=0.25 wGlm=0.40 → acc=62.1% brier=0.531
  2. wElo=0.40 wForm=0.20 wGlm=0.40 → acc=61.8% brier=0.534
  ...
  Ensemble top-5: acc=62.5% brier=0.527  (+1.3% vs default)

─── MEJOR ρ ───
  ρ = -0.11 → brier=0.531 (vs default -0.13 = 0.538)

─── MEJORES PARÁMETROS ───
  REG_LAMBDA=1.5, BASELINE_GOALS=1.40, ELO_GOAL_SCALE=780
  Form.decay=0.0025, Elo.k_wc=50, homeAdv=70

─── RESIDUOS POR SEGMENTO ───
  Grupos          : acc=60.5% brier=0.542  (342 partidos)
  Eliminatorias   : acc=62.1% brier=0.533  (421 partidos)
  Favorito gana   : acc=74.3% ← bien
  Empate real     : acc=22.1% ← PEOR SEGMENTO (solo 22% de acierto)
  Underdog gana   : acc=38.7%
  Goleada (3+ goles): acc=51.2%

─── RECOMENDACIONES ───
  ⚠ El modelo acierta solo 22% de los empates reales.
    → Sugerencia: subir ρ hacia -0.07 o aumentar wForm para captar
      volatilidad.
    → Con Platt Scaling el Brier estimado baja a ~0.51.

─── ESTRATEGIA QUINIELA ───
  SeguroSiempre   : 42 pts totales (8 exactos)
  DualPickActual  : 51 pts totales (14 exactos) ← MEJOR
  StrategyOptimizer: 53 pts totales (15 exactos)
  Conservative    : 47 pts totales (10 exactos)
  Random          : 31 pts totales (5 exactos)
```

```java
public final class BacktestReport {
    public static void printFull(Path dataFile, int burnIn, boolean seedCalibrated);
    public static void printComparison(Path dataFile, int burnIn, boolean seedCalibrated,
                                       PipelineConfig configA, PipelineConfig configB);
}
```

No guarda archivos — imprime a consola. El usuario decide si quiere persistir.

---

## 8. Data directory

```
data/backtest/
  weights_<timestamp>.json
  rho_<timestamp>.json
  params_<timestamp>.csv
  qbacktest_<timestamp>.json
```

---

## 9. Archivos del proyecto

### Nuevos

| Clase | Líneas |
|---|---|
| `BacktestPipeline` | ~250 |
| `ResidualReport` (inner record) | — |
| `WeightOptimizer` | ~180 |
| `WeightEnsemble` | ~80 |
| `RhoOptimizer` | ~120 |
| `ParamOptimizer` | ~250 |
| `ProbabilityCalibrator` (extender) | ~+100 |
| `QBacktest` | ~250 |
| `BacktestReport` | ~200 |

### Modificados

| Clase | Cambio |
|---|---|
| `ProbabilityCalibrator` | +trainPlatt(), +calibratePlatt(), +trainFromBacktest() |
| `PoissonPredictor` | +setCalibrator(), +matchProbabilitiesCalibrated() |
| `HyperparameterOptimizer` | @Deprecated |

### Tests (9 clases, ~600 líneas total)

---

## 10. Dependencias

- **Ninguna nueva.** Gson, commons-math3 (PoissonDistribution, PowellOptimizer, CMAESOptimizer), JUnit 5.
