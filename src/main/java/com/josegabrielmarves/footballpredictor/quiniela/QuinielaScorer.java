package com.josegabrielmarves.footballpredictor.quiniela;

/**
 * Calcula los puntos de la quiniela según la fase del torneo.
 *
 * Tabla oficial:
 * Fase          | Resultado | Exacto
 * Grupos        |     1     |   3
 * Dieciseisavos |     2     |   4
 * Octavos       |     3     |   5
 * Cuartos       |     4     |   6
 * Semifinal     |     5     |   7
 * Final         |     6     |   8
 */
public final class QuinielaScorer {

    public enum Stage {
        GRUPOS, DIECISEISAVOS, OCTAVOS, CUARTOS, SEMIFINAL, FINAL
    }

    private QuinielaScorer() {}

    /** Puntos por acertar el resultado (1X2) sin exacto. */
    public static int pointsResult(Stage stage) {
        return switch (stage) {
            case GRUPOS        -> 1;
            case DIECISEISAVOS -> 2;
            case OCTAVOS       -> 3;
            case CUARTOS       -> 4;
            case SEMIFINAL     -> 5;
            case FINAL         -> 6;
        };
    }

    /** Puntos por acertar el marcador exacto (incluye acertar el resultado). */
    public static int pointsExact(Stage stage) {
        return switch (stage) {
            case GRUPOS        -> 3;
            case DIECISEISAVOS -> 4;
            case OCTAVOS       -> 5;
            case CUARTOS       -> 6;
            case SEMIFINAL     -> 7;
            case FINAL         -> 8;
        };
    }

    /**
     * Puntos esperados para una predicción de marcador dado un modelo de probabilidades.
     *
     * EV(pts) = P(exacto) * ptsExacto + (P(resultado) - P(exacto)) * ptsResultado
     *
     * @param pExact    probabilidad de que el marcador predicho sea exacto
     * @param pResult   probabilidad de que el resultado 1X2 predicho sea correcto
     * @param stage     fase del torneo
     */
    public static double expectedPoints(double pExact, double pResult, Stage stage) {
        return pExact * pointsExact(stage)
                + (pResult - pExact) * pointsResult(stage);
    }

    /**
     * EV en lempiras por fallo (siempre negativo o cero).
     * −10 lempiras si el resultado 1X2 falla.
     *
     * @param pResult probabilidad de acertar el resultado
     */
    public static double expectedFine(double pResult) {
        return -10.0 * (1.0 - pResult);
    }

    /**
     * EV neto combinado: puntos esperados convertidos a valor + multa esperada.
     * Permite comparar marcadores candidatos en la misma escala.
     *
     * Nota: los puntos y las lempiras no son directamente comparables sin
     * conocer el valor monetario de un punto (depende del pozo y de P(posición)).
     * Esta función devuelve ambos por separado para que el optimizer decida.
     */
    public static MatchScore score(double pExact, double pResult, Stage stage) {
        return new MatchScore(
                expectedPoints(pExact, pResult, stage),
                expectedFine(pResult));
    }

    /** Par (puntosEsperados, multaEsperada) para un marcador candidato. */
    public record MatchScore(double expectedPoints, double expectedFine) {
        /** EV simplificado: puntos esperados menos el costo de la multa en "unidades de punto".
         *  Usa 10 lempiras = 1/3 punto como proxy (ajustar según el pozo real). */
        public double netEV(double lempirasPerPoint) {
            return expectedPoints + expectedFine / lempirasPerPoint;
        }
    }
}