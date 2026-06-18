package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.api.datasource.LiveStandingsProvider;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

import static com.josegabrielmarves.footballpredictor.ui.MainWindow.*;

/**
 * Bracket visual del Mundial 2026.
 * Clasificaciones en tiempo real desde worldcup26.ir/get/groups.
 * Fallback: computed desde el fixture de OpenFootball.
 */
public class BracketPanel extends JPanel {

    private static final int CW      = 165;
    private static final int CH      = 58;
    private static final int TEAM_H  = 29;
    private static final int RADIUS  = 10;
    private static final int COL_GAP = 44;

    private static final int X_R32L  = 20;
    private static final int X_R16L  = X_R32L + CW + COL_GAP;
    private static final int X_QFL   = X_R16L + CW + COL_GAP;
    private static final int X_SFL   = X_QFL  + CW + COL_GAP;
    private static final int X_FIN   = X_SFL  + CW + COL_GAP + 20;
    private static final int X_SFR   = X_FIN  + CW + COL_GAP + 20;
    private static final int X_QFR   = X_SFR  + CW + COL_GAP;
    private static final int X_R16R  = X_QFR  + CW + COL_GAP;
    private static final int X_R32R  = X_R16R + CW + COL_GAP;
    private static final int CANVAS_W = X_R32R + CW + 20;
    private static final int TOP_PAD  = 75;
    private static final int R32_STEP = 96;

    // Bracket oficial FIFA 2026
    private static final String[][] LEFT  = {
            {"1E","3ABCDF"},{"1I","3CDFGH"},{"2A","2B"},{"1F","2C"},
            {"1C","2F"},{"2E","2I"},{"1A","3CEFHI"},{"1L","3EHIJK"}
    };
    private static final String[][] RIGHT = {
            {"2K","2L"},{"1H","2J"},{"1D","3BEFIJ"},{"1G","3AEHIJ"},
            {"1J","2H"},{"2D","2G"},{"1B","3EFGIJ"},{"1K","3DEIJL"}
    };
    private static final int[][] R16L = {{0,1},{2,3},{4,5},{6,7}};
    private static final int[][] R16R = {{0,1},{2,3},{4,5},{6,7}};
    private static final int[][] QFL  = {{0,1},{2,3}};
    private static final int[][] QFR  = {{0,1},{2,3}};

    private record Slot(int x, int y, String home, String away,
                        int hg, int ag, double eloH, double eloA) {
        boolean played()   { return hg >= 0; }
        boolean homeWins() { return played() && hg > ag; }
        boolean awayWins() { return played() && ag > hg; }
    }

    private final Slot[] r32 = new Slot[16];
    private final Slot[] r16 = new Slot[8];
    private final Slot[] qf  = new Slot[4];
    private final Slot[] sf  = new Slot[2];
    private Slot fin = null;

    // Standings resueltos (desde API o computed)
    private Map<String, List<String>> standings = new HashMap<>();
    // team → [pts, gf, ga] para best-third
    private Map<String, int[]> teamStats = new HashMap<>();
    // Scores en vivo key = "Home:Away"
    private Map<String, int[]> liveScores = new HashMap<>();
    private Map<String, EloRating> ratings = new HashMap<>();

    private volatile boolean loading = true;
    private volatile boolean liveData = false; // true = datos de API en vivo

    public BracketPanel(MainWindow owner) {
        setBackground(BG);
        setPreferredSize(new Dimension(CANVAS_W, 900));
    }

