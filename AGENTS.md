# FootballPredictor — Proyecto Vivo
> Documento de arranque de sesión. Pegar completo al iniciar cada conversación y actualizar SOLO "Contexto dinámico".
> Última actualización: 19-jun-2026. **MOTOR V2 EN BRANCH `motor-v2`** (sin merge aún). Build compilando con warnings de @Deprecated (no errores).

---

## 🧭 CONTEXTO DINÁMICO — ACTUALIZAR CADA SESIÓN
| Campo | Valor |
|---|---|
| Fecha actual | [PEGAR FECHA] |
| Máquina | CASA (`C:\Users\Administrator\IdeaProjects\FootballPredictor`) |
| Fase del Mundial | EN CURSO. Jornada 2 completa (18-jun). |
| Próxima jornada | Jornada 3 [confirmar partidos y deadline] |
| Branch activo | `motor-v2` (NO mergeado a main) |
| ¿Hice git pull/push? | [SÍ/NO] |

⚠️ **Regla de oro:** enviar predicciones por WhatsApp ANTES del primer partido. No enviar = −3 pts + 10L por partido.
⚠️ **PENDIENTE jornada 18-jun:** olvidé enviar — penalización aplicada.

---

## 🏆 CLASIFICACIÓN REAL (18-jun, jornada 2 completa)
| Pos | Jugador | Pts |
|---|---|---|
| 1 | Rodrigo Lopez | 28 |
| 2 | Daniel Ortiz | 24 |
| 3 | Nissy Rodriguez | 23 |
| 4 | Ruben Figueroa | 22 |
| 5 | Jason Avila | 22 |
| 6 | Cristhian Brito | 20 |
| 7 | Carlos Guevara | 20 |
| 8 | Luis Flores | 17 |
| 9 | Manuel Molina | 17 |
| **10** | **Gabriel Marves (YO)** | **17** |
| 11 | Alfredo Funez | 16 |
| 12 | Carlos Davis | 16 |
| 13 | Jose Pozadas | 15 |
| 14 | Daniel Rivera | 14 |
| 15 | Moises Chavarria | 14 |
| 16 | Hector Cerrato | 13 |
| 17 | Jorge Brand | 11 |

**Estoy 10°, a 6 pts del podio (3° = 23 pts). Quedan ~80 partidos (~222 pts disponibles).**
**Objetivo: TOP 3.** P(podio) estimado realista: ~30-40% (correr MetaSimulator para número exacto).

---

## 🎯 REGLAS QUINIELA
**Puntos:** Grupos 1/3 · Dieciseisavos 2/4 · Octavos 3/5 · Cuartos 4/6 · Semi 5/7 · Final 6/8 (resultado/exacto).
**Multas:** 10L por resultado no acertado. No enviar = −3 pts + 10L/partido. Pago deuda en 24h o +5L.
**Premios:** 1° 60% · 2° 30% · 3° 10% del pozo.
**Desempates:** 1° más exactos → 2° más pts eliminatorias → 3° predicción más cercana a la Final.
**17 participantes.** Liga de puntos acumulados (NO jornada única).

---

