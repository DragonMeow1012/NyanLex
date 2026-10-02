package com.dragonmeow.nyanlex.config;

import java.util.List;

/** One row of a settings page: one or two entries (a second one sits in the right cell). */
public record SettingsRow(SettingEntry primary, SettingEntry secondary) {

    public SettingsRow(SettingEntry primary) { this(primary, null); }

    public List<SettingEntry> entries() {
        return secondary == null ? List.of(primary) : List.of(primary, secondary);
    }
}
