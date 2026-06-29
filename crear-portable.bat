@echo off
title Football Predictor — Crear Ejecutable Portable
cd /d "%~dp0"

setlocal enabledelayedexpansion

echo.
echo  ╔══════════════════════════════════════════════════════╗
echo  ║  CREAR EJECUTABLE PORTABLE                          ║
echo  ║  Football Predictor — Dashboard Mundial 2026         ║
echo  ╚══════════════════════════════════════════════════════╝
echo.

REM Detectar Maven (IntelliJ bundled o system)
set MVN_CMD=mvn
if exist "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" (
    set MVN_CMD="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd"
)

REM Detectar Java (JDK 26+ con jpackage)
set JPACKAGE_CMD=jpackage
if exist "C:\Program Files\Eclipse Adoptium\jdk-26.0.1.8-hotspot\bin\jpackage.exe" (
    set JPACKAGE_CMD="C:\Program Files\Eclipse Adoptium\jdk-26.0.1.8-hotspot\bin\jpackage.exe"
) else (
    where jpackage >nul 2>nul
    if !errorlevel! neq 0 (
        echo [AVISO] jpackage no encontrado en el PATH.
        echo Se usara javafx:jlink como alternativa.
        echo.
    )
)

echo [1/4] Compilando y empaquetando fat JAR...
call %MVN_CMD% clean package -DskipTests -q
if %errorlevel% neq 0 (
    echo [ERROR] Fallo el empaquetado.
    pause
    exit /b 1
)
echo [OK] Fat JAR creado en target\

REM Buscar el JAR generado
set JAR_FILE=
for %%f in (target\FootballPredictor-*.jar) do set JAR_FILE=%%f
if "%JAR_FILE%"=="" (
    echo [ERROR] No se encontro el JAR en target\
    pause
    exit /b 1
)
echo [OK] %JAR_FILE%
echo.

echo [2/4] Creando ejecutable portable con jpackage...
call %MVN_CMD% dependency:copy-dependencies -DoutputDirectory=target\deps -q 2>nul

REM jpackage requiere un directorio con el JAR + dependencias
mkdir target\app 2>nul
copy "%JAR_FILE%" target\app\ >nul

%JPACKAGE_CMD% ^
    --input target\app ^
    --name "FootballPredictor" ^
    --main-jar "FootballPredictor-1.0-SNAPSHOT.jar" ^
    --main-class com.josegabrielmarves.footballpredictor.main.Launcher ^
    --type exe ^
    --dest dist ^
    --vendor "Gabriel Marves" ^
    --description "Predictor de quiniela - Mundial 2026" ^
    --app-version 2.0 ^
    --icon src\main\resources\icon.ico ^
    --java-options "--add-modules javafx.controls" 2>nul

if %errorlevel% neq 0 (
    echo.
    echo [INFO] jpackage fallo o no tiene icono. Probando sin icono...
    rmdir /s /q dist 2>nul

    %JPACKAGE_CMD% ^
        --input target\app ^
        --name "FootballPredictor" ^
        --main-jar "FootballPredictor-1.0-SNAPSHOT.jar" ^
        --main-class com.josegabrielmarves.footballpredictor.main.Launcher ^
        --type exe ^
        --dest dist ^
        --vendor "Gabriel Marves" ^
        --description "Predictor de quiniela - Mundial 2026" ^
        --app-version 2.0 ^
        --java-options "--add-modules javafx.controls"

    if !errorlevel! neq 0 (
        echo.
        echo [INFO] jpackage no disponible. Creando runtime image con jlink...
        call %MVN_CMD% javafx:jlink -q 2>nul
        if !errorlevel! neq 0 (
            echo [INFO] Usando solo el fat JAR como fallback.
            echo.
            echo  Puedes ejecutar el JAR con:
            echo    java -jar "%JAR_FILE%"
            echo.
            pause
            exit /b 0
        )
    )
)

echo.
echo  ╔══════════════════════════════════════════════════════╗
echo  ║  ?  LISTO!                                          ║
echo  ║                                                     ║
if exist "dist\FootballPredictor\FootballPredictor.exe" (
    echo  ║  ?  EJECUTABLE PORTABLE CREADO:                   ║
    echo  ║     dist\FootballPredictor\FootballPredictor.exe   ║
    echo  ║                                                     ║
    echo  ║  SIN ADMIN, SIN JAVA, SOLO EJECUTAR                ║
) else if exist "target\FootballPredictor\bin\" (
    echo  ║  ?  RUNTIME IMAGE CREADA:                          ║
    echo  ║     target\FootballPredictor\bin\football-predictor  ║
) else (
    echo  ║  ?  FAT JAR CREADO:                                 ║
    echo  ║     java -jar "%JAR_FILE%"                          ║
)
echo  ╚══════════════════════════════════════════════════════╝
echo.
pause
