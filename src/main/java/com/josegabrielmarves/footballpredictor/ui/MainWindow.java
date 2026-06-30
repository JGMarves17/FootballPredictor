package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.model.Match;
import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.EnsemblePredictor;
import com.josegabrielmarves.footballpredictor.prediction.ScoreMatrix;
import com.josegabrielmarves.footballpredictor.prediction.elo.CalibratedEloRatings;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloCalculator;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.messaging.WhatsAppMessenger;
import com.josegabrielmarves.footballpredictor.prediction.RestDaysFactor;
import com.josegabrielmarves.footballpredictor.quiniela.MatchEV;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaRunnerV2;
import com.josegabrielmarves.footballpredictor.ui.theme.AppTheme;
import com.josegabrielmarves.footballpredictor.ui.StandingsPane;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MainWindow extends BorderPane {

    private static final String[] COLS =
        {"Local", "Visitante", "Fecha", "Grupo / Ronda", "Resultado", "Seguro", "Exacto arriesgado", "Riesgo"};

    private static class MatchRow {
        final SimpleStringProperty homeTeam = new SimpleStringProperty();
        final SimpleStringProperty awayTeam = new SimpleStringProperty();
        final SimpleStringProperty date     = new SimpleStringProperty();
        final SimpleStringProperty group    = new SimpleStringProperty();
        final SimpleStringProperty score    = new SimpleStringProperty();
        final SimpleStringProperty seguro   = new SimpleStringProperty();
        final SimpleStringProperty exacto   = new SimpleStringProperty();
        final SimpleStringProperty riesgo   = new SimpleStringProperty();
    }

    private final ObservableList<MatchRow> tableData = FXCollections.observableArrayList();
    private final FilteredList<MatchRow> filteredData;
    private final TableView<MatchRow> tableView;
    private final Button btnPredict, btnPipeline, btnWhatsApp;
    private final Label statusLabel;
    final BracketView bracketView;
    private final MatrixPane matrixPane;
    private final TextArea whatsappArea;
    private final TabPane tabs;
    private final TextField searchField;
    private final EnsemblePredictor ensemblePredictor;
    private List<Match> loadedMatches;
    private List<MatchEV.DualPick> lastPicks; // para el boton WhatsApp

    public MainWindow() {
        setBackground(new Background(new BackgroundFill(AppTheme.BG(), CornerRadii.EMPTY, Insets.EMPTY)));

        Label title = new Label("FOOTBALL PREDICTOR — QUINIELA MUNDIAL 2026");
        title.setTextFill(AppTheme.TXT());
        title.setFont(Font.font("Arial", FontWeight.BOLD, 20));
        title.setPadding(new Insets(10, 0, 10, 0));
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);

        ComboBox<AppTheme.Theme> themeSelector = new ComboBox<>();
        themeSelector.getItems().addAll(AppTheme.Theme.values());
        themeSelector.setValue(AppTheme.getCurrentTheme());
        themeSelector.setStyle("-fx-background-color:#1A1E28;-fx-text-fill:white;");
        themeSelector.setOnAction(e -> {
            AppTheme.Theme t = themeSelector.getValue();
            AppTheme.setCurrentTheme(t);
            applyTheme();
        });

        HBox headerBox = new HBox(10, title, new Region(), themeSelector);
        headerBox.setAlignment(Pos.CENTER);
        HBox.setHgrow(headerBox.getChildren().get(1), Priority.ALWAYS);

        // ── Tab 1: Groups & Bracket ──
        bracketView = new BracketView(this);
        bracketView.setMinWidth(1650);
        ScrollPane bracketScroll = new ScrollPane(bracketView);
        bracketScroll.setStyle("-fx-background: #0F1217; -fx-control-inner-background: #0F1217;");
        bracketScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        bracketScroll.setFitToWidth(false);

        // ── Tab 2: All matches ──
        tableView = new TableView<>();
        tableView.setStyle(AppTheme.tableStyle());
        tableView.setPlaceholder(new Label("No hay partidos cargados"));
        tableView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        for (int i = 0; i < COLS.length; i++) {
            final int ci = i;
            TableColumn<MatchRow, String> col = new TableColumn<>(COLS[i]);
            col.setSortable(true);
            col.setCellValueFactory(d -> switch (ci) {
                case 0 -> d.getValue().homeTeam;
                case 1 -> d.getValue().awayTeam;
                case 2 -> d.getValue().date;
                case 3 -> d.getValue().group;
                case 4 -> d.getValue().score;
                case 5 -> d.getValue().seguro;
                case 6 -> d.getValue().exacto;
                case 7 -> d.getValue().riesgo;
                default -> null;
            });
            if (ci == 2) {
                col.setComparator((a, b) -> {
                    try {
                        var f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
                        var d1 = java.time.LocalDate.parse(a, f);
                        var d2 = java.time.LocalDate.parse(b, f);
                        return d1.compareTo(d2);
                    } catch (Exception e) {
                        return a.compareTo(b);
                    }
                });
            }
            tableView.getColumns().add(col);
        }
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);

        tableView.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(MatchRow item, boolean empty) {
                super.updateItem(item, empty);
                if (item == null || empty) {
                    setStyle("");
                } else {
                    setStyle(getIndex() % 2 == 0
                        ? "-fx-background-color:#1A1E28;"
                        : "-fx-background-color:#141822;");
                }
            }
        });

        filteredData = new FilteredList<>(tableData, p -> true);
        tableView.setItems(filteredData);

        searchField = new TextField();
        searchField.setPromptText("Buscar equipo...");
        searchField.setStyle(AppTheme.inputStyle());
        searchField.textProperty().addListener((o, a, v) -> {
            String q = v == null ? "" : v.trim().toLowerCase();
            filteredData.setPredicate(r ->
                q.isEmpty() || r.homeTeam.get().toLowerCase().contains(q)
                || r.awayTeam.get().toLowerCase().contains(q));
        });

        VBox tablePanel = new VBox(6, searchField, tableView);
        tablePanel.setPadding(new Insets(6, 8, 6, 8));
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // ── Tab 3: Matriz 500k ──
        matrixPane = new MatrixPane();

        // ── EnsemblePredictor con mercado ──
        ensemblePredictor = new EnsemblePredictor();

        // ── Tab 4: WhatsApp Preview ──
        whatsappArea = new TextArea();
        whatsappArea.setEditable(false);
        whatsappArea.setStyle(AppTheme.consoleStyle());

        // ── Tabs ──
        tabs = new TabPane();
        tabs.setStyle("-fx-background: #0F1217; -fx-text-fill: #E8ECF2;");
        Tab tab1 = new Tab("Grupos & Bracket", bracketScroll);
        tab1.setClosable(false);
        Tab tab2 = new Tab("Todos los partidos", tablePanel);
        tab2.setClosable(false);
        Tab tab3 = new Tab("Matriz 500k", matrixPane);
        tab3.setClosable(false);
        Tab tab4 = new Tab("📱 WhatsApp Preview", whatsappArea);
        tab4.setClosable(false);
        StandingsPane standingsPane = new StandingsPane();
        Tab tab5 = new Tab("🏆 Tabla de Puntos", standingsPane);
        tab5.setClosable(false);
        tabs.getTabs().addAll(tab1, tab2, tab3, tab4, tab5);

        // ── Bottom ──
        btnPredict = new Button("Generar Predicciones");
        btnPredict.setStyle(AppTheme.primaryButtonStyle());
        btnPredict.setDisable(true);

        btnPipeline = new Button("▶ Correr Pipeline Completo");
        btnPipeline.setStyle(AppTheme.goldButtonStyle());

        btnWhatsApp = new Button("📱 Enviar a WhatsApp");
        btnWhatsApp.setStyle("-fx-background-color:#25D366;-fx-text-fill:white;"
                + "-fx-font-weight:bold;-fx-padding:8 18;-fx-background-radius:4;");
        btnWhatsApp.setDisable(true);

        statusLabel = new Label("Cargando fixture 2026...");
        statusLabel.setTextFill(AppTheme.DIM());
        statusLabel.setFont(Font.font("Arial", 12));

        HBox bottom = new HBox(10, statusLabel, new Region(), btnWhatsApp, btnPipeline, btnPredict);
        bottom.setPadding(new Insets(8, 10, 8, 10));
        bottom.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(bottom.getChildren().get(1), Priority.ALWAYS);

        btnPredict.setOnAction(e -> generatePredictions());
        btnPipeline.setOnAction(e -> runPipeline());
        btnWhatsApp.setOnAction(e -> sendToWhatsApp());

        setTop(headerBox);
        setCenter(tabs);
        setBottom(bottom);
        applyTheme();
    }

    // ── Apply theme ──

    private void applyTheme() {
        setBackground(new Background(new BackgroundFill(AppTheme.BG(), CornerRadii.EMPTY, Insets.EMPTY)));
        tabs.setStyle("-fx-background: " + AppTheme.hex(AppTheme.BG()) + "; -fx-text-fill: " + AppTheme.hex(AppTheme.TEXT()) + ";");
        tableView.setStyle(AppTheme.tableStyle());
        statusLabel.setTextFill(AppTheme.DIM());
        btnPredict.setStyle(AppTheme.primaryButtonStyle());
        btnPipeline.setStyle(AppTheme.goldButtonStyle());
        whatsappArea.setStyle(AppTheme.consoleStyle());
        searchField.setStyle(AppTheme.inputStyle());
        bracketView.setStyle("-fx-background-color: " + AppTheme.hex(AppTheme.BG()) + ";");
    }

    // ── Load ──

    public void loadFixture() {
        Task<List<Match>> t = new Task<>() {
            @Override
            protected List<Match> call() throws Exception {
                return new OpenFootballProvider().getWorldCupMatches(2026);
            }
        };
        t.setOnSucceeded(e -> {
            loadedMatches = t.getValue();
            loadedMatches.sort(Comparator.comparing(m -> m.date));

            // Inicializar factor de descanso con partidos ya jugados
            RestDaysFactor.initialize(loadedMatches);

            // Filtrar solo partidos NO jugados (sin marcador) para la tabla
            List<Match> upcoming = loadedMatches.stream()
                    .filter(m -> m.score == null)
                    .toList();

            populateTable(upcoming);
            // El bracket necesita TODOS los partidos (jugados + pendientes)
            // para calcular posiciones de grupo y cruces
            bracketView.setMatches(loadedMatches, buildRatings());
            matrixPane.setData(upcoming, buildRatings());
            btnPredict.setDisable(false);

            int total = loadedMatches.size();
            int pendientes = upcoming.size();
            int jugados = total - pendientes;
            statusLabel.setText(pendientes + " partidos pendientes de " + total + " totales (" + jugados + " ya jugados)");
        });
        t.setOnFailed(e -> statusLabel.setText("Error: " + t.getException().getMessage()));
        new Thread(t).start();
    }

    private void populateTable(List<Match> ms) {
        tableData.clear();
        for (Match m : ms) {
            MatchRow r = new MatchRow();
            r.homeTeam.set(m.homeTeam);
            r.awayTeam.set(m.awayTeam);
            r.date.set(m.date);
            r.group.set(m.group != null ? m.group : m.status);
            r.score.set(m.score != null ? m.score.toString() : "—");
            r.seguro.set("—");
            r.exacto.set("—");
            r.riesgo.set("—");
            tableData.add(r);
        }
    }

    // ── Predictions ──

    private void generatePredictions() {
        if (loadedMatches == null) return;
        btnPredict.setDisable(true);
        statusLabel.setText("Calculando predicciones con motor de torneo + mercado...");
        lastPicks = new ArrayList<>();
        Task<Void> t = new Task<>() {
            @Override
            protected Void call() {
                Map<String, EloRating> ratings = buildRatings();
                int idx = 0;
                for (Match m : loadedMatches) {
                    EloRating h = ratings.getOrDefault(m.homeTeam, EloRating.initial(m.homeTeam));
                    EloRating a = ratings.getOrDefault(m.awayTeam, EloRating.initial(m.awayTeam));
                    double bonus = isHost(m.homeTeam) ? EloCalculator.HOME_ADVANTAGE : 0;
                    // Usar dualPickWithMarket para incluir odds
                    MatchEV.DualPick pick = MatchEV.dualPickWithMarket(
                            m.homeTeam, h, m.awayTeam, a, bonus, ensemblePredictor);
                    lastPicks.add(pick);
                    String seg = String.format("%d-%d (%.0f%%)", pick.seguro().homeGoals(), pick.seguro().awayGoals(), pick.pSeguro() * 100);
                    String exc = String.format("%d-%d (%.0f%%)", pick.exacto().homeGoals(), pick.exacto().awayGoals(), pick.pExacto() * 100);
                    int fi = idx++;
                    Platform.runLater(() -> {
                        MatchRow r = tableData.get(fi);
                        r.seguro.set(seg);
                        r.exacto.set(exc);
                        r.riesgo.set(pick.risk().label);
                    });
                }
                return null;
            }
        };
        t.setOnSucceeded(e -> {
            btnPredict.setDisable(false);
            btnWhatsApp.setDisable(false);
            // Actualizar preview de WhatsApp
            buildWhatsAppPreview();
            statusLabel.setText("Predicciones generadas — ✅ Revisa la pestaña '📱 WhatsApp Preview'");
            tabs.getSelectionModel().select(3);
        });
        new Thread(t).start();
    }

    // ── Pipeline ──

    private void runPipeline() {
        btnPipeline.setDisable(true);
        statusLabel.setText("Corriendo pipeline completo...");
        tabs.getSelectionModel().select(3);
        whatsappArea.clear();
        Task<Void> t = new Task<>() {
            @Override
            protected Void call() {
                try {
                    QuinielaRunnerV2.run();
                } catch (Exception ex) {
                    System.out.println("\n[ERROR] " + ex.getMessage());
                    ex.printStackTrace();
                }
                return null;
            }
        };
        t.setOnSucceeded(e -> {
            btnPipeline.setDisable(false);
            statusLabel.setText("Pipeline completado");
        });
        new Thread(t).start();
    }

    // ── WhatsApp Preview ──

    /** Construye el mensaje de WhatsApp y lo muestra en la pestaña de preview. */
    private void buildWhatsAppPreview() {
        if (loadedMatches == null || lastPicks == null) return;
        String msg = buildWhatsAppMessage();
        whatsappArea.setText(msg);
    }

    /** Construye el mensaje completo de WhatsApp con mercado + recomendaciones. */
    private String buildWhatsAppMessage() {
        StringBuilder msg = new StringBuilder();
        msg.append("⚽ *PREDICCIONES GRUPOS — Mundial 2026*\n");
        msg.append("📅 ").append(java.time.LocalDate.now()).append("\n");
        msg.append("👤 Gabriel Marves\n");
        msg.append("🤖 Motor: Triple Blend + Mercado (α=0.15)\n");
        msg.append("─".repeat(25)).append("\n\n");

        int exactosRecomendados = 0;
        int totalPendientes = 0;

        for (int i = 0; i < loadedMatches.size(); i++) {
            Match m = loadedMatches.get(i);
            if (m.score != null) continue; // solo pendientes
            totalPendientes++;
            MatchEV.DualPick pick = lastPicks.get(i);

            msg.append("🏟️ *").append(m.homeTeam).append("* vs *").append(m.awayTeam).append("*\n");
            msg.append("   📊 ").append(pick.resultLabel()).append("\n");
            msg.append("   🎯 Seguro (1pt): ").append(pick.seguro().homeGoals()).append("-").append(pick.seguro().awayGoals());
            msg.append("  (").append(String.format("%.0f%%", pick.pSeguro()*100)).append(")\n");
            msg.append("   🎲 Exacto (3pts): ").append(pick.exacto().homeGoals()).append("-").append(pick.exacto().awayGoals());
            msg.append("  (").append(String.format("%.0f%%", pick.pExacto()*100)).append(")\n");
            msg.append("   ").append(pick.risk().label);

            // Recomendación: cuándo arriesgar el exacto vs ir por el seguro
            String rec = pick.pExacto() >= 0.08
                    ? " ✅ RECOMENDADO (exacto ≥8%)"
                    : pick.pSeguro() >= 0.20
                    ? " ✔️ Ir por seguro (exacto <8%)"
                    : pick.risk() == MatchEV.Risk.FIJO || pick.risk() == MatchEV.Risk.FUERTE
                    ? " → Recomiendo seguro (resultado confiable)"
                    : " ⚠️ Partido abierto — considera DOBLE o TRIPLE";
            msg.append(rec).append("\n");

            if (pick.pExacto() >= 0.08) exactosRecomendados++;

            msg.append("\n");
        }

        msg.append("─".repeat(25)).append("\n");
        msg.append("📊 *RESUMEN*\n");
        msg.append("   Partidos: ").append(totalPendientes).append("\n");
        msg.append("   Exactos recomendados (≥8%): ").append(exactosRecomendados).append("/").append(totalPendientes).append("\n");
        msg.append("   🎯 Estrategia: arriesgar exacto cuando ≥8%, seguro en el resto\n");
        msg.append("─".repeat(25)).append("\n");
        msg.append("⚡ FootballPredictor — Triple Blend + Mercado + xG\n");

        return msg.toString();
    }

    // ── WhatsApp Send ──

    private void sendToWhatsApp() {
        if (loadedMatches == null || lastPicks == null || loadedMatches.isEmpty()) {
            statusLabel.setText("Primero genera predicciones");
            return;
        }
        btnWhatsApp.setDisable(true);
        statusLabel.setText("Enviando a WhatsApp...");

        String message = buildWhatsAppMessage();

        Task<Void> t = new Task<>() {
            @Override
            protected Void call() {
                WhatsAppMessenger.sendWithBot(message);
                return null;
            }
        };
        t.setOnSucceeded(e -> {
            btnWhatsApp.setDisable(false);
            statusLabel.setText("✅ Mensaje enviado/copiado al portapapeles");
        });
        t.setOnFailed(e -> {
            btnWhatsApp.setDisable(false);
            statusLabel.setText("Error al enviar: " + t.getException().getMessage());
        });
        new Thread(t).start();
    }

    // ── Helpers ──

    Map<String, EloRating> buildRatings() {
        Map<String, EloRating> r = new HashMap<>();
        if (loadedMatches != null)
            for (Match m : loadedMatches)
                r.putIfAbsent(m.homeTeam, CalibratedEloRatings.getRating(m.homeTeam));
        return r;
    }

    boolean isHost(String t) {
        return t.equals("Mexico") || t.equals("USA") || t.equals("United States") || t.equals("Canada");
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Matriz 500k — mapa de calor
    // ═══════════════════════════════════════════════════════════════════

    private class MatrixPane extends BorderPane {
        private final ListView<String> matchList = new ListView<>();
        private final HeatmapView heatmap = new HeatmapView();
        private final Label info = new Label("Selecciona un partido");
        private final Map<Integer, ScoreMatrix> cache = new HashMap<>();
        private List<Match> matches;
        private Map<String, EloRating> ratings;

        MatrixPane() {
            matchList.setStyle(AppTheme.inputStyle());
            matchList.setPrefWidth(340);
            matchList.getSelectionModel().selectedIndexProperty().addListener((o, a, idx) -> {
                if (idx.intValue() >= 0) showMatch(idx.intValue());
            });

            info.setTextFill(AppTheme.TXT());
            info.setFont(Font.font("Arial", FontWeight.BOLD, 17));
            info.setPadding(new Insets(12, 0, 12, 0));

            setLeft(matchList);
            setCenter(new VBox(0, info, heatmap));
            VBox.setVgrow(heatmap, Priority.ALWAYS);
        }

        void setData(List<Match> ms, Map<String, EloRating> r) {
            matches = ms;
            ratings = r;
            matchList.getItems().clear();
            for (Match m : ms) {
                if (m.homeTeam == null || m.awayTeam == null) continue;
                if (m.homeTeam.matches(".*\\d.*")) continue;
                matchList.getItems().add(m.homeTeam + " vs " + m.awayTeam);
            }
            if (!matchList.getItems().isEmpty()) matchList.getSelectionModel().select(0);
        }

        private void showMatch(int listIdx) {
            if (listIdx < 0 || matches == null) return;
            int realIdx = -1, count = 0;
            for (int i = 0; i < matches.size(); i++) {
                Match m = matches.get(i);
                if (m.homeTeam == null || m.awayTeam == null || m.homeTeam.matches(".*\\d.*")) continue;
                if (count == listIdx) {
                    realIdx = i;
                    break;
                }
                count++;
            }
            if (realIdx < 0) return;
            int fi = realIdx;
            Match m = matches.get(fi);
            info.setText("Calculando 500.000 simulaciones...");
            heatmap.clear();

            Task<ScoreMatrix> t = new Task<>() {
                @Override
                protected ScoreMatrix call() {
                    if (cache.containsKey(fi)) return cache.get(fi);
                    EloRating h = ratings.getOrDefault(m.homeTeam, EloRating.initial(m.homeTeam));
                    EloRating a = ratings.getOrDefault(m.awayTeam, EloRating.initial(m.awayTeam));
                    double bonus = isHost(m.homeTeam) ? EloCalculator.HOME_ADVANTAGE : 0;
                    ScoreMatrix sm = ScoreMatrix.compute(m.homeTeam, h, m.awayTeam, a, bonus, 42L);
                    cache.put(fi, sm);
                    return sm;
                }
            };
            t.setOnSucceeded(e -> {
                ScoreMatrix sm = t.getValue();
                Score modal = sm.mostLikelyScore();
                info.setText(String.format(
                    "%s vs %s  |  Local %.0f%%  Empate %.0f%%  Visitante %.0f%%  |  Pico: %d-%d (%.1f%%)",
                    m.homeTeam, m.awayTeam,
                    sm.pHomeWin() * 100, sm.pDraw() * 100, sm.pAwayWin() * 100,
                    modal.homeGoals(), modal.awayGoals(), sm.probability(modal.homeGoals(), modal.awayGoals()) * 100));
                heatmap.setMatrix(sm, m.homeTeam, m.awayTeam);
            });
            new Thread(t).start();
        }
    }

    private static class HeatmapView extends Pane {
        private ScoreMatrix sm;
        private String homeTeam = "", awayTeam = "";
        private static final int N = 6;
        private final Canvas canvas = new Canvas();

        HeatmapView() {
            canvas.widthProperty().bind(widthProperty());
            canvas.heightProperty().bind(heightProperty());
            canvas.widthProperty().addListener(o -> draw());
            canvas.heightProperty().addListener(o -> draw());
            getChildren().add(canvas);
        }

        void setMatrix(ScoreMatrix sm, String h, String a) {
            this.sm = sm;
            this.homeTeam = h;
            this.awayTeam = a;
            draw();
        }

        void clear() {
            sm = null;
            draw();
        }

        private void draw() {
            GraphicsContext g = canvas.getGraphicsContext2D();
            g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            if (sm == null) return;

            double w = canvas.getWidth(), h = canvas.getHeight();
            int cell = (int) Math.min((w - 120) / N, (h - 120) / N);
            cell = Math.max(48, Math.min(cell, 92));
            int x0 = 90, y0 = 70;

            double maxP = 0;
            for (int i = 0; i < N; i++)
                for (int j = 0; j < N; j++)
                    maxP = Math.max(maxP, sm.probability(i, j));

            int mh = 0, ma = 0;
            double best = -1;
            for (int i = 0; i < N; i++)
                for (int j = 0; j < N; j++)
                    if (sm.probability(i, j) > best) {
                        best = sm.probability(i, j);
                        mh = i;
                        ma = j;
                    }

            g.setFont(Font.font("Arial", FontWeight.BOLD, 13));
            g.setFill(AppTheme.ACCENT());
            g.fillText(awayTeam + " (goles →)", x0, y0 - 40);
            g.save();
            g.translate(25, y0 + N * cell / 2.0);
            g.rotate(-Math.PI / 2);
            g.fillText(homeTeam + " (goles →)", 0, 0);
            g.restore();

            for (int a = 0; a < N; a++) {
                g.setFill(AppTheme.DIM());
                g.setFont(Font.font("Arial", FontWeight.BOLD, 12));
                g.fillText(String.valueOf(a), x0 + a * cell + cell / 2.0 - 4, y0 - 8);
            }
            for (int i = 0; i < N; i++) {
                g.setFill(AppTheme.DIM());
                g.fillText(String.valueOf(i), x0 - 24, y0 + i * cell + cell / 2.0 + 5);
            }

            for (int i = 0; i < N; i++) {
                for (int j = 0; j < N; j++) {
                    double p = sm.probability(i, j);
                    double intensity = maxP > 0 ? p / maxP : 0;
                    g.setFill(Color.rgb(0, (int) (0x60 + 0x90 * intensity), (int) (0x90 + 0x6F * intensity),
                        Math.min(1.0, (40 + 200 * intensity) / 255.0)));
                    g.fillRoundRect(x0 + j * cell + 2, y0 + i * cell + 2, cell - 4, cell - 4, 8, 8);

                    if (i == mh && j == ma) {
                        g.setStroke(AppTheme.GOLD());
                        g.setLineWidth(3);
                        g.strokeRoundRect(x0 + j * cell + 2, y0 + i * cell + 2, cell - 4, cell - 4, 8, 8);
                    }
                    g.setFill(p > maxP * 0.25 ? Color.WHITE : AppTheme.DIM());
                    g.setFont(Font.font("Arial", FontWeight.BOLD, 12));
                    g.fillText(i + "-" + j, x0 + j * cell + cell / 2.0 - 12, y0 + i * cell + cell / 2.0 - 2);
                    g.setFont(Font.font("Arial", FontWeight.NORMAL, 11));
                    g.fillText(String.format("%.1f%%", p * 100), x0 + j * cell + cell / 2.0 - 14, y0 + i * cell + cell / 2.0 + 13);
                }
            }
        }
    }
}
