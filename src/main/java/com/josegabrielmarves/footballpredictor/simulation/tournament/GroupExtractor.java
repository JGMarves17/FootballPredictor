package com.josegabrielmarves.footballpredictor.simulation.tournament;

import com.josegabrielmarves.footballpredictor.model.Match;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Extrae los grupos de fase de grupos desde el fixture cargado por
 * OpenFootballProvider. Filtra partidos cuyo campo {@code match.group}
 * sea no nulo (los partidos de eliminatorias tienen group == null).
 */
public final class GroupExtractor {

    private GroupExtractor() {}

    /**
     * @param matches todos los partidos del torneo (fixture completo)
     * @return mapa groupName → lista de partidos de ese grupo
     */
    public static Map<String, List<Match>> extractGroups(List<Match> matches) {
        return matches.stream()
                .filter(m -> m.group != null && !m.group.isBlank())
                .collect(Collectors.groupingBy(m -> m.group,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * Nombres de los 4 equipos de un grupo (en orden de aparición en el fixture).
     */
    public static List<String> teamsInGroup(List<Match> groupMatches) {
        List<String> teams = new ArrayList<>();
        for (Match m : groupMatches) {
            if (!teams.contains(m.homeTeam)) teams.add(m.homeTeam);
            if (!teams.contains(m.awayTeam)) teams.add(m.awayTeam);
        }
        return teams;
    }
}