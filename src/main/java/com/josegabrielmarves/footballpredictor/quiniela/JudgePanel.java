package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.EnsemblePredictor;
import com.josegabrielmarves.footballpredictor.prediction.TournamentGLM;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer.Stage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Panel de jueces analistas para FootballPredictor.
 * <p>
 * Agrega múltiples {@link MatchJudge} y permite:
 * <ul>
 *   <li>{@link #judgeAll(String, EloRating, String, EloRating, double, Stage)}
 *       — ejecuta todos los jueces y devuelve sus veredictos</li>
 *   <li>{@link #consensus(String, EloRating, String, EloRating, double, Stage)}
 *       — veredicto mayoritario (por resultado 1X2)</li>
 *   <li>{@link #printReport(String, EloRating, String, EloRating, double, Stage)}
 *       — reporte formateado para consola</li>
 * </ul>
 * <p>
 * Diseño POO: composición de {@link MatchJudge} + métodos de agregación.
 */
public final class JudgePanel {

    private final List<MatchJudge> judges;

    /**
     * Crea un panel con la lista de jueces proporcionada.
     * Se recomienda usar {@link #withDefaultJudges(Object...)} para obtener
     * el conjunto completo de analistas.
     *
     * @param judges lista de jueces (no vacía, no nula)
     */
    public JudgePanel(List<MatchJudge> judges) {
        if (judges == null || judges.isEmpty()) {
            throw new IllegalArgumentException("Se requiere al menos un juez");
        }
        this.judges = List.copyOf(judges);
    }

    /**
     * Crea un JudgePanel con los 6 jueces por defecto.
     * <p>
     * Los parámetros opcionales permiten pasar dependencias:
     * <ol>
     *   <li>{@code TournamentGLM} — para GLMJudge (puede ser null)</li>
     *   <li>{@code EnsemblePredictor} — para MarketJudge (puede ser null)</li>
     * </ol>
     *
     * @param dependencies 0, 1 o 2 objetos: TournamentGLM, EnsemblePredictor
     * @return JudgePanel con todos los jueces
     */
    public static JudgePanel withDefaultJudges(Object... dependencies) {
        TournamentGLM glm = null;
        EnsemblePredictor ep = null;
        for (Object dep : dependencies) {
            if (dep instanceof TournamentGLM g) glm = g;
            else if (dep instanceof EnsemblePredictor e) ep = e;
        }

        List<MatchJudge> all = new ArrayList<>();
        all.add(new EloJudge());
        all.add(new FormJudge());
        all.add(new GLMJudge(glm));
        all.add(new H2HJudge());
        all.add(new MarketJudge(ep));
        all.add(new RestDaysJudge());
        return new JudgePanel(all);
    }

    /**
     * Ejecuta todos los jueces y devuelve la lista de veredictos.
     * Solo incluye aquellos con confianza > 0 (que tenían datos para opinar).
     */
    public List<Verdict> judgeAll(String homeTeam, EloRating home,
                                   String awayTeam, EloRating away,
                                   double homeBonus, Stage stage) {
        List<Verdict> results = new ArrayList<>();
        for (MatchJudge judge : judges) {
            Verdict v = judge.judge(homeTeam, home, awayTeam, away, homeBonus, stage);
            if (v.confidence() > 0.0) {
                results.add(v);
            }
        }
        return results;
    }

    /**
     * Veredicto por consenso: resultado 1X2 más votado entre los jueces.
     * Si hay empate, se elige el de mayor confianza acumulada.
     *
     * @return veredicto mayoritario (puede ser neutral si no hay consenso)
     */
    public Verdict consensus(String homeTeam, EloRating home,
                              String awayTeam, EloRating away,
                              double homeBonus, Stage stage) {
        List<Verdict> all = judgeAll(homeTeam, home, awayTeam, away, homeBonus, stage);
        if (all.isEmpty()) {
            return new Verdict("Consenso", "X", new Score(1, 1), 0.0,
                    "Sin datos suficientes para consenso");
        }

        // Votación ponderada por confianza
        Map<String, Double> votes = new HashMap<>();
        Map<String, List<Verdict>> byResult = new HashMap<>();
        for (Verdict v : all) {
            votes.merge(v.result(), v.confidence(), Double::sum);
            byResult.computeIfAbsent(v.result(), k -> new ArrayList<>()).add(v);
        }

        // Resultado con mayor confianza acumulada
        String winnerResult = votes.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("X");

        double totalConf = votes.values().stream().mapToDouble(Double::doubleValue).sum();
        double consensusConf = votes.get(winnerResult) / Math.max(1.0, totalConf);

        // Para el marcador exacto del consenso: tomar el más votado entre los que
        // coinciden con el resultado ganador, o el promedio redondeado
        List<Verdict> winnerVerdicts = byResult.get(winnerResult);
        Score consensusScore = winnerVerdicts.stream()
                .collect(Collectors.groupingBy(
                        v -> v.exactScore(),
                        Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(new Score(1, 1));

        int nJudges = all.size();
        String summary = String.format(
                "Consenso %d/%d jueces: %s | Marcador: %s | Confianza agregada: %.0f%%",
                winnerVerdicts.size(), nJudges,
                winnerResult.equals("1") ? homeTeam
                        : winnerResult.equals("2") ? awayTeam : "Empate",
                consensusScore, consensusConf * 100);

        return new Verdict("Consenso", winnerResult, consensusScore, consensusConf, summary);
    }

    /**
     * Genera un reporte formateado para consola con todos los veredictos.
     */
    public String printReport(String homeTeam, EloRating home,
                               String awayTeam, EloRating away,
                               double homeBonus, Stage stage) {
        List<Verdict> all = judgeAll(homeTeam, home, awayTeam, away, homeBonus, stage);
        Verdict cons = consensus(homeTeam, home, awayTeam, away, homeBonus, stage);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  👨‍⚖️  PANEL DE JUECES — %s vs %s%n", homeTeam, awayTeam));
        sb.append(String.format("  ─────────────────────────────────────────%n"));

        for (Verdict v : all) {
            String icon = switch (v.judgeName()) {
                case "Elo" -> "📊";
                case "Forma" -> "🔥";
                case "GLM" -> "📐";
                case "H2H" -> "🔄";
                case "Mercado" -> "💰";
                case "Descanso" -> "⏰";
                default -> "▪️";
            };
            sb.append(String.format("  %s %s: %s (%.0f%%) — %s%n",
                    icon, v.judgeName(),
                    formatResult(v.result(), homeTeam, awayTeam),
                    v.confidence() * 100, v.summary()));
        }

        sb.append(String.format("  ─────────────────────────────────────────%n"));
        sb.append(String.format("  ✅ CONSENSO: %s %s — %s%n",
                cons.exactScore(),
                formatResult(cons.result(), homeTeam, awayTeam),
                cons.summary()));

        return sb.toString();
    }

    /**
     * Genera el bloque de veredictos para incrustar en WhatsApp.
     * Formato compacto, 1 línea por juez.
     */
    public String formatForWhatsApp(String homeTeam, EloRating home,
                                     String awayTeam, EloRating away,
                                     double homeBonus, Stage stage) {
        List<Verdict> all = judgeAll(homeTeam, home, awayTeam, away, homeBonus, stage);
        if (all.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("   👨‍⚖️ JUECES:\n");
        for (Verdict v : all) {
            String icon = switch (v.judgeName()) {
                case "Elo" -> "📊";
                case "Forma" -> "🔥";
                case "GLM" -> "📐";
                case "H2H" -> "🔄";
                case "Mercado" -> "💰";
                case "Descanso" -> "⏰";
                default -> "▪️";
            };
            sb.append(String.format("   %s %s: %s (%.0f%%) — %s%n",
                    icon, v.judgeName(),
                    formatResult(v.result(), homeTeam, awayTeam),
                    v.confidence() * 100,
                    truncate(v.summary(), 70)));
        }
        return sb.toString();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static String formatResult(String result, String home, String away) {
        return switch (result) {
            case "1" -> home;
            case "2" -> away;
            case "X" -> "Empate";
            default -> "?";
        };
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s == null ? "" : s;
        return s.substring(0, maxLen - 3) + "...";
    }

    public List<MatchJudge> getJudges() {
        return judges;
    }
}
