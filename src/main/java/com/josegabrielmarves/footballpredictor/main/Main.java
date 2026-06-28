package com.josegabrielmarves.footballpredictor.main;

import com.josegabrielmarves.footballpredictor.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

public class Main extends Application {
    @Override
    public void start(Stage primaryStage) {
        MainWindow root = new MainWindow();
        Scene scene = new Scene(root, 1500, 900);
        scene.setFill(Color.rgb(15, 18, 23));
        primaryStage.setTitle("Football Predictor — Quiniela Mundial 2026");
        primaryStage.setScene(scene);
        primaryStage.show();
        root.loadFixture();
    }

    public static void main(String[] args) {
        launch(args);
    }
}