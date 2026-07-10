package com.josegabrielmarves.footballpredictor.messaging;

import com.josegabrielmarves.footballpredictor.model.Score;
import com.josegabrielmarves.footballpredictor.prediction.MatchdayEngine;
import com.josegabrielmarves.footballpredictor.prediction.elo.EloRating;
import com.josegabrielmarves.footballpredictor.prediction.poisson.PoissonPredictor;
import com.josegabrielmarves.footballpredictor.quiniela.MatchEV;
import com.josegabrielmarves.footballpredictor.quiniela.QuinielaScorer;
import com.josegabrielmarves.footballpredictor.quiniela.FastStrategyOptimizer;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Genera y envía el mensaje de WhatsApp con las predicciones de la jornada.
 *
 * Modos de envío:
 *   1. COPY    — copia al portapapeles (lo pegas manual en WhatsApp)
 *   2. BROWSER — abre WhatsApp Web con el texto pre-escrito
 *   3. BOTH    — ambos
 *   4. API     — envía vía CallMeBot (gratis, 100 msg/día)
 *   5. AUTO    — intenta API, si falla o no está configurado → COPY (default)
 */
public final class WhatsAppMessenger {

    public enum Mode { COPY, BROWSER, BOTH, API, AUTO }

    private static final String GROUP_NAME = "Quiniela Mundial 2026";
    private static final String MI_NOMBRE  = "Gabriel Marves";

    private WhatsAppMessenger() {}

    /**
     * Genera el mensaje de texto con las predicciones óptimas.
     */
    public static String buildMessage(int jornada,
                                      List<MatchdayEngine.MatchInput> matchday,
                                      List<EloRating> homeRatings,
                                      List<EloRating> awayRatings) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚽ *PREDICCIONES JORNADA ").append(jornada).append("*\n");
        sb.append("📅 ").append(java.time.LocalDate.now()).append("\n");
        sb.append("👤 ").append(MI_NOMBRE).append("\n");
        sb.append("─".repeat(25)).append("\n\n");

        for (int i = 0; i < matchday.size(); i++) {
            MatchdayEngine.MatchInput m = matchday.get(i);
            double bonus = MatchdayEngine.hostBonus(m.team1());
            EloRating hR = homeRatings.get(i);
            EloRating aR = awayRatings.get(i);
            Score honesto = PoissonPredictor.mostLikelyScore(hR, aR, bonus);
            MatchEV.Candidate optimo = MatchEV.best(hR, aR, bonus, m.stage());
            MatchEV.Risk riesgo = MatchEV.risk(hR, aR, bonus);

            double pHome = PoissonPredictor.matchProbabilities(hR, aR, bonus).homeWin();
            double pDraw = PoissonPredictor.matchProbabilities(hR, aR, bonus).draw();
            double pAway = PoissonPredictor.matchProbabilities(hR, aR, bonus).awayWin();

            sb.append("🏟️ *").append(m.team1()).append("* vs *").append(m.team2()).append("*\n");
            sb.append("   📊 ").append(String.format("1=%.0f%% X=%.0f%% 2=%.0f%%", pHome*100, pDraw*100, pAway*100)).append("\n");
            sb.append("   🎯 ").append(optimo.score().homeGoals()).append("-").append(optimo.score().awayGoals());
            sb.append("  (").append(riesgo.label).append(")\n");
            sb.append("   📝 Honesta: ").append(honesto.homeGoals()).append("-").append(honesto.awayGoals()).append("\n");
            sb.append("\n");
        }

        sb.append("─".repeat(25)).append("\n");
        sb.append("⚡ Generado por FootballPredictor — Triple Blend + xG\n");
        sb.append("📱 Enviado desde ").append(System.getProperty("os.name")).append("\n");

