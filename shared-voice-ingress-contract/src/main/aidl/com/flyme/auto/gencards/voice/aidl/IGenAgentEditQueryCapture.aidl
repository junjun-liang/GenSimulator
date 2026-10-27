package com.flyme.auto.gencards.voice.aidl;

import com.flyme.auto.gencards.voice.aidl.IGenCardsEditQueryCallback;

interface IGenAgentEditQueryCapture {
    boolean requestEditQuery(String requestId, IGenCardsEditQueryCallback callback);
    void cancelEditQuery(String requestId);
}
