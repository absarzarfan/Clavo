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
    fun registerAttachDetachReceiver(ctx: Context): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val usb = c.getSystemService(Context.USB_SERVICE) as? UsbManager
                d("RECEIVER ${intent.action} device=${dev?.let { describe(usb, it) }}")
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
