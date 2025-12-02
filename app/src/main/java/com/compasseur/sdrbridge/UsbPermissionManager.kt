package com.compasseur.sdrbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat.getSystemService
import java.security.Provider

/**
 * Module:      UsbPermissionManager
 * Description: Asks and checks for USB access permissions for accessing the HackRF or Airspy
 *
 * Copyright (C) 2024 Grégoire de Courtivron
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val onPermissionGranted: (UsbDevice) -> Unit,
    private val onPermissionDenied: () -> Unit,
    private val onHackrfDisconnected: ((UsbDevice) -> Unit)? = null
) {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.compasseur.USB_PERMISSION"
    }

    private var logTag = "UsbPermissionManagerTag"
    private var isReceiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            LogParameters.appendLine("$logTag, BROADCAST: $action")

            when (action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        //val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && device != null) {
                            LogParameters.appendLine("$logTag, Permission granted for ${device.productName}")
                            onPermissionGranted(device)
                        } else {
                            LogParameters.appendLine("$logTag, Permission denied for ${device?.productName}")
                            onPermissionDenied()
                        }
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    //val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (device != null && isCompatibleSdrDevice(device)) {
                        LogParameters.appendLine("$logTag, Device disconnected: ${device.productName}")
                        onHackrfDisconnected?.invoke(device)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        LogParameters.appendLine("$logTag, Device connected: ${device.productName} - VID: ${device.vendorId} - PID: ${device.productId}")
                        if (isCompatibleSdrDevice(device)) {
                            LogParameters.appendLine("$logTag, Compatible SDR device attached: ${device.productName}")
                            //checkUsbPermission(device)
                        } else {
                            LogParameters.appendLine("$logTag, Incompatible USB device attached: ${device.productName}")
                        }
                    }
                }
            }
        }
    }

    fun registerReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            }
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
        }
    }

    fun unregisterReceiver() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbPermissionReceiver)
            } catch (e: IllegalArgumentException) {
                LogParameters.appendLine("$logTag, Receiver was not registered or already unregistered.")
            }
            isReceiverRegistered = false
        }
    }

    fun checkUsbPermission(device: UsbDevice) {
        /*if (!usbPermissionReceiver.isOrderedBroadcast) {
            registerReceiver()
        }*/
        registerReceiver()
        if (usbManager.hasPermission(device)) {
            LogParameters.appendLine("$logTag, Already has permission for ${device.productName}")
            onPermissionGranted(device)
        } else {
            LogParameters.appendLine("$logTag, Requesting permission for ${device.productName}")
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun isCompatibleSdrDevice(device: UsbDevice): Boolean {
        return (device.vendorId == hackRFVendorID && device.productId == hackRFProductID) ||
                (device.vendorId == hackRFJawbreakerVendorID && device.productId == hackRFJawbreakerProductID) ||
                (device.vendorId == hackRFRad1oVendorID && device.productId == hackRFRad1oProductID) ||
                (device.vendorId == airspyMiniVendorID && device.productId == airspyMiniProductID)
    }

    fun findRfSourceDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) {
            LogParameters.appendLine("$logTag, No USB devices found.")
        }
        var foundDevice: UsbDevice? = null
        for (device in deviceList.values) {
            val vendorId = device.vendorId
            val productId = device.productId
            val productName = device.productName ?: "Unknown"

            LogParameters.appendLine(
                "$logTag, Detected USB Device: $productName | VID: $vendorId | PID: $productId"
            )
            LogParameters.appendLine(
                "$logTag, Class: ${device.deviceClass} | Subclass: ${device.deviceSubclass} | Protocol: ${device.deviceProtocol}"
            )

            if ((vendorId == hackRFVendorID && productId == hackRFProductID) ||
                (vendorId == hackRFJawbreakerVendorID && productId == hackRFJawbreakerProductID) ||
                (vendorId == hackRFRad1oVendorID && productId == hackRFRad1oProductID) ||
                (vendorId == airspyMiniVendorID && productId == airspyMiniProductID)
            ) {
                foundDevice = device
                LogParameters.appendLine("$logTag, Found ${foundDevice?.productName} - ${foundDevice?.vendorId} - ${foundDevice?.productId} - ${foundDevice?.version}")
            }
        }

        return foundDevice
    }
}