        return sb.toString();
    }

    /**
     * Construye el mensaje desde un OptimizationResult.
     */
    public static String buildMessage(int jornada,
                                      List<MatchdayEngine.MatchInput> matchday,
                                      FastStrategyOptimizer.OptimizationResult opt,
                                      List<FastStrategyOptimizer.StrategyMatch> strategyMatches) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚽ *PREDICCIONES JORNADA ").append(jornada).append("*\n");
        sb.append("📅 ").append(java.time.LocalDate.now()).append("\n");
        sb.append("👤 ").append(MI_NOMBRE).append("\n");
        sb.append("📊 P(podio)=").append(String.format("%.1f%%", opt.pPodio()*100));
        sb.append(" · Pos.Esp=").append(String.format("%.2f", opt.expectedPosition()));
        sb.append("/").append(opt.participants()).append("\n");
        sb.append("─".repeat(25)).append("\n\n");

        for (int i = 0; i < matchday.size(); i++) {
            MatchdayEngine.MatchInput m = matchday.get(i);
            FastStrategyOptimizer.StrategyMatch sm = strategyMatches.get(i);
            double bonus = MatchdayEngine.hostBonus(m.team1());
            Score p = opt.predictions().get(i);
            MatchEV.Risk riesgo = MatchEV.risk(sm.home(), sm.away(), bonus);
            double pHome = PoissonPredictor.matchProbabilities(sm.home(), sm.away(), bonus).homeWin();
            double pDraw = PoissonPredictor.matchProbabilities(sm.home(), sm.away(), bonus).draw();
            double pAway = PoissonPredictor.matchProbabilities(sm.home(), sm.away(), bonus).awayWin();

            sb.append("🏟️ *").append(m.team1()).append("* vs *").append(m.team2()).append("*\n");
            sb.append("   📊 ").append(String.format("1=%.0f%% X=%.0f%% 2=%.0f%%", pHome*100, pDraw*100, pAway*100)).append("\n");
            sb.append("   🎯 *").append(p.homeGoals()).append("-").append(p.awayGoals()).append("*  (").append(riesgo.label).append(")\n");
            sb.append("\n");
        }

        sb.append("─".repeat(25)).append("\n");
        sb.append("⚡ Optimizado por FootballPredictor\n");
        sb.append("📱 ").append(java.time.LocalDateTime.now()).append("\n");

        return sb.toString();
    }

    /**
     * Envía el mensaje según el modo elegido.
     */
    public static void send(String message, Mode mode) {
        switch (mode) {
            case COPY -> copyToClipboard(message);
            case BROWSER -> openWhatsApp(message);
            case BOTH -> {
                copyToClipboard(message);
                openWhatsApp(message);
            }
            case API -> sendViaApi(message);
            case AUTO -> sendWithBot(message);
        }
    }

    /**
     * Envía por clipboard (default).
     */
    public static void send(String message) {
        send(message, Mode.COPY);
    }

    /**
     * Envía intentando primero Telegram, luego CallMeBot, y si nada funciona
     * copia al portapapeles. Nunca pierde el mensaje.
     * <p>
     * Orden de intentos:
     * <ol>
     *   <li>{@link TelegramBotSender} (recomendado — gratis ilimitado)</li>
     *   <li>{@link CallMeBotSender} (WhatsApp vía API, 100 msg/día)</li>
     *   <li>Portapapeles (fallback universal)</li>
     * </ol>
     */
    public static void sendWithBot(String message) {
        // 1. Telegram (mejor opción)
        WhatsAppBot tg = new TelegramBotSender();
        if (tg.isAvailable()) {
            try {
                tg.send(message);
                System.out.println("📱 Enviado por Telegram");
                return;
            } catch (Exception e) {
                System.err.println("[Telegram] Falló: " + e.getMessage() + " — probando siguiente...");
            }
        }

        // 2. CallMeBot (WhatsApp)
        WhatsAppBot wa = new CallMeBotSender();
        if (wa.isAvailable()) {
            try {
                wa.send(message);
                System.out.println("📱 Enviado por WhatsApp (CallMeBot)");
                return;
            } catch (Exception e) {
                System.err.println("[CallMeBot] Falló: " + e.getMessage() + " — probando siguiente...");
            }
        }

        // 3. Fallback: portapapeles
        if (!tg.isAvailable() && !wa.isAvailable()) {
            System.out.println("\n📋 Ningún bot configurado.");
            System.out.println("   Para activar Telegram: define TELEGRAM_TOKEN y TELEGRAM_CHAT_ID");
            System.out.println("   Para activar WhatsApp: define WA_PHONE y WA_APIKEY");
            System.out.println("→ Copiando al portapapeles");
        } else {
            System.out.println("→ Copiando al portapapeles como fallback");
        }
        copyToClipboard(message);
    }

    /** Intenta enviar vía API; si no hay config, lanza IllegalStateException. */
    private static void sendViaApi(String message) {
        WhatsAppBot bot = new CallMeBotSender();
        if (!bot.isAvailable()) {
            throw new IllegalStateException(
                    "Modo API sin configurar. Define WA_PHONE y WA_APIKEY");
        }
        try {
            bot.send(message);
        } catch (Exception e) {
            throw new RuntimeException("Error al enviar por API", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void copyToClipboard(String message) {
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(message), null);
        System.out.println("\n✅ Mensaje copiado al portapapeles — pégalo en WhatsApp");
        System.out.println("─".repeat(40));
        System.out.println(message);
        System.out.println("─".repeat(40));
    }

    private static void openWhatsApp(String message) {
        try {
            String encoded = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String url = "https://web.whatsapp.com/send?text=" + encoded;

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                System.out.println("✅ WhatsApp Web abierto — selecciona el chat y envía");
            } else {
                System.out.println("\n⚠️  No se pudo abrir el navegador automáticamente.");
                System.out.println("👉 Abre manualmente: web.whatsapp.com");
                System.out.println("👉 Pega el mensaje del portapapeles (Ctrl+V)");
            }
        } catch (Exception e) {
            System.err.println("[WhatsApp] Error al abrir navegador: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        // Demo
        String demo = "⚽ *PREDICCIONES JORNADA 3*\n📅 2026-06-20\n👤 Gabriel Marves\n\n"
                + "🏟️ *USA* vs *Canada*\n   📊 1=58% X=22% 2=20%\n   🎯 2-1  (💪 FUERTE)";
        send(demo, Mode.COPY);
    }
}
