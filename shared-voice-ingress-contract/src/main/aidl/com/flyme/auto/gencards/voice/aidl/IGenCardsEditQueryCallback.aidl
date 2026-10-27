package com.flyme.auto.gencards.voice.aidl;

interface IGenCardsEditQueryCallback {
    void onQueryCaptured(String requestId, String query);
    void onQueryCancelled(String requestId);
    void onQueryFailed(String requestId, String errorCode, boolean retryable);
}
