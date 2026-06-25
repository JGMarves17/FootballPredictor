param(
    [string]$Action = "all",
    [int]$Jornada = 3
)

# FootballPredictor - Pipeline Automatizado
# Acciones:
#   all          - Pipeline completo (default)
#   predictions  - Solo generar predicciones
#   backtest     - Correr backtest honesto
#   gridsearch   - Optimización de hiperparámetros
#   calibrate    - Mostrar factores de calibración
#   market       - Comparación modelo vs mercado

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $ProjectRoot

# Usar Maven de IntelliJ (ajustar si tu ruta es distinta)
$mvn = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd"
if (!(Test-Path -LiteralPath $mvn)) {
    # Buscar en otras rutas comunes
    $altPaths = @(
        "$env:IDEA_HOME\plugins\maven\lib\maven3\bin\mvn.cmd",
        "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd"
    )
    foreach ($p in $altPaths) {
        if (Test-Path -LiteralPath $p) { $mvn = $p; break }
    }
}

Write-Host "╔══════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║  FOOTBALL PREDICTOR - PIPELINE AUTOMATIZADO  ║" -ForegroundColor Cyan
Write-Host "║  $(Get-Date -Format 'yyyy-MM-dd HH:mm')                     ║" -ForegroundColor Cyan
Write-Host "╚══════════════════════════════════════════════╝" -ForegroundColor Cyan

$mvnArgs = @("-q", "compile", "exec:java")

switch ($Action.ToLower()) {
    "predictions" {
        Write-Host "[ACCION] Generar predicciones para jornada $Jornada" -ForegroundColor Yellow
        & $mvn compile exec:java "-Dexec.mainClass=com.josegabrielmarves.footballpredictor.quiniela.QuinielaRunnerV2" -q
    }
    "backtest" {
        Write-Host "[ACCION] Correr backtest walk-forward (dataset results.json)" -ForegroundColor Yellow
        & $mvn compile exec:java "-Dexec.mainClass=com.josegabrielmarves.footballpredictor.prediction.backtest.WorldCupBacktest" -q
    }
    "gridsearch" {
        Write-Host "[ACCION] Grid search de hiperparámetros" -ForegroundColor Yellow
        & $mvn compile exec:java "-Dexec.mainClass=com.josegabrielmarves.footballpredictor.prediction.backtest.HyperparameterOptimizer" -q
    }
    "calibrate" {
        Write-Host "[ACCION] Mostrar factores de calibración" -ForegroundColor Yellow
        & $mvn compile exec:java "-Dexec.mainClass=com.josegabrielmarves.footballpredictor.prediction.ProbabilityCalibrator" -q
    }
    "market" {
        Write-Host "[ACCION] Comparación modelo vs mercado" -ForegroundColor Yellow
        & $mvn compile exec:java "-Dexec.mainClass=com.josegabrielmarves.footballpredictor.prediction.MarketComparator" -q
    }
    "reload-xg" {
        Write-Host "[ACCION] Recargar datos xG desde JSON" -ForegroundColor Yellow
        & $mvn compile exec:java "-Dexec.mainClass=com.josegabrielmarves.footballpredictor.prediction.TournamentConditioner" -q
    }
    "pipeline" {
        Write-Host "[ACCION] Pipeline completo (PipelineRunner)" -ForegroundColor Yellow
        & $mvn compile exec:java "-Dexec.mainClass=com.josegabrielmarves.footballpredictor.prediction.PipelineRunner" -q
    }
    "ui" {
        Write-Host "[ACCION] Lanzar interfaz gráfica JavaFX" -ForegroundColor Yellow
        & $mvn javafx:run
    }
    "all" {
        Write-Host "`n=== FASE 1: COMPILAR ===" -ForegroundColor Green
        & $mvn compile
        if ($LASTEXITCODE -ne 0) {
            Write-Host "ERROR: Compilación fallida" -ForegroundColor Red
            exit 1
        }
        Write-Host "OK" -ForegroundColor Green

        Write-Host "`n=== FASE 2: BACKTEST ===" -ForegroundColor Green
        & $mvn compile exec:java "-Dexec.mainClass=com.josegabrielmarves.footballpredictor.prediction.backtest.WorldCupBacktest" -q

        Write-Host "`n=== FASE 3: PREDICCIONES ===" -ForegroundColor Green
        & $mvn compile exec:java "-Dexec.mainClass=com.josegabrielmarves.footballpredictor.quiniela.QuinielaRunnerV2" -q

        Write-Host "`n=== COMPLETADO ===" -ForegroundColor Green
        Write-Host "No olvides enviar las predicciones por WhatsApp antes del primer partido!" -ForegroundColor Yellow
    }
    default {
        Write-Host "Acción desconocida: $Action" -ForegroundColor Red
        Write-Host "Usa: predictions, backtest, gridsearch, calibrate, market, reload-xg, pipeline, ui, all"
    }
}
