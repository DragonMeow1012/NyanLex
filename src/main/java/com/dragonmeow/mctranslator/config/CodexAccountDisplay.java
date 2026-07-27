package com.dragonmeow.mctranslator.config;

import java.util.Locale;

/** Privacy-safe text shown for the game-owned Codex login session. */
public final class CodexAccountDisplay {

    private CodexAccountDisplay() {
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) return "*****";
        String value = email.trim();
        int at = value.indexOf('@');
        if (at <= 0 || at == value.length() - 1) {
            return value.length() <= 2
                    ? "*****"
                    : value.substring(0, 1) + "***" + value.substring(value.length() - 1);
        }
        String local = value.substring(0, at);
        String domain = value.substring(at);
        if (local.length() <= 2) return local.substring(0, 1) + "***" + domain;
        return local.substring(0, 2) + "***" + local.substring(local.length() - 1) + domain;
    }

    public static String formatPlan(String plan) {
        if (plan == null || plan.isBlank()) return "ChatGPT";
        String normalized = plan.trim().replace('_', ' ').replace('-', ' ');
        StringBuilder title = new StringBuilder();
        for (String part : normalized.split("\\s+")) {
            if (part.isEmpty()) continue;
            if (!title.isEmpty()) title.append(' ');
            title.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                title.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return "ChatGPT " + title;
    }
}
