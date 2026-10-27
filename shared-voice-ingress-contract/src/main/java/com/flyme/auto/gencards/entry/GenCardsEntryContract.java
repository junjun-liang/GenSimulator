package com.flyme.auto.gencards.entry;

/** Stable cross-app entry contract shared by GenCards and trusted integration clients. */
public final class GenCardsEntryContract {
    public static final String ACTION_OPEN_ENTRY = "com.flyme.auto.gencards.action.OPEN_ENTRY";

    public static final String EXTRA_PROTOCOL_VERSION =
            "com.flyme.auto.gencards.extra.PROTOCOL_VERSION";
    public static final String EXTRA_CHANNEL = "com.flyme.auto.gencards.extra.CHANNEL";
    public static final String EXTRA_DESTINATION = "com.flyme.auto.gencards.extra.DESTINATION";
    public static final String EXTRA_CARD_ID = "com.flyme.auto.gencards.extra.CARD_ID";

    public static final int PROTOCOL_VERSION = 1;
    public static final String PERMISSION_OPEN_EXTERNAL_ENTRY =
            "com.flyme.auto.gencards.permission.OPEN_EXTERNAL_ENTRY";

    public static final String AUTO_DESKTOP_ENTRY_COMPONENT =
            "com.flyme.auto.gencards.entry.AutoDesktopEntry";
    public static final String VOICE_ENTRY_COMPONENT = "com.flyme.auto.gencards.entry.VoiceEntry";

    public static final String CHANNEL_VOICE = "VOICE";
    public static final String DESTINATION_CREATE_CARD = "CREATE_CARD";

    private GenCardsEntryContract() {}
}
