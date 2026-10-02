package com.dragonmeow.nyanlex.config;

/** Where the project lives; the 關於 card and the last chapter of the manual both read these. */
public final class ProjectLinks {
    public static final String GITHUB_URL = "https://github.com/DragonMeow1012/NyanLex";
    /** The address as it is shown on a card, without the scheme. */
    public static final String GITHUB_DISPLAY = "github.com/DragonMeow1012/NyanLex";
    /** Placeholder that a lang text uses for {@link #GITHUB_URL}. */
    public static final String GITHUB_TOKEN = "{GITHUB}";

    private ProjectLinks() {}

    /** Replaces {@link #GITHUB_TOKEN} in a lang text by the address. */
    public static String fill(String text) {
        return text == null ? null : text.replace(GITHUB_TOKEN, GITHUB_URL);
    }
}
