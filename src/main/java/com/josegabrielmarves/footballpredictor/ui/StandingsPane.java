package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.ui.theme.AppTheme;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;

public class StandingsPane extends BorderPane {

    // ── Data model ──
    public static final class StandingsRow {
        private final IntegerProperty pos = new SimpleIntegerProperty();
        private final StringProperty  name = new SimpleStringProperty();
        private final IntegerProperty points = new SimpleIntegerProperty();
        private final IntegerProperty exactScores = new SimpleIntegerProperty();
        private final IntegerProperty knockoutPoints = new SimpleIntegerProperty();
        private final StringProperty  change = new SimpleStringProperty("—");

        public StandingsRow() {}

        public StandingsRow(int pos, String name, int points, int exact, int ko, String change) {
            setPos(pos); setName(name); setPoints(points);
            setExactScores(exact); setKnockoutPoints(ko); setChange(change);
        }

        // Properties
        public IntegerProperty posProperty()            { return pos; }
        public StringProperty  nameProperty()           { return name; }
        public IntegerProperty pointsProperty()         { return points; }
        public IntegerProperty exactScoresProperty()    { return exactScores; }
        public IntegerProperty knockoutPointsProperty() { return knockoutPoints; }
        public StringProperty  changeProperty()         { return change; }

        public int    getPos()           { return pos.get(); }
        public void   setPos(int v)      { pos.set(v); }
        public String getName()          { return name.get(); }
        public void   setName(String v)  { name.set(v); }
        public int    getPoints()        { return points.get(); }
        public void   setPoints(int v)   { points.set(v); }
        public int    getExactScores()   { return exactScores.get(); }
        public void   setExactScores(int v) { exactScores.set(v); }
        public int    getKnockoutPoints(){ return knockoutPoints.get(); }
        public void   setKnockoutPoints(int v) { knockoutPoints.set(v); }
        public String getChange()        { return change.get(); }
        public void   setChange(String v){ change.set(v); }
    }

    // ── JSON structure ──
    private static final class StandingsFile {
        String lastUpdated;
        String phase;
        int participants;
        List<PlayerEntry> players;
    }

    private static final class PlayerEntry {
        int pos;
        String name;
        int points;
        int exactScores;
        int knockoutPoints;
    }

    // ── Constants ──
    private static final String DATA_PATH = "data/quiniela_standings.json";
    private static final String MY_NAME = "Gabriel Marves";

    // ── Fields ──
    private final ObservableList<StandingsRow> standingsData = FXCollections.observableArrayList();
    private final TableView<StandingsRow> tableView = new TableView<>();
    private final Label statusLabel = new Label();
    private final Label lastUpdatedLabel = new Label();
    private boolean editMode = false;

    public StandingsPane() {
        buildUI();
        load();
    }

