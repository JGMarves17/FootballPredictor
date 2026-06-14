package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Match;

/**
 * Detecta la fase de la quiniela (Stage) a partir de un partido del fixture.
 * Usa match.group (grupos) y match.status/round (eliminatorias).
 */
public final class StageDetector {

    private StageDetector() {}

    /**
     * Devuelve la Stage correcta para un partido según su ronda en el fixture.
     *
     * @param match partido del fixture 2026
     */
    public static QuinielaScorer.Stage detect(Match match) {
        // Fase de grupos: tiene group != null
        if (match.group != null) return QuinielaScorer.Stage.GRUPOS;

        // Eliminatorias: usar el campo status (que en openfootball contiene el round)
        String round = match.status != null ? match.status.toLowerCase() : "";

        if (round.contains("round of 32"))         return QuinielaScorer.Stage.DIECISEISAVOS;
        if (round.contains("round of 16"))         return QuinielaScorer.Stage.OCTAVOS;
        if (round.contains("quarter"))             return QuinielaScorer.Stage.CUARTOS;
        if (round.contains("semi"))                return QuinielaScorer.Stage.SEMIFINAL;
        if (round.contains("final"))               return QuinielaScorer.Stage.FINAL;
        if (round.contains("third"))               return QuinielaScorer.Stage.SEMIFINAL; // 3er lugar

        // Fallback seguro
        return QuinielaScorer.Stage.GRUPOS;
    }
}