@echo off
title Football Predictor — Crear Ejecutable Portable
cd /d "%~dp0"

echo.
echo  ╔══════════════════════════════════════════════════════╗
echo  ║  CREAR EJECUTABLE PORTABLE                          ║
echo  ║  Football Predictor — Dashboard Mundial 2026         ║
echo  ╚══════════════════════════════════════════════════════╝
echo.
echo [1/4] Compilando y empaquetando fat JAR...
call mvn clean package -DskipTests -q
if %errorlevel% neq 0 (
    echo [ERROR] Fallo el empaquetado.
    pause
    exit /b 1
)

echo [2/4] Fat JAR creado en target\FootballPredictor-1.0-SNAPSHOT.jar
echo.

echo [3/4] Creando runtime image con jlink...
call mvn javafx:jlink -q
if %errorlevel% neq 0 (
    echo.
    echo [INFO] jlink no disponible. Usando solo el fat JAR.
    echo [INFO] Puedes ejecutarlo con: java -jar target\FootballPredictor-1.0-SNAPSHOT.jar
    echo.
    echo [INFO] Para crear un EXE portable manualmente:
    echo   jpackage --input target --name FootballPredictor ^
    echo           --main-jar FootballPredictor-1.0-SNAPSHOT.jar ^
    echo           --main-class com.josegabrielmarves.footballpredictor.main.Launcher ^
    echo           --type exe --dest dist/
    pause
    exit /b 0
)

echo [4/4] Runtime image creada en target/FootballPredictor/
echo.
echo  ╔══════════════════════════════════════════════════════╗
echo  ║  ?  LISTO!                                          ║
echo  ║                                                     ║
echo  ║  Ejecutable portable:                               ║
echo  ║    target\FootballPredictor\bin\football-predictor   ║
echo  ║                                                     ║
echo  ║  Zip portable:                                      ║
echo  ║    target/FootballPredictor-portable.zip             ║
echo  ║                                                     ║
echo  ║  SIN ADMIN, SIN JAVA, SOLO DESCOMPRIMIR Y EJECUTAR  ║
echo  ╚══════════════════════════════════════════════════════╝
echo.
pause
