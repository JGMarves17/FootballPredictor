package com.josegabrielmarves.footballpredictor.simulation.tournament;

import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;

import java.util.*;

/**
 * Simulador Monte Carlo del torneo completo del Mundial 2026.
 * Corre grupos → R32 → R16 → QF → SF → Final y devuelve
 * P(alcanzar cada ronda) para los 48 equipos.
 *
 * <p>Bracket hardcodeado del fixture oficial openfootball (partidos 73-104).
 * Mejores terceros asignados a sus slots via backtracking (garantiza
 * asignación válida según las restricciones FIFA).
 *
 * <p>Empates al 90' en eliminatorias: 50/50 (penales). Cancha neutral
 * en todas las rondas eliminatorias (el HOME_ADVANTAGE de los anfitriones
 * solo aplica en grupos, ya manejado por GroupSimulator).
 */
public final class TournamentSimulator {

    public static final int DEFAULT_SIMULATIONS = 50_000;

    // === R32 BRACKET (matches 73-88) =========================================
    // Specs: "1A"=1° Grupo A, "2A"=2° Grupo A, "3ABCDF"=mejor 3° de esos grupos
    private static final String[][] R32_SPECS = {
            {"2A", "2B"},       // 73
            {"1E", "3ABCDF"},   // 74
            {"1F", "2C"},       // 75
            {"1C", "2F"},       // 76
            {"1I", "3CDFGH"},   // 77
            {"2E", "2I"},       // 78
            {"1A", "3CEFHI"},   // 79
            {"1L", "3EHIJK"},   // 80
            {"1D", "3BEFIJ"},   // 81
            {"1G", "3AEHIJ"},   // 82
            {"2K", "2L"},       // 83
            {"1H", "2J"},       // 84
            {"1B", "3EFGIJ"},   // 85
            {"1J", "2H"},       // 86
            {"1K", "3DEIJL"},   // 87
            {"2D", "2G"},       // 88
    };

    // Índices en R32_SPECS que usan tercero (0-based: match73=0, match74=1, ...)
    private static final int[] THIRD_SLOT_IDX    = {1, 4, 6, 7, 8, 9, 12, 14};
    private static final String[] THIRD_ELIGIBLE = {
            "ABCDF",  // match 74
            "CDFGH",  // match 77
            "CEFHI",  // match 79
            "EHIJK",  // match 80
            "BEFIJ",  // match 81
            "AEHIJ",  // match 82
            "EFGIJ",  // match 85
            "DEIJL",  // match 87
    };

    // === R16 BRACKET (matches 89-96) — índices en ganadores de R32 ==========
    // r32w[0]=W73, r32w[1]=W74, ..., r32w[15]=W88
    private static final int[][] R16 = {
            {1, 4},   // W74 vs W77 → 89
            {0, 2},   // W73 vs W75 → 90
            {3, 5},   // W76 vs W78 → 91
            {6, 7},   // W79 vs W80 → 92
            {10, 11}, // W83 vs W84 → 93
            {8, 9},   // W81 vs W82 → 94
            {13, 15}, // W86 vs W88 → 95
            {12, 14}, // W85 vs W87 → 96
    };

    // QF (97-100) — índices en ganadores de R16
    private static final int[][] QF = {
            {0, 1}, // W89 vs W90 → 97
            {4, 5}, // W93 vs W94 → 98
            {2, 3}, // W91 vs W92 → 99
            {6, 7}, // W95 vs W96 → 100
    };

    // SF (101-102) — índices en ganadores de QF
    private static final int[][] SF = {
            {0, 1}, // W97 vs W98 → 101
            {2, 3}, // W99 vs W100 → 102
    };

    private TournamentSimulator() {}

    /** Corre la simulación con burn-in por defecto ({@value #DEFAULT_SIMULATIONS}). */
    public static TournamentResult run(
            Map<String, List<Match>> groups,
            Map<String, EloRating> ratings,
            long seed) {
        return run(groups, ratings, DEFAULT_SIMULATIONS, seed);
    }

