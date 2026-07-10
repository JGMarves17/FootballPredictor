package com.josegabrielmarves.footballpredictor.quiniela;

import com.google.gson.Gson;
import com.josegabrielmarves.footballpredictor.rivals.RivalProfile;
import com.josegabrielmarves.footballpredictor.rivals.StandingsSimulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Carga los perfiles de predicción de los rivales desde JSON.
 * <p>
 * Lee {@code data/quiniela_standings.json} y genera {@link RivalProfile}
 * para cada jugador que NO sea "Nosotros" (= {@link StandingsSimulator#US}).
 * <p>
 * El campo {@code type} en el JSON determina el estilo de predicción:
 * <ul>
 *   <li>{@code CONSERVATIVE} — marcadores bajos (0-2 por lado)</li>
 *   <li>{@code FAVORITE} — predice el favorito según el modelo</li>
 *   <li>{@code RANDOM} — marcadores variados</li>
 *   <li>{@code FAN} — apoya a su equipo sin análisis</li>
 * </ul>
 * <p>
 * Si un jugador no tiene {@code type}, se usa {@code CONSERVATIVE} por defecto.
 */
public final class RivalLoader {

    private static final Path DEFAULT_PATH = Path.of("data", "quiniela_standings.json");
    private static final Gson GSON = new Gson();

    private RivalLoader() {}

    /**
     * Carga los rivales desde la ruta por defecto.
     *
     * @return lista de perfiles de rivales (excluye "Nosotros")
     */
    public static List<RivalProfile> load() {
        return load(DEFAULT_PATH);
    }

    /**
     * Carga los rivales desde una ruta personalizada.
     *
     * @param path ruta al JSON de standings
     * @return lista de perfiles de rivales (excluye "Nosotros")
     */
    public static List<RivalProfile> load(Path path) {
        if (!Files.exists(path)) {
            System.err.println("[RivalLoader] No existe " + path + " — devolviendo lista vacía");
            return List.of();
        }

        String json;
        try {
            json = Files.readString(path);
        } catch (IOException e) {
            System.err.println("[RivalLoader] Error leyendo " + path + ": " + e.getMessage());
            return List.of();
        }

        StandingsFile file;
        try {
            file = GSON.fromJson(json, StandingsFile.class);
        } catch (Exception e) {
            System.err.println("[RivalLoader] JSON mal formado: " + e.getMessage());
            return List.of();
        }

        if (file == null || file.players == null || file.players.isEmpty()) {
            System.err.println("[RivalLoader] Archivo vacío o sin jugadores");
            return List.of();
        }

        List<RivalProfile> rivals = new ArrayList<>();
        for (PlayerEntry p : file.players) {
            // Saltar "Nosotros" — no es rival
            if (StandingsSimulator.US.equals(p.name)) continue;

            RivalProfile.Type type = parseType(p.type);
            rivals.add(new RivalProfile(p.name, type));
        }

        System.out.printf("[RivalLoader] Cargados %d rivales desde %s%n", rivals.size(), path);
        return rivals;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static RivalProfile.Type parseType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return RivalProfile.Type.CONSERVATIVE; // default
        }
        try {
            return RivalProfile.Type.valueOf(typeStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.printf("[RivalLoader] Tipo desconocido '%s', usando CONSERVATIVE%n", typeStr);
            return RivalProfile.Type.CONSERVATIVE;
        }
    }

    // ── Estructura del JSON ──────────────────────────────────────────────────

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
        String type;
    }
}
