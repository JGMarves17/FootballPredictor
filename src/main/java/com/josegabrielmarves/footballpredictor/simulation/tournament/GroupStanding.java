package com.josegabrielmarves.footballpredictor.simulation.tournament;

/**
 * Standing de un equipo al final de la fase de grupos (una simulación).
 * Implementa Comparable para ordenar: puntos → DG → GF → nombre (desempate
 * determinista; empates exactos son raros en MC y el impacto en probabilidades
 * es negligible).
 */
public record GroupStanding(
        String teamName,
        int points,
        int gf,
        int ga,
        int wins,
        int draws,
        int losses
) implements Comparable<GroupStanding> {

    public int goalDiff() { return gf - ga; }
    public int played()   { return wins + draws + losses; }

    /** Mayor puntuación = mejor posición (orden descendente). */
    @Override
    public int compareTo(GroupStanding other) {
        if (other.points != this.points)     return other.points - this.points;
        if (other.goalDiff() != this.goalDiff()) return other.goalDiff() - this.goalDiff();
        if (other.gf != this.gf)             return other.gf - this.gf;
        return this.teamName.compareTo(other.teamName); // desempate determinista
    }
}