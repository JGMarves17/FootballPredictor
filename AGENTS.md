# FootballPredictor — Proyecto Vivo
> Documento de arranque de sesión. Pegar completo al iniciar cada conversación y actualizar SOLO "Contexto dinámico".
> Última actualización: 24-jun-2026. **DASHBOARD COMPLETO en `main`** (`4337e23`). 98 tests verdes. FlatLaf + Matriz 500k + Consola integrada + Pipeline desde botón.

---
## 🧭 CONTEXTO DINÁMICO — ACTUALIZAR CADA SESIÓN
| Campo | Valor |
|---|---|
| Fecha actual | [PEGAR FECHA] |
| Máquina | [CASA: `C:\Users\Administrator\IdeaProjects\FootballPredictor` / OFICINA: `C:\Users\JoseGabrielMarves\Documents\Universidad - JGMF\Proyectos - JAVA\FootballPredictor`] |
| Fase del Mundial | Jornada 3 de grupos en curso (24-jun). Eliminatorias (R32) arrancan 28-jun. |
| Branch activo | `main` |
| ¿Hice git pull? | [SÍ/NO — SIEMPRE al abrir, sobre todo al cambiar de máquina] |

⚠️ **Regla de oro:** enviar predicciones por WhatsApp ANTES del primer partido. No enviar = −3 pts + 10L/partido.
⚠️ **TRABAJO EN 2 MÁQUINAS:** SIEMPRE `git pull` al abrir y `git push` al cerrar. La oficina y la casa comparten el mismo repo.
⏰ **RECORDATORIO PENDIENTE:** activar la API key de The Odds (`a1d46a53187e24d4f564000bb9319181`) en EnsemblePredictor cuando esté lista.

---
## 🏆 CLASIFICACIÓN REAL (24-jun, jornada 3 parcial)
| Pos | Jugador | Pts |
|---|---|---|
| 1 | Rodrigo Lopez | 38 |
| 2 | Jason Avila | 36 |
| 3 | Ruben Figueroa | 33 |
| 4 | Nissy Rodriguez | 31 |
| 5 | Daniel Ortiz | 31 |
| 6 | Cristhian Brito | 28 |
| 7 | Carlos Guevara | 28 |
| 8 | Hector Cerrato | 27 |
| 9 | Alfredo Funez | 27 |
| 10 | Jose Pozadas | 27 |
| 11 | Carlos Davis | 26 |
| 12 | Daniel Rivera | 25 |
| 13 | Moises Chavarria | 25 |
| 14 | Luis Flores | 24 |
| 15 | Manuel Molina | 24 |
| 16 | Jorge Brand | 22 |
| **17** | **Gabriel Marves (YO)** | **19** |

