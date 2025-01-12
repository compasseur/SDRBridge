package com.compasseur.sdrbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

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
    private val onPermissionDenied: () -> Unit
) {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.compasseur.USB_PERMISSION"
    }

    private var logTag = "UsbPermissionManagerTag"

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LogParameters.appendLine("$logTag: PERMISSION: ${intent?.action}")
            if (intent?.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        LogParameters.appendLine("$logTag: Permission granted for ${device.productName}")
                        onPermissionGranted(device) // Call the callback if permission granted
                    } else {
                        LogParameters.appendLine("$logTag: Permission denied for ${device?.productName}")
                        onPermissionDenied()
                    }
                }
            }
        }
    }

    fun registerReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (e: IllegalArgumentException) {
            LogParameters.appendLine("$logTag: Receiver was not registered or already unregistered.")
        }
    }

    fun checkUsbPermission(device: UsbDevice) {
        if (!usbPermissionReceiver.isOrderedBroadcast) {
            registerReceiver()
        }
        if (usbManager.hasPermission(device)) {
            LogParameters.appendLine("$logTag: Already has permission for ${device.productName}")
            onPermissionGranted(device)
        } else {
            LogParameters.appendLine("$logTag: Requesting permission for ${device.productName}")
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    fun findRfSourceDevice(): UsbDevice? {
        val deviceList = usbManager.deviceList
        val foundDevice = deviceList.values.firstOrNull {
            (it.vendorId == hackRFVendorID && it.productId == hackRFProductID)
                    || (it.vendorId == airspyMiniVendorID && it.productId == airspyMiniProductID)
        }
        LogParameters.appendLine("$logTag: Found ${foundDevice?.productName} - ${foundDevice?.vendorId} - ${foundDevice?.productId}")
        return foundDevice
    }
}