    private void buildUI() {
        // ── Title ──
        Label title = new Label("🏆 TABLA DE POSICIONES — Quiniela Mundial 2026");
        title.setTextFill(AppTheme.GOLD());
        title.setFont(Font.font("Arial", FontWeight.BOLD, 16));
        title.setPadding(new Insets(10, 0, 2, 0));
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(Pos.CENTER);

        lastUpdatedLabel.setTextFill(AppTheme.DIM());
        lastUpdatedLabel.setFont(Font.font("Arial", 11));
        lastUpdatedLabel.setPadding(new Insets(0, 0, 8, 0));
        lastUpdatedLabel.setMaxWidth(Double.MAX_VALUE);
        lastUpdatedLabel.setAlignment(Pos.CENTER);

        VBox headerBox = new VBox(0, title, lastUpdatedLabel);
        headerBox.setPadding(new Insets(0, 10, 4, 10));

        // ── Table ──
        tableView.setStyle(AppTheme.tableStyle());
        tableView.setPlaceholder(new Label("No hay datos de clasificación"));
        tableView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        tableView.setEditable(true);
        tableView.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        tableView.setRowFactory(tv -> new TableRow<StandingsRow>() {
            @Override
            protected void updateItem(StandingsRow item, boolean empty) {
                super.updateItem(item, empty);
                if (item == null || empty) {
                    setStyle("");
                } else {
                    boolean isMe = MY_NAME.equals(item.getName());
                    String bg = getIndex() % 2 == 0 ? "#1A1E28" : "#141822";
                    String border = isMe
                        ? "-fx-border-color: #FFD700; -fx-border-width: 1.5; -fx-border-radius: 2;"
                        : "";
                    setStyle("-fx-background-color:" + bg + ";" + border);
                    if (isMe) setTextFill(Color.GOLD);
                    else       setTextFill(AppTheme.TEXT());
                }
            }
        });

        // ── Columns ──
        TableColumn<StandingsRow, Number> colPos = new TableColumn<>("#");
        colPos.setPrefWidth(50);
        colPos.setCellValueFactory(d -> d.getValue().posProperty());
        colPos.setSortable(false);

        TableColumn<StandingsRow, String> colName = new TableColumn<>("JUGADOR");
        colName.setCellValueFactory(d -> d.getValue().nameProperty());
        colName.setSortable(false);
        colName.setPrefWidth(180);

        TableColumn<StandingsRow, Integer> colPts = new TableColumn<>("PTS");
        colPts.setPrefWidth(70);
        colPts.setCellValueFactory(d -> d.getValue().pointsProperty().asObject());
        colPts.setSortable(false);
        colPts.setStyle("-fx-alignment: CENTER; -fx-font-weight: bold;");
        colPts.setCellFactory(TextFieldTableCell.forTableColumn(new javafx.util.converter.IntegerStringConverter()));
        colPts.setOnEditCommit(e -> {
            e.getRowValue().setPoints(e.getNewValue());
            updatePositions();
            save();
        });

        TableColumn<StandingsRow, Integer> colExact = new TableColumn<>("EXACTOS");
        colExact.setPrefWidth(80);
        colExact.setCellValueFactory(d -> d.getValue().exactScoresProperty().asObject());
        colExact.setSortable(false);
        colExact.setStyle("-fx-alignment: CENTER;");
        colExact.setCellFactory(TextFieldTableCell.forTableColumn(new javafx.util.converter.IntegerStringConverter()));
        colExact.setOnEditCommit(e -> {
            e.getRowValue().setExactScores(e.getNewValue());
            save();
        });

        TableColumn<StandingsRow, Integer> colElim = new TableColumn<>("ELIM.");
        colElim.setPrefWidth(70);
        colElim.setCellValueFactory(d -> d.getValue().knockoutPointsProperty().asObject());
        colElim.setSortable(false);
        colElim.setStyle("-fx-alignment: CENTER;");
        colElim.setCellFactory(TextFieldTableCell.forTableColumn(new javafx.util.converter.IntegerStringConverter()));
        colElim.setOnEditCommit(e -> {
            e.getRowValue().setKnockoutPoints(e.getNewValue());
            save();
        });

        TableColumn<StandingsRow, String> colChange = new TableColumn<>("📈");
        colChange.setPrefWidth(60);
        colChange.setCellValueFactory(d -> d.getValue().changeProperty());
        colChange.setSortable(false);
        colChange.setStyle("-fx-alignment: CENTER;");

        //noinspection unchecked
        tableView.getColumns().addAll(colPos, colName, colPts, colExact, colElim, colChange);
        tableView.setItems(standingsData);

        // ── Bottom controls ──
        Button btnSave = new Button("💾 Guardar");
        btnSave.setStyle(
            "-fx-background-color:" + AppTheme.hex(AppTheme.GOLD()) + ";"
            + "-fx-text-fill:#000000;-fx-font-weight:bold;"
            + "-fx-padding:6 16;-fx-background-radius:4;");
        btnSave.setOnAction(e -> save());

        Button btnEdit = new Button("✏️ Modificar Puntos");
        btnEdit.setStyle(
            "-fx-background-color:#3A4152;-fx-text-fill:white;"
            + "-fx-padding:6 16;-fx-background-radius:4;");
        btnEdit.setOnAction(e -> {
            editMode = !editMode;
            tableView.setEditable(editMode);
            btnEdit.setText(editMode ? "🔒 Bloquear" : "✏️ Modificar Puntos");
        });

        statusLabel.setTextFill(AppTheme.DIM());
        statusLabel.setFont(Font.font("Arial", 11));

        HBox bottomControls = new HBox(10, btnEdit, btnSave, statusLabel);
        bottomControls.setPadding(new Insets(6, 10, 6, 10));
        bottomControls.setAlignment(Pos.CENTER_LEFT);

        // ── Phase info ──
        Label phaseInfo = new Label("Fase actual: Eliminatorias (R32 en curso) · 17 participantes");
        phaseInfo.setTextFill(AppTheme.DIM());
        phaseInfo.setFont(Font.font("Arial", 11));
        phaseInfo.setPadding(new Insets(0, 10, 4, 10));
        phaseInfo.setMaxWidth(Double.MAX_VALUE);
        phaseInfo.setAlignment(Pos.CENTER);

        // Scoring legend
        Label legend = new Label(
            "Puntaje: Grupos 1/3 · R32 2/4 · R16 3/5 · QF 4/6 · SF 5/7 · Final 6/8  |  "
            + "Desempates: Exactos → Pts Eliminatorias → Cercanía Final");
        legend.setTextFill(AppTheme.DIM());
        legend.setFont(Font.font("Arial", 10));
        legend.setPadding(new Insets(0, 10, 6, 10));
        legend.setMaxWidth(Double.MAX_VALUE);
        legend.setAlignment(Pos.CENTER);

        // ── Assemble ──
        VBox topBox = new VBox(0, headerBox, phaseInfo, legend);
        VBox centerBox = new VBox(4, tableView);
        centerBox.setPadding(new Insets(0, 10, 0, 10));
        VBox.setVgrow(tableView, Priority.ALWAYS);

        setTop(topBox);
        setCenter(centerBox);
        setBottom(bottomControls);
    }

