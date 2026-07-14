package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.api.BracketApiClient;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.ui.theme.AppTheme;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;

import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BracketView extends VBox {

    private static final Map<String, Color> GCOLOR = new LinkedHashMap<>();

    static {
        GCOLOR.put("Group A", Color.rgb(0x27, 0xAE, 0x60));
        GCOLOR.put("Group B", Color.rgb(0xC0, 0x39, 0x2B));
        GCOLOR.put("Group C", Color.rgb(0xE6, 0x7E, 0x22));
        GCOLOR.put("Group D", Color.rgb(0x1A, 0x5C, 0x9E));
        GCOLOR.put("Group E", Color.rgb(0x8E, 0x44, 0xAD));
        GCOLOR.put("Group F", Color.rgb(0x17, 0x9E, 0x86));
        GCOLOR.put("Group G", Color.rgb(0x92, 0x2B, 0x21));
        GCOLOR.put("Group H", Color.rgb(0x1E, 0x84, 0x49));
        GCOLOR.put("Group I", Color.rgb(0x6C, 0x34, 0x83));
        GCOLOR.put("Group J", Color.rgb(0x1A, 0x52, 0x76));
        GCOLOR.put("Group K", Color.rgb(0xD3, 0x54, 0x00));
        GCOLOR.put("Group L", Color.rgb(0x7D, 0x66, 0x08));
    }

    private static final String[] GROUP_ORDER = {
        "Group A", "Group B", "Group C", "Group D",
        "Group E", "Group F", "Group G", "Group H",
        "Group I", "Group J", "Group K", "Group L"
    };

    private record TeamRow(String name, int played, int pts, int gf, int ga) {
        int gd() {
            return gf - ga;
        }
    }

    private record BracketMatch(String t1, String t2, String winner, double confidence) {}

    private final Map<String, List<TeamRow>> groupData = new LinkedHashMap<>();
    private Map<String, EloRating> ratings = new HashMap<>();
    private final GridPane groupsGrid = new GridPane();
    private String predictedChampion = "TBD";

    private static final int CARD_W = 300, CARD_H = 130;
    private static final int HGAP = 12, VGAP = 10, PAD = 16;

    private static final double BW = 148;
    private static final double BH = 36;
    private static final double BGAP = 4;
    private static final double SLOT = BH + BGAP;
    private static final double GAP = 36;
    private static final double R32_LEFT  = 10;
    private static final double R16_LEFT  = R32_LEFT + BW + GAP;
    private static final double QF_LEFT   = R16_LEFT + BW + GAP;
    private static final double SF_LEFT   = QF_LEFT + BW + GAP;
    private static final double FINAL_X   = SF_LEFT + BW + GAP;
    private static final double SF_RIGHT  = FINAL_X + BW + GAP;
    private static final double QF_RIGHT  = SF_RIGHT + BW + GAP;
    private static final double R16_RIGHT = QF_RIGHT + BW + GAP;
    private static final double R32_RIGHT = R16_RIGHT + BW + GAP;
    private static final double BRACKET_W = R32_RIGHT + BW + 10;
    private static final double TOP_Y = 16;

    private final VBox bracketContainer = new VBox();
    private final AnchorPane bracketPane = new AnchorPane();
    private final BracketApiClient bracketApi = new BracketApiClient();
    private ScheduledExecutorService refresher;

    // Bracket real por ronda (desde API con placeholders resueltos)
    private List<BracketMatch> realR32Left = new ArrayList<>();
    private List<BracketMatch> realR32Right = new ArrayList<>();
    private List<BracketMatch> realR16Left = new ArrayList<>();
    private List<BracketMatch> realR16Right = new ArrayList<>();
    private List<BracketMatch> realQFLeft = new ArrayList<>();
    private List<BracketMatch> realQFRight = new ArrayList<>();
    private List<BracketMatch> realSF = new ArrayList<>();
    private List<BracketMatch> realFinal = new ArrayList<>();

    public BracketView(MainWindow owner) {
        setSpacing(0);
        setStyle("-fx-background-color: #0F1217;");

        Label tt = new Label("🏆 MUNDIAL 2026 — RESULTADOS EN VIVO");
        tt.setTextFill(AppTheme.GOLD());
        tt.setFont(Font.font("Arial", FontWeight.BOLD, 17));
        tt.setPadding(new Insets(10, 0, 8, 0));
        tt.setAlignment(Pos.CENTER);
        tt.setMaxWidth(Double.MAX_VALUE);

        Label sub = new Label("Datos actualizados cada 6h · openfootball.org");
        sub.setTextFill(AppTheme.DIM());
        sub.setFont(Font.font("Arial", 11));
        sub.setPadding(new Insets(0, 0, 6, 0));
        sub.setAlignment(Pos.CENTER);
        sub.setMaxWidth(Double.MAX_VALUE);

        groupsGrid.setHgap(HGAP);
        groupsGrid.setVgap(VGAP);
        groupsGrid.setPadding(new Insets(0, PAD, 0, PAD));

        getChildren().addAll(tt, sub, groupsGrid, bracketContainer);

        // Auto-refresh cada 6 horas
        refresher = Executors.newSingleThreadScheduledExecutor();
        refresher.scheduleAtFixedRate(() -> Platform.runLater(this::refreshBracket), 6, 6, TimeUnit.HOURS);

        // NUEVO: Hacer clic en el título para refrescar manualmente
        tt.setOnMouseClicked(e -> refreshNow());
        sub.setOnMouseClicked(e -> refreshNow());
        setOnMouseClicked(e -> refreshNow()); // Clic en cualquier parte del VBox
    }

    // NUEVO: Método público para forzar actualización inmediata (botón manual)
    public void refreshNow() {
        Platform.runLater(this::refreshBracket);
    }

    public void setMatches(List<Match> ms, Map<String, EloRating> r) {
        ratings = r;
        Task<Void> t = new Task<>() {
            @Override
            protected Void call() {
                computeGroupData(ms);
                refreshBracket();
                return null;
            }
        };
        t.setOnSucceeded(e -> {
            renderGroups();
            FadeTransition ft = new FadeTransition(Duration.millis(400), BracketView.this);
            ft.setFromValue(0.3);
            ft.setToValue(1.0);
            ft.play();
        });
        new Thread(t).start();
    }

    /**
     * Carga el bracket REAL completo desde la API de openfootball.
     * NO predice nada. Si un partido no está jugado → TBD.
     */
    private void refreshBracket() {
        try {
            bracketApi.refresh();
            Map<String, List<BracketApiClient.BracketMatch>> full = bracketApi.getFullBracket();

            // Log detallado
            full.forEach((round, matches) ->
                System.out.printf("[BracketView] API round: '%s' → %d matches%n", round, matches.size()));

            // Limpiar listas anteriores
            realR32Left.clear(); realR32Right.clear();
            realR16Left.clear(); realR16Right.clear();
            realQFLeft.clear(); realQFRight.clear();
            realSF.clear(); realFinal.clear();

            // R32 — separar en izquierda/derecha (8+8)
            List<BracketApiClient.BracketMatch> r32 = full.getOrDefault("Round of 32", List.of());
            if (r32.isEmpty()) {
                System.err.println("[BracketView] ⚠️ R32 vacío — mostrando TBD");
                return;
            }
            int half = r32.size() / 2;
            for (int i = 0; i < r32.size(); i++) {
                BracketApiClient.BracketMatch bm = r32.get(i);
                if (i < half) realR32Left.add(apiToBracketMatch(bm, true, true));
                else          realR32Right.add(apiToBracketMatch(bm, false, true));
            }

            // R16 — 4 izquierda, 4 derecha
            List<BracketApiClient.BracketMatch> r16 = full.getOrDefault("Round of 16", List.of());
            int r16Half = r16.size() / 2;
            for (int i = 0; i < r16.size(); i++) {
                BracketApiClient.BracketMatch bm = r16.get(i);
                if (i < r16Half) realR16Left.add(apiToBracketMatch(bm, false, false));
                else             realR16Right.add(apiToBracketMatch(bm, false, false));
            }

            // QF — 2 izquierda, 2 derecha
            List<BracketApiClient.BracketMatch> qf = full.getOrDefault("Quarter-final", List.of());
            int qfHalf = qf.size() / 2;
            for (int i = 0; i < qf.size(); i++) {
                BracketApiClient.BracketMatch bm = qf.get(i);
                if (i < qfHalf) realQFLeft.add(apiToBracketMatch(bm, false, false));
                else            realQFRight.add(apiToBracketMatch(bm, false, false));
            }

            // SF — 2 partidos (101, 102)
            List<BracketApiClient.BracketMatch> sf = full.getOrDefault("Semi-final", List.of());
            for (BracketApiClient.BracketMatch bm : sf) {
                realSF.add(apiToBracketMatch(bm, false, false));
            }

            // Final
            List<BracketApiClient.BracketMatch> finalMatch = full.getOrDefault("Final", List.of());
            for (BracketApiClient.BracketMatch bm : finalMatch) {
                realFinal.add(apiToBracketMatch(bm, false, false));
            }

            // Campeón
            if (!realFinal.isEmpty() && realFinal.get(0).winner != null
                    && !realFinal.get(0).winner.equals("?")) {
                predictedChampion = realFinal.get(0).winner;
            } else {
                predictedChampion = "TBD";
            }

            System.out.printf("[BracketView] ✅ Cargado: R32 L=%d R=%d, R16 L=%d R=%d, QF L=%d R=%d, SF=%d, Final=%d%n",
                realR32Left.size(), realR32Right.size(),
                realR16Left.size(), realR16Right.size(),
                realQFLeft.size(), realQFRight.size(),
                realSF.size(), realFinal.size());

        } catch (Exception e) {
            System.err.println("[BracketView] API error: " + e.getMessage());
            e.printStackTrace();
            // Sin fallback de predicción — todo queda TBD
        }
    }

    /**
     * Convierte un BracketMatch de la API a nuestro BracketMatch interno.
     * Resuelve placeholders no resueltos a "TBD".
     */
    private BracketMatch apiToBracketMatch(BracketApiClient.BracketMatch bm, boolean isLeft, boolean isR32) {
        String t1 = displayName(bm.team1());
        String t2 = displayName(bm.team2());
        String w = bm.isPlayed() ? bm.winner() : "?";
        if (w != null) w = displayName(w);
        else w = "?";
        return new BracketMatch(t1, t2, w, 1.0);
    }

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
                h[1] += hg;
                h[2] += ag;
                h[3]++;
                a[1] += ag;
                a[2] += hg;
                a[3]++;
                if (hg > ag) h[0] += 3;
                else if (ag > hg) a[0] += 3;
                else {
                    h[0]++;
                    a[0]++;
                }
            }
        }
        groupData.clear();
        for (String gn : GROUP_ORDER) {
            Map<String, int[]> g = raw.get(gn);
            if (g == null) continue;
            List<TeamRow> rows = new ArrayList<>();
            g.forEach((t, s) -> rows.add(new TeamRow(t, s[3], s[0], s[1], s[2])));
            rows.sort((a, b) -> {
                if (b.pts() != a.pts()) return b.pts() - a.pts();
                if (b.gd() != a.gd()) return b.gd() - a.gd();
                if (b.gf() != a.gf()) return b.gf() - a.gf();
                return Double.compare(elo(b.name()), elo(a.name()));
            });
            groupData.put(gn, rows);
        }
    }

    /**
     * Muestra el nombre real del equipo o "TBD" si es placeholder sin resolver.
     */
    private String displayName(String team) {
        if (team == null || team.isEmpty()) return "TBD";
        if (team.matches("^[WL]\\d+$")) return "TBD"; // W73, L101, etc.
        return team;
    }

    private void renderGroups() {
        groupsGrid.getChildren().clear();
        int idx = 0;
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 4; col++) {
                if (idx >= GROUP_ORDER.length) break;
                groupsGrid.add(createGroupCard(GROUP_ORDER[idx++]), col, row);
            }

        // Actualizar o agregar label de campeon/bracket
        boolean hasRealChamp = !"TBD".equals(predictedChampion);
        String sepText = hasRealChamp
                ? "🏆 CAMPEÓN:  " + predictedChampion
                : "⏳ ELIMINATORIAS — bracket en vivo";
        Label sep;
        if (getChildren().size() > 4 && getChildren().get(4) instanceof Label old) {
            sep = old;
            sep.setText(sepText);
            sep.setTextFill(hasRealChamp ? AppTheme.GOLD() : AppTheme.DIM());
        } else {
            sep = new Label(sepText);
            sep.setTextFill(hasRealChamp ? AppTheme.GOLD() : AppTheme.DIM());
            sep.setFont(Font.font("Arial", FontWeight.BOLD, 15));
            sep.setPadding(new Insets(12, 0, 4, PAD));
            sep.setAlignment(Pos.CENTER);
            sep.setMaxWidth(Double.MAX_VALUE);
            getChildren().add(sep);
        }

        renderBracket();
    }

    /**
     * Renderiza el bracket COMPLETO usando datos reales de la API.
     * Sin predicciones — partidos no jugados muestran "TBD".
     */
    private void renderBracket() {
        bracketContainer.getChildren().clear();
        if (realR32Left.isEmpty() && realR32Right.isEmpty()) return;

        Label title = new Label("FASE ELIMINATORIA — RESULTADOS EN VIVO");
        title.setTextFill(AppTheme.GOLD());
        title.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        title.setPadding(new Insets(14, 0, 2, PAD));
        title.setMaxWidth(Double.MAX_VALUE);
        bracketContainer.getChildren().add(title);

        double totalH = 8 * SLOT + 40;
        bracketPane.setPrefSize(BRACKET_W, totalH);
        bracketPane.setMinSize(BRACKET_W, totalH);
        bracketPane.setStyle("-fx-background-color: #0F1217;");
        bracketPane.getChildren().clear();

        // ── Labels de ronda ──
        double labelTopY = TOP_Y - 4;
        addRoundLabel("R32", R32_LEFT, labelTopY);
        addRoundLabel("R16", R16_LEFT, labelTopY);
        addRoundLabel("QF", QF_LEFT, labelTopY);
        addRoundLabel("SF", SF_LEFT, labelTopY);
        addRoundLabel("FINAL", FINAL_X + BW * 0.15, labelTopY);
        addRoundLabel("SF", SF_RIGHT, labelTopY);
        addRoundLabel("QF", QF_RIGHT, labelTopY);
        addRoundLabel("R16", R16_RIGHT, labelTopY);
        addRoundLabel("R32", R32_RIGHT, labelTopY);

        // ── LADO IZQUIERDO ──

        // R32 Izq — 8 cards
        for (int i = 0; i < realR32Left.size(); i++)
            bracketPane.getChildren().add(createBracketCard(realR32Left.get(i), R32_LEFT, TOP_Y + i * SLOT));

        // R16 Izq — 4 cards (posicionadas entre pares de R32)
        for (int i = 0; i < realR16Left.size(); i++)
            bracketPane.getChildren().add(createBracketCard(realR16Left.get(i), R16_LEFT, TOP_Y + (i * 2 + 0.5) * SLOT - BH / 2));

        // QF Izq — 2 cards
        for (int i = 0; i < realQFLeft.size(); i++)
            bracketPane.getChildren().add(createBracketCard(realQFLeft.get(i), QF_LEFT, TOP_Y + (i * 4 + 1) * SLOT - BH / 2));

        // SF Izq — 1 card (match 101)
        if (!realSF.isEmpty())
            bracketPane.getChildren().add(createBracketCard(realSF.get(0), SF_LEFT, TOP_Y + 3.5 * SLOT - BH / 2));

        // ── LADO DERECHO ──

        // R32 Der — 8 cards
        for (int i = 0; i < realR32Right.size(); i++)
            bracketPane.getChildren().add(createBracketCard(realR32Right.get(i), R32_RIGHT, TOP_Y + i * SLOT));

        // R16 Der — 4 cards
        for (int i = 0; i < realR16Right.size(); i++)
            bracketPane.getChildren().add(createBracketCard(realR16Right.get(i), R16_RIGHT, TOP_Y + (i * 2 + 0.5) * SLOT - BH / 2));

        // QF Der — 2 cards
        for (int i = 0; i < realQFRight.size(); i++)
            bracketPane.getChildren().add(createBracketCard(realQFRight.get(i), QF_RIGHT, TOP_Y + (i * 4 + 1) * SLOT - BH / 2));

        // SF Der — 1 card (match 102)
        if (realSF.size() > 1)
            bracketPane.getChildren().add(createBracketCard(realSF.get(1), SF_RIGHT, TOP_Y + 3.5 * SLOT - BH / 2));

        // ── FINAL y CAMPEÓN ──
        if (!realFinal.isEmpty())
            bracketPane.getChildren().add(createBracketCard(realFinal.get(0), FINAL_X, TOP_Y + 3.5 * SLOT - BH / 2));

        bracketPane.getChildren().add(createChampionLabel(predictedChampion, FINAL_X, TOP_Y + 3.5 * SLOT + BH / 2 + 6));

        // ── Conectores ──
        drawConLines();

        bracketContainer.getChildren().add(bracketPane);
    }

    private void drawConLines() {
        drawSideLines(R32_LEFT + BW, R16_LEFT, TOP_Y, 8, 4);
        drawSideLines(R16_LEFT + BW, QF_LEFT, TOP_Y, 4, 2);
        drawSideLines(QF_LEFT + BW, SF_LEFT, TOP_Y, 2, 1);
        drawSideLines(SF_LEFT + BW, FINAL_X, TOP_Y, 1, 1);

        drawSideLinesR(FINAL_X + BW, SF_RIGHT, TOP_Y, 1, 1);
        drawSideLinesR(SF_RIGHT + BW, QF_RIGHT, TOP_Y, 1, 2);
        drawSideLinesR(QF_RIGHT + BW, R16_RIGHT, TOP_Y, 2, 4);
        drawSideLinesR(R16_RIGHT + BW, R32_RIGHT, TOP_Y, 4, 8);
    }

    private void drawSideLines(double xStart, double xEnd, double top, int nFrom, int nTo) {
        double stepFrom = SLOT;
        for (int i = 0; i < nFrom; i += 2) {
            double y1 = top + i * stepFrom + BH / 2;
            double y2 = top + (i + 1) * stepFrom + BH / 2;
            double yM = (y1 + y2) / 2;
            double midX = (xStart + xEnd) / 2;
            addLine(xStart, y1, midX, y1);
            addLine(xStart, y2, midX, y2);
            addLine(midX, y1, midX, y2);
            addLine(midX, yM, xEnd, yM);
        }
    }

    /**
     * Conectores del LADO DERECHO del bracket: expansión de nFrom → nTo cards.
     * Cada card fuente se ramifica en (nTo/nFrom) cards destino.
     * Usa las posiciones Y reales de cada ronda para alinearse con las cards dibujadas.
     */
    private void drawSideLinesR(double xStart, double xEnd, double top, int nFrom, int nTo) {
        double[] yFrom = bracketCenters(top, nFrom);
        double[] yTo   = bracketCenters(top, nTo);
        int ratio = nTo / nFrom;
        double midX = (xStart + xEnd) / 2;
        for (int j = 0; j < nFrom; j++) {
            double yM = yFrom[j];
            double y1 = yTo[j * ratio];
            double y2 = yTo[j * ratio + (ratio > 1 ? ratio - 1 : 0)];
            addLine(xStart, yM, midX, yM);
            if (Math.abs(y2 - y1) > 0.5) {
                addLine(midX, y1, midX, y2);
                addLine(midX, y1, xEnd, y1);
                addLine(midX, y2, xEnd, y2);
            } else {
                addLine(midX, yM, xEnd, yM);
            }
        }
    }

    /** Centros Y de las cards de cada ronda según cómo las posiciona renderBracket(). */
    private double[] bracketCenters(double top, int n) {
        double[] c = new double[n];
        switch (n) {
            case 1 -> c[0] = top + 3.5 * SLOT;
            case 2 -> { c[0] = top + SLOT; c[1] = top + 5 * SLOT; }
            case 4 -> { for (int i = 0; i < 4; i++) c[i] = top + (i * 2 + 0.5) * SLOT; }
            default -> { for (int i = 0; i < n; i++) c[i] = top + i * SLOT + BH / 2.0; }
        }
        return c;
    }

    private void addLine(double x1, double y1, double x2, double y2) {
        Line ln = new Line(x1, y1, x2, y2);
        ln.setStroke(AppTheme.DIV());
        ln.setStrokeWidth(1.5);
        bracketPane.getChildren().add(ln);
    }

    private Node createBracketCard(BracketMatch bm, double x, double y) {
        String t1 = shorten(bm.t1, 16);
        String t2 = shorten(bm.t2, 16);
        String winner = bm.winner;
        boolean isTBD1 = "TBD".equals(bm.t1);
        boolean isTBD2 = "TBD".equals(bm.t2);

        VBox card = new VBox(0);
        card.setLayoutX(x);
        card.setLayoutY(y);
        card.setPrefSize(BW, BH);
        card.setStyle("-fx-background-color: #1A1E28; -fx-background-radius: 4; -fx-border-color: #3A4152; -fx-border-radius: 4; -fx-border-width: 0.5;");

        HBox row1 = new HBox();
        row1.setPadding(new Insets(1, 6, 0, 6));

        Label l1 = new Label(t1);
        l1.setTextFill(isTBD1 ? AppTheme.DIM() : (t1.equals(winner) ? Color.WHITE : AppTheme.DIM()));
        l1.setFont(Font.font("Arial", (!isTBD1 && t1.equals(winner)) ? FontWeight.BOLD : FontWeight.NORMAL, 9));
        l1.setPrefWidth(BW - 70);

        Label l2 = new Label(t2);
        l2.setTextFill(isTBD2 ? AppTheme.DIM() : (t2.equals(winner) ? Color.WHITE : AppTheme.DIM()));
        l2.setFont(Font.font("Arial", (!isTBD2 && t2.equals(winner)) ? FontWeight.BOLD : FontWeight.NORMAL, 9));
        l2.setPrefWidth(BW - 70);

        row1.getChildren().addAll(l1, new Label(), l2);
        HBox.setHgrow(row1.getChildren().get(1), Priority.ALWAYS);

        HBox row2 = new HBox();
        row2.setPadding(new Insets(0, 6, 1, 6));
        row2.getChildren().add(new Label());

        String confStr;
        if (isTBD1 && isTBD2) {
            confStr = "Pendiente";
        } else if (winner.equals("?")) {
            confStr = "Pendiente";
        } else {
            confStr = winner;
        }
        Label conf = new Label(confStr);
        conf.setTextFill(winner.equals("?") ? AppTheme.DIM() : AppTheme.ACCENT());
        conf.setFont(Font.font("Arial", FontWeight.BOLD, 9));
        conf.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(conf, Priority.ALWAYS);
        row2.getChildren().add(conf);

        card.getChildren().addAll(row1, row2);
        return card;
    }

    private Node createTeamLabel(String team, double x, double y) {
        VBox box = new VBox();
        box.setLayoutX(x);
        box.setLayoutY(y);
        box.setPrefSize(BW, BH);
        box.setStyle("-fx-background-color: #222838; -fx-background-radius: 4; -fx-border-color: #4A5A72; -fx-border-radius: 4; -fx-border-width: 0.5;");

        Label lbl = new Label(shorten(team, 18));
        lbl.setTextFill(Color.WHITE);
        lbl.setFont(Font.font("Arial", FontWeight.BOLD, 10));
        lbl.setAlignment(Pos.CENTER);
        lbl.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(lbl, Priority.ALWAYS);

        Tooltip tip = new Tooltip("Clasificado a siguiente ronda");
        tip.setStyle("-fx-background: #1A1E28; -fx-text-fill: #E8ECF2;");
        Tooltip.install(box, tip);

        box.getChildren().add(lbl);
        return box;
    }

    private Node createChampionLabel(String champ, double x, double y) {
        boolean isTBD = "TBD".equals(champ);
        VBox box = new VBox();
        box.setLayoutX(x);
        box.setLayoutY(y);
        box.setPrefSize(BW, 40);
        box.setStyle(isTBD
            ? "-fx-background-color: #1A1E28; -fx-background-radius: 6; -fx-border-color: #3A4152; -fx-border-radius: 6; -fx-border-width: 1;"
            : "-fx-background-color: #2A2010; -fx-background-radius: 6; -fx-border-color: #FFD700; -fx-border-radius: 6; -fx-border-width: 2;");

        Label l1 = new Label("CAMPEÓN");
        l1.setTextFill(isTBD ? AppTheme.DIM() : AppTheme.GOLD());
        l1.setFont(Font.font("Arial", FontWeight.BOLD, 9));
        l1.setAlignment(Pos.CENTER);
        l1.setMaxWidth(Double.MAX_VALUE);

        Label l2 = new Label(shorten(champ, 18));
        l2.setTextFill(isTBD ? AppTheme.DIM() : Color.WHITE);
        l2.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        l2.setAlignment(Pos.CENTER);
        l2.setMaxWidth(Double.MAX_VALUE);

        box.getChildren().addAll(l1, l2);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void addRoundLabel(String text, double x, double y) {
        Label lbl = new Label(text);
        lbl.setTextFill(AppTheme.DIM());
        lbl.setFont(Font.font("Arial", FontWeight.BOLD, 10));
        lbl.setLayoutX(x + 4);
        lbl.setLayoutY(y);
        bracketPane.getChildren().add(lbl);
    }

    private Node createGroupCard(String groupName) {
        Color gc = GCOLOR.getOrDefault(groupName, AppTheme.ACCENT());
        List<TeamRow> teams = groupData.get(groupName);

        VBox card = new VBox(0);
        card.setPrefSize(CARD_W, CARD_H);
        card.setStyle("-fx-background-color: #2A2F3B; -fx-background-radius: 8;");

        String hex = String.format("#%02x%02x%02x",
            (int) (gc.getRed() * 255), (int) (gc.getGreen() * 255), (int) (gc.getBlue() * 255));
        Label hdr = new Label("GROUP " + groupName.replace("Group ", ""));
        hdr.setMaxWidth(Double.MAX_VALUE);
        hdr.setAlignment(Pos.CENTER);
        hdr.setTextFill(Color.WHITE);
        hdr.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        hdr.setStyle("-fx-background-color: " + hex + "; -fx-background-radius: 8 8 0 0;");
        hdr.setPadding(new Insets(5, 0, 5, 0));

        HBox colHdr = new HBox();
        colHdr.setPadding(new Insets(4, 8, 2, 8));
        String[] ch = {"EQUIPO", "PJ", "GF", "GC", "DIF", "PTS"};
        int[] cw = {145, 25, 25, 25, 30, 30};
        for (int i = 0; i < ch.length; i++) {
            Label l = new Label(ch[i]);
            l.setTextFill(AppTheme.DIM());
            l.setFont(Font.font("Arial", FontWeight.BOLD, 8));
            l.setPrefWidth(cw[i]);
            if (i > 0) l.setAlignment(Pos.CENTER_RIGHT);
            colHdr.getChildren().add(l);
        }

        VBox rows = new VBox(0);
        if (teams != null) {
            for (int i = 0; i < Math.min(4, teams.size()); i++) {
                TeamRow t = teams.get(i);
                boolean q = i < 2;
                HBox rbox = new HBox();
                rbox.setPadding(new Insets(2, 8, 2, 8));
                if (i == 0 && t.pts() > 0)
                    rbox.setStyle("-fx-background-color: rgba("
                        + (int) (gc.getRed() * 255) + "," + (int) (gc.getGreen() * 255) + ","
                        + (int) (gc.getBlue() * 255) + ", 0.1); -fx-background-radius: 4;");

                Label pos = new Label(String.valueOf(i + 1));
                pos.setTextFill(i == 0 ? gc : AppTheme.DIM());
                pos.setFont(Font.font("Arial", FontWeight.BOLD, 9));
                pos.setPrefWidth(14);

                Label name = new Label(shorten(t.name(), 17));
                name.setTextFill(q ? AppTheme.TXT() : AppTheme.DIM());
                name.setFont(Font.font("Arial", q ? FontWeight.BOLD : FontWeight.NORMAL, 10));
                name.setPrefWidth(130);

                rbox.getChildren().addAll(pos, name,
                    txt(String.valueOf(t.played()), 24, AppTheme.DIM()),
                    txt(String.valueOf(t.gf()), 24, AppTheme.DIM()),
                    txt(String.valueOf(t.ga()), 24, AppTheme.DIM()),
                    txt((t.gd() > 0 ? "+" : "") + t.gd(), 28,
                        t.gd() > 0 ? Color.rgb(0x27, 0xAE, 0x60) : t.gd() < 0 ? Color.rgb(0xE7, 0x4C, 0x3C) : AppTheme.DIM()),
                    txt(String.valueOf(t.pts()), 26, q ? Color.WHITE : AppTheme.DIM()));
                rows.getChildren().add(rbox);
            }
        } else {
            Label nd = new Label("Sin datos");
            nd.setTextFill(AppTheme.DIM());
            nd.setFont(Font.font("Arial", 10));
            nd.setPadding(new Insets(10, 0, 0, 10));
            rows.getChildren().add(nd);
        }

        card.getChildren().addAll(hdr, colHdr, rows);

        if (teams != null && !teams.isEmpty()) {
            StringBuilder tip = new StringBuilder(groupName.replace("Group ", "Group ") + "\n");
            int rank = 1;
            for (TeamRow t : teams)
                tip.append(String.format("\n%d. %s — %d pts (%d PJ, %d:%d, %+d)",
                    rank++, t.name(), t.pts(), t.played(), t.gf(), t.ga(), t.gd()));
            Tooltip.install(card, new Tooltip(tip.toString()));
        }
        return card;
    }

    private static Label txt(String s, double w, Color c) {
        Label l = new Label(s);
        l.setPrefWidth(w);
        l.setAlignment(Pos.CENTER_RIGHT);
        l.setTextFill(c);
        l.setFont(Font.font("Arial", FontWeight.NORMAL, 10));
        return l;
    }

    private double elo(String team) {
        EloRating r = ratings.get(team);
        return r != null ? r.rating() : 1500.0;
    }

    private String shorten(String t, int max) {
        if (t == null || t.isEmpty()) return "?";
        if (t.length() <= max) return t;
        String[] w = t.split("\\s+");
        if (w.length >= 2 && w[0].length() + 1 + w[w.length - 1].length() <= max)
            return w[0] + " " + w[w.length - 1];
        return t.substring(0, max - 1) + "...";
    }
}
