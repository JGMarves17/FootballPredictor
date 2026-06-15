package com.josegabrielmarves.footballpredictor.quiniela;

import java.util.Map;

/**
 * Reglas oficiales de clasificación de la quiniela, incluidos los DESEMPATES.
 *
 * Criterio principal: más puntos.
 * Desempates (en este orden):
 *   1. Más marcadores exactos acertados (en todo el torneo).
 *   2. Más puntos en fases eliminatorias.
 *   3. Predicción más cercana al marcador de la final (menor error).
 *
 * Helper compartido por StandingsSimulator y MetaSimulator para que ambos
 * apliquen EXACTAMENTE las mismas reglas (evita que la lógica diverja).
 */
public final class QuinielaRanking {

    private QuinielaRanking() {}

    /**
     * Acumulado de un jugador durante la simulación.
     * Mutable a propósito: se va sumando partido a partido.
     */
    public static final class Tally {
        /** Criterio principal. */
        public int points;
        /** Desempate 1: marcadores exactos acertados. */
        public int exactScores;
        /** Desempate 2: puntos obtenidos en fases eliminatorias. */
        public int knockoutPoints;
        /**
         * Desempate 3: error en el marcador de la final (|Δlocal| + |Δvisita|).
         * Menor = más cerca. MAX_VALUE = la final aún no se jugó / sin predicción.
         */
        public int finalError = Integer.MAX_VALUE;

        public Tally() {}

        public Tally(int points) { this.points = points; }
    }

    /**
     * ¿'a' va estrictamente por delante de 'b' según puntos + desempates oficiales?
     */
    public static boolean isBetter(Tally a, Tally b) {
        if (a.points != b.points)                 return a.points > b.points;
        if (a.exactScores != b.exactScores)       return a.exactScores > b.exactScores;
        if (a.knockoutPoints != b.knockoutPoints) return a.knockoutPoints > b.knockoutPoints;
        return a.finalError < b.finalError; // más cerca de la final gana
    }

    /**
     * Posición de 'us' (1 = primero) aplicando los desempates.
     * Cuenta cuántos jugadores van estrictamente por delante.
     */
    public static int rankOf(Map<String, Tally> tallies, String us) {
        Tally ours = tallies.get(us);
        int better = 0;
        for (Map.Entry<String, Tally> e : tallies.entrySet()) {
            if (!e.getKey().equals(us) && isBetter(e.getValue(), ours)) better++;
        }
        return better + 1;
    }
}
