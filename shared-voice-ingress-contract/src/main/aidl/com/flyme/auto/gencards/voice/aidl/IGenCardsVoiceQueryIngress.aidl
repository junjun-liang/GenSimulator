package com.flyme.auto.gencards.voice.aidl;

interface IGenCardsVoiceQueryIngress {
    void submitVoiceQuery(String query, int sceneType);
    boolean submitTextCreateQuery(String query);
}
