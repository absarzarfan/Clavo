package com.google.mediapipe.examples.objectdetection.utils

// TEMP-ClavoUSB: USB diagnostics. Delete this file and every line marked "TEMP-ClavoUSB" when done.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

object UsbDebugLog {
    const val TAG = "ClavoUSB"

    fun d(msg: String) {
        Log.d(TAG, msg)
    }

    private val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    /** Event line with wall-clock timestamp: "EVENT @HH:mm:ss.SSS detail". */
    @Synchronized
    fun ev(event: String, detail: String = "") {
        Log.i(TAG, "$event @${stamp.format(java.util.Date())} $detail".trim())
    }

    private var crashLoggerInstalled = false

    /**
     * Logs "app-start" once per process (pid, launch cause, how the previous process ended) and
     * installs an uncaught-exception logger that writes to ClavoUSB before the process dies.
     */
    @Synchronized
    fun logProcessStart(ctx: Context, intent: Intent?, savedState: Boolean) {
        val cause = when {
            intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED -> "usb-attach-intent"
            intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true -> "launcher"
            else -> "other(action=${intent?.action})"
        }
        ev("app-start", "pid=${android.os.Process.myPid()} cause=$cause action=${intent?.action} savedState=$savedState")
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.getHistoricalProcessExitReasons(null, 0, 5).forEach {
                    ev("prev-process-exit", "pid=${it.pid} reason=${it.reason} status=${it.status} importance=${it.importance} at=${stamp.format(java.util.Date(it.timestamp))} desc=${it.description}")
                }
            } catch (e: Exception) {
                ev("prev-process-exit", "unavailable: ${e.javaClass.simpleName}")
            }
        }
        if (!crashLoggerInstalled) {
            crashLoggerInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    Log.e(TAG, "uncaught @${stamp.format(java.util.Date())} pid=${android.os.Process.myPid()} thread=${t.name}: ${Log.getStackTraceString(e)}")
                } catch (_: Throwable) {
                }
                previous?.uncaughtException(t, e)
            }
        }
    }

    fun describe(usb: UsbManager?, dev: UsbDevice): String {
        val ifaceClasses = (0 until dev.interfaceCount).joinToString(",") { dev.getInterface(it).interfaceClass.toString() }
        val perm = try { usb?.hasPermission(dev) } catch (e: Exception) { null }
        return "name=${dev.deviceName} VID=${dev.vendorId} PID=${dev.productId} " +
            "class=${dev.deviceClass} ifaceClasses=[$ifaceClasses] hasPermission=$perm"
    }

    /** Session header: Android/device info plus the full UsbManager device list. */
    fun logSession(ctx: Context) {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
        d("SESSION SDK_INT=${Build.VERSION.SDK_INT} manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
            "targetSdk=${ctx.applicationInfo.targetSdkVersion}")
        val list = usb?.deviceList?.values.orEmpty()
        d("SESSION UsbManager.getDeviceList() size=${list.size}")
        list.forEach { d("SESSION   ${describe(usb, it)}") }
    }

    /** Own receiver for ATTACHED/DETACHED, registered not-exported. Caller must unregister it. */
    fun registerAttachDetachReceiver(
        ctx: Context,
        onEvent: ((attached: Boolean, device: UsbDevice?) -> Unit)? = null
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val usb = c.getSystemService(Context.USB_SERVICE) as? UsbManager
                d("RECEIVER ${intent.action} device=${dev?.let { describe(usb, it) }}")
                val attached = intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
                ev(if (attached) "ATTACHED" else "DETACHED", "device=${dev?.deviceName}")
                onEvent?.invoke(attached, dev)
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }
}
