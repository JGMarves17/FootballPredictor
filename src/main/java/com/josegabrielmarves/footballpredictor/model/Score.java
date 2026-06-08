package com.josegabrielmarves.footballpredictor.model;

/**
 * Representa el marcador de un partido.
 * Inmutable por diseño — record de Java 25.
 */
public record Score(int homeGoals, int awayGoals) {

    public Score {
        if (homeGoals < 0 || awayGoals < 0) {
            throw new IllegalArgumentException("Los goles no pueden ser negativos.");
        }
    }

    @Override
    public String toString() {
        return homeGoals + " - " + awayGoals;
    }
}