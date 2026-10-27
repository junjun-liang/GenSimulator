package com.flyme.auto.gencards.voice.contract;

/** Stable wire constants for correlated Card EDIT query capture hosted by GenAgent. */
public final class GenAgentEditCaptureContract {
    /**
     * Stable signature permission protecting the reverse GenCards -> GenAgent Binder.
     * Host package/service names are deployment configuration, not wire protocol constants.
     */
    public static final String PERMISSION_REQUEST_EDIT_QUERY =
            "com.flyme.auto.genagent.permission.REQUEST_EDIT_QUERY";

    /** Stable callback-side error codes. Never put exception text or user data on this wire. */
    public static final String ERROR_UI_NOT_READY = "UI_NOT_READY";
    public static final String ERROR_ACTIVE_REQUEST_CONFLICT = "ACTIVE_REQUEST_CONFLICT";
    public static final String ERROR_CALLBACK_DEAD = "CALLBACK_DEAD";
    public static final String ERROR_INTERNAL = "INTERNAL_ERROR";

    private GenAgentEditCaptureContract() {}
}
