package com.josegabrielmarves.footballpredictor.api;

import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.model.Score;

/**
 * Convierte MatchDTO → Match (modelo de dominio limpio).
 */
public class MatchMapper {

    public Match toMatch(MatchDTO dto) {
        String homeTeam = dto.homeTeam != null ? dto.homeTeam.name : "Desconocido";
        String awayTeam = dto.awayTeam != null ? dto.awayTeam.name : "Desconocido";
        String date     = dto.utcDate != null ? dto.utcDate : "";
        String status   = dto.status != null ? dto.status : "";

        Score score = null;
        if (dto.score != null && dto.score.fullTime != null
                && dto.score.fullTime.home != null
                && dto.score.fullTime.away != null) {
            score = new Score(dto.score.fullTime.home, dto.score.fullTime.away);
        }

        String winner = null;
        if (dto.score != null) {
            winner = dto.score.winner;
        }

        return new Match(dto.id, homeTeam, awayTeam, date, status, score, winner);
    }
}