    public void setMatches(List<Match> matches, Map<String, EloRating> r) {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                ratings = r;

                // 1. Intentar standings en tiempo real desde worldcup26.ir
                Map<String, List<String>> live = LiveStandingsProvider.fetchGroupStandings();
                if (!live.isEmpty()) {
                    standings = live;
                    liveScores = LiveStandingsProvider.fetchScores();
                    liveData = true;
                    System.out.println("[BracketPanel] Usando standings en vivo (" + live.size() + " grupos)");
                } else {
                    // 2. Fallback: computar desde fixture
                    computeStandingsFromFixture(matches);
                    liveData = false;
                    System.out.println("[BracketPanel] Fallback: standings computados del fixture");
                }

                buildSlots(matches);
                return null;
            }
            @Override protected void done() {
                loading = false;
                int h = TOP_PAD + 8 * R32_STEP + CH + TOP_PAD;
                setPreferredSize(new Dimension(CANVAS_W, h));
                revalidate();
                repaint();
            }
        }.execute();
    }

    // ── Standings computados (fallback) ───────────────────────────────────────

    private void computeStandingsFromFixture(List<Match> matches) {
        Map<String, List<Match>> byGroup = new LinkedHashMap<>();
        for (Match m : matches)
            if (m.group != null) byGroup.computeIfAbsent(m.group, k -> new ArrayList<>()).add(m);

        for (Map.Entry<String, List<Match>> e : byGroup.entrySet()) {
            Map<String, int[]> stats = new LinkedHashMap<>();
            for (Match m : e.getValue()) {
                stats.putIfAbsent(m.homeTeam, new int[3]);
                stats.putIfAbsent(m.awayTeam, new int[3]);
                if (m.score != null) {
                    int h = m.score.homeGoals(), a = m.score.awayGoals();
                    stats.get(m.homeTeam)[1] += h; stats.get(m.homeTeam)[2] += a;
                    stats.get(m.awayTeam)[1] += a; stats.get(m.awayTeam)[2] += h;
                    if (h > a)      stats.get(m.homeTeam)[0] += 3;
                    else if (a > h) stats.get(m.awayTeam)[0] += 3;
                    else { stats.get(m.homeTeam)[0]++; stats.get(m.awayTeam)[0]++; }
                }
            }
            teamStats.putAll(stats);
            List<String> ranked = new ArrayList<>(stats.keySet());
            ranked.sort((a, b) -> {
                int[] sa = stats.get(a), sb = stats.get(b);
                if (sb[0] != sa[0]) return sb[0] - sa[0];
                int gdA = sa[1]-sa[2], gdB = sb[1]-sb[2];
                if (gdB != gdA) return gdB - gdA;
                if (sb[1] != sa[1]) return sb[1] - sa[1];
                return Double.compare(elo(b), elo(a)); // desempate Elo
            });
            standings.put(e.getKey(), ranked);
        }
    }

    // ── Construcción de slots ─────────────────────────────────────────────────

    private void buildSlots(List<Match> matches) {
        for (int i = 0; i < 8; i++) {
            r32[i]   = makeSlot(X_R32L, TOP_PAD + i * R32_STEP, LEFT[i],  matches);
            r32[8+i] = makeSlot(X_R32R, TOP_PAD + i * R32_STEP, RIGHT[i], matches);
        }
        for (int i = 0; i < 4; i++) {
            r16[i]   = mergeSlot(X_R16L, r32[R16L[i][0]], r32[R16L[i][1]], matches);
            r16[4+i] = mergeSlot(X_R16R, r32[R16R[i][0]+8], r32[R16R[i][1]+8], matches);
        }
        for (int i = 0; i < 2; i++) {
            qf[i]   = mergeSlot(X_QFL, r16[QFL[i][0]], r16[QFL[i][1]], matches);
            qf[2+i] = mergeSlot(X_QFR, r16[QFR[i][0]+4], r16[QFR[i][1]+4], matches);
        }
        sf[0] = mergeSlot(X_SFL, qf[0], qf[1], matches);
        sf[1] = mergeSlot(X_SFR, qf[2], qf[3], matches);
        fin   = mergeSlot(X_FIN, sf[0], sf[1], matches);
    }

    private Slot makeSlot(int x, int y, String[] specs, List<Match> matches) {
        String home = resolve(specs[0]);
        String away = resolve(specs[1]);
        int[] score = findScore(home, away);
        return new Slot(x, y, home, away, score[0], score[1], elo(home), elo(away));
    }

    private Slot mergeSlot(int x, Slot top, Slot bot, List<Match> matches) {
        if (top == null || bot == null) return null;
        int y = (top.y + bot.y) / 2;
        String home = projected(top);
        String away = projected(bot);
        int[] score = findScore(home, away);
        return new Slot(x, y, home, away, score[0], score[1], elo(home), elo(away));
    }

    // ── Resolución de specs ───────────────────────────────────────────────────

    private String resolve(String spec) {
        if (spec == null) return "?";
        if (spec.length() > 1 && spec.charAt(0) == '3')
            return bestThird(spec.substring(1));
        if (spec.length() == 2 && Character.isDigit(spec.charAt(0))) {
            int pos = spec.charAt(0) - '0';
            List<String> st = standings.get("Group " + spec.charAt(1));
            if (st != null && st.size() >= pos) return st.get(pos - 1);
        }
        return spec;
    }

    private String bestThird(String letters) {
        String best = null;
        int bestPts = -1, bestGD = Integer.MIN_VALUE;
        double bestElo = -1;
        for (char c : letters.toCharArray()) {
            List<String> st = standings.get("Group " + c);
            if (st == null || st.size() < 3) continue;
            String team = st.get(2);
            int[] s = teamStats.getOrDefault(team, new int[3]);
            int pts = s[0], gd = s[1] - s[2];
            double e = elo(team);
            if (best == null || pts > bestPts
                    || (pts == bestPts && gd > bestGD)
                    || (pts == bestPts && gd == bestGD && e > bestElo)) {
                best = team; bestPts = pts; bestGD = gd; bestElo = e;
            }
        }
        if (best == null) { // sin partidos aún: mejor Elo del 3er lugar
            for (char c : letters.toCharArray()) {
                List<String> st = standings.get("Group " + c);
                if (st == null || st.size() < 3) continue;
                String team = st.get(2);
                if (best == null || elo(team) > bestElo) { best = team; bestElo = elo(team); }
            }
        }
        return best != null ? best : "Mejor 3°";
    }

    private String projected(Slot s) {
        if (s == null) return "?";
        if (s.homeWins()) return s.home;
        if (s.awayWins()) return s.away;
        return elo(s.home) >= elo(s.away) ? s.home : s.away;
    }

    private int[] findScore(String home, String away) {
        // Buscar en scores en vivo primero
        String key = home + ":" + away;
        int[] s = liveScores.get(key);
        if (s != null) return s;
        return new int[]{-1, -1};
    }

    private double elo(String team) {
        EloRating r = ratings.get(team);
        return r != null ? r.rating() : 1500.0;
    }

    // ── Pintura ───────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setColor(BG);
        g2.fillRect(0, 0, getWidth(), getHeight());

        if (loading) {
            g2.setColor(TEXT_DIM);
            g2.setFont(new Font("Arial", Font.BOLD, 16));
            String msg = "Cargando clasificaciones en vivo...";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
            g2.dispose();
            return;
        }

        // Título central
        g2.setColor(GOLD);
        g2.setFont(new Font("Arial", Font.BOLD, 15));
        String t = "FIFA WORLD CUP 2026";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(t, X_FIN + (CW - fm.stringWidth(t)) / 2, 28);

        // Indicador LIVE o Proyectado
        g2.setFont(new Font("Arial", Font.BOLD, 9));
        if (liveData) {
            g2.setColor(new Color(0x22, 0xC5, 0x5E));
            g2.fillRoundRect(X_FIN + CW/2 - 18, 32, 36, 13, 5, 5);
            g2.setColor(Color.WHITE);
            g2.drawString("● LIVE", X_FIN + CW/2 - 14, 42);
        } else {
            g2.setColor(TEXT_DIM);
            g2.setFont(new Font("Arial", Font.PLAIN, 10));
            String sub = "PROYECCIÓN DEL MODELO";
            fm = g2.getFontMetrics();
            g2.drawString(sub, X_FIN + (CW - fm.stringWidth(sub)) / 2, 44);
        }

        // Etiquetas de rondas
        String[] lbls = {"R32","R16","QF","SF","FINAL","SF","QF","R16","R32"};
        int[] xs = {X_R32L,X_R16L,X_QFL,X_SFL,X_FIN,X_SFR,X_QFR,X_R16R,X_R32R};
        for (int i = 0; i < lbls.length; i++) drawRoundLabel(g2, xs[i], lbls[i]);

        drawConnectors(g2);
        for (Slot s : r32) drawCard(g2, s, false);
        for (Slot s : r16) drawCard(g2, s, false);
        for (Slot s : qf)  drawCard(g2, s, false);
        for (Slot s : sf)  drawCard(g2, s, false);
        drawCard(g2, fin, true);

        g2.dispose();
    }

    private void drawRoundLabel(Graphics2D g2, int x, String label) {
        g2.setColor(TEXT_DIM);
        g2.setFont(new Font("Arial", Font.BOLD, 10));
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(label, x + (CW - fm.stringWidth(label)) / 2, TOP_PAD - 14);
    }

    private void drawCard(Graphics2D g2, Slot s, boolean isFinal) {
        if (s == null) return;
        int x = s.x, y = s.y;

        if (isFinal) {
            for (int i = 5; i > 0; i--) {
                g2.setColor(new Color(255, 215, 0, 10 * i));
                g2.setStroke(new BasicStroke(i * 1.5f));
                g2.draw(new RoundRectangle2D.Float(x-i, y-i, CW+2*i, CH+2*i, RADIUS+i, RADIUS+i));
            }
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(GOLD);
            g2.fillRoundRect(x + CW/2 - 22, y - 17, 44, 14, 6, 6);
            g2.setColor(BG);
            g2.setFont(new Font("Arial", Font.BOLD, 9));
            g2.drawString("FINAL", x + CW/2 - 15, y - 6);
        }

        g2.setColor(new Color(0, 0, 0, 50));
        g2.fill(new RoundRectangle2D.Float(x+3, y+3, CW, CH, RADIUS, RADIUS));

        g2.setColor(CARD_BG);
        g2.fill(new RoundRectangle2D.Float(x, y, CW, CH, RADIUS, RADIUS));

        if (isFinal)         g2.setColor(new Color(255, 215, 0, 100));
        else if (s.played()) g2.setColor(new Color(0, 163, 255, 80));
        else                 g2.setColor(DIVIDER);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(x, y, CW, CH, RADIUS, RADIUS));

        g2.setColor(DIVIDER);
        g2.drawLine(x + 8, y + TEAM_H, x + CW - 8, y + TEAM_H);

        drawTeamRow(g2, s.home, s.played() ? s.hg : -1, s.homeWins(), s.eloH, x, y, true);
        drawTeamRow(g2, s.away, s.played() ? s.ag : -1, s.awayWins(), s.eloA, x, y + TEAM_H, false);
    }

    private void drawTeamRow(Graphics2D g2, String team, int goals,
                             boolean winner, double eloVal, int cx, int ry, boolean top) {
        if (winner) {
            g2.setColor(WINNER_BG);
            g2.fill(top
                    ? new RoundRectangle2D.Float(cx+1, ry+1, CW-2, TEAM_H-1, RADIUS, RADIUS)
                    : new RoundRectangle2D.Float(cx+1, ry,   CW-2, TEAM_H-2, RADIUS, RADIUS));
        }
        int ty = ry + TEAM_H / 2 + 4;
        g2.setFont(new Font("Arial", Font.BOLD, 11));
        g2.setColor(winner ? ACCENT : TEXT_MAIN);
        g2.drawString(shorten(team), cx + 8, ty);

        if (goals < 0) {
            g2.setFont(new Font("Arial", Font.PLAIN, 8));
            g2.setColor(TEXT_DIM);
            g2.drawString(String.format("Elo %.0f", eloVal), cx + 8, ty + 9);
        }
        if (goals >= 0) {
            g2.setFont(new Font("Arial", Font.BOLD, 14));
            g2.setColor(winner ? GOLD : TEXT_MAIN);
            String gs = String.valueOf(goals);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(gs, cx + CW - 13 - fm.stringWidth(gs), ty);
        }
    }

    private void drawConnectors(Graphics2D g2) {
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 4; i++) {
            connect(g2, r32[R16L[i][0]],   r16[i],   true);
            connect(g2, r32[R16L[i][1]],   r16[i],   true);
            connect(g2, r32[R16R[i][0]+8], r16[4+i], false);
            connect(g2, r32[R16R[i][1]+8], r16[4+i], false);
        }
        for (int i = 0; i < 2; i++) {
            connect(g2, r16[QFL[i][0]],   qf[i],   true);
            connect(g2, r16[QFL[i][1]],   qf[i],   true);
            connect(g2, r16[QFR[i][0]+4], qf[2+i], false);
            connect(g2, r16[QFR[i][1]+4], qf[2+i], false);
        }
        connect(g2, qf[0], sf[0], true);  connect(g2, qf[1], sf[0], true);
        connect(g2, qf[2], sf[1], false); connect(g2, qf[3], sf[1], false);
        connect(g2, sf[0], fin,   true);  connect(g2, sf[1], fin,   false);
    }

    private void connect(Graphics2D g2, Slot src, Slot tgt, boolean left) {
        if (src == null || tgt == null) return;
        boolean confirmed = src.homeWins() || src.awayWins();
        g2.setColor(confirmed ? new Color(255, 215, 0, 160) : new Color(0x4A, 0x52, 0x6A));
        int sy = src.y + CH / 2, ty = tgt.y + CH / 2;
        int sx, tx, mx;
        if (left) { sx = src.x + CW; tx = tgt.x; mx = sx + (tx - sx) / 2; }
        else       { sx = src.x; tx = tgt.x + CW; mx = tx + (sx - tx) / 2; }
        g2.drawLine(sx, sy, mx, sy);
        g2.drawLine(mx, sy, mx, ty);
        g2.drawLine(mx, ty, tx, ty);
    }

    private String shorten(String t) {
        if (t == null || t.isEmpty()) return "?";
        if (t.length() <= 14) return t;
        String[] w = t.split("\\s+");
        return w.length >= 2
                ? w[0].substring(0, Math.min(3, w[0].length())).toUpperCase() + ". " + w[w.length-1]
                : t.substring(0, 13) + "…";
    }
}