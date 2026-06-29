package com.josegabrielmarves.footballpredictor.main;

/**
 *  Lanzador puente para el dashboard JavaFX.
 *  <p>
 *  No extiende {@code Application}, asi que Java 26+ no exige
 *  JavaFX en el module-path al arrancar. Internamente llama a
 *  {@code Application.launch()} que carga JavaFX cuando ya estamos
 *  dentro del JVM.
 *  <p>
 *  Usa esta clase como Main-Class en el fat JAR y en run.bat.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        // Bridge: Java 26+ no exige JavaFX en module-path desde aqui
        Main.main(args);
    }
}
