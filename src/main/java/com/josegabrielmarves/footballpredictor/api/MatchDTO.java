package com.josegabrielmarves.footballpredictor.api;

/**
 * DTO que representa un partido tal como llega del JSON de football-data.org v4.
 */
public class MatchDTO {

    public int id;
    public String utcDate;
    public String status;
    public TeamDTO homeTeam;
    public TeamDTO awayTeam;
    public ScoreDTO score;

    public static class TeamDTO {
        public int id;
        public String name;
    }

    public static class ScoreDTO {
        public String winner;
        public FullTimeDTO fullTime;

        public static class FullTimeDTO {
            public Integer home;
            public Integer away;
        }
    }
}