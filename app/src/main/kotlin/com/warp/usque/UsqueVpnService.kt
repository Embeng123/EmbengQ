package com.warp.usque

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import usqueandroid.Usqueandroid
import usqueandroid.VpnStateCallback
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UsqueVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.warp.usque.STOP_VPN"
        private const val TAG = "EmbengQVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "embengq_vpn"
        @Volatile private var activeService: UsqueVpnService? = null

        fun stopActiveTunnel() {
            activeService?.stopVpn("external stop") ?: runCatching { Usqueandroid.stopTunnel() }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var tun: ParcelFileDescriptor? = null
    private var detachedTunFd: Int = -1
    private val running = AtomicBoolean(false)
    private val manualStop = AtomicBoolean(false)
    private val restarting = AtomicBoolean(false)
    @Volatile private var lastConfigPath: String = ""
    @Volatile private var lastSni: String = ""
    @Volatile private var lastEndpoint: String = ""
    @Volatile private var lastSplitMode: Boolean = false
    @Volatile private var lastAllowedApps: ArrayList<String> = arrayListOf()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeService = this
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "stop requested")
            manualStop.set(true)
            stopVpn("ACTION_STOP")
            stopSelf()
            return Service.START_NOT_STICKY
        }

        startForegroundCompat()
        manualStop.set(false)

        val configPath = intent?.getStringExtra("configPath") ?: File(filesDir, "config.json").absolutePath
        val sni = intent?.getStringExtra("sni") ?: "engage.cloudflareclient.com"
        val endpoint = intent?.getStringExtra("endpoint") ?: "162.159.198.2:443"
        val splitMode = intent?.getBooleanExtra("splitMode", false) ?: false
        val allowedApps = intent?.getStringArrayListExtra("allowedApps") ?: arrayListOf()

        lastConfigPath = configPath
        lastSni = sni
        lastEndpoint = endpoint
        lastSplitMode = splitMode
        lastAllowedApps = ArrayList(allowedApps)

        if (running.get()) return Service.START_STICKY
        executor.execute { startNativeTunnel(configPath, sni, endpoint, splitMode, allowedApps) }
        return Service.START_STICKY
    }

    private fun startNativeTunnel(configPath: String, sni: String, endpoint: String, splitMode: Boolean, allowedApps: ArrayList<String>) {
        try {
            running.set(true)
            Log.i(TAG, "starting vpn service endpoint=$endpoint sni=$sni splitMode=$splitMode allowedApps=${allowedApps.size} config=$configPath")
            Usqueandroid.resetConnectionOptions()
            Usqueandroid.setSNI(sni)
            Usqueandroid.setEndpoint(endpoint)
            Log.i(TAG, "native endpoint now=${runCatching { Usqueandroid.getEndpoint() }.getOrDefault("")}")

            val builder = Builder()
                .setSession("EmbengQ VPN")
                .setMtu(1280)
                .addAddress(safeIPv4(configPath), 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .addRoute("0.0.0.0", 0)

            if (splitMode) {
                if (allowedApps.isEmpty()) throw IllegalStateException("split mode enabled but no apps selected")
                allowedApps.distinct().forEach { pkg ->
                    if (pkg == packageName) {
                        Log.i(TAG, "skip allowing own package to avoid VPN loop: $pkg")
                    } else {
                        runCatching { builder.addAllowedApplication(pkg) }
                            .onFailure { Log.w(TAG, "addAllowedApplication failed: $pkg", it) }
                    }
                }
            } else {
                // Critical: do not route this app's own MASQUE/QUIC control connection into itself.
                runCatching { builder.addDisallowedApplication(packageName) }
                    .onFailure { Log.w(TAG, "addDisallowedApplication failed", it) }
            }

            val ipv6 = runCatching { Usqueandroid.getAssignedIPv6(configPath) }.getOrDefault("")
            if (ipv6.isNotBlank()) runCatching {
                builder.addAddress(ipv6, 128)
                builder.addRoute("::", 0)
                builder.addDnsServer("2606:4700:4700::1111")
                builder.addDnsServer("2606:4700:4700::1001")
            }.onFailure { Log.w(TAG, "ipv6 setup failed", it) }

            val pfd = builder.establish() ?: throw IllegalStateException("builder.establish returned null")
            tun = pfd
            detachedTunFd = pfd.detachFd()
            tun = null
            Log.i(TAG, "tun established fd=$detachedTunFd")

            val err = Usqueandroid.startTunnelWithFd(configPath, detachedTunFd.toLong(), object : VpnStateCallback {
                override fun onConnected() {
                    restarting.set(false)
                    Log.i(TAG, "tunnel connected")
                }
                override fun onDisconnected(reason: String?) {
                    Log.w(TAG, "tunnel disconnected: $reason")
                    handleTunnelFailure("native disconnected: ${reason.orEmpty()}")
                }
                override fun onError(message: String?) {
                    Log.e(TAG, "tunnel error: $message")
                    handleTunnelFailure("native error: ${message.orEmpty()}")
                }
            })
            if (!err.isNullOrBlank()) throw IllegalStateException(err)

            Log.i(TAG, "startTunnelWithFd returned without error")
        } catch (e: Exception) {
            Log.e(TAG, "vpn service failed", e)
            handleTunnelFailure("exception: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun handleTunnelFailure(reason: String) {
        if (manualStop.get()) {
            stopVpn(reason)
            stopSelf()
            return
        }
        scheduleRestart(reason)
    }

    private fun scheduleRestart(reason: String) {
        if (restarting.getAndSet(true)) {
            Log.w(TAG, "restart already scheduled: $reason")
            return
        }
        Log.w(TAG, "scheduling VPN restart: $reason")
        stopVpn("restart: $reason")
        executor.execute {
            runCatching { TimeUnit.SECONDS.sleep(3) }
            if (manualStop.get()) {
                restarting.set(false)
                return@execute
            }
            restarting.set(false)
            startForegroundCompat()
            startNativeTunnel(lastConfigPath, lastSni, lastEndpoint, lastSplitMode, ArrayList(lastAllowedApps))
        }
    }

    private fun startForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "EmbengQ VPN", NotificationManager.IMPORTANCE_LOW)
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
                .setContentTitle("EmbengQ VPN")
                .setContentText("EmbengQ is running")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "startForeground failed", it) }
    }

    private fun safeIPv4(configPath: String): String {
        return runCatching { Usqueandroid.getAssignedIPv4(configPath) }
            .getOrDefault("")
            .ifBlank { "172.16.0.2" }
    }

    private fun stopVpn(reason: String = "stop") {
        Log.i(TAG, "stopping vpn: $reason fd=$detachedTunFd running=${running.get()}")
        running.set(false)
        runCatching { Usqueandroid.stopTunnel() }
            .onFailure { Log.w(TAG, "native stopTunnel failed", it) }
        runCatching { tun?.close() }
            .onFailure { Log.w(TAG, "tun close failed", it) }
        tun = null
        if (detachedTunFd >= 0) {
            runCatching { Os.close(fileDescriptorFromInt(detachedTunFd)) }
                .onFailure { Log.w(TAG, "detached fd close failed", it) }
            detachedTunFd = -1
        }
    }

    private fun fileDescriptorFromInt(fdInt: Int): FileDescriptor {
        val fd = FileDescriptor()
        val field = FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.setInt(fd, fdInt)
        return fd
    }

    override fun onDestroy() {
        manualStop.set(true)
        stopVpn("onDestroy")
        if (activeService === this) activeService = null
        super.onDestroy()
    }
}
