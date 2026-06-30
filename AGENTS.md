# FootballPredictor — Proyecto Vivo
> Documento de arranque de sesión. Pegar completo al iniciar cada conversación y actualizar SOLO "Contexto dinámico".
> Última actualización: 29-jun-2026. **Dashboard JavaFX completo** (`1ca7994`). 5 pestañas. Motor con H2H + Descanso + ρ por fase. Tabla de Puntos editable.

---
## 🧭 CONTEXTO DINÁMICO — ACTUALIZAR CADA SESIÓN
| Campo | Valor |
|---|---|
| Fecha actual | [PEGAR FECHA] |
| Máquina | [CASA: `C:\Users\Administrator\IdeaProjects\FootballPredictor` / OFICINA: `C:\Users\JoseGabrielMarves\Documents\Universidad - JGMF\Proyectos - JAVA\FootballPredictor`] |
| Fase del Mundial | **Eliminatorias R32 completadas. R16 (Octavos) próximo.** |
| Branch activo | `main` |
| Último commit | `1ca7994` |
| ¿Hice git pull? | [SÍ/NO — SIEMPRE al abrir, sobre todo al cambiar de máquina] |

⚠️ **Regla de oro:** enviar predicciones por WhatsApp ANTES del primer partido. No enviar = −3 pts + 10L/partido.
⚠️ **TRABAJO EN 2 MÁQUINAS:** SIEMPRE `git pull` al abrir y `git push` al cerrar.
⚠️ **RestDaysFactor.initialize()** YA conectado en MainWindow.loadFixture(). No olvidar en tests.
⚠️ **DC_RHO por fase:** ρ = -0.15 (grupos), ρ = -0.09 (eliminatorias). Se resuelve automáticamente vía `rhoForStage()`.

---
## 🏆 CLASIFICACIÓN REAL (29-jun, R32 completado)
| Pos | Jugador | Pts |
|-----|---------|:---:|
| 1 | Ruben Figueroa | 73 |
| 2 | Cristhian Brito | 72 |
| 3 | Moises Chavarria | 72 |
| 4 | Daniel Ortiz | 69 |
| 5 | Hector Cerrato | 68 |
| 6 | Rodrigo Lopez | 67 |
| 7 | Jorge Brand | 65 |
| 8 | Jose Pozadas | 65 |
| 9 | Nissy Rodriguez | 64 |
| 10 | Jason Avila | 63 |
| 11 | Luis Flores | 59 |
| **12** | **Gabriel Marves** | **59** |
| 13 | Alfredo Funez | 58 |
| 14 | Manuel Molina | 57 |
| 15 | Carlos Davis | 56 |
| 16 | Daniel Rivera | 43 |
| 17 | Carlos Guevara | 0 |

📈 **Remontada:** de 19 pts (P17) a 59 pts (P12). Estrategia actual: **optimize()** (maximiza EV de premio 60/30/10 — óptimo para posición media-alta).
⚡ Diferencia con podio: ~9 pts. 100% remontable con aciertos en eliminatorias (puntos x2-x4).

---
## 🎯 REGLAS QUINIELA
**Puntos:** Grupos 1/3 · Dieciseisavos 2/4 · Octavos 3/5 · Cuartos 4/6 · Semi 5/7 · Final 6/8 (resultado/exacto).
**Multas:** 10L por resultado no acertado. No enviar = −3 pts + 10L/partido. Pago deuda 24h o +5L.
**Premios:** 1° 60% · 2° 30% · 3° 10% del pozo.
**Desempates:** 1° más exactos → 2° más pts eliminatorias → 3° predicción más cercana a la Final.
**17 participantes.** Liga de puntos acumulados (NO jornada única).

---
## ⚙️ ENTORNO
- **CASA:** Temurin JDK 26.0.1+8, IntelliJ 2026.1.3, admin SÍ. `C:\Users\Administrator\IdeaProjects\FootballPredictor`
- **OFICINA:** openjdk-26.0.1, IntelliJ 2025.3.4. `C:\Users\JoseGabrielMarves\Documents\Universidad - JGMF\Proyectos - JAVA\FootballPredictor`
- Repo: https://github.com/JGMarves17/FootballPredictor — branch `main`, último commit `4337e23`
- **NO usar VS Code** (sin Maven). Usar IntelliJ.
- `pom.xml`: Java 25 + Gson 2.10.1 + **flatlaf 3.5.4** + commons-math3 3.6.1 + JUnit 5.11
- PowerShell: para UTF-8 correr `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`. Usar `Get-Content -Encoding UTF8`, NO `type`. No hay `grep`; usar `New-Item` para archivos vacíos.
- Push/merge SIEMPRE manual por el usuario (el entorno Claude no tiene credenciales).

