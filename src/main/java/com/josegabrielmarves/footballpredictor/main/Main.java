package com.josegabrielmarves.footballpredictor.main;

import com.josegabrielmarves.footballpredictor.ui.MainWindow;

import javax.swing.*;

public class Main {
    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow();
            window.setVisible(true);
        });

    }
}