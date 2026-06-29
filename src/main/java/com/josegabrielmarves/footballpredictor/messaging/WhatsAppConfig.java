package com.josegabrielmarves.footballpredictor.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Proveedor de configuración para los bots de mensajería.
 *
 * Soportados actualmente:
 * <ul>
 *   <li><b>Telegram</b> (recomendado) — token de @BotFather + chat_id</li>
 *   <li><b>CallMeBot</b> (WhatsApp) — phone + apikey</li>
 * </ul>
 *
 * Orden de precedencia (mayor prioridad primero):
 * <ol>
 *   <li>Variables de entorno</li>
 *   <li>Archivo {@code config.properties} en classpath</li>
 * </ol>
 *
 * Así puedes tener un {@code config.properties} con valores por defecto
 * y sobreescribirlos con variables de entorno sin tocar archivos.
 */
public final class WhatsAppConfig {

    private static final String CONFIG_FILE = "config.properties";

    // CallMeBot
    private static final String KEY_PHONE  = "whatsapp.phone";
    private static final String KEY_APIKEY = "whatsapp.apikey";
    private static final String ENV_PHONE  = "WA_PHONE";
    private static final String ENV_APIKEY = "WA_APIKEY";

    // Telegram
    private static final String KEY_TELEGRAM_TOKEN  = "telegram.token";
    private static final String KEY_TELEGRAM_CHATID = "telegram.chatId";
    private static final String ENV_TELEGRAM_TOKEN  = "TELEGRAM_TOKEN";
    private static final String ENV_TELEGRAM_CHATID = "TELEGRAM_CHAT_ID";

    private static Properties cachedProps;

    private WhatsAppConfig() {}

    // ── Acceso público ────────────────────────────────────────────────────────

    /** Número de teléfono en formato internacional (ej: 521234567890). */
    public static String getPhone() {
        return fromEnvOrFile(ENV_PHONE, KEY_PHONE);
    }

    /** API key de CallMeBot. */
    public static String getApiKey() {
        return fromEnvOrFile(ENV_APIKEY, KEY_APIKEY);
    }

    /** true si hay teléfono Y apiKey configurados. */
    public static boolean isConfigured() {
        return !getPhone().isBlank() && !getApiKey().isBlank();
    }

    // ── Telegram ───────────────────────────────────────────────────────────────

    /** Token del bot de Telegram (de @BotFather). */
    public static String getTelegramToken() {
        return fromEnvOrFile(ENV_TELEGRAM_TOKEN, KEY_TELEGRAM_TOKEN);
    }

    /** Chat ID numérico de Telegram. */
    public static String getTelegramChatId() {
        return fromEnvOrFile(ENV_TELEGRAM_CHATID, KEY_TELEGRAM_CHATID);
    }

    /** true si hay token Y chatId configurados. */
    public static boolean isTelegramConfigured() {
        return !getTelegramToken().isBlank() && !getTelegramChatId().isBlank();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static String fromEnvOrFile(String envName, String propKey) {
        // 1. Variable de entorno (máxima prioridad)
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }

        // 2. Archivo de propiedades
        String fromFile = getProperty(propKey);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile.trim();
        }

        return "";
    }

    private static String getProperty(String key) {
        try {
            Properties props = loadProperties();
            return props != null ? props.getProperty(key) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static synchronized Properties loadProperties() {
        if (cachedProps != null) return cachedProps;

        cachedProps = new Properties();
        try (InputStream is = WhatsAppConfig.class.getClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                cachedProps.load(is);
                System.out.println("[Config] Cargado " + CONFIG_FILE);
            } else {
                // El archivo no existe — no es error, usamos solo env vars
                cachedProps = new Properties();
            }
        } catch (IOException e) {
            System.err.println("[Config] Error leyendo " + CONFIG_FILE + ": " + e.getMessage());
            cachedProps = new Properties();
        }
        return cachedProps;
    }

    /**
     * Limpia la caché de propiedades (útil para tests).
     */
    static synchronized void reset() {
        cachedProps = null;
    }
}
