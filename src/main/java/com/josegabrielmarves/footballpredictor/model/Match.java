package com.josegabrielmarves.footballpredictor.model;

/**
 * Modelo de dominio limpio de un partido.
 */
public class Match {

    public int id;
    public String homeTeam;
    public String awayTeam;
    public String date;
    public String status;
    public Score score;
    public String winner;

    // Campos estadísticos — se rellenan en fases posteriores
    public double homeXG;
    public double awayXG;
    public double homeOdds;
    public double drawOdds;
    public double awayOdds;

    public Match(int id, String homeTeam, String awayTeam,
                 String date, String status, Score score, String winner) {
        this.id       = id;
        this.homeTeam = homeTeam;
        this.awayTeam = awayTeam;
        this.date     = date;
        this.status   = status;
        this.score    = score;
        this.winner   = winner;
    }
}