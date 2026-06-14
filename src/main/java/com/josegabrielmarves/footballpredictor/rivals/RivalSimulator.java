package com.josegabrielmarves.footballpredictor.rivals;

import com.josegabrielmarves.footballpredictor.model.Score;

import java.util.Random;

/**
 * Genera la predicción de marcador de un rival según su perfil.
 */
public final class RivalSimulator {

    /** Marcadores comunes para el tipo RANDOM. */
    private static final Score[] COMMON_SCORES = {
            new Score(0,0), new Score(1,0), new Score(2,0), new Score(3,0),
            new Score(0,1), new Score(1,1), new Score(2,1), new Score(3,1),
            new Score(0,2), new Score(1,2), new Score(2,2),
            new Score(0,3), new Score(1,3)
    };

    private RivalSimulator() {}

    /**
     * Predice el marcador de un partido según el perfil del rival.
     *
     * @param profile   perfil del rival
     * @param matrix    matriz de probabilidades Poisson del partido
     * @param homeTeam  nombre del equipo local
     * @param awayTeam  nombre del visitante
     * @param rng       generador de aleatoriedad
     */
    public static Score predict(RivalProfile profile, double[][] matrix,
                                String homeTeam, String awayTeam, Random rng) {
        return switch (profile.type()) {
            case CONSERVATIVE -> predictConservative(matrix, rng);
            case FAVORITE     -> predictModal(matrix);
            case FAN          -> predictFan(profile, homeTeam, awayTeam, matrix);
            case RANDOM       -> COMMON_SCORES[rng.nextInt(COMMON_SCORES.length)];
        };
    }

    /** Marcador modal (más probable) de la matriz. */
    static Score predictModal(double[][] matrix) {
        int bH = 0, bA = 0;
        double best = -1;
        for (int h = 0; h < matrix.length; h++)
            for (int a = 0; a < matrix[h].length; a++)
                if (matrix[h][a] > best) { best = matrix[h][a]; bH = h; bA = a; }
        return new Score(bH, bA);
    }

    /** Conservador: muestrea de la submatriz 3×3 (0-2 goles por lado). */
    private static Score predictConservative(double[][] matrix, Random rng) {
        double total = 0;
        for (int h = 0; h <= 2; h++)
            for (int a = 0; a <= 2; a++)
                total += matrix[h][a];
        double r = rng.nextDouble() * total, cum = 0;
        for (int h = 0; h <= 2; h++)
            for (int a = 0; a <= 2; a++) {
                cum += matrix[h][a];
                if (r <= cum) return new Score(h, a);
            }
        return new Score(1, 0);
    }

    /** Fanático: siempre apoya a su equipo; modal si su equipo no juega. */
    private static Score predictFan(RivalProfile profile, String homeTeam,
                                    String awayTeam, double[][] matrix) {
        String fav = profile.favoriteTeam();
        if (fav == null) return predictModal(matrix);
        if (fav.equalsIgnoreCase(homeTeam)) return new Score(2, 0);
        if (fav.equalsIgnoreCase(awayTeam)) return new Score(0, 2);
        return predictModal(matrix);
    }
}