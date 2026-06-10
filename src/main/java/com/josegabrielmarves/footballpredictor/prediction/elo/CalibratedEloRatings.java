package com.josegabrielmarves.footballpredictor.prediction.elo;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Ratings Elo calibrados como semilla del modelo.
 *
 * Fuente: github.com/Hicruben/world-cup-2026-prediction-model (MIT),
 * archivo data/elo-calibrated.json — calibrados sobre 920 partidos
 * internacionales reales (oct 2023 - may 2026) con ponderación por
 * importancia y recencia (vida media 18 meses).
 *
 * Los equipos sin rating calibrado reciben {@link EloRating#DEFAULT_RATING};
 * usar {@link #hasCalibratedRating(String)} para detectarlos.
 */
public final class CalibratedEloRatings {

    private static final Map<String, Double> RATINGS = Map.ofEntries(
            Map.entry("argentina", 2064.0),
            Map.entry("france", 2040.0),
            Map.entry("spain", 2074.0),
            Map.entry("brazil", 1994.0),
            Map.entry("england", 1982.0),
            Map.entry("portugal", 1934.0),
            Map.entry("netherlands", 1942.0),
            Map.entry("germany", 1927.0),
            Map.entry("belgium", 1871.0),
            Map.entry("italy", 1915.0),
            Map.entry("colombia", 1884.0),
            Map.entry("uruguay", 1833.0),
            Map.entry("croatia", 1878.0),
            Map.entry("morocco", 1875.0),
            Map.entry("switzerland", 1807.0),
            Map.entry("usa", 1794.0),
            Map.entry("mexico", 1830.0),
            Map.entry("japan", 1851.0),
            Map.entry("senegal", 1830.0),
            Map.entry("denmark", 1790.0),
            Map.entry("ecuador", 1790.0),
            Map.entry("australia", 1769.0),
            Map.entry("south-korea", 1742.0),
            Map.entry("iran", 1733.0),
            Map.entry("poland", 1715.0),
            Map.entry("canada", 1725.0),
            Map.entry("serbia", 1695.0),
            Map.entry("wales", 1665.0),
            Map.entry("ghana", 1630.0),
            Map.entry("tunisia", 1666.0),
            Map.entry("ivory-coast", 1706.0),
            Map.entry("nigeria", 1645.0),
            Map.entry("saudi-arabia", 1619.0),
            Map.entry("qatar", 1552.0),
            Map.entry("egypt", 1671.0),
            Map.entry("algeria", 1676.0),
            Map.entry("scotland", 1616.0),
            Map.entry("cameroon", 1600.0),
            Map.entry("paraguay", 1653.0),
            Map.entry("venezuela", 1590.0),
            Map.entry("chile", 1580.0),
            Map.entry("peru", 1575.0),
            Map.entry("czech-republic", 1613.0),
            Map.entry("bosnia-and-herzegovina", 1566.0),
            Map.entry("south-africa", 1562.0),
            Map.entry("new-zealand", 1567.0),
            Map.entry("panama", 1582.0),
            Map.entry("jamaica", 1460.0),
            Map.entry("honduras", 1440.0),
            Map.entry("jordan", 1515.0),
            Map.entry("haiti", 1481.0),
            Map.entry("el-salvador", 1370.0),
            Map.entry("trinidad-and-tobago", 1360.0),
            Map.entry("guatemala", 1345.0)
    );

    private CalibratedEloRatings() {
        // Clase utilitaria: no instanciable.
    }

    /**
     * Rating calibrado del equipo, o {@link EloRating#DEFAULT_RATING} si no
     * existe en la fuente. Acepta nombres tal como vienen de openfootball
     * ("South Africa", "Bosnia & Herzegovina", "USA", "Côte d'Ivoire"...).
     */
    public static EloRating getRating(String teamName) {
        Double r = RATINGS.get(toSlug(teamName));
        return new EloRating(teamName, r != null ? r : EloRating.DEFAULT_RATING);
    }

    /** True si el equipo tiene rating calibrado en la fuente. */
    public static boolean hasCalibratedRating(String teamName) {
        return RATINGS.containsKey(toSlug(teamName));
    }

    /** Rating calibrado exacto si existe (sin fallback). */
    public static Optional<Double> findRating(String teamName) {
        return Optional.ofNullable(RATINGS.get(toSlug(teamName)));
    }

    /**
     * Normaliza un nombre de equipo al formato slug de la fuente:
     * minúsculas, "&"/"and" unificados, acentos comunes removidos,
     * espacios a guiones. Ej.: "Bosnia & Herzegovina" -> "bosnia-and-herzegovina".
     */
    static String toSlug(String teamName) {
        if (teamName == null) return "";
        String s = teamName.toLowerCase(Locale.ROOT).trim()
                .replace("&", "and")
                .replace("ç", "c")
                .replace("é", "e")
                .replace("ô", "o")
                .replace("'", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-");
        // Alias frecuentes entre fuentes de datos
        return switch (s) {
            case "united-states", "united-states-of-america" -> "usa";
            case "korea-republic", "korea-rep." -> "south-korea";
            case "cote-divoire" -> "ivory-coast";
            case "ir-iran" -> "iran";
            case "czechia" -> "czech-republic";
            default -> s;
        };
    }
}
