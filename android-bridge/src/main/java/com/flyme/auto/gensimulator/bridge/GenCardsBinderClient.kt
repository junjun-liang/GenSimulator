package com.flyme.auto.gensimulator.bridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.flyme.auto.gencards.voice.aidl.IGenCardsVoiceQueryIngress
import com.flyme.auto.gencards.voice.contract.GenCardsVoiceIngressContract
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class GenCardsBinderClient(
    context: Context,
    private val rebindDelayMs: Long = 500L,
    private val connectTimeoutMs: Long = 5_000L,
) : CreateIngress, AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val connectionWaiters = mutableSetOf<CountDownLatch>()

    @Volatile
    private var state: BinderConnectionState = BinderConnectionState.DISCONNECTED

    @Volatile
    private var lastErrorCode: String? = null

    private var service: IGenCardsVoiceQueryIngress? = null
    private var binderToken: IBinder? = null
    private var bindRequested = false
    private var started = false

    private val deathRecipient = IBinder.DeathRecipient {
        mainHandler.post { handleBinderDeath() }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val remote = IGenCardsVoiceQueryIngress.Stub.asInterface(binder)
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (_: RemoteException) {
                handleBinderDeath()
                return
            }
            synchronized(lock) {
                service = remote
                binderToken = binder
                state = BinderConnectionState.CONNECTED
                lastErrorCode = null
                signalConnectionWaitersLocked()
            }
            Log.i(TAG, "gencards_bind_connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            clearBinder(
                BinderConnectionState.DISCONNECTED,
                BridgeErrorCode.GENCARDS_BIND_FAILED,
                signalWaiters = false,
            )
            scheduleRebind()
        }

        override fun onBindingDied(name: ComponentName) {
            clearBinder(
                BinderConnectionState.BINDER_DIED,
                BridgeErrorCode.GENCARDS_BINDER_DIED,
                signalWaiters = false,
            )
            unbindRequestedConnection()
            scheduleRebind()
        }

        override fun onNullBinding(name: ComponentName) {
            clearBinder(
                BinderConnectionState.NULL_BINDING,
                BridgeErrorCode.GENCARDS_BIND_FAILED,
                signalWaiters = true,
            )
            unbindRequestedConnection()
            scheduleRebind()
        }
    }

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        ensureBound()
    }

    fun ensureBound() {
        if (!started) return
        mainHandler.post {
            if (state != BinderConnectionState.CONNECTED) bindNow()
        }
    }

    override fun snapshot(): BridgeHealthSnapshot {
        val installed = isGenCardsInstalled()
        val signer = if (installed) sameSigner() else null
        val currentService = synchronized(lock) { service }
        val currentState = state
        return BridgeHealthSnapshot(
            genCardsInstalled = installed,
            binderConnected = currentState == BinderConnectionState.CONNECTED &&
                currentService?.asBinder()?.isBinderAlive == true,
            contractVersion = GenCardsVoiceIngressContract.VOICE_INGRESS_CONTRACT_VERSION,
            sameSigner = signer,
            binderState = currentState.name,
            lastErrorCode = lastErrorCode,
        )
    }

    override fun submit(query: String): IngressResult {
        val remote = awaitConnectedService()
            ?: return IngressResult.Failure(connectionFailureCodeAfterWait())

        Log.i(TAG, "text_create_submit")
        return try {
            val result = if (remote.submitTextCreateQuery(query)) {
                IngressResult.Accepted
            } else {
                IngressResult.Rejected
            }
            Log.i(TAG, "text_create_result result=${result.javaClass.simpleName}")
            result
        } catch (_: SecurityException) {
            clearBinder(
                BinderConnectionState.PERMISSION_DENIED,
                BridgeErrorCode.GENCARDS_PERMISSION_DENIED,
                signalWaiters = true,
            )
            Log.w(TAG, "text_create_result code=${BridgeErrorCode.GENCARDS_PERMISSION_DENIED}")
            IngressResult.Failure(BridgeErrorCode.GENCARDS_PERMISSION_DENIED)
        } catch (_: DeadObjectException) {
            mainHandler.post { handleBinderDeath() }
            Log.w(TAG, "text_create_result code=${BridgeErrorCode.GENCARDS_BINDER_DIED}")
            IngressResult.Failure(BridgeErrorCode.GENCARDS_BINDER_DIED)
        } catch (_: RemoteException) {
            // The remote side may already have accepted the request. Never retry the business RPC.
            clearBinder(
                BinderConnectionState.BIND_FAILED,
                BridgeErrorCode.GENCARDS_BIND_FAILED,
                signalWaiters = false,
            )
            scheduleRebind()
            Log.w(TAG, "text_create_result code=${BridgeErrorCode.GENCARDS_BIND_FAILED}")
            IngressResult.Failure(BridgeErrorCode.GENCARDS_BIND_FAILED)
        }
    }

    private fun awaitConnectedService(): IGenCardsVoiceQueryIngress? {
        synchronized(lock) {
            service?.let { return it }
        }

        ensureBound()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // CREATE currently runs on LocalHttpBridgeServer worker threads. Keep this guard so a
            // future caller cannot accidentally block the Android main thread.
            Log.w(TAG, "gencards_bind_timeout reason=main_thread_wait_forbidden")
            return synchronized(lock) { service }
        }

        val waiter = CountDownLatch(1)
        synchronized(lock) {
            service?.let { return it }
            if (state.isImmediateConnectionFailure()) return null
            connectionWaiters += waiter
        }

        Log.i(TAG, "gencards_bind_wait_start")
        val signalled = try {
            waiter.await(connectTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            synchronized(lock) { connectionWaiters.remove(waiter) }
        }

        val connected = synchronized(lock) { service }
        if (connected != null) {
            Log.i(TAG, "gencards_bind_connected")
        } else if (!signalled || state == BinderConnectionState.CONNECTING || state == BinderConnectionState.DISCONNECTED) {
            Log.w(TAG, "gencards_bind_timeout timeoutMs=$connectTimeoutMs state=${state.name}")
        }
        return connected
    }

    private fun bindNow() {
        if (!started || bindRequested) return
        if (!isGenCardsInstalled()) {
            updateConnectionFailure(
                BinderConnectionState.GENCARDS_NOT_INSTALLED,
                BridgeErrorCode.GENCARDS_NOT_INSTALLED,
            )
            return
        }
        if (sameSigner() == false) {
            updateConnectionFailure(
                BinderConnectionState.PERMISSION_DENIED,
                BridgeErrorCode.GENCARDS_PERMISSION_DENIED,
            )
            return
        }

        state = BinderConnectionState.CONNECTING
        lastErrorCode = null
        val intent = Intent().apply {
            component = ComponentName(
                GenCardsVoiceIngressContract.GENCARDS_PACKAGE,
                GenCardsVoiceIngressContract.QUERY_SERVICE_COMPONENT,
            )
        }
        try {
            val success = appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            bindRequested = success
            if (!success) {
                updateConnectionFailure(
                    BinderConnectionState.BIND_FAILED,
                    BridgeErrorCode.GENCARDS_BIND_FAILED,
                )
                scheduleRebind()
            }
        } catch (_: SecurityException) {
            bindRequested = false
            updateConnectionFailure(
                BinderConnectionState.PERMISSION_DENIED,
                BridgeErrorCode.GENCARDS_PERMISSION_DENIED,
            )
            scheduleRebind()
        } catch (_: RuntimeException) {
            bindRequested = false
            updateConnectionFailure(
                BinderConnectionState.BIND_FAILED,
                BridgeErrorCode.GENCARDS_BIND_FAILED,
            )
            scheduleRebind()
        }
    }

    private fun handleBinderDeath() {
        clearBinder(
            BinderConnectionState.BINDER_DIED,
            BridgeErrorCode.GENCARDS_BINDER_DIED,
            signalWaiters = false,
        )
        unbindRequestedConnection()
        scheduleRebind()
    }

    private fun clearBinder(
        newState: BinderConnectionState,
        errorCode: String?,
        signalWaiters: Boolean,
    ) {
        synchronized(lock) {
            runCatching { binderToken?.unlinkToDeath(deathRecipient, 0) }
            service = null
            binderToken = null
            state = newState
            lastErrorCode = errorCode
            if (signalWaiters) signalConnectionWaitersLocked()
        }
    }

    private fun updateConnectionFailure(newState: BinderConnectionState, errorCode: String) {
        synchronized(lock) {
            state = newState
            lastErrorCode = errorCode
            signalConnectionWaitersLocked()
        }
    }

    private fun signalConnectionWaitersLocked() {
        connectionWaiters.forEach(CountDownLatch::countDown)
        connectionWaiters.clear()
    }

    private fun BinderConnectionState.isImmediateConnectionFailure(): Boolean = when (this) {
        BinderConnectionState.GENCARDS_NOT_INSTALLED,
        BinderConnectionState.PERMISSION_DENIED,
        BinderConnectionState.BIND_FAILED,
        BinderConnectionState.NULL_BINDING,
        -> true
        else -> false
    }

    private fun connectionFailureCodeAfterWait(): String = when (state) {
        BinderConnectionState.GENCARDS_NOT_INSTALLED -> BridgeErrorCode.GENCARDS_NOT_INSTALLED
        BinderConnectionState.PERMISSION_DENIED -> BridgeErrorCode.GENCARDS_PERMISSION_DENIED
        BinderConnectionState.BINDER_DIED -> BridgeErrorCode.GENCARDS_BINDER_DIED
        BinderConnectionState.BIND_FAILED,
        BinderConnectionState.NULL_BINDING,
        -> BridgeErrorCode.GENCARDS_BIND_FAILED
        else -> BridgeErrorCode.GENCARDS_CONNECT_TIMEOUT
    }

    private fun unbindRequestedConnection() {
        if (!bindRequested) return
        runCatching { appContext.unbindService(serviceConnection) }
        bindRequested = false
    }

    private fun scheduleRebind() {
        if (!started) return
        mainHandler.removeCallbacks(rebindRunnable)
        mainHandler.postDelayed(rebindRunnable, rebindDelayMs)
    }

    private val rebindRunnable = Runnable {
        if (started) {
            unbindRequestedConnection()
            bindNow()
        }
    }

    @Suppress("DEPRECATION")
    private fun isGenCardsInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(GenCardsVoiceIngressContract.GENCARDS_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun sameSigner(): Boolean? = try {
        appContext.packageManager.checkSignatures(
            appContext.packageName,
            GenCardsVoiceIngressContract.GENCARDS_PACKAGE,
        ) == PackageManager.SIGNATURE_MATCH
    } catch (_: RuntimeException) {
        null
    }

    override fun close() {
        synchronized(lock) { started = false }
        mainHandler.removeCallbacks(rebindRunnable)
        clearBinder(BinderConnectionState.DISCONNECTED, null, signalWaiters = true)
        unbindRequestedConnection()
    }

    private companion object {
        const val TAG = "GenSimulatorBridge"
    }
}
