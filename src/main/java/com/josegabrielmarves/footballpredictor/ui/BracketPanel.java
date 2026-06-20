package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

import static com.josegabrielmarves.footballpredictor.ui.MainWindow.*;

/**
 * Panel de grupos y bracket del Mundial 2026.
 * Muestra los 12 grupos con standings en tiempo real (4 por fila, 3 filas)
 * y el bracket de R32 en la parte inferior.
 */
public class BracketPanel extends JPanel {

    // ── Colores oficiales por grupo ───────────────────────────────────────────
    private static final Map<String, Color> GCOLOR = new LinkedHashMap<>();
    static {
        GCOLOR.put("Group A", new Color(0x27,0xAE,0x60));
        GCOLOR.put("Group B", new Color(0xC0,0x39,0x2B));
        GCOLOR.put("Group C", new Color(0xE6,0x7E,0x22));
        GCOLOR.put("Group D", new Color(0x1A,0x5C,0x9E));
        GCOLOR.put("Group E", new Color(0x8E,0x44,0xAD));
        GCOLOR.put("Group F", new Color(0x17,0x9E,0x86));
        GCOLOR.put("Group G", new Color(0x92,0x2B,0x21));
        GCOLOR.put("Group H", new Color(0x1E,0x84,0x49));
        GCOLOR.put("Group I", new Color(0x6C,0x34,0x83));
        GCOLOR.put("Group J", new Color(0x1A,0x52,0x76));
        GCOLOR.put("Group K", new Color(0xD3,0x54,0x00));
        GCOLOR.put("Group L", new Color(0x7D,0x66,0x08));
    }
    private static final String[] GROUP_ORDER = {
            "Group A","Group B","Group C","Group D",
            "Group E","Group F","Group G","Group H",
            "Group I","Group J","Group K","Group L"
    };

    // ── Bracket R32 oficial FIFA 2026 ─────────────────────────────────────────
    private static final String[][] LEFT_R32 = {
            {"1E","3ABCDF"},{"1I","3CDFGH"},{"2A","2B"},{"1F","2C"},
            {"1C","2F"},{"2E","2I"},{"1A","3CEFHI"},{"1L","3EHIJK"}
    };
    private static final String[][] RIGHT_R32 = {
            {"2K","2L"},{"1H","2J"},{"1D","3BEFIJ"},{"1G","3AEHIJ"},
            {"1J","2H"},{"2D","2G"},{"1B","3EFGIJ"},{"1K","3DEIJL"}
    };

    // ── Dimensiones ───────────────────────────────────────────────────────────
    private static final int COLS        = 4;       // grupos por fila
    private static final int ROWS        = 3;       // filas de grupos
    private static final int CARD_W      = 310;     // ancho tarjeta de grupo
    private static final int CARD_H      = 150;     // alto tarjeta de grupo
    private static final int HGAP        = 12;      // gap horizontal entre tarjetas
    private static final int VGAP        = 10;      // gap vertical entre filas
    private static final int PAD         = 16;      // padding lateral
    private static final int TITLE_H     = 45;      // alto del título
    private static final int BRACKET_H   = 310;     // alto del bracket R32

    private static final int CANVAS_W = COLS * CARD_W + (COLS - 1) * HGAP + 2 * PAD;
    private static final int CANVAS_H = TITLE_H + ROWS * CARD_H + (ROWS - 1) * VGAP + VGAP + BRACKET_H + PAD;

    // ── Datos ─────────────────────────────────────────────────────────────────
    private record TeamRow(String name, int played, int pts, int gf, int ga) {
        int gd() { return gf - ga; }
    }
    private final Map<String, List<TeamRow>> groupData = new LinkedHashMap<>();
    private Map<String, EloRating> ratings = new HashMap<>();
    private volatile boolean loading = true;

    public BracketPanel(MainWindow owner) {
        setBackground(BG);
        setPreferredSize(new Dimension(CANVAS_W, CANVAS_H));
    }

    // ── API ───────────────────────────────────────────────────────────────────

