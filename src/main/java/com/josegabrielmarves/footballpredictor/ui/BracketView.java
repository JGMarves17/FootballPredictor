package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;

import javafx.animation.FadeTransition;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.*;

public class BracketView extends VBox {

    private static final Map<String, Color> GCOLOR = new LinkedHashMap<>();
    static {
        GCOLOR.put("Group A", Color.rgb(0x27,0xAE,0x60));
        GCOLOR.put("Group B", Color.rgb(0xC0,0x39,0x2B));
        GCOLOR.put("Group C", Color.rgb(0xE6,0x7E,0x22));
        GCOLOR.put("Group D", Color.rgb(0x1A,0x5C,0x9E));
        GCOLOR.put("Group E", Color.rgb(0x8E,0x44,0xAD));
        GCOLOR.put("Group F", Color.rgb(0x17,0x9E,0x86));
        GCOLOR.put("Group G", Color.rgb(0x92,0x2B,0x21));
        GCOLOR.put("Group H", Color.rgb(0x1E,0x84,0x49));
        GCOLOR.put("Group I", Color.rgb(0x6C,0x34,0x83));
        GCOLOR.put("Group J", Color.rgb(0x1A,0x52,0x76));
        GCOLOR.put("Group K", Color.rgb(0xD3,0x54,0x00));
        GCOLOR.put("Group L", Color.rgb(0x7D,0x66,0x08));
    }
    private static final String[] GROUP_ORDER = {
        "Group A","Group B","Group C","Group D",
        "Group E","Group F","Group G","Group H",
        "Group I","Group J","Group K","Group L"
    };

    private static final String[][] LEFT_R32 = {
        {"1E","3ABCDF"},{"1I","3CDFGH"},{"2A","2B"},{"1F","2C"},
        {"1C","2F"},{"2E","2I"},{"1A","3CEFHI"},{"1L","3EHIJK"}
    };
    private static final String[][] RIGHT_R32 = {
        {"2K","2L"},{"1H","2J"},{"1D","3BEFIJ"},{"1G","3AEHIJ"},
        {"1J","2H"},{"2D","2G"},{"1B","3EFGIJ"},{"1K","3DEIJL"}
    };

    private record TeamRow(String name, int played, int pts, int gf, int ga) {
        int gd() { return gf - ga; }
    }
    private record BracketMatch(String t1, String t2, String winner, double confidence) {}

    private final Map<String, List<TeamRow>> groupData = new LinkedHashMap<>();
    private Map<String, EloRating> ratings = new HashMap<>();
    private final List<BracketMatch> leftMatches  = new ArrayList<>();
    private final List<BracketMatch> rightMatches = new ArrayList<>();
    private final GridPane groupsGrid = new GridPane();
    private final Label champLabel = new Label("?");
    private String predictedChampion = "?";

    private static final int CARD_W = 300, CARD_H = 130;
    private static final int HGAP = 12, VGAP = 10, PAD = 16;

    public BracketView(MainWindow owner) {
        setSpacing(0);
        setStyle("-fx-background-color: #0F1217;");

        Label tt = new Label("FIFA WORLD CUP 2026 — FASE DE GRUPOS");
        tt.setTextFill(MainWindow.GOLD);
        tt.setFont(Font.font("Arial", FontWeight.BOLD, 17));
        tt.setPadding(new Insets(10, 0, 8, 0));
        tt.setAlignment(Pos.CENTER);
        tt.setMaxWidth(Double.MAX_VALUE);

        groupsGrid.setHgap(HGAP);
        groupsGrid.setVgap(VGAP);
        groupsGrid.setPadding(new Insets(0, PAD, 0, PAD));

        getChildren().addAll(tt, groupsGrid);
    }