    /**
     * Corre el torneo completo Monte Carlo.
     *
     * @param groups    grupos extraídos por GroupExtractor (GroupName → partidos)
     * @param ratings   ratings actualizados (incluye resultados reales ya jugados)
     * @param simulations número de simulaciones
     * @param seed      semilla para reproducibilidad
     */
    public static TournamentResult run(
            Map<String, List<Match>> groups,
            Map<String, EloRating> ratings,
            int simulations, long seed) {

        // Team → letra de grupo ("Group A" → 'A')
        Map<String, Character> teamToLetter = new HashMap<>();
        for (Map.Entry<String, List<Match>> e : groups.entrySet()) {
            char letter = e.getKey().charAt(e.getKey().length() - 1);
            for (Match m : e.getValue()) {
                teamToLetter.put(m.homeTeam, letter);
                teamToLetter.put(m.awayTeam, letter);
            }
        }

        // counts[team] = [R32, R16, QF, SF, Final, Campeón]
        Map<String, int[]> counts = new HashMap<>();
        Random rng = new Random(seed);

        for (int sim = 0; sim < simulations; sim++) {

            // ── Fase de grupos ───────────────────────────────────────────────
            Map<Character, List<GroupStanding>> groupResults = new HashMap<>();
            List<GroupStanding> allThirds = new ArrayList<>();

            for (Map.Entry<String, List<Match>> e : groups.entrySet()) {
                char letter = e.getKey().charAt(e.getKey().length() - 1);
                List<GroupStanding> s = GroupSimulator.simulate(e.getValue(), ratings, rng);
                groupResults.put(letter, s);
                if (s.size() > 2) allThirds.add(s.get(2));
            }

            // Mejores 8 terceros
            Collections.sort(allThirds);
            List<GroupStanding> thirds8 =
                    new ArrayList<>(allThirds.subList(0, Math.min(8, allThirds.size())));

            // ── Build R32 ────────────────────────────────────────────────────
            String[] r32 = buildR32Teams(groupResults, thirds8, teamToLetter);
            for (String t : r32) if (t != null) increment(counts, t, 0); // alcanzó R32

            // ── Simular R32 → R16 → QF → SF → Final ─────────────────────────
            String[] r32w = simulateRound(r32, 16, ratings, rng);
            for (String t : r32w) increment(counts, t, 1);             // alcanzó R16

            String[] r16in = buildFromWinners(r32w, R16);
            String[] r16w = simulateRound(r16in, 8, ratings, rng);
            for (String t : r16w) increment(counts, t, 2);             // alcanzó QF

            String[] qfIn = buildFromWinners(r16w, QF);
            String[] qfw = simulateRound(qfIn, 4, ratings, rng);
            for (String t : qfw) increment(counts, t, 3);              // alcanzó SF

            String[] sfIn = buildFromWinners(qfw, SF);
            String sf1 = knockoutWinner(sfIn[0], sfIn[1], ratings, rng);
            String sf2 = knockoutWinner(sfIn[2], sfIn[3], ratings, rng);
            increment(counts, sf1, 4);                                  // alcanzó Final
            increment(counts, sf2, 4);

            String champ = knockoutWinner(sf1, sf2, ratings, rng);
            increment(counts, champ, 5);                                // Campeón
        }

        return new TournamentResult(
                toProbabilities(counts, 0, simulations),
                toProbabilities(counts, 1, simulations),
                toProbabilities(counts, 2, simulations),
                toProbabilities(counts, 3, simulations),
                toProbabilities(counts, 4, simulations),
                toProbabilities(counts, 5, simulations),
                simulations);
    }

    // ── bracket helpers ───────────────────────────────────────────────────────

    /**
     * Construye el array plano de R32 (32 posiciones, índice par=team1, impar=team2).
     */
    private static String[] buildR32Teams(
            Map<Character, List<GroupStanding>> groupResults,
            List<GroupStanding> thirds8,
            Map<String, Character> teamToLetter) {

        String[] thirdAssignment = new String[8];
        boolean[] used = new boolean[thirds8.size()];
        if (!backtrack(thirdAssignment, used, thirds8, teamToLetter, 0)) {
            // Fallback: asignación sin validar (no debería ocurrir con fixture FIFA válido)
            int j = 0;
            for (int i = 0; i < thirds8.size() && j < 8; i++) {
                if (!used[i]) { thirdAssignment[j++] = thirds8.get(i).teamName(); used[i] = true; }
            }
        }

        String[] teams = new String[32];
        for (int matchIdx = 0; matchIdx < 16; matchIdx++) {
            teams[2 * matchIdx] = resolveSpec(R32_SPECS[matchIdx][0], groupResults);
            int thirdPos = findThirdSlot(matchIdx);
            teams[2 * matchIdx + 1] = thirdPos >= 0
                    ? thirdAssignment[thirdPos]
                    : resolveSpec(R32_SPECS[matchIdx][1], groupResults);
        }
        return teams;
    }

    private static int findThirdSlot(int matchIdx) {
        for (int j = 0; j < THIRD_SLOT_IDX.length; j++) {
            if (THIRD_SLOT_IDX[j] == matchIdx) return j;
        }
        return -1;
    }

    private static String resolveSpec(String spec,
                                      Map<Character, List<GroupStanding>> standings) {
        int posIdx = spec.charAt(0) == '1' ? 0 : 1;
        char groupLetter = spec.charAt(1);
        List<GroupStanding> s = standings.get(groupLetter);
        if (s == null || s.size() <= posIdx) return "Unknown";
        return s.get(posIdx).teamName();
    }

