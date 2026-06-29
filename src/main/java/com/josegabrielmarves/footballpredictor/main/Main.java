package com.josegabrielmarves.footballpredictor.main;

import com.josegabrielmarves.footballpredictor.ui.MainWindow;
import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

public class Main extends Application {
    @Override
    public void start(Stage primaryStage) {
        MainWindow root = new MainWindow();

        // Tamaño adaptativo segun pantalla
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double w = Math.min(1400, screen.getWidth() * 0.92);
        double h = Math.min(850, screen.getHeight() * 0.90);

        Scene scene = new Scene(root, w, h);
        scene.setFill(Color.rgb(15, 18, 23));
        primaryStage.setTitle("Football Predictor — Quiniela Mundial 2026");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(550);
        primaryStage.show();
        root.loadFixture();
    }

    public static void main(String[] args) {
        launch(args);
    }
}