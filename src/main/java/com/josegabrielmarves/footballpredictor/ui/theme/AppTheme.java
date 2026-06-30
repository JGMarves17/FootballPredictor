package com.josegabrielmarves.footballpredictor.ui.theme;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public final class AppTheme {

    private AppTheme() {}

    // ── Enum de temas ──
    public enum Theme {
        VERDE_CANCHA("🌿 Verde Cancha"),
        NOCHE_AZUL("🌙 Noche Azul"),
        REY_PURPURA("👑 Rey Púrpura"),
        FUEGO("🔥 Fuego"),
        CARBON("💎 Carbón");

        public final String displayName;
        Theme(String displayName) { this.displayName = displayName; }
    }

    private static Theme currentTheme = Theme.VERDE_CANCHA;

    public static Theme getCurrentTheme() { return currentTheme; }
    public static void setCurrentTheme(Theme t) { currentTheme = t; }

    // ── Colores por tema (se resuelven según currentTheme) ──
    public static Color BG()             { return themeOf(0x080E0A, 0x0A0E1A, 0x100A1A, 0x1A0A0A, 0x121212); }
    public static Color SURFACE()        { return themeOf(0x0E1610, 0x121830, 0x1A1028, 0x221010, 0x1E1E1E); }
    public static Color SURFACE_ELEVATED() { return themeOf(0x141E16, 0x1A2240, 0x281A38, 0x301818, 0x2D2D2D); }
    public static Color SURFACE_ALT()    { return themeOf(0x121C14, 0x161E38, 0x221830, 0x2A1616, 0x282828); }
    public static Color PRIMARY()        { return themeOf(0xFFD700, 0x4FC3F7, 0xCE93D8, 0xFF7043, 0xFFFFFF); }
    public static Color GOLD()           { return PRIMARY(); }
    public static Color TEXT()           { return themeOf(0xF5F5F0, 0xE8ECF2, 0xEDE8F2, 0xF2E8E8, 0xEEEEEE); }
    public static Color TEXT_MUTED()     { return themeOf(0x789178, 0x7890B0, 0xA088B0, 0xB09080, 0x909090); }
    public static Color BORDER()         { return themeOf(0x283C2A, 0x2A3850, 0x3A2848, 0x482828, 0x404040); }
    public static Color ACCENT()         { return PRIMARY(); }
    public static Color CARD_BG()        { return SURFACE_ELEVATED(); }
    public static Color TXT()            { return TEXT(); }
    public static Color DIM()            { return TEXT_MUTED(); }
    public static Color DIV()            { return BORDER(); }
    public static Color WIN()            { return Color.rgb(50, 205, 50); }
    public static Color LOSS()           { return Color.rgb(220, 50, 50); }
    public static Color NEUTRAL()        { return Color.rgb(140, 160, 140); }

    private static Color themeOf(int verde, int azul, int purple, int fuego, int carbon) {
        return switch (currentTheme) {
            case VERDE_CANCHA -> Color.rgb((verde >> 16) & 0xFF, (verde >> 8) & 0xFF, verde & 0xFF);
            case NOCHE_AZUL  -> Color.rgb((azul >> 16) & 0xFF, (azul >> 8) & 0xFF, azul & 0xFF);
            case REY_PURPURA -> Color.rgb((purple >> 16) & 0xFF, (purple >> 8) & 0xFF, purple & 0xFF);
            case FUEGO       -> Color.rgb((fuego >> 16) & 0xFF, (fuego >> 8) & 0xFF, fuego & 0xFF);
            case CARBON      -> Color.rgb((carbon >> 16) & 0xFF, (carbon >> 8) & 0xFF, carbon & 0xFF);
        };
    }

    // ── HELPER para hex ──
    public static String hex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255));
    }

    // ── Estilos (usando métodos en vez de constantes) ──
    public static String tableStyle() {
        return "-fx-base:" + hex(SURFACE()) + ";"
                + "-fx-control-inner-background:" + hex(SURFACE()) + ";"
                + "-fx-accent:" + hex(GOLD()) + ";"
                + "-fx-text-fill:" + hex(TEXT()) + ";"
                + "-fx-table-cell-border-color:" + hex(BORDER()) + ";";
    }

    public static String inputStyle() {
        return "-fx-background-color:" + hex(SURFACE_ELEVATED()) + ";"
                + "-fx-text-fill:" + hex(TEXT()) + ";"
                + "-fx-prompt-text-fill:" + hex(TEXT_MUTED()) + ";"
                + "-fx-border-color:" + hex(BORDER()) + ";";
    }

    public static String primaryButtonStyle() {
        return "-fx-background-color:" + hex(GOLD()) + ";"
                + "-fx-text-fill:" + hexTextContrast(GOLD()) + ";"
                + "-fx-font-weight:bold;"
                + "-fx-padding:8 18;-fx-background-radius:4;";
    }

    private static String hexTextContrast(Color bg) {
        double lum = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
        return lum > 0.5 ? "#000000" : "#FFFFFF";
    }

    public static String goldButtonStyle() {
        return "-fx-background-color:" + hex(WIN()) + ";"
                + "-fx-text-fill:#FFFFFF;"
                + "-fx-font-weight:bold;"
                + "-fx-padding:8 18;-fx-background-radius:4;";
    }

    public static String consoleStyle() {
        return "-fx-control-inner-background:" + hex(BG()) + ";"
                + "-fx-text-fill:" + hex(PRIMARY()) + ";"
                + "-fx-font-family:Consolas;-fx-font-size:12px;";
    }

    // ── Fonts (se mantienen igual) ──
    public static final Font TITLE = Font.font("Arial", FontWeight.BOLD, 22);
    public static final Font BODY  = Font.font("Arial", FontWeight.NORMAL, 13);
    public static final Font SMALL = Font.font("Arial", FontWeight.NORMAL, 12);
}
