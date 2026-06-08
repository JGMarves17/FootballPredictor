package com.josegabrielmarves.footballpredictor.api;

import java.util.List;

/**
 * DTO raíz que envuelve la lista de partidos del response JSON.
 */
public class MatchesResponseDTO {
    public List<MatchDTO> matches;
}