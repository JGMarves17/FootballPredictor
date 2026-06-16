package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.simulation.tournament.GroupSimulator;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

import static com.josegabrielmarves.footballpredictor.ui.MainWindow.*;

/**
 * Panel visual del bracket de eliminación directa del Mundial 2026.
 *
 * Muestra R32 → R16 → QF → SF → Final con:
 *   - Tema oscuro estilo FIFA
 *   - Tarjetas por partido con equipos y marcador
 *   - P(campeón) del modelo debajo de cada equipo
 *   - Líneas conector entre rondas
 *   - Ganador resaltado en azul eléctrico
 */
public class BracketPanel extends JPanel {

    // ── Dimensiones ───────────────────────────────────────────────────────────
    private static final int CW    = 165;  // card width
    private static final int CH    = 58;   // card height (2 teams × 29)
    private static final int TEAM_H= 29;   // height per team row
    private static final int RADIUS= 10;   // corner radius
    private static final int COL_GAP = 44; // gap between columns (connector space)

    // Posiciones X de cada columna (inicio de tarjeta)
    private static final int X_R32L  = 20;
    private static final int X_R16L  = X_R32L  + CW + COL_GAP;
    private static final int X_QFL   = X_R16L  + CW + COL_GAP;
    private static final int X_SFL   = X_QFL   + CW + COL_GAP;
    private static final int X_FIN   = X_SFL   + CW + COL_GAP + 20;
    private static final int X_SFR   = X_FIN   + CW + COL_GAP + 20;
    private static final int X_QFR   = X_SFR   + CW + COL_GAP;
    private static final int X_R16R  = X_QFR   + CW + COL_GAP;
    private static final int X_R32R  = X_R16R  + CW + COL_GAP;
    private static final int CANVAS_W= X_R32R   + CW + 20;

    // Espaciado vertical R32: 8 matches per half
    private static final int TOP_PAD  = 70;
    private static final int R32_STEP = 96; // center-to-center between adjacent R32 cards

    // ── Datos ─────────────────────────────────────────────────────────────────

    /** Un slot del bracket con toda la info necesaria para pintarlo. */
    private record Slot(
            int x, int y,          // top-left of card
            String home, String away,
            int hGoals, int aGoals, // -1 = not played
            double pHome,           // P(home wins tournament) from model
            double pAway
    ) {
        boolean played()   { return hGoals >= 0; }
        boolean homeWins() { return played() && hGoals > aGoals; }
        boolean awayWins() { return played() && aGoals > hGoals; }
    }

    // 31 slots: R32[0..15], R16[0..7], QF[0..3], SF[0..1], Final[0]
    private Slot[] r32 = new Slot[16];
    private Slot[] r16 = new Slot[8];
    private Slot[] qf  = new Slot[4];
    private Slot[] sf  = new Slot[2];
    private Slot   fin = null;

    private final MainWindow owner;
    private volatile boolean loading = true;

    // ── R32 bracket specs (hardcodeado del fixture oficial FIFA 2026) ─────────
    // Índice visual (top→bottom) → spec del TournamentSimulator
    // LEFT side (visual top-to-bottom): specs[0..7]
    // RIGHT side: specs[8..15]
    private static final String[][] R32_SPECS_LEFT = {
            {"1E", "3ABCDF"},  // visual pos 0
            {"1I", "3CDFGH"},  // visual pos 1
            {"2A", "2B"},       // visual pos 2
            {"1F", "2C"},       // visual pos 3
            {"1C", "2F"},       // visual pos 4
            {"2E", "2I"},       // visual pos 5
            {"1A", "3CEFHI"},  // visual pos 6
            {"1L", "3EHIJK"},  // visual pos 7
    };
    private static final String[][] R32_SPECS_RIGHT = {
            {"2K", "2L"},       // visual pos 0
            {"1H", "2J"},       // visual pos 1
            {"1D", "3BEFIJ"},  // visual pos 2
            {"1G", "3AEHIJ"},  // visual pos 3
            {"1J", "2H"},       // visual pos 4
            {"2D", "2G"},       // visual pos 5
            {"1B", "3EFGIJ"},  // visual pos 6
            {"1K", "3DEIJL"},  // visual pos 7
    };