---
## 🚀 MOTOR — TRIPLE BLEND (en `main`)
```
λ_final = 40% Elo + 25% FIFAForm + 35% GLM  (pesos dinámicos: GLM 15→25→35% según nº partidos)
        → ajustado por TournamentConditioner (xG real WC2026)
        → Dixon-Coles ρ por fase (-0.15 grupos, -0.09 eliminatorias)
        → HeadToHeadFactor (historial enfrentamientos directos)
        → RestDaysFactor (diferencia de descanso entre equipos)
con odds: EnsemblePredictor α=0.15 (85% mercado)
```
### Clases del motor (`prediction/`)
- **FIFAFormCalculator** — últimos 50 partidos × importancia FIFA × decaimiento e^(-0.003×días) × calidad rival. `getForm()→FormResult`. BASELINE_GOALS=1.35.
- **TournamentConditioner** — singleton `getInstance()`. **Lee `data/xg_wc2026.json`** (46 partidos j1-j3 cargados). `attackAdjustment/defenseAdjustment/adjustLambdas/addMatch`. PRIOR=1.5 (mayor peso al xG del torneo).
- **TournamentGLM** — Poisson GLM ataque/defensa, Commons Math PowellOptimizer, ridge a prior Elo. `fit()→TournamentGLM`, `lambdaHome/lambdaAway`. REG_LAMBDA=2.0.
- **ScoreMatrix** — record, 500k MC. `compute(homeTeam,home,awayTeam,away,bonus,seed[,n])`, `print()`, `topN()`, `mostLikelyScore()`, `probability(h,a)`. DEFAULT_SIMS=500_000.
- **MatchdayEngine** — `preMatchday()` 500k+JSON, `postMatchday()`. Imports: model.Score + poisson.PoissonPredictor.
- **LiveMatchUpdater** — `matchPlayed(home,away,hg,ag,xgH,xgA,homeAdv)` actualiza xG+Elo+recalibra GLM. `matchesRecorded()`.
- **PoissonPredictor** (poisson/) — `expectedGoalsElo()`, `expectedGoalsBlended()→double[]`, `scoreMatrix()`, `scoreMatrixTournament()`, `matchProbabilitiesTournament()`, `setGLM()`. `rhoForStage()` = -0.15 grupos, -0.09 eliminatorias. Pesos dinámicos según diferencia Elo, confianza forma, y confianza H2H.
- **EnsemblePredictor** — α=0.15 con odds.
- **HeadToHeadFactor** (NUEVO) — Lee `data/results.json`. Calcula factor multiplicativo para λ según historial de enfrentamientos directos. Smoothing progresivo, time decay.
- **RestDaysFactor** (NUEVO) — Factor ±12% según diferencia de días de descanso entre equipos. 3% por día. Inicializado en MainWindow.loadFixture().

### Quiniela (`quiniela/`)
- **MatchEV** — `rank/best/honest/top3MC/risk/bestResult` + **`dualPick(homeTeam,home,awayTeam,away,bonus)→DualPick`** (usa matriz de TORNEO: seguro=resultado más probable, exacto=marcador pico real). Risk: FIJO≥65% FUERTE≥55% DOBLE≥45% TRIPLE<45%. **`dualPickWithMarket()`** integra odds de mercado.
- **QuinielaRunnerV2** — Pipeline: fixture→GLM→LiveMatchUpdater→standings reales→16 rivales→preMatchday 500k→MetaSimulator P(podio)→StrategyOptimizer. Estrategia: `optimize()` (EV premio 60/30/10).
- **QuinielaRunnerR32** — Runner específico para R32 con `optimize()` (cambiado desde ALL-IN).
- StrategyOptimizer, QuinielaScorer, QuinielaRanking, StageDetector, JornadaOptimizer.

