package com.josegabrielmarves.footballpredictor.rivals;

/**
 * Perfil de predicción de un rival en la quiniela.
 * Define su estrategia para predecir marcadores.
 *
 * Perfiles disponibles:
 * - CONSERVATIVE: predice marcadores bajos (0-2 goles por lado)
 * - FAVORITE:     siempre predice el favorito según el modelo (modal Poisson)
 * - FAN:          siempre apoya a su equipo favorito sin importar el análisis
 * - RANDOM:       predice al azar entre marcadores comunes
 */
public record RivalProfile(String name, Type type, String favoriteTeam) {

    public enum Type {
        CONSERVATIVE, FAVORITE, FAN, RANDOM
    }

    /** Constructor sin equipo favorito (para tipos no-FAN). */
    public RivalProfile(String name, Type type) {
        this(name, type, null);
    }

    /** Perfil por defecto para un rival desconocido. */
    public static RivalProfile unknown(String name) {
        return new RivalProfile(name, Type.CONSERVATIVE);
    }

    /** Crea los 13 rivales con perfil desconocido (conservador). */
    public static java.util.List<RivalProfile> defaultRivals(java.util.List<String> names) {
        return names.stream().map(RivalProfile::unknown).toList();
    }
}