    public void setMatches(List<Match> ms, Map<String, EloRating> r) {
        ratings = r;
        Task<Void> t = new Task<>() {
            @Override protected Void call() {
                computeGroupData(ms);
                predictBracket();
                return null;
            }
        };
        t.setOnSucceeded(e -> {
            renderGroups();
            FadeTransition ft = new FadeTransition(Duration.millis(400), BracketView.this);
            ft.setFromValue(0.3); ft.setToValue(1.0); ft.play();
        });
        new Thread(t).start();
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
                h[1]+=hg; h[2]+=ag; h[3]++;
                a[1]+=ag; a[2]+=hg; a[3]++;
                if (hg>ag) h[0]+=3; else if (ag>hg) a[0]+=3; else { h[0]++; a[0]++; }
            }
        }
        groupData.clear();
        for (String gn : GROUP_ORDER) {
            Map<String,int[]> g = raw.get(gn);
            if (g == null) continue;
            List<TeamRow> rows = new ArrayList<>();
            g.forEach((t,s) -> rows.add(new TeamRow(t, s[3], s[0], s[1], s[2])));
            rows.sort((a,b) -> {
                if (b.pts()!=a.pts()) return b.pts()-a.pts();
                if (b.gd()!=a.gd())  return b.gd()-a.gd();
                if (b.gf()!=a.gf())  return b.gf()-a.gf();
                return Double.compare(elo(b.name()), elo(a.name()));
            });
            groupData.put(gn, rows);
        }
    }

    private void predictBracket() {
        leftMatches.clear(); rightMatches.clear();
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

        // Add champion prediction after groups
        Label sep = new Label("CAMPEÓN PROYECTADO:  " + predictedChampion);
        sep.setTextFill(MainWindow.GOLD);
        sep.setFont(Font.font("Arial", FontWeight.BOLD, 15));
        sep.setPadding(new Insets(12, 0, 4, PAD));
        sep.setAlignment(Pos.CENTER);
        sep.setMaxWidth(Double.MAX_VALUE);
        if (getChildren().size() < 3) getChildren().add(sep);
    }

    private Node createGroupCard(String groupName) {
        Color gc = GCOLOR.getOrDefault(groupName, MainWindow.ACCENT);
        List<TeamRow> teams = groupData.get(groupName);

        VBox card = new VBox(0);
        card.setPrefSize(CARD_W, CARD_H);
        card.setStyle("-fx-background-color: #2A2F3B; -fx-background-radius: 8; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 4, 0, 2, 2);");

        String hex = String.format("#%02x%02x%02x", (int)(gc.getRed()*255), (int)(gc.getGreen()*255), (int)(gc.getBlue()*255));
        Label hdr = new Label("GROUP " + groupName.replace("Group ", ""));
        hdr.setMaxWidth(Double.MAX_VALUE);
        hdr.setAlignment(Pos.CENTER);
        hdr.setTextFill(Color.WHITE);
        hdr.setFont(Font.font("Arial", FontWeight.BOLD, 12));
        hdr.setStyle("-fx-background-color: " + hex + "; -fx-background-radius: 8 8 0 0;");
        hdr.setPadding(new Insets(5, 0, 5, 0));

        HBox colHdr = new HBox();
        colHdr.setPadding(new Insets(4, 8, 2, 8));
        String[] ch = {"EQUIPO","PJ","GF","GC","DIF","PTS"};
        int[] cw = {145, 25, 25, 25, 30, 30};
        for (int i = 0; i < ch.length; i++) {
            Label l = new Label(ch[i]);
            l.setTextFill(MainWindow.DIM);
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
                    rbox.setStyle("-fx-background-color: rgba(" + (int)(gc.getRed()*255) + "," + (int)(gc.getGreen()*255) + "," + (int)(gc.getBlue()*255) + ", 0.1); -fx-background-radius: 4;");

                Label pos = new Label(String.valueOf(i + 1));
                pos.setTextFill(i == 0 ? gc : MainWindow.DIM);
                pos.setFont(Font.font("Arial", FontWeight.BOLD, 9));
                pos.setPrefWidth(14);

                Label name = new Label(shorten(t.name(), 17));
                name.setTextFill(q ? MainWindow.TXT : MainWindow.DIM);
                name.setFont(Font.font("Arial", q ? FontWeight.BOLD : FontWeight.NORMAL, 10));
                name.setPrefWidth(130);

                rbox.getChildren().addAll(pos, name,
                    txt(String.valueOf(t.played()), 24, MainWindow.DIM),
                    txt(String.valueOf(t.gf()), 24, MainWindow.DIM),
                    txt(String.valueOf(t.ga()), 24, MainWindow.DIM),
                    txt((t.gd() > 0 ? "+" : "") + t.gd(), 28,
                        t.gd() > 0 ? Color.rgb(0x27,0xAE,0x60) : t.gd() < 0 ? Color.rgb(0xE7,0x4C,0x3C) : MainWindow.DIM),
                    txt(String.valueOf(t.pts()), 26, q ? Color.WHITE : MainWindow.DIM));
                rows.getChildren().add(rbox);
            }
        } else {
            Label nd = new Label("Sin datos");
            nd.setTextFill(MainWindow.DIM);
            nd.setFont(Font.font("Arial", 10));
            nd.setPadding(new Insets(10, 0, 0, 10));
            rows.getChildren().add(nd);
        }

        card.getChildren().addAll(hdr, colHdr, rows);

        if (teams != null && !teams.isEmpty()) {
            StringBuilder tip = new StringBuilder(groupName.replace("Group ", "Group ") + "\n");
            int rank = 1;
            for (TeamRow t : teams)
                tip.append(String.format("\n%d. %s — %d pts (%d PJ, %d:%d, %+d)", rank++, t.name(), t.pts(), t.played(), t.gf(), t.ga(), t.gd()));
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
        String best = null; int bestPts = -1; double bestElo = -1;
        for (char c : letters.toCharArray()) {
            List<TeamRow> st = groupData.get("Group " + c);
            if (st == null || st.size() < 3) continue;
            TeamRow t = st.get(2);
            double e = elo(t.name());
            if (best == null || t.pts() > bestPts || (t.pts() == bestPts && e > bestElo)) {
                best = t.name(); bestPts = t.pts(); bestElo = e;
            }
        }
        return best != null ? best : "Mejor 3°";
    }

    private static Label txt(String s, double w, Color c) {
        Label l = new Label(s);
        l.setPrefWidth(w); l.setAlignment(Pos.CENTER_RIGHT);
        l.setTextFill(c);  l.setFont(Font.font("Arial", FontWeight.NORMAL, 10));
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
        return t.substring(0, max - 1) + "…";
    }
}
