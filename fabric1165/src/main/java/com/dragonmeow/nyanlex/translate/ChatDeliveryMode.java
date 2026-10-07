package com.dragonmeow.nyanlex.translate;

/** When received chat and its translation become visible; independent of display content. */
public enum ChatDeliveryMode {
    ORDERED,
    READY_FIRST,
    ORIGINAL_FIRST;

    public ChatDeliveryMode next() {
        switch (this) {
            case ORDERED: return READY_FIRST;
            case READY_FIRST: return ORIGINAL_FIRST;
            default: return ORDERED;
        }
    }

    public static ChatDeliveryMode orDefault(ChatDeliveryMode mode) {
        return mode == null ? ORIGINAL_FIRST : mode;
    }
}
