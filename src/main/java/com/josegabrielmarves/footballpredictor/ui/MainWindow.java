package com.josegabrielmarves.footballpredictor.ui;

import com.josegabrielmarves.footballpredictor.api.datasource.OpenFootballProvider;
import com.josegabrielmarves.footballpredictor.model.Match;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class MainWindow extends JFrame {

    private JTable table;

    public MainWindow() {
        setTitle("Football Predictor");
        setSize(1200, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // Título
        JLabel title = new JLabel("Football Predictor - Quiniela Mundial 2026", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 24));
        add(title, BorderLayout.NORTH);

        // Datos reales desde OpenFootball
        String[] columns = {"Local", "Visitante", "Fecha", "Grupo", "Resultado"};
        String[][] data = loadMatches();

        table = new JTable(data, columns);
        add(new JScrollPane(table), BorderLayout.CENTER);

        // Botón
        JButton button = new JButton("Generar Predicción");
        add(button, BorderLayout.SOUTH);
    }

    private String[][] loadMatches() {
        try {
            var provider = new OpenFootballProvider();
            List<Match> matches = provider.getWorldCupMatches(2026);

            String[][] data = new String[matches.size()][5];
            for (int i = 0; i < matches.size(); i++) {
                Match m = matches.get(i);
                data[i][0] = m.homeTeam;
                data[i][1] = m.awayTeam;
                data[i][2] = m.date;
                data[i][3] = m.status; // contiene el "round" (Matchday 1, etc.)
                data[i][4] = m.score != null ? m.score.toString() : "-";
            }
            return data;

        } catch (Exception e) {
            System.err.println("[MainWindow] Error cargando partidos: " + e.getMessage());
            return new String[][]{{"Error", "cargando", "datos", "", ""}};
        }
    }
}