    /** Backtracking para asignar 8 terceros a sus slots elegibles. */
    private static boolean backtrack(String[] assignment, boolean[] used,
                                     List<GroupStanding> thirds8,
                                     Map<String, Character> teamToLetter,
                                     int slotIdx) {
        if (slotIdx == 8) return true;
        String eligible = THIRD_ELIGIBLE[slotIdx];
        for (int i = 0; i < thirds8.size(); i++) {
            if (!used[i]) {
                char letter = teamToLetter.getOrDefault(thirds8.get(i).teamName(), '?');
                if (eligible.indexOf(letter) >= 0) {
                    used[i] = true;
                    assignment[slotIdx] = thirds8.get(i).teamName();
                    if (backtrack(assignment, used, thirds8, teamToLetter, slotIdx + 1)) return true;
                    used[i] = false;
                    assignment[slotIdx] = null;
                }
            }
        }
        return false;
    }

    /** Construye el array de entrada de una ronda a partir de los índices del bracket. */
    private static String[] buildFromWinners(String[] prevWinners, int[][] bracket) {
        String[] teams = new String[bracket.length * 2];
        for (int i = 0; i < bracket.length; i++) {
            teams[2 * i]     = prevWinners[bracket[i][0]];
            teams[2 * i + 1] = prevWinners[bracket[i][1]];
        }
        return teams;
    }

    /** Simula n partidos de eliminatoria y devuelve los n ganadores. */
    private static String[] simulateRound(String[] teams, int nMatches,
                                          Map<String, EloRating> ratings, Random rng) {
        String[] winners = new String[nMatches];
        for (int i = 0; i < nMatches; i++) {
            winners[i] = knockoutWinner(teams[2 * i], teams[2 * i + 1], ratings, rng);
        }
        return winners;
    }

    /**
     * Simula un partido de eliminatoria (cancha neutral).
     * Empate al 90' → 50/50 (penales).
     */
    private static String knockoutWinner(String t1, String t2,
                                         Map<String, EloRating> ratings, Random rng) {
        EloRating r1 = ratings.getOrDefault(t1, EloRating.initial(t1));
        EloRating r2 = ratings.getOrDefault(t2, EloRating.initial(t2));
        double[][] matrix = PoissonPredictor.scoreMatrix(r1, r2, 0.0);
        Score s = GroupSimulator.sampleScore(matrix, rng);
        if (s.homeGoals() > s.awayGoals()) return t1;
        if (s.homeGoals() < s.awayGoals()) return t2;
        return rng.nextBoolean() ? t1 : t2; // penales: 50/50
    }

    // ── conteo ────────────────────────────────────────────────────────────────

    private static void increment(Map<String, int[]> counts, String team, int idx) {
        counts.computeIfAbsent(team, k -> new int[6])[idx]++;
    }

    private static Map<String, Double> toProbabilities(
            Map<String, int[]> counts, int idx, int simulations) {
        Map<String, Double> probs = new LinkedHashMap<>();
        counts.forEach((k, v) -> { if (v[idx] > 0) probs.put(k, (double) v[idx] / simulations); });
        return Collections.unmodifiableMap(probs);
    }

    // ── resultado ─────────────────────────────────────────────────────────────

    /**
     * Resultado de la simulación completa del torneo.
     * Invariantes: sum(pAdvance) ≈ 32, sum(pChampion) ≈ 1.
     */
    public record TournamentResult(
            Map<String, Double> pAdvance,
            Map<String, Double> pR16,
            Map<String, Double> pQF,
            Map<String, Double> pSF,
            Map<String, Double> pFinal,
            Map<String, Double> pChampion,
            int simulations
    ) {
        public void printSummary() {
            System.out.printf(
                    "%n=== Mundial 2026 — %,d simulaciones ===%n%n" +
                            "%-22s %8s %8s %8s %8s %8s %8s%n",
                    simulations, "Equipo",
                    "R32%", "R16%", "QF%", "SF%", "Final%", "Campeón%");
            System.out.println("-".repeat(78));

            pAdvance.entrySet().stream()
                    .sorted((a, b) -> Double.compare(
                            pChampion.getOrDefault(b.getKey(), 0.0),
                            pChampion.getOrDefault(a.getKey(), 0.0)))
                    .forEach(e -> {
                        String t = e.getKey();
                        System.out.printf("%-22s %7.1f  %7.1f  %7.1f  %7.1f  %7.1f  %7.1f%n",
                                t,
                                e.getValue() * 100,
                                pR16.getOrDefault(t, 0.0) * 100,
                                pQF.getOrDefault(t, 0.0) * 100,
                                pSF.getOrDefault(t, 0.0) * 100,
                                pFinal.getOrDefault(t, 0.0) * 100,
                                pChampion.getOrDefault(t, 0.0) * 100);
                    });
            System.out.printf("%n  Sanity check: sum(campeón)=%.3f (debe ser ≈1.0)%n",
                    pChampion.values().stream().mapToDouble(Double::doubleValue).sum());
        }
    }
}