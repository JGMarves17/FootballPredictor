package com.josegabrielmarves.footballpredictor.ui.theme;

import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public final class AppTheme {

    private AppTheme() {}

    // ═══════════════════════════════════════════════════════
    //  PALETA "CAMPEÓN" — Oro + Verde de Cancha
    //  Ganar no es suerte, es diseño.
    // ═══════════════════════════════════════════════════════

    // ── FONDO (cancha oscura) ──
    public static final Color BG             = Color.rgb(8, 14, 10);
    public static final Color SURFACE        = Color.rgb(14, 22, 16);
    public static final Color SURFACE_ELEVATED = Color.rgb(20, 30, 22);
    public static final Color SURFACE_ALT    = Color.rgb(18, 28, 20);

    // ── ORO DE CAMPEÓN (principal) ──
    public static final Color PRIMARY        = Color.rgb(255, 215, 0);
    public static final Color GOLD           = Color.rgb(255, 215, 0);

    // ── TEXTO ──
    public static final Color TEXT           = Color.rgb(245, 245, 240);
    public static final Color TEXT_MUTED     = Color.rgb(120, 145, 120);
    public static final Color BORDER         = Color.rgb(40, 60, 42);

    // ── ALIASES (compatibilidad) ──
    public static final Color ACCENT  = PRIMARY;
    public static final Color CARD_BG = SURFACE_ELEVATED;
    public static final Color TXT     = TEXT;
    public static final Color DIM     = TEXT_MUTED;
    public static final Color DIV     = BORDER;

    // ── COLORES DE RESULTADO ──
    public static final Color WIN      = Color.rgb(50, 205, 50);
    public static final Color LOSS     = Color.rgb(220, 50, 50);
    public static final Color NEUTRAL  = Color.rgb(140, 160, 140);

    // ── FONTS ──
    public static final Font TITLE = Font.font("Arial", FontWeight.BOLD, 22);
    public static final Font BODY  = Font.font("Arial", FontWeight.NORMAL, 13);
    public static final Font SMALL = Font.font("Arial", FontWeight.NORMAL, 12);

    // ═══════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X",
                (int) (c.getRed() * 255),
                (int) (c.getGreen() * 255),
                (int) (c.getBlue() * 255));
    }

    public static String tableStyle() {
        return "-fx-base:" + hex(SURFACE) + ";"
                + "-fx-control-inner-background:" + hex(SURFACE) + ";"
                + "-fx-accent:" + hex(GOLD) + ";"
                + "-fx-text-fill:" + hex(TEXT) + ";"
                + "-fx-table-cell-border-color:" + hex(BORDER) + ";";
    }

    public static String inputStyle() {
        return "-fx-background-color:" + hex(SURFACE_ELEVATED) + ";"
                + "-fx-text-fill:" + hex(TEXT) + ";"
                + "-fx-prompt-text-fill:" + hex(TEXT_MUTED) + ";"
                + "-fx-border-color:" + hex(BORDER) + ";";
    }

    public static String primaryButtonStyle() {
        return "-fx-background-color:" + hex(GOLD) + ";"
                + "-fx-text-fill:#080E0A;-fx-font-weight:bold;"
                + "-fx-padding:8 18;-fx-background-radius:4;";
    }

    public static String goldButtonStyle() {
        return "-fx-background-color:" + hex(WIN) + ";"
                + "-fx-text-fill:#080E0A;-fx-font-weight:bold;"
                + "-fx-padding:8 18;-fx-background-radius:4;";
    }

    public static String consoleStyle() {
        return "-fx-control-inner-background:" + hex(BG) + ";"
                + "-fx-text-fill:#33FF66;"
                + "-fx-font-family:Consolas;-fx-font-size:12px;";
    }
}