    public void setMatches(List<Match> matches, Map<String, EloRating> r) {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                ratings = r;
                computeGroupData(matches);
                return null;
            }
            @Override protected void done() {
                loading = false;
                repaint();
            }
        }.execute();
    }

    // ── Computar standings ────────────────────────────────────────────────────

    private void computeGroupData(List<Match> matches) {
        Map<String, Map<String, int[]>> raw = new LinkedHashMap<>();
        for (Match m : matches) {
            if (m.group == null) continue;
            Map<String, int[]> g = raw.computeIfAbsent(m.group, k -> new LinkedHashMap<>());
            g.putIfAbsent(m.homeTeam, new int[4]);
            g.putIfAbsent(m.awayTeam, new int[4]);
            if (m.score != null) {
                int hg = m.score.homeGoals(), ag = m.score.awayGoals();
                int[] h = g.get(m.homeTeam), a = g.get(m.awayTeam);
                h[1]+=hg; h[2]+=ag; h[3]++;
                a[1]+=ag; a[2]+=hg; a[3]++;
                if (hg>ag) h[0]+=3; else if (ag>hg) a[0]+=3; else { h[0]++; a[0]++; }
            }
        }
        groupData.clear();
        for (String gName : GROUP_ORDER) {
            Map<String,int[]> g = raw.get(gName);
            if (g == null) continue;
            List<TeamRow> rows = new ArrayList<>();
            g.forEach((t,s) -> rows.add(new TeamRow(t, s[3], s[0], s[1], s[2])));
            rows.sort((a,b) -> {
                if (b.pts()!=a.pts()) return b.pts()-a.pts();
                if (b.gd()!=a.gd())  return b.gd()-a.gd();
                if (b.gf()!=a.gf())  return b.gf()-a.gf();
                return Double.compare(elo(b.name()), elo(a.name()));
            });
            groupData.put(gName, rows);
        }
    }

    private double elo(String team) {
        EloRating r = ratings.get(team);
        return r != null ? r.rating() : 1500.0;
    }

    // ── Pintura principal ─────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setColor(BG);
        g2.fillRect(0, 0, getWidth(), getHeight());

        if (loading) {
            g2.setColor(TEXT_DIM);
            g2.setFont(new Font("Arial", Font.BOLD, 16));
            String msg = "Cargando clasificaciones...";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, (getWidth()-fm.stringWidth(msg))/2, getHeight()/2);
            g2.dispose();
            return;
        }

        // Título
        g2.setColor(GOLD);
        g2.setFont(new Font("Arial", Font.BOLD, 17));
        String title = "⚽  FIFA WORLD CUP 2026 — FASE DE GRUPOS";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(title, (CANVAS_W - fm.stringWidth(title))/2, 30);

        // 12 grupos en grid 4×3
        int idx = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                if (idx >= GROUP_ORDER.length) break;
                int x = PAD + col * (CARD_W + HGAP);
                int y = TITLE_H + row * (CARD_H + VGAP);
                drawGroupCard(g2, x, y, GROUP_ORDER[idx++]);
            }
        }

        // Bracket R32
        int bracketY = TITLE_H + ROWS * (CARD_H + VGAP) + VGAP;
        drawBracketSection(g2, bracketY);

        g2.dispose();
    }

    // ── Tarjeta de grupo ──────────────────────────────────────────────────────

    private void drawGroupCard(Graphics2D g2, int x, int y, String groupName) {
        Color gc = GCOLOR.getOrDefault(groupName, ACCENT);
        List<TeamRow> teams = groupData.get(groupName);

        // Sombra
        g2.setColor(new Color(0,0,0,55));
        g2.fill(new RoundRectangle2D.Float(x+2, y+2, CARD_W, CARD_H, 8, 8));

        // Fondo
        g2.setColor(CARD_BG);
        g2.fill(new RoundRectangle2D.Float(x, y, CARD_W, CARD_H, 8, 8));

        // Header coloreado
        g2.setColor(gc);
        g2.fill(new RoundRectangle2D.Float(x, y, CARD_W, 26, 8, 8));
        g2.fillRect(x, y+14, CARD_W, 12);

        // Nombre del grupo
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 12));
        FontMetrics hfm = g2.getFontMetrics();
        String label = "GROUP " + groupName.replace("Group ", "");
        g2.drawString(label, x + (CARD_W - hfm.stringWidth(label))/2, y+18);

        // Encabezados de columnas
        int hy = y + 38;
        g2.setFont(new Font("Arial", Font.BOLD, 8));
        g2.setColor(TEXT_DIM);
        g2.drawString("EQUIPO",   x+22, hy);
        g2.drawString("PJ",       x+CARD_W-105, hy);
        g2.drawString("GF",       x+CARD_W-82,  hy);
        g2.drawString("GC",       x+CARD_W-60,  hy);
        g2.drawString("DIF",      x+CARD_W-37,  hy);
        g2.drawString("PTS",      x+CARD_W-14,  hy);

        // Línea separadora
        g2.setColor(DIVIDER);
        g2.setStroke(new BasicStroke(0.8f));
        g2.drawLine(x+6, hy+3, x+CARD_W-6, hy+3);

        // Filas de equipos
        if (teams != null) {
            for (int i = 0; i < Math.min(4, teams.size()); i++) {
                TeamRow t = teams.get(i);
                int ry = y + 48 + i * 24;
                boolean qualified = i < 2;

                // Fondo del líder
                if (i == 0 && t.pts() > 0) {
                    g2.setColor(new Color(gc.getRed(), gc.getGreen(), gc.getBlue(), 25));
                    g2.fillRoundRect(x+2, ry-13, CARD_W-4, 20, 4, 4);
                }

                // Posición
                g2.setColor(i == 0 ? gc : TEXT_DIM);
                g2.setFont(new Font("Arial", Font.BOLD, 9));
                g2.drawString(String.valueOf(i+1), x+7, ry);

                // Nombre del equipo
                g2.setColor(qualified ? TEXT_MAIN : TEXT_DIM);
                g2.setFont(new Font("Arial", qualified ? Font.BOLD : Font.PLAIN, 10));
                g2.drawString(shorten(t.name(), 17), x+20, ry);

                // Estadísticas
                g2.setFont(new Font("Arial", Font.PLAIN, 10));
                g2.setColor(TEXT_DIM);
                drawRight(g2, String.valueOf(t.played()), x+CARD_W-95, ry, 18);
                drawRight(g2, String.valueOf(t.gf()),     x+CARD_W-72, ry, 18);
                drawRight(g2, String.valueOf(t.ga()),     x+CARD_W-50, ry, 18);

                // Diferencia de goles coloreada
                int gd = t.gd();
                g2.setColor(gd > 0 ? new Color(0x27,0xAE,0x60) :
                        gd < 0 ? new Color(0xE7,0x4C,0x3C) : TEXT_DIM);
                drawRight(g2, (gd>0?"+":"") + gd, x+CARD_W-28, ry, 22);

                // Puntos (bold y blancos si clasifican)
                g2.setColor(qualified ? Color.WHITE : TEXT_DIM);
                g2.setFont(new Font("Arial", Font.BOLD, 10));
                drawRight(g2, String.valueOf(t.pts()), x+CARD_W-6, ry, 16);
            }
        } else {
            g2.setColor(TEXT_DIM);
            g2.setFont(new Font("Arial", Font.PLAIN, 10));
            g2.drawString("Sin datos", x+10, y+75);
        }

        // Borde de la tarjeta
        g2.setColor(gc.darker());
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(x, y, CARD_W, CARD_H, 8, 8));
    }

    // ── Sección bracket R32 ───────────────────────────────────────────────────

    private void drawBracketSection(Graphics2D g2, int y) {
        // Fondo del bracket
        g2.setColor(new Color(0x14, 0x18, 0x20));
        g2.fillRect(0, y, CANVAS_W, BRACKET_H);

        // Título sección
        g2.setColor(GOLD);
        g2.setFont(new Font("Arial", Font.BOLD, 13));
        String bt = "RONDA DE 32 — Proyección del modelo";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(bt, (CANVAS_W - fm.stringWidth(bt))/2, y + 22);

        // División central
        g2.setColor(new Color(0xFF,0xD7,0x00,60));
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0, new float[]{4,4}, 0));
        g2.drawLine(CANVAS_W/2, y+32, CANVAS_W/2, y+BRACKET_H-10);
        g2.setStroke(new BasicStroke(1f));

        // "FINAL" en el centro
        int finalY = y + BRACKET_H/2 + 10;
        g2.setColor(new Color(0x1A,0x1F,0x2A));
        g2.fillRoundRect(CANVAS_W/2-55, finalY-32, 110, 50, 8, 8);
        for (int i = 4; i > 0; i--) {
            g2.setColor(new Color(255,215,0, 8*i));
            g2.setStroke(new BasicStroke(i*1.2f));
            g2.drawRoundRect(CANVAS_W/2-55-i, finalY-32-i, 110+2*i, 50+2*i, 8, 8);
        }
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(GOLD);
        g2.setFont(new Font("Arial", Font.BOLD, 11));
        g2.drawString("🏆 FINAL", CANVAS_W/2-26, finalY-10);
        g2.setColor(TEXT_DIM);
        g2.setFont(new Font("Arial", Font.PLAIN, 9));
        g2.drawString("19 Jul · NY", CANVAS_W/2-22, finalY+6);

        // Slots R32 — 2 columnas por lado
        int slotW = 185;
        int slotH = 46;
        int slotGap = 6;
        int totalSlots = LEFT_R32.length;
        int totalH = totalSlots * (slotH + slotGap) - slotGap;
        int startY = y + (BRACKET_H - totalH) / 2;

        // Columna izquierda (slots 0-7)
        int leftColX = PAD;
        for (int i = 0; i < LEFT_R32.length; i++) {
            int sy = startY + i * (slotH + slotGap);
            drawR32Slot(g2, leftColX, sy, slotW, slotH, LEFT_R32[i][0], LEFT_R32[i][1]);
        }

        // Columna derecha (slots 0-7 espejados)
        int rightColX = CANVAS_W - PAD - slotW;
        for (int i = 0; i < RIGHT_R32.length; i++) {
            int sy = startY + i * (slotH + slotGap);
            drawR32Slot(g2, rightColX, sy, slotW, slotH, RIGHT_R32[i][0], RIGHT_R32[i][1]);
        }

        // Etiqueta IZQUIERDA / DERECHA
        g2.setColor(TEXT_DIM);
        g2.setFont(new Font("Arial", Font.BOLD, 9));
        g2.drawString("◀  LADO IZQUIERDO", leftColX, y + 35);
        g2.drawString("LADO DERECHO  ▶", rightColX + slotW - 110, y + 35);
    }

    private void drawR32Slot(Graphics2D g2, int x, int y, int w, int h,
                             String spec1, String spec2) {
        // Fondo
        g2.setColor(CARD_BG);
        g2.fill(new RoundRectangle2D.Float(x, y, w, h, 6, 6));
        g2.setColor(DIVIDER);
        g2.setStroke(new BasicStroke(0.8f));
        g2.draw(new RoundRectangle2D.Float(x, y, w, h, 6, 6));

        // Línea divisora entre los dos equipos
        g2.drawLine(x+4, y+h/2, x+w-4, y+h/2);

        // Equipo 1
        drawSpecRow(g2, x, y+h/4+3, w, spec1, true);
        // Equipo 2
        drawSpecRow(g2, x, y+3*h/4+3, w, spec2, false);
    }

    private void drawSpecRow(Graphics2D g2, int x, int y, int w,
                             String spec, boolean isTop) {
        // Badge de spec
        g2.setColor(ACCENT);
        g2.setFont(new Font("Arial", Font.BOLD, 8));
        g2.drawString(spec, x+5, y);

        // Nombre resuelto
        String resolved = resolveSpec(spec);
        g2.setColor(TEXT_MAIN);
        g2.setFont(new Font("Arial", Font.PLAIN, 9));
        String display = shorten(resolved, 22);
        g2.drawString(display, x+42, y);
    }

    // ── Resolución de specs ───────────────────────────────────────────────────

    private String resolveSpec(String spec) {
        if (spec == null) return "?";
        if (spec.length() > 1 && spec.charAt(0) == '3')
            return bestThird(spec.substring(1));
        if (spec.length() == 2 && Character.isDigit(spec.charAt(0))) {
            int pos = spec.charAt(0) - '0';
            List<TeamRow> st = groupData.get("Group " + spec.charAt(1));
            if (st != null && st.size() >= pos) return st.get(pos-1).name();
        }
        return spec;
    }

    private String bestThird(String letters) {
        String best = null; int bestPts = -1; double bestElo = -1;
        for (char c : letters.toCharArray()) {
            List<TeamRow> st = groupData.get("Group " + c);
            if (st == null || st.size() < 3) continue;
            TeamRow t = st.get(2);
            double e = elo(t.name());
            if (best==null || t.pts()>bestPts || (t.pts()==bestPts && e>bestElo)) {
                best=t.name(); bestPts=t.pts(); bestElo=e;
            }
        }
        return best != null ? best : "Mejor 3°";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Dibuja texto alineado a la derecha dentro de [x, x+width]. */
    private void drawRight(Graphics2D g2, String text, int x, int y, int width) {
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, x + width - fm.stringWidth(text), y);
    }

    private String shorten(String t, int max) {
        if (t == null || t.isEmpty()) return "?";
        if (t.length() <= max) return t;
        String[] w = t.split("\\s+");
        if (w.length >= 2 && w[0].length() + 1 + w[w.length-1].length() <= max)
            return w[0] + " " + w[w.length-1];
        return t.substring(0, max-1) + "…";
    }
}