    private void updatePositions() {
        List<StandingsRow> sorted = new ArrayList<>(standingsData);
        sorted.sort((a, b) -> {
            int cmp = Integer.compare(b.getPoints(), a.getPoints());
            if (cmp != 0) return cmp;
            return Integer.compare(b.getExactScores(), a.getExactScores());
        });
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setPos(i + 1);
        }
    }

    public void load() {
        File f = new File(DATA_PATH);
        if (!f.exists()) {
            statusLabel.setText("Archivo no encontrado. Usando datos por defecto.");
            loadDefaultData();
            return;
        }
        try {
            String json = Files.readString(f.toPath());
            Gson gson = new Gson();
            StandingsFile data = gson.fromJson(json, StandingsFile.class);
            if (data == null || data.players == null) {
                loadDefaultData();
                return;
            }
            lastUpdatedLabel.setText("Última actualización: " + (data.lastUpdated != null ? data.lastUpdated : "?"));
            standingsData.clear();
            for (PlayerEntry p : data.players) {
                standingsData.add(new StandingsRow(p.pos, p.name, p.points, p.exactScores, p.knockoutPoints, "—"));
            }
            statusLabel.setText("Cargado: " + data.players.size() + " jugadores");
        } catch (Exception e) {
            statusLabel.setText("Error al cargar: " + e.getMessage());
            loadDefaultData();
        }
    }

    private void loadDefaultData() {
        standingsData.clear();
        String[][] def = {
            {"Ruben Figueroa","73"},{"Cristhian Brito","72"},{"Moises Chavarria","72"},
            {"Daniel Ortiz","69"},{"Hector Cerrato","68"},{"Rodrigo Lopez","67"},
            {"Jorge Brand","65"},{"Jose Pozadas","65"},{"Nissy Rodriguez","64"},
            {"Jason Avila","63"},{"Luis Flores","59"},{"Gabriel Marves","59"},
            {"Alfredo Funez","58"},{"Manuel Molina","57"},{"Carlos Davis","56"},
            {"Daniel Rivera","43"},{"Carlos Guevara","0"}
        };
        for (int i = 0; i < def.length; i++) {
            standingsData.add(new StandingsRow(i + 1, def[i][0], Integer.parseInt(def[i][1]), 0, 0, "—"));
        }
        lastUpdatedLabel.setText("Última actualización: " + LocalDate.now());
        statusLabel.setText("Datos por defecto cargados");
    }

    public void save() {
        try {
            updatePositions();
            StandingsFile file = new StandingsFile();
            file.lastUpdated = LocalDate.now().toString();
            file.phase = "Eliminatorias R32";
            file.participants = standingsData.size();
            file.players = new ArrayList<>();
            for (StandingsRow row : standingsData) {
                PlayerEntry p = new PlayerEntry();
                p.pos = row.getPos();
                p.name = row.getName();
                p.points = row.getPoints();
                p.exactScores = row.getExactScores();
                p.knockoutPoints = row.getKnockoutPoints();
                file.players.add(p);
            }
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(file);
            new File("data").mkdirs();
            Files.writeString(Paths.get(DATA_PATH), json);
            statusLabel.setText("✅ Guardado: " + LocalDate.now());
        } catch (Exception e) {
            statusLabel.setText("❌ Error al guardar: " + e.getMessage());
        }
    }
}
