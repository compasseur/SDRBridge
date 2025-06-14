package com.compasseur.sdrbridge.rfsource

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.util.Log
import android.widget.Toast
import com.compasseur.sdrbridge.LogParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * <h1>HackRF USB Library for Android</h1>
 *
 * Module:      HackRF
 * Description: The Hackrf class represents the HackRF device and
 *              acts as abstraction layer that manages the USB
 *              communication between the device and the application.
 *
 * Copyright (C) 2024 Grégoire de Courtivron
 * Copyright (C) 2014 Dennis Mantz
 * based on code of libhackrf [https://github.com/mossmann/hackrf/tree/master/host/libhackrf]:
 *     Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
 *     Copyright (c) 2013, Benjamin Vernoux <titanmkd@gmail.com>
 *     Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
 *     All rights reserved.
 *     Redistribution and use in source and binary forms, with or without modification,
 *     are permitted provided that the following conditions are met:
 *     - Redistributions of source code must retain the above copyright notice, this list
 *       of conditions and the following disclaimer.
 *     - Redistributions in binary form must reproduce the above copyright notice, this
 *       list of conditions and the following disclaimer in the documentation and/or other
 *       materials provided with the distribution.
 *     - Neither the name of Great Scott Gadgets nor the names of its contributors may be
 *       used to endorse or promote products derived from this software without specific
 *       prior written permission.
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

class HackRF
@Throws(RfSourceException::class) constructor(usbManager: UsbManager, usbDevice: UsbDevice, queueSize: Int) : RfSource {
    // Attributes to hold the USB related objects:
    private var usbInterface: UsbInterface? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbEndpointIN: UsbEndpoint? = null
    private var usbEndpointOUT: UsbEndpoint? = null

    private var transceiverMode: Int = HACKRF_TRANSCEIVER_MODE_OFF // current mode of the HackRF
    private var queue: ArrayBlockingQueue<ByteArray>? = null  // queue that buffers samples to pass them
    private var bufferPool: ArrayBlockingQueue<ByteArray>? = null  // queue that holds old buffers which can be reused while receiving or transmitting samples

    // startTime (in ms since 1970) and packetCounter for statistics:
    private var transceiveStartTime: Long = 0
    private var transceivePacketCounter: Long = 0

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var receiveJob: Job? = null
    private var transmitJob: Job? = null

    private var packetSize = 1024 * 16 // Buffer Size of each UsbRequest (can be customized through a command from client app)

    // Transceiver Modes:
    companion object {
        const val HACKRF_TRANSCEIVER_MODE_OFF = 0
        const val HACKRF_TRANSCEIVER_MODE_RECEIVE = 1
        const val HACKRF_TRANSCEIVER_MODE_TRANSMIT = 2

        // USB Vendor Requests (from hackrf.c)
        private const val HACKRF_VENDOR_REQUEST_SET_TRANSCEIVER_MODE = 1
        private const val HACKRF_VENDOR_REQUEST_MAX2837_WRITE = 2
        private const val HACKRF_VENDOR_REQUEST_MAX2837_READ = 3
        private const val HACKRF_VENDOR_REQUEST_SI5351C_WRITE = 4
        private const val HACKRF_VENDOR_REQUEST_SI5351C_READ = 5
        private const val HACKRF_VENDOR_REQUEST_SAMPLE_RATE_SET = 6
        private const val HACKRF_VENDOR_REQUEST_BASEBAND_FILTER_BANDWIDTH_SET = 7
        private const val HACKRF_VENDOR_REQUEST_RFFC5071_WRITE = 8
        private const val HACKRF_VENDOR_REQUEST_RFFC5071_READ = 9
        private const val HACKRF_VENDOR_REQUEST_SPIFLASH_ERASE = 10
        private const val HACKRF_VENDOR_REQUEST_SPIFLASH_WRITE = 11
        private const val HACKRF_VENDOR_REQUEST_SPIFLASH_READ = 12
        private const val HACKRF_VENDOR_REQUEST_BOARD_ID_READ = 14
        private const val HACKRF_VENDOR_REQUEST_VERSION_STRING_READ = 15
        private const val HACKRF_VENDOR_REQUEST_SET_FREQ = 16
        private const val HACKRF_VENDOR_REQUEST_AMP_ENABLE = 17
        private const val HACKRF_VENDOR_REQUEST_BOARD_PARTID_SERIALNO_READ = 18
        private const val HACKRF_VENDOR_REQUEST_SET_LNA_GAIN = 19
        private const val HACKRF_VENDOR_REQUEST_SET_VGA_GAIN = 20
        private const val HACKRF_VENDOR_REQUEST_SET_TXVGA_GAIN = 21
        private const val HACKRF_VENDOR_REQUEST_ANTENNA_ENABLE = 23
        private const val HACKRF_VENDOR_REQUEST_SET_FREQ_EXPLICIT = 24

        // RF Filter Paths (from hackrf.c)
        const val RF_PATH_FILTER_BYPASS = 0
        const val RF_PATH_FILTER_LOW_PASS = 1
        const val RF_PATH_FILTER_HIGH_PASS = 2

        private const val LOGTAG = "hackrf_android"
        private const val NUM_USB_REQUEST = 4 // Number of parallel UsbRequests
    }

    init {
        Log.i(LOGTAG, "constructor: create Hackrf instance from ${usbDevice.deviceName}. Vendor ID: ${usbDevice.vendorId} Product ID: ${usbDevice.productId}")
        Log.i(LOGTAG, "constructor: device protocol: ${usbDevice.deviceProtocol}")
        Log.i(LOGTAG, "constructor: device class: ${usbDevice.deviceClass} subclass: ${usbDevice.deviceSubclass}")
        Log.i(LOGTAG, "constructor: interface count: ${usbDevice.interfaceCount}")
        try {
            // Extract interface from the device:
            this.usbInterface = usbDevice.getInterface(0)

            // For detailed trouble shooting: Read out interface information of the device:
            Log.i(LOGTAG, "constructor: [interface 0] interface protocol: ${usbInterface!!.interfaceProtocol} subclass: ${usbInterface!!.interfaceSubclass}")
            Log.i(LOGTAG, "constructor: [interface 0] interface class: ${usbInterface!!.interfaceClass}")
            Log.i(LOGTAG, "constructor: [interface 0] endpoint count: ${usbInterface!!.endpointCount}")

            // Extract the endpoints from the device:
            this.usbEndpointIN = usbInterface!!.getEndpoint(0)
            this.usbEndpointOUT = usbInterface!!.getEndpoint(1)

            // For detailed trouble shooting: Read out endpoint information of the interface:
            Log.i(
                LOGTAG,
                "constructor:     [endpoint 0 (IN)] address: ${usbEndpointIN!!.address} attributes: ${usbEndpointIN!!.attributes} direction: ${usbEndpointIN!!.direction} max_packet_size: ${usbEndpointIN!!.maxPacketSize}"
            )
            Log.i(
                LOGTAG,
                "constructor:     [endpoint 1 (OUT)] address: ${usbEndpointOUT!!.address} attributes: ${usbEndpointOUT!!.attributes} direction: ${usbEndpointOUT!!.direction} max_packet_size: ${usbEndpointOUT!!.maxPacketSize}"
            )

            // Open the device:
            this.usbConnection = usbManager.openDevice(usbDevice)

            if (this.usbConnection == null) {
                Log.e(LOGTAG, "constructor: Couldn't open HackRF USB Device: openDevice() returned null!")
                throw RfSourceException("Couldn't open HackRF USB Device! (device is gone)")
            }
        } catch (e: Exception) {
            Log.e(LOGTAG, "constructor: Couldn't open HackRF USB Device: ${e.message}")
            throw RfSourceException("Error: Couldn't open HackRF USB Device!")
        }
        this.queue = ArrayBlockingQueue<ByteArray>(queueSize / getPacketSize())
        this.bufferPool = ArrayBlockingQueue<ByteArray>(queueSize / getPacketSize())
    }

    @Throws(RfSourceException::class)
    override fun initializeRfSource(context: Context, callbackInterface: RfSourceCallbackInterface, device: UsbDevice, usbManager: UsbManager, queueSize: Int): Boolean {
        return try {
            val hackrf = HackRF(usbManager, device, queueSize)
            Toast.makeText(context, "HackRF at " + device.deviceName + " is ready!", Toast.LENGTH_LONG).show()
            callbackInterface.onRfSourceReady(hackrf)
            true
        } catch (e: Exception) {
            Log.i(LOGTAG, "$e")
            false
        }
    }

    @Throws(RfSourceException::class)
    override fun sendUsbRequest(endpoint: Int, request: Int, value: Int, index: Int, buffer: ByteArray?): Int {
        val len = buffer?.size ?: 0
        val connection = usbConnection ?: throw RfSourceException("USB connection is null!")

        try {
            // Send request
            return connection.controlTransfer(
                endpoint or UsbConstants.USB_TYPE_VENDOR,
                request,
                value,
                index,
                buffer,
                len,
                1000  // Add a reasonable timeout
            )
        } catch (e: Exception) {
            Log.e(LOGTAG, "USB transfer failed: ${e.message}")
            throw RfSourceException("USB Transfer failed: ${e.message}")
        }
    }

    @Throws(RfSourceException::class)
    fun setTransceiverMode(mode: Int): Boolean {
        if (mode < 0 || mode > 2) {
            Log.e(LOGTAG, "Invalid Transceiver Mode: $mode")
            return false
        }

        this.transceiverMode = mode

        if (sendUsbRequest(
                UsbConstants.USB_DIR_OUT, HACKRF_VENDOR_REQUEST_SET_TRANSCEIVER_MODE,
                mode, 0, null
            ) != 0
        ) {
            Log.e(LOGTAG, "setTransceiverMode: USB Transfer failed!")
            return false
            //throw rfSourceException("USB Transfer failed!")
        }

        return true
    }

    @Throws(RfSourceException::class)
    override fun startRX(): ArrayBlockingQueue<ByteArray> {
        // Flush the queue
        this.queue?.clear()

        // Signal the HackRF Device to start receiving
        this.setTransceiverMode(HACKRF_TRANSCEIVER_MODE_RECEIVE)

        // Start the coroutine to queue the received samples
        receiveJob = coroutineScope.launch {
            receiveLoop()
        }

        // Reset the packet counter and start time for statistics
        this.transceiveStartTime = System.currentTimeMillis()
        this.transceivePacketCounter = 0

        return this.queue!!
    }

    //Not implemented
    @Throws(RfSourceException::class)
    override fun startTX(): ArrayBlockingQueue<ByteArray> {
        // Flush the queue
        this.queue?.clear()

        // Signal the HackRF Device to start transmitting
        this.setTransceiverMode(HACKRF_TRANSCEIVER_MODE_TRANSMIT)

        transmitJob = coroutineScope.launch {
            transmitLoop()
        }

        // Reset the packet counter and start time for statistics
        this.transceiveStartTime = System.currentTimeMillis()
        this.transceivePacketCounter = 0

        return this.queue!!
    }

    @Throws(RfSourceException::class)
    override fun stop() {
        transceiverMode = HACKRF_TRANSCEIVER_MODE_OFF
        // Set mode first to ensure device stops streaming
        try {
            setTransceiverMode(transceiverMode)
            //this.sendUsbRequest(UsbConstants.USB_DIR_OUT, HACKRF_VENDOR_REQUEST_SET_TRANSCEIVER_MODE, HACKRF_TRANSCEIVER_MODE_OFF, 0, null)

        } catch (e: Exception) {
            Log.e(LOGTAG, "Error setting transceiver mode off: ${e.message}")
        }

        /*receiveJob?.cancel()
        transmitJob?.cancel()*/

        // Clear queues
        queue?.clear()
        bufferPool?.clear()

        // Release USB resources
        try {
            usbConnection?.releaseInterface(usbInterface)
            usbConnection?.close()
        } catch (e: Exception) {
            Log.e(LOGTAG, "Error releasing USB interface: ${e.message}")
        }

        // Reset variables
        usbInterface = null
        usbConnection = null
        usbEndpointIN = null
        usbEndpointOUT = null
        queue = null
        bufferPool = null

        receiveJob?.cancel()
        transmitJob?.cancel()
        receiveJob = null
        transmitJob = null
        Log.e(LOGTAG, "Closed stuff")
    }

    /*override suspend fun receiveLoop() {
        val usbRequests = arrayOfNulls<UsbRequest>(NUM_USB_REQUEST)
        var isRunning = true

        try {
            // Initialize USB requests
            for (i in 0 until NUM_USB_REQUEST) {
                val buffer = ByteBuffer.wrap(getBufferFromBufferPool())
                usbRequests[i] = UsbRequest().apply {
                    initialize(usbConnection, usbEndpointIN)
                    clientData = buffer
                }
                if (usbRequests[i]?.queue(buffer) == false) {
                    throw RfSourceException("Failed to queue initial USB Request")
                }
            }

            while (isRunning && transceiverMode == HACKRF_TRANSCEIVER_MODE_RECEIVE) {
                coroutineContext.ensureActive()  // Check if coroutine is still active

                val request = withTimeoutOrNull(1000) {
                    usbConnection?.requestWait()
                } ?: continue  // Use timeout instead of blocking indefinitely

                if (request.endpoint != usbEndpointIN) continue

                val buffer = request.clientData as ByteBuffer
                transceivePacketCounter++

                // Use a timeout when offering to queue
                val queueSuccess = withTimeoutOrNull(100) {
                    queue?.offer(buffer.array())
                } ?: false

                if (!queueSuccess) {
                    Log.w(LOGTAG, "Queue is full, dropping packet")
                    returnBufferToBufferPool(buffer.array())
                    continue
                }

                // Prepare next buffer
                val nextBuffer = ByteBuffer.wrap(getBufferFromBufferPool())
                request.clientData = nextBuffer
                if (!request.queue(nextBuffer)) {
                    ////////////
                    returnBufferToBufferPool(nextBuffer.array())
                    ////////////
                    isRunning = false
                    Log.e(LOGTAG, "Failed to queue next USB Request")
                }
            }
        } catch (e: Exception) {
            Log.e(LOGTAG, "receiveLoop error: ${e.message}")
        } finally {
            // Cleanup
            usbRequests.forEach { request ->
                try {
                    request?.cancel()
                    (request?.clientData as? ByteBuffer)?.let {
                        returnBufferToBufferPool(it.array())
                    }
                } catch (e: Exception) {
                    Log.e(LOGTAG, "Error cleaning up USB request: ${e.message}")
                }
            }

            if (transceiverMode == HACKRF_TRANSCEIVER_MODE_RECEIVE) {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.e(LOGTAG, "Error stopping device: ${e.message}")
                }
            }
        }
    }*/

    override suspend fun receiveLoop() {
        val usbRequests = arrayOfNulls<UsbRequest>(NUM_USB_REQUEST)
        var isRunning = true

        try {
            // Initialize USB requests
            for (i in 0 until NUM_USB_REQUEST) {
                val buffer = ByteBuffer.wrap(getBufferFromBufferPool())
                usbRequests[i] = UsbRequest().apply {
                    initialize(usbConnection, usbEndpointIN)
                    clientData = buffer
                }
                if (!usbRequests[i]!!.queue(buffer)) {
                    throw RfSourceException("Failed to queue initial USB Request")
                }
            }

            while (isRunning && transceiverMode == HACKRF_TRANSCEIVER_MODE_RECEIVE) {
                coroutineContext.ensureActive()  // Check if coroutine is still active
                val request = withTimeoutOrNull(1000) {
                    usbConnection?.requestWait()
                } ?: continue

                if (request.endpoint != usbEndpointIN) continue

                val buffer = request.clientData as ByteBuffer
                transceivePacketCounter++

                val offered = withTimeoutOrNull(100) {
                    queue?.offer(buffer.array())
                } ?: false

                if (!offered) {
                    Log.w(LOGTAG, "Queue is full, dropping packet")
                    returnBufferToBufferPool(buffer.array())
                }
                // Prepare next buffer
                val nextBuffer = ByteBuffer.wrap(getBufferFromBufferPool())
                request.clientData = nextBuffer
                if (!request.queue(nextBuffer)) {
                    Log.e(LOGTAG, "Failed to queue next USB Request")
                    returnBufferToBufferPool(nextBuffer.array())
                    isRunning = false
                }
            }
        } catch (e: Exception) {
            Log.e(LOGTAG, "receiveLoop error: ${e.message}")
        } finally {
            usbRequests.forEach { request ->
                try {
                    request?.cancel()
                    (request?.clientData as? ByteBuffer)?.let {
                        returnBufferToBufferPool(it.array())
                    }
                } catch (e: Exception) {
                    Log.e(LOGTAG, "Error cleaning up USB request: ${e.message}")
                }
            }
            Log.i(LOGTAG, "receiveLoop exited cleanly")
        }
    }

    override suspend fun transmitLoop() {
        val usbRequests = arrayOfNulls<UsbRequest>(NUM_USB_REQUEST)
        var buffer: ByteBuffer
        var packet: ByteArray?
        try {
            // Create, initialize, and queue all USB requests:
            for (i in 0 until NUM_USB_REQUEST) {
                // Get a packet from the queue:
                packet = withContext(Dispatchers.IO) {
                    queue?.poll(1000, TimeUnit.MILLISECONDS)
                }
                if (packet == null || packet.size != getPacketSize()) {
                    Log.e(LOGTAG, "transmitLoop: Queue empty or wrong packet format. Abort.")
                    this.stop()
                    break
                }

                // Wrap the packet in a ByteBuffer object:
                buffer = ByteBuffer.wrap(packet)

                // Initialize the USB Request:
                usbRequests[i] = UsbRequest().apply {
                    initialize(usbConnection, usbEndpointOUT)
                    clientData = buffer
                }

                // Queue the request
                //if (!usbRequests[i]!!.queue(buffer)) {
                    if (!usbRequests[i]!!.queue(buffer, getPacketSize())) {

                        Log.e(LOGTAG, "transmitLoop: Couldn't queue USB Request.")
                    this.stop()
                    break
                }
            }

            // Run loop until transceiver mode changes...
            while (this.transceiverMode == HACKRF_TRANSCEIVER_MODE_TRANSMIT) {
                // Wait for a request to return. This will block until one of the requests is ready.
                val request = usbConnection?.requestWait()

                if (request == null) {
                    Log.e(LOGTAG, "transmitLoop: Didn't receive USB Request.")
                    break
                }

                // Make sure we got an UsbRequest for the OUT endpoint!
                if (request.endpoint != usbEndpointOUT) continue

                // Increment the packetCounter (for statistics)
                this.transceivePacketCounter++

                // Extract the buffer and return it to the buffer pool:
                buffer = request.clientData as ByteBuffer
                this.returnBufferToBufferPool(buffer.array())

                // Get the next packet from the queue:
                packet = withContext(Dispatchers.IO) {
                    queue?.poll(1000, TimeUnit.MILLISECONDS)
                }

                /*val samplePreview = packet?.take(32)?.joinToString(", ") { it.toString() }
                Log.e(LOGTAG, "TX Packet preview: $samplePreview")*/

                if (packet == null || packet.size != getPacketSize()) {
                    Log.e(LOGTAG, "transmitLoop: Queue empty or wrong packet format. Stop transmitting.")
                    break
                }

                // Wrap the packet in a ByteBuffer object:
                buffer = ByteBuffer.wrap(packet)
                request.clientData = buffer

                // Queue the request again...
                //if (!request.queue(buffer)) {
                    if (!request.queue(buffer, getPacketSize())) {

                        Log.e(LOGTAG, "transmitLoop: Couldn't queue USB Request.")
                    break
                }
            }
        } catch (e: RfSourceException) {
            Log.e(LOGTAG, "transmitLoop: USB Error!")
        } catch (e: InterruptedException) {
            Log.e(LOGTAG, "transmitLoop: Interrupted while waiting on queue!")
        }finally {
            // Transmitting is done. Cancel and close all USB requests:
            for (request in usbRequests) {
                request?.cancel()
                request?.close() // This will cause the VM to crash with a SIGABRT when the next transceive starts?!?
            }
        }
    }

    /*override suspend fun transmitLoop() {
        val usbRequests = arrayOfNulls<UsbRequest>(NUM_USB_REQUEST)
        var buffer: ByteBuffer
        var packet: ByteArray

        try {
            // Create, initialize and queue all usb requests:
            for (i in 0 until NUM_USB_REQUEST) {
                // Get a packet from the queue:
                packet = queue!!.poll(1000, TimeUnit.MILLISECONDS)
                if (packet == null || packet.size != getPacketSize()) {
                    Log.e(LOGTAG, "transmitLoop: Queue empty or wrong packet format. Abort.")
                    this.stop()
                    break
                }


                // Wrap the packet in a ByteBuffer object:
                buffer = ByteBuffer.wrap(packet)


                // Initialize the USB Request:
                usbRequests[i] = UsbRequest()
                usbRequests[i]!!.initialize(usbConnection, usbEndpointOUT)
                usbRequests[i]!!.clientData = buffer


                // Queue the request
                if (usbRequests[i]!!.queue(buffer, getPacketSize()) == false) {
                    Log.e(LOGTAG, "receiveLoop: Couldn't queue USB Request.")
                    this.stop()
                    break
                }
            }


            // Run loop until transceiver mode changes...
            while (this.transceiverMode == HACKRF_TRANSCEIVER_MODE_TRANSMIT) {
                // Wait for a request to return. This will block until one of the requests is ready.
                val request = usbConnection!!.requestWait()

                if (request == null) {
                    Log.e(LOGTAG, "transmitLoop: Didn't receive USB Request.")
                    break
                }


                // Make sure we got an UsbRequest for the OUT endpoint!
                if (request.endpoint !== usbEndpointOUT) continue

                // Increment the packetCounter (for statistics)
                transceivePacketCounter++


                // Extract the buffer and return it to the buffer pool:
                buffer = request.clientData as ByteBuffer
                this.returnBufferToBufferPool(buffer.array())


                // Get the next packet from the queue:
                packet = queue!!.poll(1000, TimeUnit.MILLISECONDS)
                if (packet == null || packet.size != getPacketSize()) {
                    Log.e(LOGTAG, "transmitLoop: Queue empty or wrong packet format. Stop transmitting.")
                    break
                }


                // Wrap the packet in a ByteBuffer object:
                buffer = ByteBuffer.wrap(packet)
                request.clientData = buffer


                // Queue the request again...
                if (request.queue(buffer, getPacketSize()) == false) {
                    Log.e(LOGTAG, "transmitLoop: Couldn't queue USB Request.")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(LOGTAG, "transmitLoop: USB Error!")
        } catch (e: InterruptedException) {
            Log.e(LOGTAG, "transmitLoop: Interrup while waiting on queue!")
        }


        // Transmitting is done. Cancel and close all usb requests:
        for (request in usbRequests) {
            request?.cancel()
        }


        // If the transceiverMode is still on TRANSMIT, we stop Transmitting:
        if (this.transceiverMode == HACKRF_TRANSCEIVER_MODE_TRANSMIT) {
            try {
                this.stop()
            } catch (e: Exception) {
                Log.e(LOGTAG, "transmitLoop: Error while stopping TX!")
            }
        }
    }*/

    private fun getPacketSize(): Int {
        // return this.usbEndpointIN.maxPacketSize // <= gives 512 which is way too small
        return packetSize
    }

    override fun getBufferFromBufferPool(): ByteArray {
        var buffer: ByteArray? = bufferPool?.poll()

        // Check if we got a buffer:
        if (buffer == null) {
            buffer = ByteArray(getPacketSize())
        }

        return buffer
    }

    private fun returnBufferToBufferPool(buffer: ByteArray) {
        if (buffer.size == getPacketSize()) {
            // Throw it into the pool (don't care if it's working or not):
            bufferPool?.offer(buffer)
        } else {
            Log.w(LOGTAG, "returnBuffer: Got a buffer with wrong size. Ignore it!")
        }
    }

    @Throws(RfSourceException::class)
    override fun setSampleRate(sampRate: Int, divider: Int): Boolean {
        val byteOut = ByteArrayOutputStream()

        return try {
            byteOut.write(intToByteArray(sampRate))
            byteOut.write(intToByteArray(divider))

            if (sendUsbRequest(
                    UsbConstants.USB_DIR_OUT,
                    HACKRF_VENDOR_REQUEST_SAMPLE_RATE_SET,
                    0,
                    0,
                    byteOut.toByteArray()
                ) != 8
            ) {
                Log.e(LOGTAG, "setSampleRate: USB Transfer failed!")
                throw RfSourceException("USB Transfer failed!")
            }

            true
        } catch (e: IOException) {
            Log.e(LOGTAG, "setSampleRate: Error while converting arguments to byte buffer.")
            false
        }
    }

    @Throws(RfSourceException::class)
    override fun setFrequency(frequency: Long): Boolean {
        val byteOut = ByteArrayOutputStream()
        val mhz = (frequency / 1_000_000L).toInt()
        val hz = (frequency % 1_000_000L).toInt()
        try {
            byteOut.write(intToByteArray(mhz))
            byteOut.write(intToByteArray(hz))
        } catch (e: IOException) {
            Log.e(LOGTAG, "setFrequency: Error while converting arguments to byte buffer.")
            return false
        }

        if (sendUsbRequest(
                UsbConstants.USB_DIR_OUT,
                HACKRF_VENDOR_REQUEST_SET_FREQ,
                0,
                0,
                byteOut.toByteArray()
            ) != 8
        ) {
            Log.e(LOGTAG, "setFrequency: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }

        return true
    }

    override fun computeBasebandFilterBandwidth(sampRate: Int): Int {
        var bandwidth = 1750000
        val supportedBandwidthValues = intArrayOf(
            1750000, 2500000, 3500000, 5000000, 5500000,
            6000000, 7000000, 8000000, 9000000, 10000000,
            12000000, 14000000, 15000000, 20000000, 24000000,
            28000000
        )

        for (candidate in supportedBandwidthValues) {
            if (sampRate < candidate) break
            bandwidth = candidate
        }

        return bandwidth
    }

    @Throws(RfSourceException::class)
    override fun setBasebandFilterBandwidth(bandwidth: Int): Boolean {
        if (sendUsbRequest(
                UsbConstants.USB_DIR_OUT,
                HACKRF_VENDOR_REQUEST_BASEBAND_FILTER_BANDWIDTH_SET,
                bandwidth and 0xffff,
                (bandwidth shr 16) and 0xffff,
                null
            ) != 0
        ) {
            Log.e(LOGTAG, "setBasebandFilterBandwidth: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }

        return true
    }

    @Throws(RfSourceException::class)
    override fun setRxVGAGain(gain: Int): Boolean {
        val retVal = ByteArray(1)

        if (gain > 62) {
            Log.e(LOGTAG, "setRxVGAGain: RX VGA Gain must be within 0-62!")
            return false
        }

        // Must be in steps of two!
        val adjustedGain = if (gain % 2 != 0) gain - (gain % 2) else gain

        if (sendUsbRequest(
                UsbConstants.USB_DIR_IN,
                HACKRF_VENDOR_REQUEST_SET_VGA_GAIN,
                0,
                adjustedGain,
                retVal
            ) != 1
        ) {
            Log.e(LOGTAG, "setRxVGAGain: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }

        if (retVal[0] == 0.toByte()) {
            Log.e(LOGTAG, "setRxVGAGain: HackRF returned with an error!")
            return false
        }

        return true
    }

    //Not implemented
    @Throws(RfSourceException::class)
    override fun setTxVGAGain(gain: Int): Boolean {
        val retVal = ByteArray(1)

        if (gain > 47) {
            Log.e(LOGTAG, "setTxVGAGain: TX VGA Gain must be within 0-47!")
            return false
        }

        if (sendUsbRequest(
                UsbConstants.USB_DIR_IN,
                HACKRF_VENDOR_REQUEST_SET_TXVGA_GAIN,
                0,
                gain,
                retVal
            ) != 1
        ) {
            Log.e(LOGTAG, "setTxVGAGain: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }

        if (retVal[0] == 0.toByte()) {
            Log.e(LOGTAG, "setTxVGAGain: HackRF returned with an error!")
            return false
        }

        return true
    }

    @Throws(RfSourceException::class)
    override fun setRxLNAGain(gain: Int): Boolean {
        val retVal = ByteArray(1)

        if (gain > 40) {
            Log.e(LOGTAG, "setRxLNAGain: RX LNA Gain must be within 0-40!")
            return false
        }

        // Must be in steps of 8!
        var adjustedGain = gain // Use a mutable variable
        if (adjustedGain % 8 != 0) {
            adjustedGain -= adjustedGain % 8
        }

        if (sendUsbRequest(
                UsbConstants.USB_DIR_IN,
                HACKRF_VENDOR_REQUEST_SET_LNA_GAIN,
                0,
                adjustedGain,
                retVal
            ) != 1
        ) {
            Log.e(LOGTAG, "setRxLNAGain: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }

        if (retVal[0] == 0.toByte()) {
            Log.e(LOGTAG, "setRxLNAGain: HackRF returned with an error!")
            return false
        }

        return true
    }

    @Throws(RfSourceException::class)
    fun setFrequencyExplicit(ifFrequency: Long, loFrequency: Long, rfPath: Int): Boolean {
        val byteOut = ByteArrayOutputStream()

        // Check range of IF Frequency:
        if (ifFrequency < 2_150_000_000L || ifFrequency > 2_750_000_000L) {
            Log.e(LOGTAG, "setFrequencyExplicit: IF Frequency must be in [2150000000; 2750000000]!")
            return false
        }
        if (rfPath != RF_PATH_FILTER_BYPASS && (loFrequency < 84_375_000L || loFrequency > 5_400_000_000L)) {
            Log.e(LOGTAG, "setFrequencyExplicit: LO Frequency must be in [84375000; 5400000000]!")
            return false
        }
        // Check if path is in the valid range:
        if (rfPath < 0 || rfPath > 2) {
            Log.e(LOGTAG, "setFrequencyExplicit: Invalid value for rf_path!")
            return false
        }
        Log.d(LOGTAG, "Tune HackRF to IF: $ifFrequency Hz; LO: $loFrequency Hz...")
        try {
            byteOut.write(longToByteArray(ifFrequency))
            byteOut.write(longToByteArray(loFrequency))
            byteOut.write(rfPath)
        } catch (e: IOException) {
            Log.e(LOGTAG, "setFrequencyExplicit: Error while converting arguments to byte buffer.")
            return false
        }
        if (sendUsbRequest(
                UsbConstants.USB_DIR_OUT,
                HACKRF_VENDOR_REQUEST_SET_FREQ_EXPLICIT,
                0,
                0,
                byteOut.toByteArray()
            ) != 17
        ) {
            Log.e(LOGTAG, "setFrequencyExplicit: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }
        return true
    }

    @Throws(RfSourceException::class)
    override fun setAmp(enable: Boolean): Boolean {
        if (sendUsbRequest(
                UsbConstants.USB_DIR_OUT,
                HACKRF_VENDOR_REQUEST_AMP_ENABLE,
                if (enable) 1 else 0,
                0,
                null
            ) != 0
        ) {
            Log.e(LOGTAG, "setAmp: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }

        return true
    }

    @Throws(RfSourceException::class)
    override fun setAntennaPower(enable: Boolean): Boolean {
        // The Jawbreaker doesn't support this command!
        if (getBoardID().toInt() == 1) { // == Jawbreaker
            Log.w(LOGTAG, "setAntennaPower: Antenna Power is not supported for HackRF Jawbreaker. Ignore.")
            return false
        }
        // The rad1o doesn't support this command!
        if (getBoardID().toInt() == 3) { // == rad1o
            Log.w(LOGTAG, "setAntennaPower: Antenna Power is not supported for rad1o. Ignore.")
            return false
        }
        if (sendUsbRequest(
                UsbConstants.USB_DIR_OUT,
                HACKRF_VENDOR_REQUEST_ANTENNA_ENABLE,
                if (enable) 1 else 0,
                0,
                null
            ) != 0
        ) {
            Log.e(LOGTAG, "setAntennaPower: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }

        return true
    }

    @Throws(RfSourceException::class)
    fun getBoardID(): Byte {
        val buffer = ByteArray(1)
        if (sendUsbRequest(UsbConstants.USB_DIR_IN, HACKRF_VENDOR_REQUEST_BOARD_ID_READ, 0, 0, buffer) != 1) {
            Log.e(LOGTAG, "getBoardID: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }
        return buffer[0]
    }

    fun convertBoardIdToString(boardID: Int): String {
        return when (boardID) {
            0 -> "Jellybean"
            1 -> "Jawbreaker"
            2 -> "HackRF One"
            3 -> "rad1o"
            else -> "INVALID BOARD ID"
        }
    }

    @Throws(RfSourceException::class)
    fun getVersionString(): String {
        val buffer = ByteArray(255)
        val len = this.sendUsbRequest(UsbConstants.USB_DIR_IN, HACKRF_VENDOR_REQUEST_VERSION_STRING_READ, 0, 0, buffer)
        if (len < 1) {
            Log.e(LOGTAG, "getVersionString: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }
        return String(buffer)
    }

    @Throws(RfSourceException::class)
    fun getPartIdAndSerialNo(): IntArray {
        val buffer = ByteArray(8 + 16)
        val ret = IntArray(2 + 4)
        if (this.sendUsbRequest(UsbConstants.USB_DIR_IN, HACKRF_VENDOR_REQUEST_BOARD_PARTID_SERIALNO_READ, 0, 0, buffer) != 8 + 16) {
            Log.e(LOGTAG, "getPartIdAndSerialNo: USB Transfer failed!")
            throw RfSourceException("USB Transfer failed!")
        }
        for (i in 0 until 6) {
            ret[i] = this.byteArrayToInt(buffer, 4 * i)
        }
        return ret
    }

    private fun getTransceiverPacketCounter(): Long {
        return transceivePacketCounter
    }

    private fun getTransceivingTime(): Long {
        return if (transceiveStartTime == 0L) 0 else System.currentTimeMillis() - transceiveStartTime
    }

    fun getAverageTransceiveRate(): Long {
        val transTime = getTransceivingTime() / 1000  // Transfer Time in seconds
        return if (transTime == 0L) 0 else getTransceiverPacketCounter() * getPacketSize() / transTime
    }

    fun getTransceiverMode(): Int {
        return transceiverMode
    }

    private fun byteArrayToInt(b: ByteArray, offset: Int): Int {
        return (b[offset + 0].toInt() and 0xFF) or
                (b[offset + 1].toInt() and 0xFF shl 8) or
                (b[offset + 2].toInt() and 0xFF shl 16) or
                (b[offset + 3].toInt() and 0xFF shl 24)
    }

    private fun byteArrayToLong(b: ByteArray, offset: Int): Long {
        return (b[offset + 0].toLong() and 0xFF) or
                (b[offset + 1].toLong() and 0xFF shl 8) or
                (b[offset + 2].toLong() and 0xFF shl 16) or
                (b[offset + 3].toLong() and 0xFF shl 24) or
                (b[offset + 4].toLong() and 0xFF shl 32) or
                (b[offset + 5].toLong() and 0xFF shl 40) or
                (b[offset + 6].toLong() and 0xFF shl 48) or
                (b[offset + 7].toLong() and 0xFF shl 56)
    }

    private fun intToByteArray(i: Int): ByteArray {
        return ByteArray(4).apply {
            this[0] = (i and 0xff).toByte()
            this[1] = (i shr 8 and 0xff).toByte()
            this[2] = (i shr 16 and 0xff).toByte()
            this[3] = (i shr 24 and 0xff).toByte()
        }
    }

    private fun longToByteArray(i: Long): ByteArray {
        return ByteArray(8).apply {
            this[0] = (i and 0xff).toByte()
            this[1] = (i shr 8 and 0xff).toByte()
            this[2] = (i shr 16 and 0xff).toByte()
            this[3] = (i shr 24 and 0xff).toByte()
            this[4] = (i shr 32 and 0xff).toByte()
            this[5] = (i shr 40 and 0xff).toByte()
            this[6] = (i shr 48 and 0xff).toByte()
            this[7] = (i shr 56 and 0xff).toByte()
        }
    }

    override fun setRxMixerAGC(agcMixerEnable: Boolean): Boolean {
        //Does not do anything with HackRF
        return true
    }

    override fun setRxLNAAGC(agcLnaEnable: Boolean): Boolean {
        //Does not do anything with HackRF
        return true
    }

    override fun setRxMixerGain(gain: Int): Boolean {
        //Does not do anything with HackRF
        return true
    }

    override fun setPacking(packingEnable: Boolean): Boolean {
        //Does not do anything with HackRF
        return true
    }

    override fun setRawMode(rawModeEnable: Boolean): Boolean {
        //Does not do anything with HackRF
        return true
    }

    override fun setPacketSize(pSize: Int): Boolean {
        packetSize = pSize
        return true
    }

}
