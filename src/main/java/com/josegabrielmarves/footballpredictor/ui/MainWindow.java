package com.josegabrielmarves.footballpredictor.ui;

import javax.swing.*;
import java.awt.*;

public class MainWindow extends JFrame {

    private JTable table;

    public MainWindow() {

        setTitle("Football Predictor");
        setSize(1200, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        setLayout(new BorderLayout());

        // Título
        JLabel title = new JLabel("Football Predictor - Quiniela Mode", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 24));
        add(title, BorderLayout.NORTH);

        // Datos simulados
        String[][] data = {
                {"Real Madrid", "Barcelona", "2-1"},
                {"Bayern", "Dortmund", "3-2"},
                {"Liverpool", "Chelsea", "1-1"},
                {"PSG", "Marseille", "2-0"}
        };

        String[] columns = {"Local", "Visitante", "Resultado"};

        table = new JTable(data, columns);

        add(new JScrollPane(table), BorderLayout.CENTER);

        // Botón
        JButton button = new JButton("Generar Predicción");
        add(button, BorderLayout.SOUTH);
    }
}