package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

/**
 * Interfaz que define un JUEZ ANALISTA EXPERTO para el sistema de
 * "panel de jueces" de FootballPredictor.
 * <p>
 * Cada implementación analiza un partido desde una perspectiva diferente
 * (Elo, forma reciente, GLM del torneo, historial H2H, mercado de apuestas,
 * descanso) y emite un {@link Verdict} con su predicción y razonamiento.
 * <p>
 * Diseño POO: polimorfismo vía interfaz. Los distintos jueces se agregan
 * en {@link JudgePanel} para producir un análisis multi-perspectiva.
 */
public interface MatchJudge {

    /**
     * Nombre descriptivo del juez (ej. "Elo", "Forma", "GLM", "H2H", "Mercado").
     */
    String name();

    /**
     * Analiza el partido y emite un veredicto.
     *
     * @param homeTeam  nombre del equipo local
     * @param home      rating Elo del equipo local
     * @param awayTeam  nombre del equipo visitante
     * @param away      rating Elo del equipo visitante
     * @param homeBonus bonus de localía (EloCalculator.HOME_ADVANTAGE o 0)
     * @param stage     fase del torneo (GRUPOS, DIECISEISAVOS, etc.)
     * @return veredicto con predicción 1X2, marcador exacto, confianza y resumen
     */
    Verdict judge(String homeTeam, EloRating home,
                  String awayTeam, EloRating away,
                  double homeBonus, Stage stage);

    /**
     * Breve descripción del criterio de este juez (ej. "Análisis basado en
     * el rating Elo histórico de ambos equipos").
     */
    String reasoning();
}