### UI (`ui/`) — DASHBOARD COMPLETO (JavaFX)
- **MainWindow** — `BorderPane` con JavaFX. 5 pestañas:
    1. 🌍 Grupos & Bracket (BracketView — scroll horizontal AS_NEEDED, minWidth 1650, auto-refresh 6h)
    2. 📋 Todos los partidos — TableView con búsqueda, 8 columnas: Local/Visitante/Fecha/Grupo/Resultado/**Seguro**/**Exacto arriesgado**/**Riesgo** (usa MatchEV.dualPickWithMarket)
    3. 🎯 Matriz 500k — ListView + HeatmapView (6×6 canvas). 500k MC en background, cacheadas.
    4. 📱 WhatsApp Preview — TextArea con mensaje formateado (mercado + recomendaciones + P(exacto≥8%))
    5. 🏆 Tabla de Puntos — StandingsPane editable con JSON persistente, 6 columnas, highlight fila propia
- **AppTheme** — 5 temas seleccionables: Verde Cancha, Noche Azul, Rey Púrpura, Fuego, Carbón. Todos los colores son métodos dinámicos.
- Botones abajo: ▶ Correr Pipeline · ⚡ Generar Predicciones · 📱 Enviar a WhatsApp.
- **BracketView** — Grupos 4×3 + bracket R32→R16→QF→SF→FINAL con datos en vivo. Auto-refresh cada 6h desde openfootball API.
- **StandingsPane** — NUEVO: tabla de posiciones editable, guarda/lee JSON, resalta "Gabriel Marves" en dorado.

### Otras clases
MarketComparator, WhatsAppMessenger, LiveResultFetcher, PipelineRunner, ProbabilityCalibrator, HyperparameterOptimizer, FormDecay, HeadToHeadFactor, RestDaysFactor.

---
## ✅ DATOS
- **`data/xg_wc2026.json`** — formato `[home, away, homeXG, awayXG, homeGoals, awayGoals]`. 46 partidos: j1 (xG real), j2-j3 (marcadores REALES + xG PROXY estimado del marcador). ⚠️ REEMPLAZAR xG proxy de j2-j3 con RealGM real cuando se tenga.
- `data/results.json` — 310KB de partidos históricos. Usado por FIFAFormCalculator y HeadToHeadFactor.
- `data/quiniela_standings.json` — NUEVO: tabla de posiciones persistente con 17 jugadores. Editabla desde UI.

---
## 🔜 PENDIENTES (orden de prioridad)
| Pri | Tarea |
|---|---|
| 🔴 | **28-jun:** cuando se definan cruces R32, configurar matchday de eliminatorias en QuinielaRunnerV2 (¡aquí está el grueso de puntos!). Enviar al WhatsApp antes del 1er partido. |
| 🔴 | Completar jornada 3 (faltan partidos del 24-26 jun): agregar resultados + xG con updater.matchPlayed() |
| 🟡 | Reemplazar xG PROXY de j2-j3 con xG REAL de soccer.realgm.com en xg_wc2026.json |
| 🟡 | Activar API key The Odds en EnsemblePredictor (señal de mercado 85% = mayor accuracy) |
| 🟢 | Mejorar BracketPanel cuando R32 tenga equipos reales |
| 🟢 | Revisar/integrar clases del LLM paralelo (MarketComparator, WhatsAppMessenger, etc.) |

---
## ⚠️ NOTAS TÉCNICAS CRÍTICAS
- **dualPick** usa matriz de TORNEO (xG+GLM), no solo-Elo. Por eso ya NO da "2-0 a todo". Seguro=resultado dominante, Exacto=pico absoluto de la matriz.
- Consola del Sistema: System.out/err redirigidos a JTextArea. Funciona al correr MainWindow. QuinielaRunnerV2 standalone (consola) sigue yendo al CMD.
- Pipeline desde botón: pestaña índice 3 (Consola). Tarda segundos (500k×4 + Meta + Strategy).
- PoissonPredictor: alias expectedGoals() @Deprecated → 5 warnings normales, NO errores.
- ScoreMatrix.compute firma: (homeTeam, home, awayTeam, away, bonus, seed) o (..., seed, n).
- CalibratedEloRatings.getRating()→EloRating (NO double, usar .rating()).
- Score → homeGoals()/awayGoals(). UpdatedRatings → .home()/.away().
- StandingsSimulator.US="Nosotros", 17 participantes.
- Brier multiclase [0,2]. Accuracy honesta 58.1%.
- 98 tests verdes. commons-math3 + flatlaf OBLIGATORIOS (Maven Reload tras tocar pom).
- Probabilidades de marcador exacto: máximo realista ~15%. Bajo NO es bug, es el azar del fútbol.