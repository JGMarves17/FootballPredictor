package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;

/**
 * Veredicto emitido por un juez analista ({@link MatchJudge}) para un partido.
 *
 * @param judgeName  nombre del juez que emite el veredicto
 * @param result     predicción 1XZ: "1" (local), "X" (empate), "2" (visitante)
 * @param exactScore marcador exacto que recomienda
 * @param confidence nivel de confianza en el resultado [0.0, 1.0]
 * @param summary    razonamiento humano legible (con datos reales observados)
 */
public record Verdict(
        String judgeName,
        String result,
        Score exactScore,
        double confidence,
        String summary
) {

    public Verdict {
        if (judgeName == null || judgeName.isBlank()) {
            throw new IllegalArgumentException("judgeName no puede ser nulo ni vacío");
        }
        if (result == null || (!result.equals("1") && !result.equals("X") && !result.equals("2"))) {
            throw new IllegalArgumentException("result debe ser '1', 'X' o '2', got: " + result);
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence debe estar en [0,1], got: " + confidence);
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary no puede ser nulo ni vacío");
        }
    }

    @Override
    public String toString() {
        return String.format("[%s] %s (conf=%.0f%%) — %s — %s",
                judgeName, result, confidence * 100, exactScore, summary);
    }
}
