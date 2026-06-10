package com.josegabrielmarves.footballpredictor.prediction.elo;

/**
 * Rating Elo de una selección. Inmutable: las actualizaciones producen
 * nuevas instancias (mismo patrón que {@code Score}).
 */
public record EloRating(String teamName, double rating) {

    /** Rating por defecto para equipos sin historial calibrado. */
    public static final double DEFAULT_RATING = 1500.0;

    public EloRating {
        if (teamName == null || teamName.isBlank()) {
            throw new IllegalArgumentException("teamName no puede ser nulo ni vacío");
        }
    }

    /** Crea un rating inicial (1500) para un equipo. */
    public static EloRating initial(String teamName) {
        return new EloRating(teamName, DEFAULT_RATING);
    }

    /** Devuelve una copia con el rating modificado. */
    public EloRating withRating(double newRating) {
        return new EloRating(teamName, newRating);
    }

    @Override
    public String toString() {
        return teamName + " (" + Math.round(rating) + ")";
    }
}
