package com.josegabrielmarves.footballpredictor.messaging;

import java.io.IOException;

/**
 * Interfaz para envío de mensajes por WhatsApp.
 *
 * Implementaciones actuales:
 *   - {@link CallMeBotSender} — vía API CallMeBot (gratis, 100 msg/día)
 *
 * Uso típico:
 * <pre>{@code
 *   WhatsAppBot bot = new CallMeBotSender();
 *   if (bot.isAvailable()) {
 *       bot.send("⚽ Predicciones...");
 *   }
 * }</pre>
 */
public interface WhatsAppBot {

    /**
     * Envía un mensaje de texto por WhatsApp.
     *
     * @param message texto a enviar (se codifica como UTF-8)
     * @throws IOException si hay error de red o la API rechaza el mensaje
     */
    void send(String message) throws IOException;

    /**
     * Indica si el bot está configurado y listo para enviar.
     * Útil para decidir entre API vs clipboard sin try-catch.
     *
     * @return true si hay credenciales configuradas
     */
    boolean isAvailable();
}
