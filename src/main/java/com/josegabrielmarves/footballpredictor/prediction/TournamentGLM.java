package com.josegabrielmarves.footballpredictor.prediction;

import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.optim.*;
import org.apache.commons.math3.optim.nonlinear.scalar.*;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.PowellOptimizer;

import java.util.*;

/**
 * MEJORA C — Poisson GLM con parámetros ataque/defensa por equipo.
 *
 * Modelo (Rue & Salvesen 2000 / Dixon-Coles 1997):
 *   log(λ_home) = μ + attack[home] - defense[away]
 *   log(λ_away) = μ + attack[away] - defense[home]
 *   goals ~ Poisson(λ) con corrección Dixon-Coles
 *
 * Diferencia vs nuestro modelo actual:
 *   Actual:  λ = BASE_GOALS + Elo_diff/400  (lineal, un solo parámetro)
 *   GLM:     λ = exp(μ + attack_i - defense_j) (exponencial, parámetros separados)
 *
 * Con solo 24 partidos jugados, se regulariza fuertemente hacia los priors de Elo.
 * A medida que avanza el torneo, el GLM se calibra y pesa más.
 *
 * Uso:
 *   TournamentGLM glm = TournamentGLM.fit(matches, eloRatings);
 *   double lambdaHome = glm.lambdaHome("Spain", "Cape Verde");
 *   double lambdaAway = glm.lambdaAway("Spain", "Cape Verde");
 */
public final class TournamentGLM {

    private static final double REG_LAMBDA = 2.0; // regularización (ridge)
    private static final double HOME_ADV   = 0.10; // log-scale home advantage

    private final Map<String, Double> attackParams  = new HashMap<>();
    private final Map<String, Double> defenseParams = new HashMap<>();
    private final double mu;
    private final int matchesUsed;

    private TournamentGLM(Map<String, Double> atk, Map<String, Double> def,
                          double mu, int matchesUsed) {
        this.attackParams.putAll(atk);
        this.defenseParams.putAll(def);
        this.mu = mu;
        this.matchesUsed = matchesUsed;
    }

    // ── Datos de entrada ──────────────────────────────────────────────────────

    public record MatchData(String home, String away,
                            int homeGoals, int awayGoals, boolean homeAdv) {}

    // ── Ajuste del modelo ─────────────────────────────────────────────────────

