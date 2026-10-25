package io.mosip.vivid.demo;

/** What stands in front of the (mock) camera. */
public enum Subject {
    LIVE("Live person (responds to prompts)", "live", null),
    PRINT("Printed photo", "print", "print-attack"),
    VIDEO_REPLAY("Video replay", "video", "video-replay"),
    SCREEN_REPLAY("Screen replay", "screen", "screen-replay"),
    INJECTED("Injected stream", "injected", null);

    private final String label;
    private final String key;
    private final String mockScenario;

    Subject(String label, String key, String mockScenario) { this.label = label; this.key = key; this.mockScenario = mockScenario; }

    /** Text shown in the subject selector. */
    public String label() { return label; }
    /** Short command-line key. */
    public String key() { return key; }
    /** The bundled device-mock scenario that plays this attack, or null when the demo renders it interactively. */
    public String mockScenario() { return mockScenario; }
    /** True for presentation or injection attacks. */
    public boolean isAttack() { return this != LIVE; }

    public static Subject parse(String s) {
        for (Subject v : values()) if (v.key.equalsIgnoreCase(s) || v.name().equalsIgnoreCase(s)) return v;
        throw new IllegalArgumentException("unknown subject '" + s + "' (live, print, video, screen, injected)");
    }

    @Override public String toString() { return label; }
}
