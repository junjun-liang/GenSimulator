package com.flyme.auto.gencards.voice.contract;

/** Stable Binder transport constants for GenCards text/voice query ingress. */
public final class GenCardsVoiceIngressContract {
    /** Contract metadata version for shared CREATE ingress + correlated EDIT capture V2. */
    public static final int VOICE_INGRESS_CONTRACT_VERSION = 2;

    public static final String GENCARDS_PACKAGE = "com.flyme.auto.gencards";
    public static final String QUERY_SERVICE_COMPONENT =
            "com.flyme.auto.gencards.voice.VoiceQueryIngressService";
    public static final String PERMISSION_SUBMIT_QUERY =
            "com.flyme.auto.gencards.permission.SUBMIT_QUERY";

    private GenCardsVoiceIngressContract() {}
}