⚠️ Voy ÚLTIMO con 19 pts. PERO: torneo a <50% jugado. Eliminatorias (28-jun→19-jul) tienen 32 partidos sin jugar con puntos x2-x4 (R32 4pts exacto, ... Final 8pts). REMONTABLE.
**Análisis honesto:** el sistema acierta ~58% resultado (lo prometido), pero el grueso de jornadas se jugó sin enviar predicciones del sistema (peleando con builds). El problema fue operativo, no del modelo. Estrategia clave para remontar: ARRIESGAR EXACTOS en partidos FIJO/FUERTE (el exacto vale x3 y es desempate #1).

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
## 🚀 MOTOR V2 — TRIPLE BLEND (en `main`)
```
λ_final = 40% Elo + 25% FIFAForm + 35% GLM  (pesos dinámicos: GLM 15→25→35% según nº partidos)
        → ajustado por TournamentConditioner (xG real WC2026)
        → Dixon-Coles ρ = -0.09 (torneo)
con odds: EnsemblePredictor α=0.15 (85% mercado)
```
### Clases del motor (`prediction/`)
- **FIFAFormCalculator** — últimos 50 partidos × importancia FIFA × decaimiento e^(-0.003×días) × calidad rival. `getForm()→FormResult`. BASELINE_GOALS=1.35.
- **TournamentConditioner** — singleton `getInstance()`. **Lee `data/xg_wc2026.json`** (46 partidos j1-j3 cargados). `attackAdjustment/defenseAdjustment/adjustLambdas/addMatch`. PRIOR=3.0. España atk~0.69, Alemania~1.4.
- **TournamentGLM** — Poisson GLM ataque/defensa, Commons Math PowellOptimizer, ridge a prior Elo. `fit()→TournamentGLM`, `lambdaHome/lambdaAway`. REG_LAMBDA=2.0.
- **ScoreMatrix** — record, 500k MC. `compute(homeTeam,home,awayTeam,away,bonus,seed[,n])`, `print()`, `topN()`, `mostLikelyScore()`, `probability(h,a)`. DEFAULT_SIMS=500_000.
- **MatchdayEngine** — `preMatchday()` 500k+JSON, `postMatchday()`. Imports: model.Score + poisson.PoissonPredictor.
- **LiveMatchUpdater** — `matchPlayed(home,away,hg,ag,xgH,xgA,homeAdv)` actualiza xG+Elo+recalibra GLM. `matchesRecorded()`.
- **PoissonPredictor** (poisson/) — `expectedGoalsElo()`, `expectedGoalsBlended()→double[]`, `scoreMatrix()` (solo-Elo, backtest), `scoreMatrixTournament()`, `matchProbabilitiesTournament()`, `setGLM()`. Alias `expectedGoals()` @Deprecated→warnings normales. DC_RHO=-0.13, DC_RHO_TOURNAMENT=-0.09.
- **EnsemblePredictor** — α=0.15 con odds.

### Quiniela (`quiniela/`)
- **MatchEV** — `rank/best/honest/top3MC/risk/bestResult` + **`dualPick(homeTeam,home,awayTeam,away,bonus)→DualPick`** (usa matriz de TORNEO: seguro=resultado más probable, exacto=marcador pico real). Risk: FIJO≥65% FUERTE≥55% DOBLE≥45% TRIPLE<45%.
- **QuinielaRunnerV2** — lógica en **`run()` público** (main solo lo llama). Pipeline: fixture→GLM (24 partidos j1)→LiveMatchUpdater→standings reales→16 rivales→preMatchday 500k→MetaSimulator P(podio)→StrategyOptimizer. Secciones a editar cada jornada: standings, matchday, nº jornada, resultados con updater.matchPlayed().
- StrategyOptimizer, QuinielaScorer, QuinielaRanking, StageDetector, JornadaOptimizer (sin cambios relevantes).

### UI (`ui/`) — DASHBOARD COMPLETO
- **MainWindow** — FlatDarkLaf (`FlatDarkLaf.setup()` en main). 4 pestañas:
    1. 🌍 Grupos & Bracket (BracketPanel)
    2. 📋 Todos los partidos — tabla con búsqueda, orden, columnas: Local/Visitante/Fecha/Grupo/Resultado/**Seguro**/**Exacto arriesgado**/**Riesgo** (usa MatchEV.dualPick, motor de torneo)
    3. 🎯 Matriz 500k — lista de partidos + mapa de calor (HeatmapView). 500k en background, cacheadas. Título HTML con colores (blanco/cyan/dorado). Salta placeholders (equipos con dígitos).
    4. 🖥️ Consola del Sistema — JTextArea que captura System.out/err redirigidos. Todo el output va aquí, NADA al CMD.
- Botones abajo: **▶ Correr Pipeline Completo** (llama QuinielaRunnerV2.run() en SwingWorker, salta a pestaña Consola, muestra TODO: matrices+WhatsApp+P(podio)) · ⚡ Generar Predicciones (llena columnas de la tabla).
- **BracketPanel** — grupos 4×3 + bracket R32. (Pendiente: mejorar cuando R32 tenga equipos reales el 28-jun.)

### Otras clases creadas por LLM paralelo (en main, NO tocadas a fondo)
MarketComparator, WhatsAppMessenger, LiveResultFetcher, PipelineRunner, ProbabilityCalibrator, HyperparameterOptimizer, FormDecay. (Revisar/integrar en próxima sesión si se necesitan.)

---
## ✅ DATOS
- **`data/xg_wc2026.json`** — formato `[home, away, homeXG, awayXG, homeGoals, awayGoals]`. 46 partidos: j1 (xG real), j2-j3 (marcadores REALES + xG PROXY estimado del marcador). ⚠️ REEMPLAZAR xG proxy de j2-j3 con RealGM real cuando se tenga.
- Resultados reales cargados: ver json. j3 parcial: Portugal 5-0 Uzbekistan, England 0-0 Ghana, Panama 0-1 Croatia, Colombia 1-0 DR Congo.

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