    /**
     * Ajusta los parámetros ataque/defensa a los partidos del WC 2026 jugados.
     * Usa regularización ridge hacia los priors de Elo.
     *
     * @param matches  partidos jugados con sus marcadores
     * @param ratings  ratings Elo actualizados (usados como prior)
     */
    public static TournamentGLM fit(List<MatchData> matches,
                                    Map<String, EloRating> ratings) {
        if (matches.isEmpty()) return empty(ratings);

        // Recopilar todos los equipos
        Set<String> teamsSet = new LinkedHashSet<>();
        for (MatchData m : matches) {
            teamsSet.add(m.home());
            teamsSet.add(m.away());
        }
        List<String> teams = new ArrayList<>(teamsSet);
        int n = teams.size();

        // Priors basados en Elo: attack_prior_i = log(elo_i / 1500) * 1.5
        Map<String, Double> attackPrior  = new HashMap<>();
        Map<String, Double> defensePrior = new HashMap<>();
        for (String t : teams) {
            double elo = ratings.containsKey(t) ? ratings.get(t).rating() : 1500.0;
            double prior = Math.log(elo / 1500.0) * 1.5;
            attackPrior.put(t, prior);
            defensePrior.put(t, -prior * 0.5); // defensa inversamente proporcional
        }

        // Inicializar parámetros desde el prior
        double[] initParams = new double[2 * n + 1];
        for (int i = 0; i < n; i++) {
            initParams[i]     = attackPrior.getOrDefault(teams.get(i), 0.0);
            initParams[n + i] = defensePrior.getOrDefault(teams.get(i), 0.0);
        }
        initParams[2 * n] = 0.45; // mu = log(BASE_GOALS)

        // Función objetivo: -log-likelihood + regularización
        MultivariateFunction negLogLik = params -> {
            double nll = 0.0;
            for (MatchData m : matches) {
                int hi = teams.indexOf(m.home());
                int ai = teams.indexOf(m.away());
                if (hi < 0 || ai < 0) continue;

                double muV     = params[2 * n];
                double homeAdj = m.homeAdv() ? HOME_ADV : 0.0;
                double lH = Math.exp(muV + params[hi] - params[n + ai] + homeAdj);
                double lA = Math.exp(muV + params[ai] - params[n + hi]);
                lH = Math.max(0.10, Math.min(6.0, lH));
                lA = Math.max(0.10, Math.min(6.0, lA));

                // Poisson log-likelihood
                nll -= (m.homeGoals() * Math.log(lH) - lH);
                nll -= (m.awayGoals() * Math.log(lA) - lA);
            }

            // Regularización ridge hacia el prior
            for (int i = 0; i < n; i++) {
                double atkPrior = attackPrior.getOrDefault(teams.get(i), 0.0);
                double defPrior = defensePrior.getOrDefault(teams.get(i), 0.0);
                nll += REG_LAMBDA * Math.pow(params[i] - atkPrior, 2);
                nll += REG_LAMBDA * Math.pow(params[n + i] - defPrior, 2);
            }
            return nll;
        };

        // Optimización con Powell (sin gradiente, robusto)
        try {
            PointValuePair result = new PowellOptimizer(1e-6, 1e-6)
                    .optimize(
                            new MaxEval(50_000),
                            new ObjectiveFunction(negLogLik),
                            GoalType.MINIMIZE,
                            new InitialGuess(initParams)
                    );

            double[] opt = result.getPoint();
            Map<String, Double> atk = new HashMap<>(), def = new HashMap<>();
            for (int i = 0; i < n; i++) {
                atk.put(teams.get(i), opt[i]);
                def.put(teams.get(i), opt[n + i]);
            }
            return new TournamentGLM(atk, def, opt[2 * n], matches.size());

        } catch (Exception e) {
            System.err.println("[TournamentGLM] Optimización falló: " + e.getMessage());
            return empty(ratings);
        }
    }

    private static TournamentGLM empty(Map<String, EloRating> ratings) {
        Map<String, Double> atk = new HashMap<>(), def = new HashMap<>();
        for (Map.Entry<String, EloRating> e : ratings.entrySet()) {
            double prior = Math.log(e.getValue().rating() / 1500.0) * 1.5;
            atk.put(e.getKey(), prior);
            def.put(e.getKey(), -prior * 0.5);
        }
        return new TournamentGLM(atk, def, 0.30, 0);
    }

    // ── Predicción ────────────────────────────────────────────────────────────

    /** Lambda de goles esperados del equipo local. */
    public double lambdaHome(String home, String away, boolean homeAdv) {
        double homeAdj = homeAdv ? HOME_ADV : 0.0;
        double lH = Math.exp(mu
                + attackParams.getOrDefault(home, 0.0)
                - defenseParams.getOrDefault(away, 0.0)
                + homeAdj);
        return Math.max(0.20, Math.min(5.0, lH));
    }

    /** Lambda de goles esperados del equipo visitante. */
    public double lambdaAway(String home, String away) {
        double lA = Math.exp(mu
                + attackParams.getOrDefault(away, 0.0)
                - defenseParams.getOrDefault(home, 0.0));
        return Math.max(0.20, Math.min(5.0, lA));
    }

    public int matchesUsed() { return matchesUsed; }

    /** Imprime ranking de ataque y defensa. */
    public void printStrengths(List<String> teams) {
        System.out.println("\n── TournamentGLM — Fortalezas calibradas ──");
        System.out.printf("%-25s %8s %8s%n", "Equipo", "Ataque", "Defensa");
        teams.stream()
                .filter(t -> attackParams.containsKey(t))
                .sorted(Comparator.comparingDouble(t -> -attackParams.get(t)))
                .limit(16)
                .forEach(t -> System.out.printf("%-25s %+7.3f %+7.3f%n",
                        t, attackParams.getOrDefault(t, 0.0),
                        defenseParams.getOrDefault(t, 0.0)));
        System.out.println();
    }
}