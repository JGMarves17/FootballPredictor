package com.josegabrielmarves.footballpredictor.quiniela;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.MatchdayEngine;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.util.MatrixUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 🧱 Constructor de mensajes para WhatsApp con POO y recursividad.
 * <p>
 * <b>POO:</b> Cada partido se modela como un {@link MatchBlock} que encapsula
 * sus datos y sabe formatearse a sí mismo ({@link MatchBlock#format()}).
 * <p>
 * <b>Recursividad:</b> {@link MatrixUtils#topN(double[][], int)} extrae los N
 * marcadores más probables de la matriz Poisson mediante un algoritmo
 * recursivo de búsqueda del máximo.
 * <p>
 * <b>Fallback:</b> Si el {@code FastStrategyOptimizer.OptimizationResult} es
 * {@code null} (no disponible por timeout), usa {@link MatchEV#dualPick}
 * para generar los picks.
 */
public final class WhatsAppMessageBuilder {

    // ──────────────────────────────────────────────────────────────────────
    // Record: marcador individual con probabilidad
    // ──────────────────────────────────────────────────────────────────────

    /** Un marcador (goles local, goles visitante) con su probabilidad [0,1]. */
    public record ScoreWithProb(int homeGoals, int awayGoals, double probability) {}

    // ──────────────────────────────────────────────────────────────────────
    // Clase MatchBlock — POO: encapsula un partido y su formateo
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Bloque completo de un partido para el mensaje de WhatsApp.
     * <p>
     * Cada instancia contiene TODA la información necesaria y expone
     * {@link #format()} para renderizarse a texto.
     */
    public static final class MatchBlock {

        private final String homeTeam;
        private final String awayTeam;
        private final double pHome;
        private final double pDraw;
        private final double pAway;
        private final List<ScoreWithProb> topScores;   // top 3
        private final Score pick;                       // pick recomendado
        private final MatchEV.Risk risk;                // nivel de riesgo

        /**
         * Construye un bloque de partido.
         *
         * @param homeTeam  nombre del equipo local
         * @param awayTeam  nombre del equipo visitante
         * @param pHome     probabilidad de victoria local [0,1]
         * @param pDraw     probabilidad de empate [0,1]
         * @param pAway     probabilidad de victoria visitante [0,1]
         * @param topScores lista ordenada (descendente) de marcadores
         * @param pick      marcador recomendado (seguro o exacto)
         * @param risk      nivel de riesgo del pick
         */
        public MatchBlock(String homeTeam, String awayTeam,
                          double pHome, double pDraw, double pAway,
                          List<ScoreWithProb> topScores,
                          Score pick, MatchEV.Risk risk) {
            this.homeTeam = homeTeam;
            this.awayTeam = awayTeam;
            this.pHome    = pHome;
            this.pDraw    = pDraw;
            this.pAway    = pAway;
            this.topScores = topScores;
            this.pick     = pick;
            this.risk     = risk;
        }

        // ── Getters ─────────────────────────────────────────────────────

        public String homeTeam()    { return homeTeam; }
        public String awayTeam()    { return awayTeam; }
        public double pHome()       { return pHome; }
        public double pDraw()       { return pDraw; }
        public double pAway()       { return pAway; }
        public List<ScoreWithProb> topScores() { return topScores; }
        public Score pick()         { return pick; }
        public MatchEV.Risk risk()  { return risk; }

        // ── Formateo a texto ────────────────────────────────────────────

        /**
         * Renderiza el bloque completo del partido para WhatsApp:
         * <pre>
         * 🏟️ *Francia* vs *Suecia*
         *    📊 1=44% X=23% 2=32%
         *    🥇 1-1 (9.7%) ◀ PICK
         *    🥈 2-1 (8.6%)
         *    🥉 1-2 (7.3%)
         *    🎯 *1-1* (🔵 FIJO)
         * </pre>
         */
        public String format() {
            StringBuilder sb = new StringBuilder();

            // Cabecera
            sb.append("🏟️ *").append(homeTeam).append("* vs *").append(awayTeam).append("*\n");

            // Probabilidades 1X2
            sb.append(String.format("   📊 1=%.0f%% X=%.0f%% 2=%.0f%%%n",
                    pHome * 100, pDraw * 100, pAway * 100));

            // Top 3 marcadores con medallas
            int rank = 0;
            for (ScoreWithProb sp : topScores) {
                String medal = medalForRank(rank);
                boolean isPick = sp.homeGoals() == pick.homeGoals()
                        && sp.awayGoals() == pick.awayGoals();
                String mark = isPick ? " ◀ PICK" : "";
                sb.append(String.format("   %s %d-%d (%.1f%%)%s%n",
                        medal, sp.homeGoals(), sp.awayGoals(),
                        sp.probability() * 100, mark));
                rank++;
            }

            // Pick recomendado con riesgo
            String icon = iconForRisk(risk);
            sb.append(String.format("   🎯 *%d-%d* (%s %s)%n",
                    pick.homeGoals(), pick.awayGoals(), icon, risk.label));

            return sb.toString();
        }

        /**
         * Devuelve la medalla según la posición (0-indexed).
         */
        private static String medalForRank(int rank) {
            return switch (rank) {
                case 0 -> "🥇";
                case 1 -> "🥈";
                case 2 -> "🥉";
                default -> "";
            };
        }

        /**
         * Devuelve el icono correspondiente al nivel de riesgo.
         */
        private static String iconForRisk(MatchEV.Risk r) {
            return switch (r) {
                case FIJO   -> "🔒";
                case FUERTE -> "🔵";
                case DOBLE  -> "🟡";
                case TRIPLE -> "⚡";
                default     -> "▪️";
            };
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Conversión desde MatrixUtils.ScoredCell → ScoreWithProb
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Convierte una lista de {@link MatrixUtils.ScoredCell} (genérica)
     * a {@link ScoreWithProb} (específica de quiniela) para consumo interno.
     *
     * @param cells lista de celdas con coordenadas y probabilidad
     * @return lista de marcadores con goles local, visitante y probabilidad
     */
    private static List<ScoreWithProb> toScoreWithProb(List<MatrixUtils.ScoredCell> cells) {
        List<ScoreWithProb> result = new ArrayList<>();
        for (MatrixUtils.ScoredCell cell : cells) {
            result.add(new ScoreWithProb(cell.row(), cell.col(), cell.value()));
        }
        return result;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Constructor principal del mensaje
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Construye el mensaje completo de WhatsApp con TOP 3 marcadores.
     * <p>
     * Si {@code opt} es {@code null} (optimizer no disponible), usa
     * {@link MatchEV#dualPick} como fallback para generar los picks.
     *
     * @param stageName       nombre de la fase (ej. "DIECISEISAVOS DE FINAL (R32)")
     * @param ptsResult       puntos por resultado acertado
     * @param ptsExact        puntos por marcador exacto
     * @param matchday        lista de partidos a predecir
     * @param strategyMatches datos de cada partido con ratings
     * @param opt             resultado del optimizer (puede ser null)
     * @return mensaje listo para copiar a WhatsApp
     */
    public static String build(
            String stageName, int ptsResult, int ptsExact,
            List<MatchdayEngine.MatchInput> matchday,
            List<FastStrategyOptimizer.StrategyMatch> strategyMatches,
            FastStrategyOptimizer.OptimizationResult opt) {

        StringBuilder sb = new StringBuilder();

        // ── Encabezado ───────────────────────────────────────────────────
        sb.append("⚽ *PREDICCIONES ").append(stageName).append("*\n");
        sb.append("📅 ").append(LocalDate.now()).append("\n");
        sb.append("👤 Gabriel Marves\n");

        if (opt != null) {
            sb.append(String.format("📊 P(podio)=%.1f%% · P(1°)=%.1f%% · Esp.%.2f/%d%n",
                    opt.pPodio() * 100, opt.p1st() * 100,
                    opt.expectedPosition(), opt.participants()));
        }
        sb.append(String.format("💡 Pts: %dR / %dE · −10L fallo%n", ptsResult, ptsExact));
        sb.append(" ".repeat(30)).append("\n");

        // ── Cuerpo: un MatchBlock por partido ────────────────────────────
        for (int i = 0; i < matchday.size(); i++) {
            MatchdayEngine.MatchInput m = matchday.get(i);
            FastStrategyOptimizer.StrategyMatch sm = strategyMatches.get(i);
            double bonus = MatchdayEngine.hostBonus(m.team1());

            // Matriz y probabilidades del torneo (Triple Blend + xG)
            double[][] matrix = PoissonPredictor.scoreMatrixTournament(
                    sm.homeTeam(), sm.home(), sm.awayTeam(), sm.away(),
                    bonus, m.stage());
            var probs = PoissonPredictor.matchProbabilitiesTournament(
                    sm.homeTeam(), sm.home(), sm.awayTeam(), sm.away(),
                    bonus, m.stage());

            // Top 3 marcadores (recursivo via MatrixUtils)
            List<ScoreWithProb> top3 = toScoreWithProb(MatrixUtils.topN(matrix, 3));

            // Pick: optimizer > dualPick (fallback)
            Score pick;
            if (opt != null && i < opt.predictions().size()) {
                pick = opt.predictions().get(i);
            } else {
                // Fallback: generar predicción segura usando MatchEV.secure (juega seguro)
                pick = MatchEV.secure(sm.homeTeam(), sm.home(), sm.awayTeam(), sm.away(), bonus);
            }

            MatchEV.Risk riesgo = MatchEV.risk(sm.home(), sm.away(), bonus);

            MatchBlock block = new MatchBlock(
                    m.team1(), m.team2(),
                    probs.homeWin(), probs.draw(), probs.awayWin(),
                    top3, pick, riesgo);

            sb.append(block.format());
            sb.append("\n");
        }

        // ── Pie ─────────────────────────────────────────────────────────
        sb.append(" ".repeat(30)).append("\n");
        sb.append("⚡ FootballPredictor — ALL-IN · Triple Blend + xG\n");
        sb.append("🎯 Estrategia P1_FIRST (FastStrategyOptimizer)\n");
        sb.append("📱 ").append(LocalDateTime.now()).append("\n");

        return sb.toString();
    }

    /**
     * Construye el mensaje de WhatsApp con TOP 3 marcadores
     * y panel de jueces opcional.
     * <p>
     * Si se proporciona un {@code judgePanel}, después del bloque de cada
     * partido se añade una sección con los veredictos de todos los jueces.
     *
     * @param stageName       nombre de la fase (ej. "DIECISEISAVOS DE FINAL (R32)")
     * @param ptsResult       puntos por resultado acertado
     * @param ptsExact        puntos por marcador exacto
     * @param matchday        lista de partidos a predecir
     * @param strategyMatches datos de cada partido con ratings
     * @param opt             resultado del optimizer (puede ser null)
     * @param judgePanel      panel de jueces (puede ser null — mismo comportamiento que build() simple)
     * @return mensaje listo para copiar a WhatsApp
     */
    public static String build(
            String stageName, int ptsResult, int ptsExact,
            List<MatchdayEngine.MatchInput> matchday,
            List<FastStrategyOptimizer.StrategyMatch> strategyMatches,
            FastStrategyOptimizer.OptimizationResult opt,
            JudgePanel judgePanel) {

        StringBuilder sb = new StringBuilder();

        // ── Encabezado ───────────────────────────────────────────────────
        sb.append("⚽ *PREDICCIONES ").append(stageName).append("*\n");
        sb.append("📅 ").append(LocalDate.now()).append("\n");
        sb.append("👤 Gabriel Marves\n");

        if (opt != null) {
            sb.append(String.format("📊 P(podio)=%.1f%% · P(1°)=%.1f%% · Esp.%.2f/%d%n",
                    opt.pPodio() * 100, opt.p1st() * 100,
                    opt.expectedPosition(), opt.participants()));
        }
        sb.append(String.format("💡 Pts: %dR / %dE · −10L fallo%n", ptsResult, ptsExact));
        sb.append(" ".repeat(30)).append("\n");

        // ── Cuerpo: un MatchBlock por partido ────────────────────────────
        for (int i = 0; i < matchday.size(); i++) {
            MatchdayEngine.MatchInput m = matchday.get(i);
            FastStrategyOptimizer.StrategyMatch sm = strategyMatches.get(i);
            double bonus = MatchdayEngine.hostBonus(m.team1());

            // Matriz y probabilidades del torneo (Triple Blend + xG)
            double[][] matrix = PoissonPredictor.scoreMatrixTournament(
                    sm.homeTeam(), sm.home(), sm.awayTeam(), sm.away(),
                    bonus, m.stage());
            var probs = PoissonPredictor.matchProbabilitiesTournament(
                    sm.homeTeam(), sm.home(), sm.awayTeam(), sm.away(),
                    bonus, m.stage());

            // Top 3 marcadores (recursivo via MatrixUtils)
            List<ScoreWithProb> top3 = toScoreWithProb(MatrixUtils.topN(matrix, 3));

            // Pick: optimizer > dualPick (fallback)
            Score pick;
            if (opt != null && i < opt.predictions().size()) {
                pick = opt.predictions().get(i);
            } else {
                // Fallback: generar predicción segura usando MatchEV.secure (juega seguro)
                pick = MatchEV.secure(sm.homeTeam(), sm.home(), sm.awayTeam(), sm.away(), bonus);
            }

            MatchEV.Risk riesgo = MatchEV.risk(sm.home(), sm.away(), bonus);

            MatchBlock block = new MatchBlock(
                    m.team1(), m.team2(),
                    probs.homeWin(), probs.draw(), probs.awayWin(),
                    top3, pick, riesgo);

            sb.append(block.format());

            // ── Panel de jueces (opcional) ──────────────────────────────
            if (judgePanel != null) {
                String judgesReport = judgePanel.formatForWhatsApp(
                        sm.homeTeam(), sm.home(), sm.awayTeam(), sm.away(),
                        bonus, m.stage());
                if (!judgesReport.isEmpty()) {
                    sb.append(judgesReport);
                }
            }

            sb.append("\n");
        }

        // ── Pie ─────────────────────────────────────────────────────────
        sb.append(" ".repeat(30)).append("\n");
        sb.append("⚡ FootballPredictor — ALL-IN · Triple Blend + xG\n");
        sb.append("🎯 Estrategia P1_FIRST (FastStrategyOptimizer)\n");
        sb.append("📱 ").append(LocalDateTime.now()).append("\n");

        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Constructor privado — clase utilitaria
    // ──────────────────────────────────────────────────────────────────────

    private WhatsAppMessageBuilder() {}
}
