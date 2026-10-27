package com.flyme.auto.gencards.voice.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.flyme.auto.gencards.entry.GenCardsEntryContract;
import com.flyme.auto.gencards.voice.aidl.IGenAgentEditQueryCapture;
import com.flyme.auto.gencards.voice.aidl.IGenCardsEditQueryCallback;
import com.flyme.auto.gencards.voice.aidl.IGenCardsVoiceQueryIngress;
import java.lang.reflect.Method;
import org.junit.Test;

public class VoiceIngressContractTest {
    @Test
    public void stableConstantsExposeV2EditCaptureWithoutChangingEntryProtocol() {
        assertEquals(2, GenCardsVoiceIngressContract.VOICE_INGRESS_CONTRACT_VERSION);
        assertEquals("com.flyme.auto.gencards", GenCardsVoiceIngressContract.GENCARDS_PACKAGE);
        assertEquals(
                "com.flyme.auto.genagent.permission.REQUEST_EDIT_QUERY",
                GenAgentEditCaptureContract.PERMISSION_REQUEST_EDIT_QUERY);
        assertEquals("UI_NOT_READY", GenAgentEditCaptureContract.ERROR_UI_NOT_READY);
        assertEquals(
                "ACTIVE_REQUEST_CONFLICT",
                GenAgentEditCaptureContract.ERROR_ACTIVE_REQUEST_CONFLICT);
        assertEquals("CALLBACK_DEAD", GenAgentEditCaptureContract.ERROR_CALLBACK_DEAD);
        assertEquals("INTERNAL_ERROR", GenAgentEditCaptureContract.ERROR_INTERNAL);
        assertEquals(1, GenCardsEntryContract.PROTOCOL_VERSION);
        assertEquals("VOICE", GenCardsEntryContract.CHANNEL_VOICE);
        assertEquals("CREATE_CARD", GenCardsEntryContract.DESTINATION_CREATE_CARD);
    }

    @Test
    public void generatedAidlKeepsLegacyIngressAndAddsCorrelatedEditCapture() throws Exception {
        Method voice = IGenCardsVoiceQueryIngress.class.getMethod(
                "submitVoiceQuery", String.class, int.class);
        Method textCreate = IGenCardsVoiceQueryIngress.class.getMethod(
                "submitTextCreateQuery", String.class);
        Method requestEdit = IGenAgentEditQueryCapture.class.getMethod(
                "requestEditQuery", String.class, IGenCardsEditQueryCallback.class);
        Method cancelEdit = IGenAgentEditQueryCapture.class.getMethod(
                "cancelEditQuery", String.class);
        Method captured = IGenCardsEditQueryCallback.class.getMethod(
                "onQueryCaptured", String.class, String.class);
        Method cancelled = IGenCardsEditQueryCallback.class.getMethod(
                "onQueryCancelled", String.class);
        Method failed = IGenCardsEditQueryCallback.class.getMethod(
                "onQueryFailed", String.class, String.class, boolean.class);

        assertEquals(void.class, voice.getReturnType());
        assertEquals(boolean.class, textCreate.getReturnType());
        assertEquals(boolean.class, requestEdit.getReturnType());
        assertEquals(void.class, cancelEdit.getReturnType());
        assertEquals(void.class, captured.getReturnType());
        assertEquals(void.class, cancelled.getReturnType());
        assertEquals(void.class, failed.getReturnType());
        assertNotNull(IGenCardsVoiceQueryIngress.Stub.class);
        assertNotNull(IGenAgentEditQueryCapture.Stub.class);
        assertNotNull(IGenCardsEditQueryCallback.Stub.class);
    }
}
