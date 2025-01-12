package com.compasseur.sdrbridge

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.compasseur.sdrbridge.databinding.ActivityMainBinding
import com.compasseur.sdrbridge.rfsource.HackRF
import com.compasseur.sdrbridge.rfsource.RfSource
import com.compasseur.sdrbridge.rfsource.RfSourceCallbackInterface
import com.compasseur.sdrbridge.rfsource.RfSourceHolder
import com.compasseur.sdrbridge.sources.Airspy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Module:      MainActivity
 * Description: Receives intent from IntentHandlerActivity,
 *              launches USB permission manager, gets the RfSource,
 *              starts the DriverService and displays the Log on the XML.
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

class MainActivity : AppCompatActivity(), RfSourceCallbackInterface {

    private lateinit var binding: ActivityMainBinding
    private var logTag = "MainActivityTag"
    private lateinit var usbPermissionManager: UsbPermissionManager
    private lateinit var usbManager: UsbManager
    private var intentParameters: Uri? = null
    private var rfSource: RfSource? = null
    private var logChangedJob: Job? = null
    private var fromResultHandler = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbPermissionManager = UsbPermissionManager(
            context = this,
            usbManager = usbManager,
            onPermissionGranted = { device ->
                rfSource = when {
                    device.vendorId == hackRFVendorID && device.productId == hackRFProductID -> HackRF(usbManager, device, 20000000 * 2)
                    device.vendorId == airspyMiniVendorID && device.productId == airspyMiniProductID -> Airspy(usbManager, device, 1000000 * 2)
                    else -> HackRF(usbManager, device, 200000000 * 2)
                }
                rfSource?.initializeRfSource(this, this, device, usbManager, 200000000 * 2)
                val deviceDetecte = if (rfSource is HackRF) "HAckRF" else if (rfSource is Airspy) "Airspy" else "Nothing"
                LogParameters.appendLine("$logTag: Permission granted for $deviceDetecte, starting service with ${device.productName}")
            },
            onPermissionDenied = {
                Log.w(logTag, "Permission denied for HackRF.")
                LogParameters.appendLine("$logTag: Permission denied for HackRF.")
            }
        )
        usbPermissionManager.registerReceiver()

        //Displays some Log messages on MainActivity XML coming from anywhere in the app
        observeLogParametersChanges()

        //Checks if opened MainActivity through an intent or not. If through an intent, MainActivity should stay in
        //the back to keep the client app in the front
        fromResultHandler = intent.getBooleanExtra("fromResultHandler", false)
        if (fromResultHandler) {
            intentParameters = intent.data
            LogParameters.appendLine("$logTag: Intent received data = $intentParameters")
            usbPermissionChecker()
            Handler(Looper.getMainLooper()).post {
                // Move task to the background to keep the client app in the foreground
                moveTaskToBack(true)
            }
        }

        binding.apply {
            btnClearLog.setOnClickListener {
                LogParameters.clearLog()
            }

            btnAbout.setOnClickListener {
                showAbout()
            }
        }
    }

    private fun usbPermissionChecker() {
        usbPermissionManager.findRfSourceDevice()?.let { device ->
            //LogParameters.appendLine("$logTag:  /// ${RfSourceHolder.rfSource}")
            LogParameters.appendLine("$logTag:  /// ${device.productName}")
            usbPermissionManager.checkUsbPermission(device)
        }
    }

    private fun startDriverService() {
        val serviceIntent = Intent(this, DriverService::class.java).apply {
            data = intentParameters
        }
        startForegroundService(serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        logChangedJob?.cancel()
        logChangedJob = null
        usbPermissionManager.unregisterReceiver()
    }

    //Receives callback from device class with success. Launches the driver service
    override fun onRfSourceReady(sourcerf: RfSource) {
        RfSourceHolder.rfSource = sourcerf
        if (fromResultHandler) {
            LogParameters.appendLine("$logTag: Source is ready !!! Starting driver service")
            startDriverService()
        }
    }

    //Receives callback from device class with error
    override fun onRfSourceError(message: String) {
        LogParameters.appendLine("$logTag: Error on source callback !!! $message")
    }

    private fun observeLogParametersChanges() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { // Runs when the lifecycle is at least STARTED
                try {
                    LogParameters.logFlow.collect { log ->
                        binding.apply {
                            logData.text = log
                            scrollview.post { scrollview.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                } catch (e: Exception) {
                    Log.i(logTag, "Error: $e")
                }
            }
        }
    }

    private fun showAbout() {
        val listOfChoices = arrayOf("SDRBridge", "Airspy", "HackRF", "Compasseur")
        AlertDialog.Builder(this).apply {
            setTitle("About")
            setItems(listOfChoices) { dialogInterface, i ->
                when (i) {
                    0 -> openWebPage("https://github.com/compasseur/SDRBridge")
                    1 -> showLicenseDialog("Airspy license", getString(R.string.airspy_license))
                    2 -> showLicenseDialog("HackRF license", getString(R.string.hackrf_license))
                    else -> openWebPage("https://www.compasseur.com")
                }
                dialogInterface.dismiss()
            }
            setNeutralButton("Dismiss") { dialog, _ -> dialog.cancel() }
        }.create().show()
    }

    private fun openWebPage(url: String) {
        try {
            val intentWeb = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intentWeb)
        } catch (e: Exception) {
            Toast.makeText(this@MainActivity, "Could not load the browser. You can visit $url.", Toast.LENGTH_LONG).show()
            LogParameters.appendLine("$logTag: Error opening browser: ${e.message}")
        }
    }

    private fun showLicenseDialog(title: String, message: String) {
        AlertDialog.Builder(this@MainActivity).apply {
            setTitle(title)
            setMessage(message)
            setPositiveButton("OK") { _, _ -> }
        }.create().show()
    }

}

