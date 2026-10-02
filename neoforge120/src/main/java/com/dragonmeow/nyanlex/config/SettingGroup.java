package com.dragonmeow.nyanlex.config;

import java.util.List;

/** A collapsible group of cards (accordion), e.g. one display surface with its mode and engine. */
public record SettingGroup(String id, SettingsCategory category, String titleKey, String descKey,
                           List<SettingCard> cards) {
}