## ⚙️ ENTORNO
| | CASA |
|---|---|
| JDK | Temurin 26.0.1+8 (`C:\Program Files\Eclipse Adoptium\`) |
| IDE | IntelliJ 2026.1.3 (NO usar VS Code — sin Maven) |
| Admin | SÍ |
| Repo | `C:\Users\Administrator\IdeaProjects\FootballPredictor` |

- Repo: https://github.com/JGMarves17/FootballPredictor
- **`main`: `6916d58`** · **`motor-v2`: `58f60eb`** (8 commits sin merge)
- `pom.xml`: Java 25 + Gson 2.10.1 + JUnit 5.11 + **commons-math3 3.6.1** (NUEVO)
- PowerShell sin grep. `New-Item ruta\Archivo.java` para crear vacíos.
- Push desde entorno Claude NO funciona (sin credenciales) — usuario hace push/merge manual.

---

## 🚀 MOTOR V2 — TRIPLE BLEND (branch `motor-v2`)

### Fórmula del motor
```
λ_final = 40% Elo + 25% FIFAForm + 35% GLM    (pesos dinámicos)
        → ajustado por TournamentConditioner (xG real WC2026)
        → Dixon-Coles ρ = -0.09 (calibrado torneo)
con odds disponibles: EnsemblePredictor α=0.15 (85% mercado)
```

### Clases nuevas (paquete `prediction/`)
- **`FIFAFormCalculator`** — últimos 50 partidos ponderados: importancia FIFA (amistoso 15 → Final 60) × decaimiento e^(-0.003×días) × calidad rival. `getForm(team, dataFile, today) → FormResult(attackFactor, defenseFactor, matchesUsed, avgImportance)`. BASELINE_GOALS=1.35.
- **`TournamentConditioner`** — singleton `getInstance()`. xG real WC2026 hardcodeado (24 partidos j1). España attack~0.69 (2.29xG→0 goles), Alemania~1.4 (7 goles). `attackAdjustment()`, `defenseAdjustment()`, `adjustLambdas()`, `addMatch()`. PRIOR=3.0.
- **`TournamentGLM`** — Poisson GLM ataque/defensa separados, Apache Commons Math PowellOptimizer, regularización ridge hacia prior Elo. `fit(matches, ratings) → TournamentGLM`. `lambdaHome()`, `lambdaAway()`. REG_LAMBDA=2.0. Pesos GLM: 15%→25%→35% según nº partidos.
- **`ScoreMatrix`** — record con 500k simulaciones MC. `compute(homeTeam, home, awayTeam, away, bonus, seed[, n])`. matrix, pHomeWin/Draw/AwayWin, top5, `print()` visual 7×7. DEFAULT_SIMS=500_000.
- **`MatchdayEngine`** — `preMatchday()` 500k por partido + JSON en data/predictions/jornada_N.json. `postMatchday()` actualiza Elo + reporte accuracy. Records MatchInput, MatchResult. **IMPORTS críticos: Score + PoissonPredictor.**
- **`LiveMatchUpdater`** — auto-update tras cada partido: `matchPlayed(home, away, hg, ag, xgH, xgA, homeAdv)` → actualiza xG + Elo + re-calibra GLM. Versión simplificada sin xG (proxy). `matchesRecorded()`.

### Clases modificadas
- **`PoissonPredictor`** (poisson/) — triple-blend. `expectedGoalsElo()`, `expectedGoalsBlended()→double[]`, `scoreMatrix()` (solo-Elo, backtest compat), `scoreMatrixTournament()`, `setGLM()`. MAX_LAMBDA=5.0, MAX_GOALS=9, PoissonDistribution de Commons Math. **Alias `expectedGoals()` @Deprecated (compat tests) — genera warnings, NO errores.** DC_RHO=-0.13 (backtest), DC_RHO_TOURNAMENT=-0.09.
- **`EnsemblePredictor`** — α=0.15 cuando hay odds (85% mercado), α=1.0 sin odds. ALPHA_WITH_ODDS=0.15.
- **`pom.xml`** — agregado commons-math3 3.6.1.

### Punto de entrada
- **`QuinielaRunnerV2`** (quiniela/) — flujo completo: carga fixture, aplica 24 resultados j1, calibra GLM con 24 partidos, LiveMatchUpdater, standings reales, 16 perfiles rivales, MatchdayEngine.preMatchday 500k, MetaSimulator P(podio), StrategyOptimizer. Secciones a actualizar cada jornada: RESULTADOS / xG / CLASIFICACIÓN / PARTIDOS / nº jornada.

### Tests nuevos (6 archivos, src/test/.../prediction/)
FIFAFormCalculatorTest(6) · ScoreMatrixTest(7) · MatchdayEngineTest(2) · TournamentConditionerTest(5) · TournamentGLMTest(4) · LiveMatchUpdaterTest(3).

---

## ✅ SISTEMA BASE (ya en main, fases 1-13)
- **Elo:** `CalibratedEloRatings.getRating(name) → EloRating` (NO double, usar `.rating()`). K_WORLD_CUP=55, HOME_ADVANTAGE=75. 48 equipos.
- **Poisson+DC:** scoreMatrix, matchProbabilities, mostLikelyScore.
- **Backtest:** HONESTO 58.1% (sin fuga) · CALIBRADO 62.8% (con fuga). Brier multiclase [0,2]. Usar 58.1% para comunicar.
- **Simulación:** MonteCarloSimulator.sample(), GroupSimulator.sampleScore() (public static), TournamentSimulator (R32→Final), SimulationRunner (España 17.4%, Argentina 16.5%, Francia 14.1%).
- **Quiniela:** QuinielaScorer (Stage enum), MatchEV (rank/best/honest/top3MC/risk), StageDetector, QuinielaRanking (desempates), StrategyOptimizer (dados comunes, P(podio)), JornadaOptimizer.
- **Rivales:** RivalProfile (CONSERVATIVE/FAVORITE/FAN/RANDOM), RivalSimulator, StandingsSimulator (US="Nosotros", 17 jugadores), MetaSimulator.
- **APIs:** OpenFootballProvider (fixture), OddsProvider (The Odds API key `a1d46a53187e24d4f564000bb9319181`), LiveStandingsProvider (worldcup26.ir).
- **UI:** MainWindow (2 pestañas: Grupos&Bracket + Todos los partidos), BracketPanel (grupos 4×3 + bracket R32). Tabla con búsqueda y orden por columna.
- **Docs:** README.md completo.

---

## 📊 COMPARACIÓN MODELO vs MERCADO (19-jun, validado manualmente)
| Partido | Modelo | Mercado | Veredicto |
|---|---|---|---|
| USA vs Australia | 2-1, 57% local | 56.6% local (idéntico) | ✅ Coincide perfecto |
| Brazil vs Haiti | 2-0 | Brasil -800 (~89%), goleada | ⚠️ Modelo conservador (xG) |
| Scotland vs Morocco | 1-1 empate | Marruecos -130 favorito | ❌ Modelo falló (mucho a Escocia) |
| Türkiye vs Paraguay | 1-1 | Parejo | — |

**Lección:** confirma valor de α=0.15. Con odds, seguir mercado 85%. **PENDIENTE: clase `MarketComparator`** (tabla + recomendación + ensemble final) — solicitada, NO implementada aún.

---

## 🔜 PENDIENTES
| Prioridad | Tarea |
|---|---|
| 🔴 | Terminar build motor-v2 (compila con warnings) → correr tests (esperado ~84 verdes) |
| 🔴 | Configurar partidos jornada 3 en QuinielaRunnerV2 → correr → WhatsApp |
| 🔴 | Commit + push motor-v2 → merge a main |
| 🟡 | Implementar `MarketComparator` (tabla modelo vs mercado + recomendación + ensemble) |
| 🟡 | Agregar resultados+xG jornada 2 con updater.matchPlayed() (xG de soccer.realgm.com) |
| 🟢 | Cada noche: agregar resultados del día con LiveMatchUpdater |

---

## ⚠️ NOTAS TÉCNICAS CRÍTICAS
- **MatchdayEngine** necesita imports `model.Score` y `poisson.PoissonPredictor`.
- **PoissonPredictor** alias `expectedGoals()` @Deprecated → warnings normales, NO errores. Balance llaves 26/26, 210 líneas.
- `CalibratedEloRatings.getRating()` → EloRating, NO double.
- `UpdatedRatings` → `.home()`/`.away()`. `Score` → `homeGoals()`/`awayGoals()`.
- `GroupSimulator.sampleScore` → public static.
- `StandingsSimulator.US = "Nosotros"`, 17 participantes.
- Brier multiclase [0,2]. Accuracy honesta 58.1%.
- xG en soccer.realgm.com tras cada partido.
- commons-math3 OBLIGATORIO antes de compilar (Maven Reload).
- IntelliJ: Ctrl+Shift+F10 corre archivo activo. Ctrl+F9 build.
- Push/merge SIEMPRE manual por el usuario.
- branch motor-v2: 8 commits (01fdbea, 35d4a47, 8c2fb4a, 8be8829, 9680a48, 58f60eb + 2 previos dc5e5a3, 6e211eb).