@echo off
title Football Predictor — Dashboard Mundial 2026
cd /d "%~dp0"

echo.
echo  ╔══════════════════════════════════════════════╗
echo  ║   FOOTBALL PREDICTOR — Dashboard Mundial 2026 ║
echo  ║   Iniciando...                                ║
echo  ╚══════════════════════════════════════════════╝
echo.

REM Buscar Maven (IntelliJ bundled o system)
if exist "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" (
    set MVN_CMD="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd"
) else (
    where mvn >nul 2>nul
    if %errorlevel% equ 0 (
        set MVN_CMD=mvn
    ) else (
        echo [ERROR] No se encontro Maven. Instala Maven o asegurate de tener IntelliJ.
        pause
        exit /b 1
    )
)

echo [1/3] Compilando proyecto...
call %MVN_CMD% clean compile -q
if %errorlevel% neq 0 (
    echo [ERROR] Fallo la compilacion. Revisa errores arriba.
    pause
    exit /b 1
)
echo.
echo [2/3] Compilacion exitosa. Lanzando dashboard...
echo.
echo  ╔══════════════════════════════════════════════╗
echo  ║  ?  Dashboard abierto.                        ║
echo  ║  ?  Cierra la ventana para salir.             ║
echo  ╚══════════════════════════════════════════════╝
echo.

REM Ejecutar con JavaFX via Maven plugin (maneja el module-path automaticamente)
call %MVN_CMD% javafx:run -q

if %errorlevel% neq 0 (
    echo.
    echo [INFO] Si fallo, intenta: mvn clean javafx:run
    pause
)
