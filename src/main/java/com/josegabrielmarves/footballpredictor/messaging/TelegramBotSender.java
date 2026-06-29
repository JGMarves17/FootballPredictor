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
 * Envía mensajes por Telegram usando la API oficial de BotFather (GRATIS, ILIMITADO).
 *
 * <h3>Setup (una sola vez):</h3>
 * <ol>
 *   <li>En Telegram, buscá <b>@BotFather</b> y creá un bot con <code>/newbot</code></li>
 *   <li>Guardá el <b>token</b> que te da (ej: <code>123456:ABC-def</code>)</li>
 *   <li>Buscá tu bot en Telegram y enviále <code>/start</code></li>
 *   <li>Obtené tu <b>chat_id</b> abriendo en el navegador:</li>
 * </ol>
 * <pre>{@code
 *   https://api.telegram.org/bot<TU_TOKEN>/getUpdates
 * }</pre>
 *
 * <h3>Configuración:</h3>
 * <b>Variables de entorno (recomendado):</b>
 * <pre>{@code
 *   $env:TELEGRAM_TOKEN="123456:ABC-def"
 *   $env:TELEGRAM_CHAT_ID="123456789"
 * }</pre>
 * <b>O en {@code config.properties}:</b>
 * <pre>{@code
 *   telegram.token=123456:ABC-def
 *   telegram.chatId=123456789
 * }</pre>
 *
 * <h3>Límites:</h3>
 * <ul>
 *   <li>GRATIS total — sin límite de mensajes por día</li>
 *   <li>Hasta 4096 caracteres por mensaje</li>
 *   <li>Puede enviar a grupos (chat_id negativo) y canales</li>
 * </ul>
 */
public final class TelegramBotSender implements WhatsAppBot {

    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_MSG_LENGTH = 4096;

    private final String token;
    private final String chatId;
    private final HttpClient client;

    /**
     * @param token  token del bot (de @BotFather)
     * @param chatId tu chat_id numérico
     */
    public TelegramBotSender(String token, String chatId) {
        if (token == null || chatId == null) {
            throw new IllegalArgumentException("token y chatId no pueden ser null");
        }
        this.token = token.trim();
        this.chatId = chatId.trim();
        this.client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * Carga credenciales desde {@link WhatsAppConfig} (env vars o config.properties).
     */
    public TelegramBotSender() {
        this(WhatsAppConfig.getTelegramToken(), WhatsAppConfig.getTelegramChatId());
    }

    @Override
    public void send(String message) throws IOException {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "Telegram no configurado. Definí TELEGRAM_TOKEN y TELEGRAM_CHAT_ID");
        }

        // Telegram permite hasta 4096 chars; si excede, partimos en varios mensajes
        if (message.length() <= MAX_MSG_LENGTH) {
            sendSingle(message);
        } else {
            // Partir en chunks de 4096
            int total = message.length();
            int parts = (total + MAX_MSG_LENGTH - 1) / MAX_MSG_LENGTH;
            System.out.printf("[Telegram] Mensaje largo (%d chars) — enviando en %d partes%n",
                    total, parts);
            for (int i = 0; i < total; i += MAX_MSG_LENGTH) {
                int end = Math.min(i + MAX_MSG_LENGTH, total);
                String chunk = message.substring(i, end);
                // Agregar numeración si va en partes
                if (parts > 1) {
                    chunk = "📝 *Parte " + ((i / MAX_MSG_LENGTH) + 1) + "/" + parts + "*\n\n" + chunk;
                }
                sendSingle(chunk);
            }
        }
    }

    @Override
    public boolean isAvailable() {
        return !token.isBlank() && !chatId.isBlank();
    }

    /**
     * Envía un mensaje corto de prueba al chat.
     *
     * @return true si se envió correctamente
     */
    public boolean test() {
        try {
            send("⚽ *FootballPredictor* — Bot configurado correctamente ✅\n\n"
                    + "Las predicciones te llegarán automáticamente aquí.");
            return true;
        } catch (Exception e) {
            System.err.println("[Telegram] Test falló: " + e.getMessage());
            return false;
        }
    }

    /**
     * Obtiene las últimas actualizaciones del bot (para descubrir tu chat_id).
     * Llamá esto después de enviarle /start a tu bot desde Telegram.
     *
     * @return JSON crudo con las updates
     */
    public static String getUpdates(String token) throws IOException {
        String url = API_BASE + token + "/getUpdates";
        try {
            HttpClient tempClient = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> res = tempClient.send(req,
                    HttpResponse.BodyHandlers.ofString());
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupción al obtener updates", e);
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void sendSingle(String text) throws IOException {
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
        String url = API_BASE + token + "/sendMessage"
                + "?chat_id=" + chatId
                + "&text=" + encoded
                + "&parse_mode=Markdown"
                + "&disable_web_page_preview=true";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body();

            if (status == 200 && body.contains("\"ok\":true")) {
                System.out.printf("[Telegram] ✅ Mensaje enviado (chat %s)%n",
                        maskChatId(chatId));
            } else {
                String msg = String.format("HTTP %d — %s", status, body);
                System.err.println("[Telegram] Error: " + msg);
                throw new IOException("Telegram rechazó el mensaje: " + msg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Telegram interrumpido", e);
        }
    }

    private static String maskChatId(String id) {
        if (id.length() <= 4) return id;
        return id.substring(0, 2) + "****" + id.substring(id.length() - 2);
    }

    // ── Main de prueba ────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        // 1. Si no hay token configurado, mostrar ayuda
        if (!WhatsAppConfig.isTelegramConfigured()) {
            System.out.println("""
                ╔══════════════════════════════════════════════════╗
                ║   TELEGRAM BOT — CONFIGURACIÓN                   ║
                ╚══════════════════════════════════════════════════╝

                Paso 1: Definí las variables de entorno:
                  $env:TELEGRAM_TOKEN="tu_token_de_botfather"
                  $env:TELEGRAM_CHAT_ID="tu_chat_id"

                Paso 2: O buscá tu chat_id primero con:
                  TelegramBotSender.buscarChatId("TU_TOKEN")

                Paso 3: Corré este main de nuevo.
                """);
            return;
        }

        TelegramBotSender bot = new TelegramBotSender();
        System.out.println("📱 Telegram configurado — enviando prueba...");
        if (bot.test()) {
            System.out.println("✅ Revisá Telegram — te llegó el mensaje de prueba.");
        } else {
            System.out.println("❌ Falló. Revisá token y chat_id.");
        }
    }

    /**
     * Helper para descubrir tu chat_id.
     * 1. Envíale /start a tu bot desde Telegram
     * 2. Ejecutá: TelegramBotSender.buscarChatId("TU_TOKEN")
     * 3. Buscá "chat":{"id":123456789} en la respuesta
     */
    public static void buscarChatId(String token) throws IOException {
        System.out.println("🔍 Buscando chat_id para token: " + token.substring(0, 8) + "...");
        String json = getUpdates(token);
        System.out.println("\n📦 Respuesta de Telegram:");
        System.out.println(json);
        System.out.println("\n🔎 Buscá 'chat':{\"id\":123456789} — ese número es tu chat_id.");
    }
}
