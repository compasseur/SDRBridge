package com.compasseur.sdrbridge.sources

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.util.Log
import com.compasseur.sdrbridge.LogParameters
import com.compasseur.sdrbridge.rfsource.RfSource
import com.compasseur.sdrbridge.rfsource.RfSourceCallbackInterface
import com.compasseur.sdrbridge.rfsource.RfSourceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.ArrayBlockingQueue
/**
 * <h1>Airspy USB Library for Android</h1>
 *
 * Module:      Airspy
 * Description: The Airspy class represents the Airspy device and
 *              acts as abstraction layer that manages the USB
 *              communication between the device and the application.
 *
 * Copyright (C) 2024 Grégoire de Courtivron
 * Copyright (C) 2015 Dennis Mantz
 * based on code of libairspy [https://github.com/airspy/host/tree/master/libairspy]:
 *     Copyright (C) 2015 Dennis Mantz
 *     Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
 *     Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
 *     Copyright (c) 2014, Youssef Touil <youssef@airspy.com>
 *     Copyright (c) 2014, Benjamin Vernoux <bvernoux@airspy.com>
 *     Copyright (c) 2015, Ian Gilmour <ian@sdrsharp.com>
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
class Airspy
@Throws(RfSourceException::class) constructor(usbManager: UsbManager, usbDevice: UsbDevice, queueSize: Int) : RfSource {

    // Attributes to hold the USB related objects:
    private var usbInterface: UsbInterface? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbEndpointIN: UsbEndpoint? = null
    private var usbEndpointOUT: UsbEndpoint? = null

    // Receiver Modes:
    val AIRSPY_RECEIVER_MODE_OFF: Int = 0
    val AIRSPY_RECEIVER_MODE_RECEIVE: Int = 1

    private var receiverMode = AIRSPY_RECEIVER_MODE_OFF // current mode of the Airspy
    private var sampleType = AIRSPY_SAMPLE_INT16_IQ //AIRSPY_SAMPLE_UINT16_REAL // Type of the samples that should be delivered
    private var packingEnabled = false // is packing currently enabled in the Airspy?
    private var rawMode = true // if true, the conversion thread is bypassed and the
                                // user will access the usbQueue directly

    private var queue: ArrayBlockingQueue<ByteArray>? = null // queue that buffers samples received from the Airspy
    private var bufferPool: ArrayBlockingQueue<ByteArray>? = null // queue that holds spare buffers which can be
                                                                  // reused while receiving  samples from the Airspy

    //private var conversionQueueInt16: ArrayBlockingQueue<ShortArray>? = null // queue that buffers samples that were processed by
    //private var conversionBufferPoolInt16: ArrayBlockingQueue<ShortArray>? = null // queue that buffers samples that were processed by
    //private val conversionQueueSize = 20

    // startTime (in ms since 1970) and packetCounter for statistics:
    private var receiveStartTime: Long = 0
    private var receivePacketCounter: Long = 0

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var receiveJob: Job? = null
    private var processJob: Job? = null
    //private var int16Converter: AirspyInt16Converter? = null

    //private val logTag = "airspy_android"
    private val logTag: String = "airspy_android"

    private var packetSize = 1024 * 16 // Buffer Size of each UsbRequest

    companion object {
        // Sample types:
         const val AIRSPY_SAMPLE_FLOAT32_IQ: Int = 0 // 2 * 32bit float per sample
         const val AIRSPY_SAMPLE_FLOAT32_REAL: Int = 1 // 1 * 32bit float per sample
         const val AIRSPY_SAMPLE_INT16_IQ: Int = 2 // 2 * 16bit int per sample
         const val AIRSPY_SAMPLE_INT16_REAL: Int = 3 // 1 * 16bit int per sample
         const val AIRSPY_SAMPLE_UINT16_REAL: Int = 4 // 1 * 16bit unsigned int per sample (raw)

        // USB Vendor Requests (from airspy_commands.h)
        private const val AIRSPY_INVALID: Int = 0
        private const val AIRSPY_RECEIVER_MODE: Int = 1
        private const val AIRSPY_SI5351C_WRITE: Int = 2
        private const val AIRSPY_SI5351C_READ: Int = 3
        private const val AIRSPY_R820T_WRITE: Int = 4
        private const val AIRSPY_R820T_READ: Int = 5
        private const val AIRSPY_SPIFLASH_ERASE: Int = 6
        private const val AIRSPY_SPIFLASH_WRITE: Int = 7
        private const val AIRSPY_SPIFLASH_READ: Int = 8
        private const val AIRSPY_BOARD_ID_READ: Int = 9
        private const val AIRSPY_VERSION_STRING_READ: Int = 10
        private const val AIRSPY_BOARD_PARTID_SERIALNO_READ: Int = 11
        private const val AIRSPY_SET_SAMPLERATE: Int = 12
        private const val AIRSPY_SET_FREQ: Int = 13
        private const val AIRSPY_SET_LNA_GAIN: Int = 14
        private const val AIRSPY_SET_MIXER_GAIN: Int = 15
        private const val AIRSPY_SET_VGA_GAIN: Int = 16
        private const val AIRSPY_SET_LNA_AGC: Int = 17
        private const val AIRSPY_SET_MIXER_AGC: Int = 18
        private const val AIRSPY_MS_VENDOR_CMD: Int = 19
        private const val AIRSPY_SET_RF_BIAS_CMD: Int = 20
        private const val AIRSPY_GPIO_WRITE: Int = 21
        private const val AIRSPY_GPIO_READ: Int = 22
        private const val AIRSPY_GPIODIR_WRITE: Int = 23
        private const val AIRSPY_GPIODIR_READ: Int = 24
        private const val AIRSPY_GET_SAMPLERATES: Int = 25
        private const val AIRSPY_SET_PACKING: Int = 26

        // Some Constants:

        private const val numUsbRequests: Int = 4 //16 //16 // Number of parallel UsbRequests

        //private val packetSize = 192000 //96000 192000 //1024 * 16 // Buffer Size of each UsbRequest
    }

    init {
        try {
            // Extract interface from the device:
            this.usbInterface = usbDevice.getInterface(0)

            // For detailed trouble shooting: Read out interface information of the device:
            Log.i(
                logTag, "constructor: [interface 0] interface protocol: " + usbInterface!!.interfaceProtocol
                        + " subclass: " + usbInterface!!.interfaceSubclass
            )
            Log.i(logTag, "constructor: [interface 0] interface class: " + usbInterface!!.interfaceClass)
            Log.i(logTag, "constructor: [interface 0] endpoint count: " + usbInterface!!.endpointCount)

            // Extract the endpoint from the device:
            this.usbEndpointIN = usbInterface!!.getEndpoint(0)
            this.usbEndpointOUT = usbInterface!!.getEndpoint(1)

            // For detailed trouble shooting: Read out endpoint information of the interface:
            Log.i(
                logTag, "constructor:     [endpoint 0 (IN)] address: " + usbEndpointIN!!.address
                        + " attributes: " + usbEndpointIN!!.attributes + " direction: " + usbEndpointIN!!.direction
                        + " max_packet_size: " + usbEndpointIN!!.maxPacketSize
            )
            Log.i(
                logTag, "constructor:     [endpoint 1 (OUT)] address: " + usbEndpointOUT!!.address
                        + " attributes: " + usbEndpointOUT!!.attributes + " direction: " + usbEndpointOUT!!.direction
                        + " max_packet_size: " + usbEndpointOUT!!.maxPacketSize
            )

            // Open the device:
            this.usbConnection = usbManager.openDevice(usbDevice)

            if (this.usbConnection == null) {
                Log.e(logTag, "constructor: Couldn't open Airspy USB Device: openDevice() returned null!")
                throw (RfSourceException("Couldn't open Airspy USB Device! (device is gone)"))
            }
        } catch (e: Exception) {
            Log.e(logTag, "constructor: Couldn't open Airspy USB Device: " + e.message)
            throw (RfSourceException("Error: Couldn't open Airspy USB Device!"))
        }
        this.queue = ArrayBlockingQueue(queueSize / packetSize)
        this.bufferPool = ArrayBlockingQueue(queueSize / packetSize)
    }

    override fun initializeRfSource(context: Context, callbackInterface: RfSourceCallbackInterface, device: UsbDevice, usbManager: UsbManager, queueSize: Int): Boolean {
        return try {
            val airSpy = Airspy(usbManager, device, queueSize)
            val version = getVersionString()
            LogParameters.appendLine("$logTag: Airspy version: $version")
            callbackInterface.onRfSourceReady(airSpy)
            return true
        } catch (e: Exception) {
            Log.i(logTag, e.toString())
            false
        }
    }

    @Throws(RfSourceException::class)
    override fun startRX(): ArrayBlockingQueue<ByteArray> {

        this.queue?.clear()

        /*if (!rawMode){
            when (sampleType){
                AIRSPY_SAMPLE_FLOAT32_IQ -> {}
                AIRSPY_SAMPLE_FLOAT32_REAL -> {}
                AIRSPY_SAMPLE_UINT16_REAL -> {}
                AIRSPY_SAMPLE_INT16_REAL -> {}
                AIRSPY_SAMPLE_INT16_IQ -> {
                    conversionQueueInt16 = ArrayBlockingQueue<ShortArray>(conversionQueueSize)
                    for (i in 0 until conversionQueueSize) conversionBufferPoolInt16!!.offer(ShortArray(getUsbPacketSize() / 2))
                    int16Converter = AirspyInt16Converter(sampleType, packingEnabled, queue!!, bufferPool!!, conversionQueueInt16!!, conversionBufferPoolInt16!!)
                    int16Converter?.start()
                }
            }
        }*/


        //setSampleType(sampleType)
        //setRawMode(true)
        // Signal the Airspy Device to start receiving:
        setReceiverMode(AIRSPY_RECEIVER_MODE_RECEIVE)

        // Start the coroutine to queue the received samples
        receiveJob = coroutineScope.launch {
            receiveLoop()
        }

        // Reset the packet counter and start time for statistics:
        this.receiveStartTime = System.currentTimeMillis()
        this.receivePacketCounter = 0

        return this.queue!!
    }

    /*override suspend fun receiveLoop() {
        val usbRequests = arrayOfNulls<UsbRequest>(numUsbRequests)
        var buffer: ByteBuffer

        try {
            // Initialize and queue all USB requests
            for (i in 0 until numUsbRequests) {
                buffer = ByteBuffer.wrap(this.getBufferFromBufferPool())
                usbRequests[i] = UsbRequest().apply {
                    initialize(usbConnection, usbEndpointIN)
                    clientData = buffer
                }
                usbRequests[i]?.queue(buffer, packetSize) ?: break
            }

            while (this.receiverMode == AIRSPY_RECEIVER_MODE_RECEIVE) {
                val request =  usbConnection?.requestWait()
                if (request == null || request.endpoint != usbEndpointIN) break

                buffer = request.clientData as ByteBuffer
                this.receivePacketCounter++

                //this.queue?.offer(buffer.array()) ?: break
                queue?.offer(buffer.array()) ?: break

                buffer = ByteBuffer.wrap(this.getBufferFromBufferPool())
                request.clientData = buffer
                request.queue(buffer, packetSize) ?: break
            }

        } catch (e: RfSourceException) {
            Log.e(logTag, "receiveLoop: USB Error!")
        } finally {
            // Clean up USB requests
            usbRequests.forEach { it?.close() }
        }
    }*/

    override suspend fun receiveLoop() {
        val usbRequests = arrayOfNulls<UsbRequest>(numUsbRequests)
        var buffer: ByteBuffer

        try {
            // Create, initialize, and queue all USB requests
            for (i in 0 until numUsbRequests) {
                buffer = ByteBuffer.wrap(this.getBufferFromBufferPool())

                // Initialize the USB request
                usbRequests[i] = UsbRequest().apply {
                    initialize(usbConnection, usbEndpointIN)
                    clientData = buffer
                }

                // Queue the request
                if (usbRequests[i]?.queue(buffer, packetSize) == false) {
                    Log.e(logTag, "receiveLoop: Couldn't queue USB Request.")
                    this.stop()
                    return
                }
            }

            // Run loop until receiver mode changes
            while (this.receiverMode == AIRSPY_RECEIVER_MODE_RECEIVE) {
                // Wait for a request to return. This will block until one of the requests is ready
                val request = usbConnection?.requestWait()
                if (request == null) {
                    Log.e(logTag, "receiveLoop: Didn't receive USB Request.")
                    break
                }

                // Make sure we got a UsbRequest for the IN endpoint
                if (request.endpoint != usbEndpointIN) {
                    continue
                }

                // Extract the buffer
                buffer = request.clientData as ByteBuffer
                this.receivePacketCounter++

                // Put the received samples into the queue
                if (!queue?.offer(buffer.array())!!) {
                    Log.e(logTag, "receiveLoop: Queue is full. Stopping receive!")
                    break
                }

                // Get a fresh ByteBuffer from the buffer pool
                buffer = ByteBuffer.wrap(this.getBufferFromBufferPool())
                request.clientData = buffer

                // Requeue the request
                if (request.queue(buffer, packetSize) == false) {
                    Log.e(logTag, "receiveLoop: Couldn't queue USB Request.")
                    break
                }
            }
        } catch (e: RfSourceException) {
            Log.e(logTag, "receiveLoop: USB Error!")
        } finally {
            // Clean up USB requests
            usbRequests.forEach { it?.cancel() }

            // If the receiver mode is still on RECEIVE, stop receiving
            if (this.receiverMode == AIRSPY_RECEIVER_MODE_RECEIVE) {
                try {
                    this.stop()
                } catch (e: RfSourceException) {
                    Log.e(logTag, "receiveLoop: Error while stopping RX!")
                }
            }
        }
    }


    private fun getBufferFromBufferPool(): ByteArray {
        var buffer: ByteArray? = bufferPool?.poll()

        // Check if we got a buffer:
        if (buffer == null) {
            buffer = ByteArray(getUsbPacketSize())
        }
        return buffer
    }

    @Throws(RfSourceException::class)
    override fun stop() {
        // Signal the HackRF Device to turn off the transceiver
        this.setReceiverMode(AIRSPY_RECEIVER_MODE_OFF)
        receiveJob?.cancel()
        receiveJob = null
        processJob?.cancel()
        processJob = null
    }

    private fun getUsbPacketSize(): Int {
        return if (rawMode || !packingEnabled) packetSize
        else packetSize * 4 / 3
    }

    private fun getReceiverPacketCounter(): Long {
        return this.receivePacketCounter
    }

    private fun getReceivingTime(): Long {
        if (this.receiveStartTime == 0L) return 0
        return System.currentTimeMillis() - this.receiveStartTime
    }

    private fun getAverageReceiveRate(): Long {
        val transTime = this.getReceivingTime() / 1000 // Transfer Time in seconds
        if (transTime == 0L) return 0
        return this.getReceiverPacketCounter() * this.getUsbPacketSize() / transTime
    }

    private fun getReceiverMode(): Int {
        return receiverMode
    }

    private fun byteArrayToInt(b: ByteArray, offset: Int): Int {
        return b[offset + 0].toInt() and 0xFF or ((b[offset + 1].toInt() and 0xFF) shl 8) or (
                (b[offset + 2].toInt() and 0xFF) shl 16) or ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun byteArrayToLong(b: ByteArray, offset: Int): Long {
        return (b[offset + 0].toInt() and 0xFF or ((b[offset + 1].toInt() and 0xFF) shl 8) or ((b[offset + 2].toInt() and 0xFF) shl 16) or (
                (b[offset + 3].toInt() and 0xFF) shl 24) or ((b[offset + 4].toInt() and 0xFF) shl 32) or ((b[offset + 5].toInt() and 0xFF) shl 40) or (
                (b[offset + 6].toInt() and 0xFF) shl 48) or ((b[offset + 7].toInt() and 0xFF) shl 56)).toLong()
    }

    private fun intToByteArray(i: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (i and 0xff).toByte()
        b[1] = ((i shr 8) and 0xff).toByte()
        b[2] = ((i shr 16) and 0xff).toByte()
        b[3] = ((i shr 24) and 0xff).toByte()
        return b
    }

    private fun longToByteArray(i: Long): ByteArray {
        val b = ByteArray(8)
        b[0] = (i and 0xffL).toByte()
        b[1] = ((i shr 8) and 0xffL).toByte()
        b[2] = ((i shr 16) and 0xffL).toByte()
        b[3] = ((i shr 24) and 0xffL).toByte()
        b[4] = ((i shr 32) and 0xffL).toByte()
        b[5] = ((i shr 40) and 0xffL).toByte()
        b[6] = ((i shr 48) and 0xffL).toByte()
        b[7] = ((i shr 56) and 0xffL).toByte()
        return b
    }

    private fun setSampleType(sampleType: Int): Boolean {
        if (receiverMode != AIRSPY_RECEIVER_MODE_OFF) {
            Log.e(logTag, "setSampleType: Airspy is not in receiver mode OFF. Cannot change sample type!")
            return false
        }

        if (sampleType < 0 || sampleType > 4) {
            Log.e(logTag, "setSampleType: Not a valid sample type: $sampleType")
            return false
        }

        this.sampleType = sampleType
        return true
    }

    override fun setRawMode(enabled: Boolean): Boolean {
        if (receiverMode != AIRSPY_RECEIVER_MODE_OFF) {
            Log.e(logTag, "setRawMode: Airspy is not in receiver mode OFF. Cannot change rawMode!")
            return false
        }
        this.rawMode = enabled
        return true
    }

    override fun setPacketSize(pSize: Int): Boolean {
        packetSize = pSize
        Log.e(logTag, "setPacketSize: set to $pSize")
        return true
    }

    private fun getRawMode(): Boolean {
        return rawMode
    }

    /*@Throws(rfSourceException::class)
    override fun sendUsbRequest(endpoint: Int, request: Int, value: Int, index: Int, buffer: ByteArray?): Int {
        //var len = 0
        // Determine the length of the buffer:
        //if (buffer != null) len = buffer.size
        // Determine the length of the buffer:
        var len = buffer?.size ?: 0

        try {
// Ensure usbConnection is not null and is still active
            if (usbConnection == null) {
                Log.e(LOGTAG, "USB connection is null. Device may have been disconnected.")
                throw rfSourceException("USB connection is null.")
            }

            // Claim the usb interface
            if (!usbConnection!!.claimInterface(this.usbInterface, true)) {
                Log.e(LOGTAG, "Couldn't claim Airspy USB Interface!")
                throw (rfSourceException("Couldn't claim Airspy USB Interface!"))
            }

            // Send Board ID Read request
            len = usbConnection!!.controlTransfer(
                endpoint or UsbConstants.USB_TYPE_VENDOR,  // Request Type
                request,  // Request
                value,  // Value (unused)
                index,  // Index (unused)
                buffer,  // Buffer
                len,  // Length
                0 // Timeout
            )
        } catch (e: Exception) {
            Log.e(LOGTAG, "Error during USB request: ${e.message}", e)
            return -1
            //throw rfSourceException("Error during USB request: ${e.message}")
        } finally {
            // Release usb interface
            usbConnection!!.releaseInterface(this.usbInterface)
        }
        return len
    }*/

    @Throws(RfSourceException::class)
    override fun sendUsbRequest(endpoint: Int, request: Int, value: Int, index: Int, buffer: ByteArray?): Int {
        // Determine the length of the buffer
        val len = buffer?.size ?: 0

        // Check for usbConnection nullity
        val connection = usbConnection ?: throw RfSourceException("USB connection is null!")

        try {
            // Claim the USB interface
            if (!connection.claimInterface(usbInterface, true)) {
                Log.e(logTag, "Couldn't claim HackRF USB Interface!")
                return -1
                //throw rfSourceException("Couldn't claim HackRF USB Interface!")
            }
        }catch (e: Exception){
            return -1
        }

            // Send request
            val transferredLength = connection.controlTransfer(
                endpoint or UsbConstants.USB_TYPE_VENDOR,  // Request Type
                request,                                    // Request
                value,                                      // Value (unused)
                index,                                      // Index (unused)
                buffer,                                     // Buffer
                len,                                        // Length
                0                                           // Timeout
            )

            // Release USB interface
            connection.releaseInterface(usbInterface)

        return transferredLength
    }

    @Throws(RfSourceException::class)
    private fun getBoardID(): Byte {
        val buffer = ByteArray(1)

        if (this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_BOARD_ID_READ, 0, 0, buffer) != 1) {
            Log.e(logTag, "getBoardID: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        return buffer[0]
    }

    private fun convertBoardIdToString(boardID: Int): String {
        return when (boardID) {
            0 -> "AIRSPY"
            else -> "INVALID BOARD ID"
        }
    }

    @Throws(RfSourceException::class)
    private fun getVersionString(): String {
        val buffer = ByteArray(255)
        var len = 0

        len = this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_VERSION_STRING_READ, 0, 0, buffer)

        if (len < 1) {
            Log.e(logTag, "getVersionString: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        return String(buffer)
    }

    @Throws(RfSourceException::class)
    fun getPartIdAndSerialNo(): IntArray {
        val buffer = ByteArray(8 + 16)
        val ret = IntArray(2 + 4)

        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_IN, AIRSPY_BOARD_PARTID_SERIALNO_READ,
                0, 0, buffer
            ) != 8 + 16
        ) {
            Log.e(logTag, "getPartIdAndSerialNo: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        for (i in 0..5) {
            ret[i] = this.byteArrayToInt(buffer, 4 * i)
        }

        return ret
    }

    @Throws(RfSourceException::class)
    fun getSampleRates(): IntArray {
        var buffer = ByteArray(4)
        val rates: IntArray
        var len = 0

        // First read the number of supported sample rates:
        len = this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_GET_SAMPLERATES, 0, 0, buffer)

        if (len < buffer.size) {
            Log.e(logTag, "getSampleRates: USB Transfer failed (reading count)!")
            throw (RfSourceException("USB Transfer failed!"))
        }
        val count = byteArrayToInt(buffer, 0)
        Log.d(logTag, "getSampleRates: Airspy supports $count different sample rates!")

        // Now read the actual sample rates:
        buffer = ByteArray(count * 4) // every rate is stored in a 32bit int
        rates = IntArray(count)
        len = this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_GET_SAMPLERATES, 0, count, buffer)

        if (len < buffer.size) {
            Log.e(logTag, "getSampleRates: USB Transfer failed (reading rates)!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        for (i in rates.indices) {
            rates[i] = byteArrayToInt(buffer, i * 4)
        }

        return rates
    }

    @Throws(RfSourceException::class)
    override fun setSampleRate(sampRate: Int, divider: Int): Boolean {
        val sampRateIndex = if (sampRate <= 4500000) 1 else 0

        val retVal = ByteArray(1)

        val len = this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_SET_SAMPLERATE, 0, sampRateIndex, retVal)

        if (len != 1) {
            Log.e(logTag, "setSampleRate: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        if (retVal[0] < 0) {
            Log.e(logTag, "setSampleRate: Airspy returned with an error!")
            return false
        }

        return true
    }

    @Throws(RfSourceException::class)
    override fun setRxMixerGain(gain: Int): Boolean {
        val retVal = ByteArray(1)

        if (gain > 15 || gain < 0) {
            Log.e(logTag, "setMixerGain: Mixer gain must be within 0-15!")
            return false
        }

        if (this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_SET_MIXER_GAIN, 0, gain, retVal) != 1) {
            Log.e(logTag, "setMixerGain: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        if (retVal[0] < 0) {
            Log.e(logTag, "setMixerGain: Airspy returned with an error!")
            return false
        }

        return true
    }

    @Throws(RfSourceException::class)
    override fun setRxVGAGain(gain: Int): Boolean {
        val retVal = ByteArray(1)

        if (gain > 15 || gain < 0) {
            Log.e(logTag, "setVGAGain: VGA gain must be within 0-15!")
            return false
        }

        if (this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_SET_VGA_GAIN, 0, gain, retVal) != 1) {
            Log.e(logTag, "setVGAGain: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        if (retVal[0] < 0) {
            Log.e(logTag, "setVGAGain: Airspy returned with an error!")
            return false
        }

        return true
    }

    @Throws(RfSourceException::class)
    override fun setRxLNAGain(gain: Int): Boolean {
        val retVal = ByteArray(1)

        if (gain > 14 || gain < 0) {
            Log.e(logTag, "setLNAGain: LNA gain must be within 0-14!")
            return false
        }

        if (this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_SET_LNA_GAIN, 0, gain, retVal) != 1) {
            Log.e(logTag, "setLNAGain: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        if (retVal[0] < 0) {
            Log.e(logTag, "setLNAGain: Airspy returned with an error!")
            return false
        }

        return true
    }

    @Throws(RfSourceException::class)
    override fun setRxLNAAGC(agcLnaEnable: Boolean): Boolean {
        val retVal = ByteArray(1)

        if (this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_SET_LNA_AGC, 0, if (agcLnaEnable) 1 else 0, retVal) != 1) {
            Log.e(logTag, "setLNAAutomaticGainControl: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        if (retVal[0] < 0) {
            Log.e(logTag, "setLNAAutomaticGainControl: Airspy returned with an error!")
            return false
        }

        return true
    }

    @Throws(RfSourceException::class)
    override fun setRxMixerAGC(agcMixerEnable: Boolean): Boolean {
        val retVal = ByteArray(1)

        if (this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_SET_MIXER_AGC, 0, if (agcMixerEnable) 1 else 0, retVal) != 1) {
            Log.e(logTag, "setMixerAutomaticGainControl: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        if (retVal[0] < 0) {
            Log.e(logTag, "setMixerAutomaticGainControl: Airspy returned with an error!")
            return false
        }

        return true
    }

    @Throws(RfSourceException::class)
    override fun setPacking(packingEnable: Boolean): Boolean {
        val retVal = ByteArray(1)

        if (receiverMode != AIRSPY_RECEIVER_MODE_OFF) {
            Log.e(logTag, "setPacking: Airspy is not in receiver mode OFF. Cannot change packing setting!")
            return false
        }

        if (this.sendUsbRequest(UsbConstants.USB_DIR_IN, AIRSPY_SET_PACKING, 0, if (packingEnable) 1 else 0, retVal) != 1) {
            Log.e(logTag, "setPacking: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }

        if (retVal[0] < 0) {
            Log.e(logTag, "setPacking: Airspy returned with an error!")
            return false
        }

        this.packingEnabled = packingEnable

        return true
    }

    @Throws(RfSourceException::class)
    override fun setFrequency(frequency: Long): Boolean {
        val freq = intToByteArray(frequency.toInt()) //longToByteArray(frequency)

        Log.d(logTag, "Tune Airspy to " + frequency + "Hz...")

        if (this.sendUsbRequest(UsbConstants.USB_DIR_OUT, AIRSPY_SET_FREQ, 0, 0, freq) != 4) {
            Log.e(logTag, "setFrequency: USB Transfer failed!")
            throw (RfSourceException("USB Transfer failed!"))
        }
        //this.queue?.clear()
        return true
    }

    @Throws(RfSourceException::class)
    fun setReceiverMode(mode: Int): Boolean {
        if (mode != AIRSPY_RECEIVER_MODE_OFF && mode != AIRSPY_RECEIVER_MODE_RECEIVE) {
            Log.e(logTag, "Invalid Receiver Mode: $mode")
            return false
        }

        this.receiverMode = mode

        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_OUT, AIRSPY_RECEIVER_MODE,
                mode, 0, null
            ) != 0
        ) {
            Log.e(logTag, "setReceiverMode: USB Transfer failed!")
            return false
            //throw (rfSourceException("USB Transfer failed!"))
        }

        return true
    }

    /*fun getInt16Queue(): ArrayBlockingQueue<ShortArray>? {
        if (receiverMode != AIRSPY_RECEIVER_MODE_RECEIVE) return null
        return if (sampleType == AIRSPY_SAMPLE_INT16_IQ || sampleType == AIRSPY_SAMPLE_INT16_REAL || sampleType == AIRSPY_SAMPLE_UINT16_REAL) conversionQueueInt16
        else null
    }*/

    private fun getRawQueue(): ArrayBlockingQueue<ByteArray>? {
        if (receiverMode != AIRSPY_RECEIVER_MODE_RECEIVE) return null
        return if (rawMode) queue
        else null
    }

    private fun getRawReturnPoolQueue(): ArrayBlockingQueue<ByteArray>? {
        if (receiverMode != AIRSPY_RECEIVER_MODE_RECEIVE) return null
        return if (rawMode) bufferPool
        else null
    }

    private fun unpackSamples(src: ByteArray, dest: ByteArray, length: Int) {
        if (length % 16 != 0) {
            Log.e(logTag, "convertSamplesFloat: length has to be multiple of 16!")
            return
        }

        if (src.size < 3 * dest.size / 4 || dest.size < length) {
            Log.e(logTag, "convertSamplesFloat: input buffers have invalid length!")
            return
        }

        var i = 0
        var j = 0
        while (i < length) {
            dest[i] = ((src[j + 3].toInt() shl 4) and 0xF0 or ((src[j + 2].toInt() shr 4) and 0x0F)).toByte()
            dest[i + 1] = ((src[j + 3].toInt() shr 4) and 0x0F).toByte()
            dest[i + 2] = src[j + 1]
            dest[i + 3] = (src[j + 2].toInt() and 0x0F).toByte()
            dest[i + 4] = ((src[j].toInt() shl 4) and 0xF0 or ((src[j + 7].toInt() shr 4) and 0x0F)).toByte()
            dest[i + 5] = ((src[j].toInt() shr 4) and 0x0F).toByte()
            dest[i + 6] = src[j + 6]
            dest[i + 7] = (src[j + 7].toInt() and 0x0F).toByte()
            dest[i + 8] = ((src[j + 5].toInt() shl 4) and 0xF0 or ((src[j + 4].toInt() shr 4) and 0x0F)).toByte()
            dest[i + 9] = ((src[j + 5].toInt() shr 4) and 0x0F).toByte()
            dest[i + 10] = src[j + 11]
            dest[i + 11] = (src[j + 4].toInt() and 0x0F).toByte()
            dest[i + 12] = ((src[j + 10].toInt() shl 4) and 0xF0 or ((src[j + 9].toInt() shr 4) and 0x0F)).toByte()
            dest[i + 13] = ((src[j + 10].toInt() shr 4) and 0x0F).toByte()
            dest[i + 14] = src[j + 8]
            dest[i + 15] = (src[j + 9].toInt() and 0x0F).toByte()

            i += 16
            j += 12
        }
    }

    override fun startTX(): ArrayBlockingQueue<ByteArray> {
        //Does not do anything with Airspy
        return ArrayBlockingQueue(0)
    }

    override suspend fun transmitLoop() {
        //Does not do anything with Airspy
    }

    override fun computeBasebandFilterBandwidth(sampRate: Int): Int {
        //Does not do anything with Airspy
        return 0
    }

    override fun setBasebandFilterBandwidth(bandwidth: Int): Boolean {
        //Does not do anything with Airspy
        return true
    }

    override fun setTxVGAGain(gain: Int): Boolean {
        //Does not do anything with Airspy
        return true
    }

    override fun setAmp(enable: Boolean): Boolean {
        //Does not do anything with Airspy
        return true
    }

    override fun setAntennaPower(enable: Boolean): Boolean {
        //Does not do anything with Airspy
        return true
    }
}