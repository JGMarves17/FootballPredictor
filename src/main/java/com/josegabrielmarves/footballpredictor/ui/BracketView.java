package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.api.BracketApiClient;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.ui.theme.AppTheme;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
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

    private static final String[][] LEFT_R32 = {
        {"1E", "3ABCDF"}, {"1I", "3CDFGH"}, {"2A", "2B"}, {"1F", "2C"},
        {"1C", "2F"}, {"2E", "2I"}, {"1A", "3CEFHI"}, {"1L", "3EHIJK"}
    };
    private static final String[][] RIGHT_R32 = {
        {"2K", "2L"}, {"1H", "2J"}, {"1D", "3BEFIJ"}, {"1G", "3AEHIJ"},
        {"1J", "2H"}, {"2D", "2G"}, {"1B", "3EFGIJ"}, {"1K", "3DEIJL"}
    };

    private record TeamRow(String name, int played, int pts, int gf, int ga) {
        int gd() {
            return gf - ga;
        }
    }

    private record BracketMatch(String t1, String t2, String winner, double confidence) {}

    private final Map<String, List<TeamRow>> groupData = new LinkedHashMap<>();
    private Map<String, EloRating> ratings = new HashMap<>();
    private final List<BracketMatch> leftMatches  = new ArrayList<>();
    private final List<BracketMatch> rightMatches = new ArrayList<>();
    private final GridPane groupsGrid = new GridPane();
    private String predictedChampion = "?";

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

    public BracketView(MainWindow owner) {
        setSpacing(0);
        setStyle("-fx-background-color: #0F1217;");

        Label tt = new Label("🏆 MUNDIAL 2026 — RESULTADOS EN VIVO");
        tt.setTextFill(AppTheme.GOLD);
        tt.setFont(Font.font("Arial", FontWeight.BOLD, 17));
        tt.setPadding(new Insets(10, 0, 8, 0));
        tt.setAlignment(Pos.CENTER);
        tt.setMaxWidth(Double.MAX_VALUE);

        Label sub = new Label("Datos actualizados cada 6h · openfootball.org");
        sub.setTextFill(AppTheme.DIM);
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
     * Carga el bracket REAL desde la API de openfootball.
     * Reemplaza la prediccion con datos reales.
     */
    private void refreshBracket() {
        try {
            bracketApi.refresh();
            var full = bracketApi.getFullBracket();

            leftMatches.clear();
            rightMatches.clear();

            // R32 — datos reales
            List<BracketApiClient.BracketMatch> r32 = full.getOrDefault("Round of 32", List.of());
            if (r32.isEmpty()) {
                // Fallback a prediccion si no hay datos
                predictBracket();
                return;
            }

            int half = r32.size() / 2;
            for (int i = 0; i < r32.size(); i++) {
                BracketApiClient.BracketMatch bm = r32.get(i);
                String w = bm.isPlayed() ? bm.winner() : "?";
                BracketMatch bm2 = new BracketMatch(bm.team1(), bm.team2(), w, 1.0);
                if (i < half) leftMatches.add(bm2);
                else rightMatches.add(bm2);
            }

            // Campeon real (si la final ya se jugo)
            List<BracketApiClient.BracketMatch> finals = full.getOrDefault("Final", List.of());
            if (!finals.isEmpty() && finals.get(0).isPlayed()) {
                predictedChampion = finals.get(0).winner();
            } else {
                predictedChampion = "? (en juego)";
            }

        } catch (Exception e) {
            System.err.println("[BracketView] API error: " + e.getMessage());
            predictBracket(); // fallback
        }
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

    private void predictBracket() {
        leftMatches.clear();
        rightMatches.clear();
        List<String[][]> sides = List.of(LEFT_R32, RIGHT_R32);
        List<List<BracketMatch>> out = List.of(leftMatches, rightMatches);
        for (int si = 0; si < 2; si++) {
            for (String[] spec : sides.get(si)) {
                String t1 = resolveSpec(spec[0]);
                String t2 = resolveSpec(spec[1]);
                String winner = predictWinner(t1, t2);
                double conf = winnerConfidence(t1, t2, winner);
                out.get(si).add(new BracketMatch(t1, t2, winner, conf));
            }
        }
        predictedChampion = simulateRounds(leftMatches, rightMatches);
    }

    private String simulateRounds(List<BracketMatch> left, List<BracketMatch> right) {
        List<String> l = advanceRound(left);
        List<String> r = advanceRound(right);
        List<String> ql = advanceStr(l);
        List<String> qr = advanceStr(r);
        String sfL = predictWinner(ql.get(0), ql.get(1));
        String sfR = predictWinner(qr.get(0), qr.get(1));
        return predictWinner(sfL, sfR);
    }

    private List<String> advanceRound(List<BracketMatch> matches) {
        List<String> winners = new ArrayList<>();
        for (int i = 0; i < matches.size(); i += 2) {
            winners.add(predictWinner(matches.get(i).winner, matches.get(i + 1).winner));
        }
        return winners;
    }

    private List<String> advanceStr(List<String> teams) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < teams.size(); i += 2)
            out.add(predictWinner(teams.get(i), teams.get(i + 1)));
        return out;
    }

    private String predictWinner(String t1, String t2) {
        if (t1 == null || t2 == null || t1.equals("?") || t2.equals("?")) return "?";
        EloRating r1 = ratings.getOrDefault(t1, EloRating.initial(t1));
        EloRating r2 = ratings.getOrDefault(t2, EloRating.initial(t2));
        return EloCalculator.calculateExpectedScore(r1.rating(), r2.rating(), 0) >= 0.5 ? t1 : t2;
    }

    private double winnerConfidence(String t1, String t2, String winner) {
        if (winner.equals("?") || t1.equals("?") || t2.equals("?")) return 0.5;
        EloRating r1 = ratings.getOrDefault(t1, EloRating.initial(t1));
        EloRating r2 = ratings.getOrDefault(t2, EloRating.initial(t2));
        double p = EloCalculator.calculateExpectedScore(r1.rating(), r2.rating(), 0);
        return winner.equals(t1) ? p : 1 - p;
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
        boolean hasRealChamp = !predictedChampion.contains("?");
        String sepText = hasRealChamp
                ? "🏆 CAMPEÓN:  " + predictedChampion
                : "⏳ ELIMINATORIAS — bracket en vivo";
        Label sep;
        if (getChildren().size() > 4 && getChildren().get(4) instanceof Label old) {
            sep = old;
            sep.setText(sepText);
            sep.setTextFill(hasRealChamp ? AppTheme.GOLD : AppTheme.DIM);
        } else {
            sep = new Label(sepText);
            sep.setTextFill(hasRealChamp ? AppTheme.GOLD : AppTheme.DIM);
            sep.setFont(Font.font("Arial", FontWeight.BOLD, 15));
            sep.setPadding(new Insets(12, 0, 4, PAD));
            sep.setAlignment(Pos.CENTER);
            sep.setMaxWidth(Double.MAX_VALUE);
            getChildren().add(sep);
        }

        renderBracket();
    }

    private void renderBracket() {
        bracketContainer.getChildren().clear();
        if (leftMatches.isEmpty()) return;

        Label title = new Label("FASE ELIMINATORIA — RESULTADOS EN VIVO");
        title.setTextFill(AppTheme.GOLD);
        title.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        title.setPadding(new Insets(14, 0, 2, PAD));
        title.setMaxWidth(Double.MAX_VALUE);
        bracketContainer.getChildren().add(title);

        double totalH = 8 * SLOT + 40;
        bracketPane.setPrefSize(BRACKET_W, totalH);
        bracketPane.setMinSize(BRACKET_W, totalH);
        bracketPane.setStyle("-fx-background-color: #0F1217;");
        bracketPane.getChildren().clear();

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

        for (int i = 0; i < leftMatches.size(); i++) {
            BracketMatch bm = leftMatches.get(i);
            bracketPane.getChildren().add(createBracketCard(bm, R32_LEFT, TOP_Y + i * SLOT));
        }

        List<String> r16L = advanceRound(leftMatches);
        for (int i = 0; i < r16L.size(); i++)
            bracketPane.getChildren().add(createTeamLabel(r16L.get(i), R16_LEFT, TOP_Y + (i * 2 + 0.5) * SLOT - BH / 2));

        List<String> qfL = advanceStr(r16L);
        for (int i = 0; i < qfL.size(); i++)
            bracketPane.getChildren().add(createTeamLabel(qfL.get(i), QF_LEFT, TOP_Y + (i * 4 + 1) * SLOT - BH / 2));

        String sfL = predictWinner(qfL.get(0), qfL.get(1));
        bracketPane.getChildren().add(createTeamLabel(sfL, SF_LEFT, TOP_Y + 3.5 * SLOT - BH / 2));

        for (int i = 0; i < rightMatches.size(); i++) {
            BracketMatch bm = rightMatches.get(i);
            bracketPane.getChildren().add(createBracketCard(bm, R32_RIGHT, TOP_Y + i * SLOT));
        }

        List<String> r16R = advanceRound(rightMatches);
        for (int i = 0; i < r16R.size(); i++)
            bracketPane.getChildren().add(createTeamLabel(r16R.get(i), R16_RIGHT, TOP_Y + (i * 2 + 0.5) * SLOT - BH / 2));

        List<String> qfR = advanceStr(r16R);
        for (int i = 0; i < qfR.size(); i++)
            bracketPane.getChildren().add(createTeamLabel(qfR.get(i), QF_RIGHT, TOP_Y + (i * 4 + 1) * SLOT - BH / 2));

        String sfR = predictWinner(qfR.get(0), qfR.get(1));
        bracketPane.getChildren().add(createTeamLabel(sfR, SF_RIGHT, TOP_Y + 3.5 * SLOT - BH / 2));

        String champion = predictWinner(sfL, sfR);
        bracketPane.getChildren().add(createChampionLabel(champion, FINAL_X, TOP_Y + 3.5 * SLOT - 14));

        drawConLines();

        ScrollPane sp = new ScrollPane(bracketPane);
        sp.setStyle("-fx-background: #0F1217; -fx-control-inner-background: #0F1217;");
        sp.setPrefHeight(560);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        bracketContainer.getChildren().add(sp);
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

    private void drawSideLinesR(double xStart, double xEnd, double top, int nFrom, int nTo) {
        double stepFrom = SLOT * nFrom;
        for (int i = 0; i < nFrom; i += 2) {
            double y1 = top + (i / 2) * stepFrom + BH / 2;
            double y2 = top + ((i + 1) / 2) * stepFrom + BH / 2;
            double yM = (y1 + y2) / 2;
            double midX = (xStart + xEnd) / 2;
            addLine(xStart, y1, midX, y1);
            addLine(xStart, y2, midX, y2);
            addLine(midX, y1, midX, y2);
            addLine(midX, yM, xEnd, yM);
        }
    }

    private void addLine(double x1, double y1, double x2, double y2) {
        Line ln = new Line(x1, y1, x2, y2);
        ln.setStroke(AppTheme.DIV);
        ln.setStrokeWidth(1.5);
        bracketPane.getChildren().add(ln);
    }

    private Node createBracketCard(BracketMatch bm, double x, double y) {
        String t1 = shorten(bm.t1, 16);
        String t2 = shorten(bm.t2, 16);
        String winner = bm.winner;

        VBox card = new VBox(0);
        card.setLayoutX(x);
        card.setLayoutY(y);
        card.setPrefSize(BW, BH);
        card.setStyle("-fx-background-color: #1A1E28; -fx-background-radius: 4; -fx-border-color: #3A4152; -fx-border-radius: 4; -fx-border-width: 0.5;");

        HBox row1 = new HBox();
        row1.setPadding(new Insets(1, 6, 0, 6));

        Label l1 = new Label(t1);
        l1.setTextFill(t1.equals(winner) ? Color.WHITE : AppTheme.DIM);
        l1.setFont(Font.font("Arial", t1.equals(winner) ? FontWeight.BOLD : FontWeight.NORMAL, 9));
        l1.setPrefWidth(BW - 70);

        Label l2 = new Label(t2);
        l2.setTextFill(t2.equals(winner) ? Color.WHITE : AppTheme.DIM);
        l2.setFont(Font.font("Arial", t2.equals(winner) ? FontWeight.BOLD : FontWeight.NORMAL, 9));
        l2.setPrefWidth(BW - 70);

        row1.getChildren().addAll(l1, new Label(), l2);
        HBox.setHgrow(row1.getChildren().get(1), Priority.ALWAYS);

        HBox row2 = new HBox();
        row2.setPadding(new Insets(0, 6, 1, 6));
        row2.getChildren().add(new Label());
        String confStr = winner.equals("?") ? "?" : String.format("%.0f%%", bm.confidence * 100);
        Label conf = new Label("→ " + winner + " (" + confStr + ")");
        conf.setTextFill(AppTheme.ACCENT);
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
        VBox box = new VBox();
        box.setLayoutX(x);
        box.setLayoutY(y);
        box.setPrefSize(BW, 40);
        box.setStyle("-fx-background-color: #2A2010; -fx-background-radius: 6; -fx-border-color: #FFD700; -fx-border-radius: 6; -fx-border-width: 2;");

        Label l1 = new Label("CAMPEON");
        l1.setTextFill(AppTheme.GOLD);
        l1.setFont(Font.font("Arial", FontWeight.BOLD, 9));
        l1.setAlignment(Pos.CENTER);
        l1.setMaxWidth(Double.MAX_VALUE);

        Label l2 = new Label(shorten(champ, 18));
        l2.setTextFill(Color.WHITE);
        l2.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        l2.setAlignment(Pos.CENTER);
        l2.setMaxWidth(Double.MAX_VALUE);

        box.getChildren().addAll(l1, l2);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void addRoundLabel(String text, double x, double y) {
        Label lbl = new Label(text);
        lbl.setTextFill(AppTheme.DIM);
        lbl.setFont(Font.font("Arial", FontWeight.BOLD, 10));
        lbl.setLayoutX(x + 4);
        lbl.setLayoutY(y);
        bracketPane.getChildren().add(lbl);
    }

    private Node createGroupCard(String groupName) {
        Color gc = GCOLOR.getOrDefault(groupName, AppTheme.ACCENT);
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
            l.setTextFill(AppTheme.DIM);
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
                pos.setTextFill(i == 0 ? gc : AppTheme.DIM);
                pos.setFont(Font.font("Arial", FontWeight.BOLD, 9));
                pos.setPrefWidth(14);

                Label name = new Label(shorten(t.name(), 17));
                name.setTextFill(q ? AppTheme.TXT : AppTheme.DIM);
                name.setFont(Font.font("Arial", q ? FontWeight.BOLD : FontWeight.NORMAL, 10));
                name.setPrefWidth(130);

                rbox.getChildren().addAll(pos, name,
                    txt(String.valueOf(t.played()), 24, AppTheme.DIM),
                    txt(String.valueOf(t.gf()), 24, AppTheme.DIM),
                    txt(String.valueOf(t.ga()), 24, AppTheme.DIM),
                    txt((t.gd() > 0 ? "+" : "") + t.gd(), 28,
                        t.gd() > 0 ? Color.rgb(0x27, 0xAE, 0x60) : t.gd() < 0 ? Color.rgb(0xE7, 0x4C, 0x3C) : AppTheme.DIM),
                    txt(String.valueOf(t.pts()), 26, q ? Color.WHITE : AppTheme.DIM));
                rows.getChildren().add(rbox);
            }
        } else {
            Label nd = new Label("Sin datos");
            nd.setTextFill(AppTheme.DIM);
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

    private String resolveSpec(String spec) {
        if (spec == null) return "?";
        if (spec.length() > 1 && spec.charAt(0) == '3') return bestThird(spec.substring(1));
        if (spec.length() == 2 && Character.isDigit(spec.charAt(0))) {
            int pos = spec.charAt(0) - '0';
            List<TeamRow> st = groupData.get("Group " + spec.charAt(1));
            if (st != null && st.size() >= pos) return st.get(pos - 1).name();
        }
        return spec;
    }

    private String bestThird(String letters) {
        String best = null;
        int bestPts = -1;
        double bestElo = -1;
        for (char c : letters.toCharArray()) {
            List<TeamRow> st = groupData.get("Group " + c);
            if (st == null || st.size() < 3) continue;
            TeamRow t = st.get(2);
            double e = elo(t.name());
            if (best == null || t.pts() > bestPts || (t.pts() == bestPts && e > bestElo)) {
                best = t.name();
                bestPts = t.pts();
                bestElo = e;
            }
        }
        return best != null ? best : "Mejor 3°";
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
