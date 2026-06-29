package com.josegabrielmarves.footballpredictor.messaging;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Envía mensajes de WhatsApp usando la API gratuita de CallMeBot.
 *
 * <h3>Cómo configurar:</h3>
 * <ol>
 *   <li>Ve a <a href="https://www.callmebot.com">callmebot.com</a></li>
 *   <li>Registra tu número (formato internacional, ej: 521234567890)</li>
 *   <li>Copia tu API key</li>
 *   <li>Configúrala vía variables de entorno:
 *     <ul>
 *       <li><code>WA_PHONE</code> — tu número internacional</li>
 *       <li><code>WA_APIKEY</code> — tu API key de CallMeBot</li>
 *     </ul>
 *     O en {@code src/main/resources/config.properties}:
 *     <ul>
 *       <li><code>whatsapp.phone=521234567890</code></li>
 *       <li><code>whatsapp.apikey=123456</code></li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>Límites:</h3>
 * <ul>
 *   <li>100 mensajes/día por número</li>
 *   <li>1 mensaje cada ~2 segundos</li>
 *   <li>Solo puede enviar a TU número registrado (no a grupos)</li>
 * </ul>
 *
 * <h3>Comportamiento:</h3>
 * Si el bot no está configurado, {@link #isAvailable()} retorna {@code false}
 * y {@link #send(String}) lanza {@link IllegalStateException}.
 * La clase {@link WhatsAppMessenger#sendWithBot(String)} maneja el fallback
 * a clipboard automáticamente.
 */
public final class CallMeBotSender implements WhatsAppBot {

    private static final String BASE_URL = "https://api.callmebot.com/whatsapp.php";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_MSG_LENGTH = 1500;

    private final String phone;
    private final String apiKey;
    private final HttpClient client;

    /**
     * Crea el sender con credenciales explícitas.
     *
     * @param phone  número en formato internacional (ej: 521234567890)
     * @param apiKey API key de CallMeBot
     */
    public CallMeBotSender(String phone, String apiKey) {
        if (phone == null || apiKey == null) {
            throw new IllegalArgumentException("phone y apiKey no pueden ser null");
        }
        this.phone = phone.trim();
        this.apiKey = apiKey.trim();
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * Crea el sender leyendo credenciales de {@link WhatsAppConfig}
     * (variables de entorno o config.properties).
     */
    public CallMeBotSender() {
        this(WhatsAppConfig.getPhone(), WhatsAppConfig.getApiKey());
    }

    @Override
    public void send(String message) throws IOException {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "CallMeBot no configurado. Define WA_PHONE y WA_APIKEY " +
                    "como variables de entorno o en config.properties");
        }

        // Truncar si excede el límite de CallMeBot
        String text = message;
        if (text.length() > MAX_MSG_LENGTH) {
            text = text.substring(0, MAX_MSG_LENGTH - 3) + "...";
            System.out.printf("[CallMeBot] Mensaje truncado de %d a %d caracteres%n",
                    message.length(), text.length());
        }

        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
        String url = BASE_URL + "?phone=" + phone + "&apikey=" + apiKey + "&text=" + encoded;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body().trim().toLowerCase();

            if (status == 200 && (body.contains("success") || body.contains("message sent"))) {
                System.out.println("\n✅ [CallMeBot] Mensaje enviado a " + maskPhone(phone));
            } else {
                String msg = String.format("HTTP %d — %s", status, body);
                System.err.println("\n⚠️ [CallMeBot] Error: " + msg);
                throw new IOException("CallMeBot rechazó el mensaje: " + msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("CallMeBot interrumpido", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return !phone.isBlank() && !apiKey.isBlank();
    }

    /**
     * Envía un mensaje corto de prueba para verificar la configuración.
     *
     * @return true si se envió correctamente
     */
    public boolean test() {
        try {
            send("⚽ FootballPredictor: Bot configurado correctamente ✅");
            return true;
        } catch (Exception e) {
            System.err.println("[CallMeBot] Test falló: " + e.getMessage());
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Enmascara el teléfono para logs: +52*****7890 */
    private static String maskPhone(String phone) {
        if (phone.length() <= 6) return phone;
        return phone.substring(0, 3) + "*****" + phone.substring(phone.length() - 4);
    }

    // ── Main de prueba ────────────────────────────────────────────────────────

    public static void main(String[] args) {
        CallMeBotSender bot = new CallMeBotSender();
        if (bot.isAvailable()) {
            System.out.println("📱 CallMeBot configurado — enviando prueba...");
            bot.test();
        } else {
            System.out.println("⚠️ CallMeBot no configurado.");
            System.out.println("  Define WA_PHONE y WA_APIKEY como variables de entorno");
            System.out.println("  o en src/main/resources/config.properties");
        }
    }
}