    // R16 bracket: {leftR32idx_A, leftR32idx_B} → pareja que juega
    // Basado en el bracket oficial (usando los índices visuales izquierda/derecha)
    private static final int[][] R16_LEFT_PAIRS  = {{0,1},{2,3},{4,5},{6,7}};
    private static final int[][] R16_RIGHT_PAIRS = {{0,1},{2,3},{4,5},{6,7}};
    private static final int[][] QF_LEFT_PAIRS   = {{0,1},{2,3}};
    private static final int[][] QF_RIGHT_PAIRS  = {{0,1},{2,3}};
    private static final int[][] SF_PAIRS        = {{0,1},{0,1}}; // SF[0]=QF[0]vsQF[1], SF[1]=QF[2]vsQF[3]

    // Grupo → equipos (1°, 2°, 3°, ...) — resuelto de los partidos jugados
    private Map<String, List<String>> groupStandings = new HashMap<>();
    private Map<String, Double> pChampion = new HashMap<>();

    public BracketPanel(MainWindow owner) {
        this.owner = owner;
        setBackground(BG);
        setPreferredSize(new Dimension(CANVAS_W, 900));
    }

    // ── API pública ───────────────────────────────────────────────────────────

    public void setMatches(List<Match> matches, Map<String, EloRating> ratings) {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                computeGroupStandings(matches, ratings);
                computeProbabilities(ratings);
                buildSlots(matches, ratings);
                return null;
            }
            @Override protected void done() {
                loading = false;
                repaint();
            }
        }.execute();
    }

    // ── Computación de datos ──────────────────────────────────────────────────

    private void computeGroupStandings(List<Match> matches, Map<String, EloRating> ratings) {
        // Agrupar partidos jugados por grupo
        Map<String, List<Match>> groups = new LinkedHashMap<>();
        for (Match m : matches) {
            if (m.group != null) groups.computeIfAbsent(m.group, k -> new ArrayList<>()).add(m);
        }

        for (Map.Entry<String, List<Match>> e : groups.entrySet()) {
            Map<String, int[]> stats = new LinkedHashMap<>(); // team → [pts, gf, ga]
            for (Match m : e.getValue()) {
                stats.putIfAbsent(m.homeTeam, new int[3]);
                stats.putIfAbsent(m.awayTeam, new int[3]);
                if (m.score != null) {
                    int hg = m.score.homeGoals(), ag = m.score.awayGoals();
                    stats.get(m.homeTeam)[1] += hg; stats.get(m.homeTeam)[2] += ag;
                    stats.get(m.awayTeam)[1] += ag; stats.get(m.awayTeam)[2] += hg;
                    if (hg > ag) stats.get(m.homeTeam)[0] += 3;
                    else if (ag > hg) stats.get(m.awayTeam)[0] += 3;
                    else { stats.get(m.homeTeam)[0]++; stats.get(m.awayTeam)[0]++; }
                }
            }
            List<String> ranked = new ArrayList<>(stats.keySet());
            ranked.sort((a, b) -> {
                int[] sa = stats.get(a), sb = stats.get(b);
                if (sb[0] != sa[0]) return sb[0] - sa[0];
                int gdA = sa[1]-sa[2], gdB = sb[1]-sb[2];
                if (gdB != gdA) return gdB - gdA;
                return sb[1] - sa[1];
            });
            groupStandings.put(e.getKey(), ranked);
        }
    }

    private void computeProbabilities(Map<String, EloRating> ratings) {
        // P(champion) simplificado: proporcional al rating Elo
        double totalRating = ratings.values().stream()
                .mapToDouble(r -> Math.pow(10, r.rating() / 400.0)).sum();
        for (Map.Entry<String, EloRating> e : ratings.entrySet()) {
            pChampion.put(e.getKey(),
                    Math.pow(10, e.getValue().rating() / 400.0) / totalRating);
        }
    }

    private void buildSlots(List<Match> matches, Map<String, EloRating> ratings) {
        // Build R32 left side
        for (int i = 0; i < 8; i++) {
            int y = TOP_PAD + i * R32_STEP;
            String home = resolveSpec(R32_SPECS_LEFT[i][0]);
            String away = resolveSpec(R32_SPECS_LEFT[i][1]);
            Match played = findKnockoutMatch(matches, "Round of 32", home, away);
            int hg = -1, ag = -1;
            if (played != null && played.score != null) {
                hg = played.score.homeGoals(); ag = played.score.awayGoals();
            }
            r32[i] = new Slot(X_R32L, y, home, away, hg, ag, pChamp(home), pChamp(away));
        }
        // Build R32 right side
        for (int i = 0; i < 8; i++) {
            int y = TOP_PAD + i * R32_STEP;
            String home = resolveSpec(R32_SPECS_RIGHT[i][0]);
            String away = resolveSpec(R32_SPECS_RIGHT[i][1]);
            Match played = findKnockoutMatch(matches, "Round of 32", home, away);
            int hg = -1, ag = -1;
            if (played != null && played.score != null) {
                hg = played.score.homeGoals(); ag = played.score.awayGoals();
            }
            r32[8 + i] = new Slot(X_R32R, y, home, away, hg, ag, pChamp(home), pChamp(away));
        }

        // Build R16 left (4 matches)
        for (int i = 0; i < 4; i++) {
            int topIdx = R16_LEFT_PAIRS[i][0], botIdx = R16_LEFT_PAIRS[i][1];
            int centerY = (r32[topIdx].y + r32[botIdx].y) / 2;
            String home = winnerOf(r32[topIdx]);
            String away = winnerOf(r32[botIdx]);
            Match played = findKnockoutMatch(matches, "Round of 16", home, away);
            int hg = -1, ag = -1;
            if (played != null && played.score != null) { hg=played.score.homeGoals(); ag=played.score.awayGoals(); }
            r16[i] = new Slot(X_R16L, centerY, home, away, hg, ag, pChamp(home), pChamp(away));
        }
        // Build R16 right (4 matches)
        for (int i = 0; i < 4; i++) {
            int topIdx = R16_RIGHT_PAIRS[i][0] + 8, botIdx = R16_RIGHT_PAIRS[i][1] + 8;
            int centerY = (r32[topIdx].y + r32[botIdx].y) / 2;
            String home = winnerOf(r32[topIdx]);
            String away = winnerOf(r32[botIdx]);
            Match played = findKnockoutMatch(matches, "Round of 16", home, away);
            int hg = -1, ag = -1;
            if (played != null && played.score != null) { hg=played.score.homeGoals(); ag=played.score.awayGoals(); }
            r16[4 + i] = new Slot(X_R16R, centerY, home, away, hg, ag, pChamp(home), pChamp(away));
        }

        // QF left (2 matches)
        for (int i = 0; i < 2; i++) {
            int topIdx = QF_LEFT_PAIRS[i][0], botIdx = QF_LEFT_PAIRS[i][1];
            int centerY = (r16[topIdx].y + r16[botIdx].y) / 2;
            String home = winnerOf(r16[topIdx]);
            String away = winnerOf(r16[botIdx]);
            Match played = findKnockoutMatch(matches, "Quarter-final", home, away);
            int hg = -1, ag = -1;
            if (played != null && played.score != null) { hg=played.score.homeGoals(); ag=played.score.awayGoals(); }
            qf[i] = new Slot(X_QFL, centerY, home, away, hg, ag, pChamp(home), pChamp(away));
        }
        // QF right (2 matches)
        for (int i = 0; i < 2; i++) {
            int topIdx = QF_RIGHT_PAIRS[i][0] + 4, botIdx = QF_RIGHT_PAIRS[i][1] + 4;
            int centerY = (r16[topIdx].y + r16[botIdx].y) / 2;
            String home = winnerOf(r16[topIdx]);
            String away = winnerOf(r16[botIdx]);
            Match played = findKnockoutMatch(matches, "Quarter-final", home, away);
            int hg = -1, ag = -1;
            if (played != null && played.score != null) { hg=played.score.homeGoals(); ag=played.score.awayGoals(); }
            qf[2 + i] = new Slot(X_QFR, centerY, home, away, hg, ag, pChamp(home), pChamp(away));
        }

        // SF (2 matches)
        int sfCenterY = (qf[0].y + qf[1].y) / 2;
        String sfHomeL = winnerOf(qf[0]), sfAwayL = winnerOf(qf[1]);
        sf[0] = new Slot(X_SFL, sfCenterY, sfHomeL, sfAwayL, -1, -1, pChamp(sfHomeL), pChamp(sfAwayL));

        int sfCenterYR = (qf[2].y + qf[3].y) / 2;
        String sfHomeR = winnerOf(qf[2]), sfAwayR = winnerOf(qf[3]);
        sf[1] = new Slot(X_SFR, sfCenterYR, sfHomeR, sfAwayR, -1, -1, pChamp(sfHomeR), pChamp(sfAwayR));

        // Final
        int finCenterY = (sf[0].y + sf[1].y) / 2;
        String finHome = winnerOf(sf[0]), finAway = winnerOf(sf[1]);
        fin = new Slot(X_FIN, finCenterY, finHome, finAway, -1, -1, pChamp(finHome), pChamp(finAway));

        int totalHeight = TOP_PAD + 8 * R32_STEP + CH + TOP_PAD;
        SwingUtilities.invokeLater(() -> setPreferredSize(new Dimension(CANVAS_W, totalHeight)));
    }

    // ── Pintura ───────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Fondo
        g2.setColor(BG);
        g2.fillRect(0, 0, getWidth(), getHeight());

        if (loading) {
            g2.setColor(TEXT_DIM);
            g2.setFont(new Font("Arial", Font.BOLD, 18));
            g2.drawString("Cargando bracket...", getWidth()/2 - 80, getHeight()/2);
            g2.dispose();
            return;
        }

        // Título del bracket
        g2.setColor(GOLD);
        g2.setFont(new Font("Arial", Font.BOLD, 16));
        g2.drawString("FIFA WORLD CUP 2026", X_FIN + CW/2 - 80, 30);
        g2.setColor(TEXT_DIM);
        g2.setFont(new Font("Arial", Font.PLAIN, 11));
        g2.drawString("FASE ELIMINATORIA", X_FIN + CW/2 - 50, 48);

        // Etiquetas de rondas
        drawRoundLabel(g2, X_R32L,  "R32");
        drawRoundLabel(g2, X_R16L,  "R16");
        drawRoundLabel(g2, X_QFL,   "QF");
        drawRoundLabel(g2, X_SFL,   "SF");
        drawRoundLabel(g2, X_FIN,   "FINAL");
        drawRoundLabel(g2, X_SFR,   "SF");
        drawRoundLabel(g2, X_QFR,   "QF");
        drawRoundLabel(g2, X_R16R,  "R16");
        drawRoundLabel(g2, X_R32R,  "R32");

        // Conectores (antes de las tarjetas para que queden detrás)
        drawConnectors(g2);

        // Tarjetas
        for (Slot s : r32) if (s != null) drawCard(g2, s);
        for (Slot s : r16) if (s != null) drawCard(g2, s);
        for (Slot s : qf)  if (s != null) drawCard(g2, s);
        for (Slot s : sf)  if (s != null) drawCard(g2, s);
        if (fin != null) drawFinalCard(g2, fin);

        g2.dispose();
    }

    private void drawRoundLabel(Graphics2D g2, int x, String label) {
        g2.setColor(TEXT_DIM);
        g2.setFont(new Font("Arial", Font.BOLD, 10));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(label, x + (CW - fm.stringWidth(label)) / 2, TOP_PAD - 12);
    }

    private void drawCard(Graphics2D g2, Slot s) {
        if (s == null) return;
        int x = s.x, y = s.y;

        // Sombra
        g2.setColor(new Color(0, 0, 0, 60));
        g2.fill(new RoundRectangle2D.Float(x+3, y+3, CW, CH, RADIUS, RADIUS));

        // Fondo de tarjeta
        g2.setColor(CARD_BG);
        g2.fill(new RoundRectangle2D.Float(x, y, CW, CH, RADIUS, RADIUS));

        // Borde
        g2.setColor(CARD_BORDER);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(x, y, CW, CH, RADIUS, RADIUS));

        // Línea divisora entre equipos
        g2.setColor(DIVIDER);
        g2.drawLine(x + 8, y + TEAM_H, x + CW - 8, y + TEAM_H);

        // Equipo local (home)
        drawTeamRow(g2, s.home, s.played() ? s.hGoals : -1,
                s.homeWins(), s.pHome,
                x, y, true);

        // Equipo visitante (away)
        drawTeamRow(g2, s.away, s.played() ? s.aGoals : -1,
                s.awayWins(), s.pAway,
                x, y + TEAM_H, false);
    }

    private void drawTeamRow(Graphics2D g2, String team, int goals,
                             boolean isWinner, double pChamp,
                             int cardX, int rowY, boolean isTop) {

        // Fondo ganador
        if (isWinner) {
            g2.setColor(WINNER_BG);
            if (isTop) {
                g2.fill(new RoundRectangle2D.Float(cardX+1, rowY+1, CW-2, TEAM_H-1, RADIUS, RADIUS));
            } else {
                g2.fill(new RoundRectangle2D.Float(cardX+1, rowY, CW-2, TEAM_H-1, RADIUS, RADIUS));
            }
        }

        int textY = rowY + TEAM_H/2 + 4;

        // Nombre del equipo
        g2.setFont(new Font("Arial", Font.BOLD, 11));
        g2.setColor(isWinner ? ACCENT : TEXT_MAIN);
        String display = abbreviate(team);
        g2.drawString(display, cardX + 8, textY);

        // P(campeón) en gris pequeño debajo del nombre — solo si no hay goles
        if (goals < 0 && pChamp > 0) {
            g2.setFont(new Font("Arial", Font.PLAIN, 8));
            g2.setColor(TEXT_DIM);
            g2.drawString(String.format("%.1f%%", pChamp * 100), cardX + 8, textY + 9);
        }

        // Goles
        if (goals >= 0) {
            g2.setFont(new Font("Arial", Font.BOLD, 14));
            g2.setColor(isWinner ? GOLD : TEXT_MAIN);
            String g = String.valueOf(goals);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(g, cardX + CW - 14 - fm.stringWidth(g), textY);
        }
    }

    private void drawFinalCard(Graphics2D g2, Slot s) {
        int x = s.x, y = s.y;

        // Glow dorado para la final
        for (int i = 6; i > 0; i--) {
            g2.setColor(new Color(255, 215, 0, 8 * i));
            g2.setStroke(new BasicStroke(i * 1.5f));
            g2.draw(new RoundRectangle2D.Float(x-i, y-i, CW+2*i, CH+2*i, RADIUS+i, RADIUS+i));
        }

        g2.setStroke(new BasicStroke(1f));
        drawCard(g2, s);

        // Badge "FINAL"
        g2.setColor(GOLD);
        g2.fillRoundRect(x + CW/2 - 20, y - 16, 40, 14, 6, 6);
        g2.setColor(BG);
        g2.setFont(new Font("Arial", Font.BOLD, 9));
        g2.drawString("FINAL", x + CW/2 - 14, y - 5);
    }

    private void drawConnectors(Graphics2D g2) {
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // R32 → R16 left
        for (int i = 0; i < 4; i++) {
            Slot top = r32[R16_LEFT_PAIRS[i][0]];
            Slot bot = r32[R16_LEFT_PAIRS[i][1]];
            Slot tgt = r16[i];
            if (top != null && bot != null && tgt != null) {
                drawConnector(g2, top, tgt, true);
                drawConnector(g2, bot, tgt, true);
            }
        }
        // R32 → R16 right
        for (int i = 0; i < 4; i++) {
            Slot top = r32[R16_RIGHT_PAIRS[i][0] + 8];
            Slot bot = r32[R16_RIGHT_PAIRS[i][1] + 8];
            Slot tgt = r16[4 + i];
            if (top != null && bot != null && tgt != null) {
                drawConnector(g2, top, tgt, false);
                drawConnector(g2, bot, tgt, false);
            }
        }
        // R16 → QF left
        for (int i = 0; i < 2; i++) {
            drawConnector(g2, r16[QF_LEFT_PAIRS[i][0]], qf[i], true);
            drawConnector(g2, r16[QF_LEFT_PAIRS[i][1]], qf[i], true);
        }
        // R16 → QF right
        for (int i = 0; i < 2; i++) {
            drawConnector(g2, r16[QF_RIGHT_PAIRS[i][0]+4], qf[2+i], false);
            drawConnector(g2, r16[QF_RIGHT_PAIRS[i][1]+4], qf[2+i], false);
        }
        // QF → SF
        drawConnector(g2, qf[0], sf[0], true);
        drawConnector(g2, qf[1], sf[0], true);
        drawConnector(g2, qf[2], sf[1], false);
        drawConnector(g2, qf[3], sf[1], false);
        // SF → Final
        drawConnector(g2, sf[0], fin, true);
        drawConnector(g2, sf[1], fin, false);
    }

    /**
     * Dibuja una línea en L desde el borde derecho/izquierdo del slot fuente
     * al borde izquierdo/derecho del slot destino.
     * @param rightToLeft si false, la línea va de derecha a izquierda (lado derecho del bracket)
     */
    private void drawConnector(Graphics2D g2, Slot src, Slot tgt, boolean leftSide) {
        if (src == null || tgt == null) return;

        int srcCenterY = src.y + CH / 2;
        int tgtCenterY = tgt.y + CH / 2;

        int srcX, tgtX, midX;
        if (leftSide) {
            srcX = src.x + CW;
            tgtX = tgt.x;
            midX = srcX + (tgtX - srcX) / 2;
        } else {
            srcX = src.x;
            tgtX = tgt.x + CW;
            midX = tgtX + (srcX - tgtX) / 2;
        }

        // Color de la línea: dorado si hay ganador definido, dim si no
        boolean hasWinner = src.homeWins() || src.awayWins();
        g2.setColor(hasWinner ? new Color(255, 215, 0, 180) : new Color(0x5A, 0x62, 0x7A));

        // Línea horizontal desde source
        g2.drawLine(srcX, srcCenterY, midX, srcCenterY);
        // Línea vertical al nivel del target
        g2.drawLine(midX, srcCenterY, midX, tgtCenterY);
        // Línea horizontal hacia target
        g2.drawLine(midX, tgtCenterY, tgtX, tgtCenterY);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveSpec(String spec) {
        if (spec == null) return "TBD";
        if (spec.startsWith("3")) return "Mejor 3°"; // best third — resolved later
        if (spec.length() == 2 && Character.isDigit(spec.charAt(0))) {
            int pos    = spec.charAt(0) - '0'; // 1 o 2
            char letter = spec.charAt(1);
            String groupName = "Group " + letter;
            List<String> standing = groupStandings.get(groupName);
            if (standing != null && standing.size() >= pos) return standing.get(pos - 1);
            return pos + letter + " (TBD)";
        }
        return spec;
    }

    /** Devuelve el ganador del slot si ya se jugó, o el favorito según rating si no. */
    private String winnerOf(Slot s) {
        if (s == null) return "TBD";
        if (s.homeWins()) return s.home;
        if (s.awayWins()) return s.away;
        // Sin resultado: usar el que tenga mayor P(campeón)
        return s.pHome >= s.pAway ? s.home : s.away;
    }

    private double pChamp(String team) {
        return pChampion.getOrDefault(team, 0.0);
    }

    private Match findKnockoutMatch(List<Match> matches, String round, String h, String a) {
        for (Match m : matches) {
            if (round.equalsIgnoreCase(m.status)
                    && m.homeTeam.equalsIgnoreCase(h)
                    && m.awayTeam.equalsIgnoreCase(a)) return m;
        }
        return null;
    }

    private String abbreviate(String team) {
        if (team == null || team.isEmpty()) return "TBD";
        if (team.length() <= 14) return team;
        // Abreviar nombres largos
        String[] words = team.split("\\s+");
        if (words.length >= 2) return words[0].substring(0, Math.min(3, words[0].length())).toUpperCase()
                + ". " + words[words.length-1];
        return team.substring(0, 13) + "…";
    }
}