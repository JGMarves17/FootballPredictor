package com.josegabrielmarves.footballpredictor.quiniela;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.josegabrielmarves.footballpredictor.rivals.StandingsSimulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carga la clasificación de la quiniela desde JSON persistente.
 * <p>
 * El archivo {@code data/quiniela_standings.json} se mantiene actualizado
 * desde la UI (pestaña "🏆 Tabla de Puntos") y contiene:
 * <ul>
 *   <li>Jugadores con sus puntos actuales</li>
 *   <li>Marcadores exactos acertados (desempate 1)</li>
 *   <li>Puntos de eliminatorias (desempate 2)</li>
 * </ul>
 * <p>
 * Uso:
 * <pre>
 * Map<String, Integer> standings = StandingsLoader.load();
 * int ourPoints = standings.get(StandingsSimulator.US);
 * </pre>
 */
public final class StandingsLoader {

    private static final Path DEFAULT_PATH = Path.of("data", "quiniela_standings.json");
    private static final Gson GSON = new Gson();

    private StandingsLoader() {}

    /**
     * Carga la clasificación desde la ruta por defecto.
     *
     * @return mapa ordenado nombre → puntos (incluye clave {@link StandingsSimulator#US})
     * @throws IllegalStateException si el archivo no existe o está mal formado
     */
    public static Map<String, Integer> load() {
        return load(DEFAULT_PATH);
    }

    /**
     * Carga la clasificación desde una ruta personalizada.
     *
     * @param path ruta al JSON de standings
     * @return mapa ordenado nombre → puntos
     * @throws IllegalStateException si el archivo no existe o está mal formado
     */
    public static Map<String, Integer> load(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "No existe el archivo de clasificación: " + path.toAbsolutePath() +
                    ". Ejecuta la app y usa la pestaña '🏆 Tabla de Puntos' para crearlo.");
        }

        String json;
        try {
            json = Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Error leyendo " + path, e);
        }

        StandingsFile file;
        try {
            file = GSON.fromJson(json, StandingsFile.class);
        } catch (Exception e) {
            throw new IllegalStateException("JSON mal formado en " + path, e);
        }

        if (file == null || file.players == null || file.players.isEmpty()) {
            throw new IllegalStateException("Archivo de clasificación vacío: " + path);
        }

        Map<String, Integer> standings = new LinkedHashMap<>();
        for (PlayerEntry p : file.players) {
            standings.put(p.name, p.points);
        }

        // Verificar que "Nosotros" existe
        if (!standings.containsKey(StandingsSimulator.US)) {
            throw new IllegalStateException(
                    "Falta la entrada '" + StandingsSimulator.US + "' en " + path +
                    ". Añádela desde la UI o edita el JSON manualmente.");
        }

        return standings;
    }

    // ── Estructura del JSON ───────────────────────────────────────────────────

    private static class StandingsFile {
        String lastUpdated;
        String phase;
        int participants;
        List<PlayerEntry> players;
    }

    private static class PlayerEntry {
        int pos;
        String name;
        int points;
        int exactScores;
        int knockoutPoints;
    }
}