package io.mosip.vivid.demo;

import java.awt.Color;
import java.awt.Font;

/** Colours and fonts of the attack-lab view. */
final class Theme {
    private Theme() {}
    static final Color BG = new Color(0xF3F5F8);
    static final Color CARD = Color.WHITE;
    static final Color BORDER = new Color(0xDDE2E8);
    static final Color TEXT = new Color(0x1C2733);
    static final Color MUTED = new Color(0x667385);
    static final Color FAINT = new Color(0xEEF1F5);
    static final Color ACCENT = new Color(0x1E5AA8);
    static final Color GREEN = new Color(0x1E8E3E);
    static final Color GREEN_BG = new Color(0xE6F4EA);
    static final Color RED = new Color(0xC5221F);
    static final Color RED_BG = new Color(0xFCE8E6);
    static final Color AMBER = new Color(0xE37400);
    static final Color AMBER_BG = new Color(0xFEF3E0);
    static final Color GREY_BG = new Color(0xECEFF3);
    static final Color PREVIEW_BG = new Color(0x20262E);

    static final String SANS = "SansSerif";
    static final Font TITLE = new Font(SANS, Font.BOLD, 13);
    static final Font LABEL = new Font(SANS, Font.PLAIN, 12);
    static final Font LABEL_BOLD = new Font(SANS, Font.BOLD, 12);
    static final Font SMALL = new Font(SANS, Font.PLAIN, 11);
    static final Font PROMPT = new Font(SANS, Font.BOLD, 22);
    static final Font BANNER = new Font(SANS, Font.BOLD, 30);
    static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 11);
    static final Font MONO_BOLD = new Font(Font.MONOSPACED, Font.BOLD, 11);
}
