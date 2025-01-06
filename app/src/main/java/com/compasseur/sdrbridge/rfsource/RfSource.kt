package com.compasseur.sdrbridge.rfsource

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.concurrent.ArrayBlockingQueue
import kotlin.jvm.Throws

interface RfSource {
        @Throws(RfSourceException::class)
        fun initializeRfSource(context: Context, callbackInterface: RfSourceCallbackInterface, device: UsbDevice, usbManager: UsbManager, queueSize: Int): Boolean
        @Throws(RfSourceException::class)
        fun setSampleRate(sampRate: Int, divider: Int): Boolean
        @Throws(RfSourceException::class)
        fun sendUsbRequest(endpoint: Int, request: Int, value: Int, index: Int, buffer: ByteArray?): Int
        fun startRX(): ArrayBlockingQueue<ByteArray>
        fun startTX(): ArrayBlockingQueue<ByteArray>
        fun stop()
        suspend fun receiveLoop()
        suspend fun transmitLoop()
        @Throws(RfSourceException::class)
        fun setFrequency(frequency: Long): Boolean
        fun computeBasebandFilterBandwidth(sampRate: Int): Int
        @Throws(RfSourceException::class)
        fun setBasebandFilterBandwidth(bandwidth: Int): Boolean
        @Throws(RfSourceException::class)
        fun setRxVGAGain(gain: Int): Boolean
        @Throws(RfSourceException::class)
        fun setRxMixerGain(gain: Int): Boolean
        @Throws(RfSourceException::class)
        fun setRxMixerAGC(agcMixerEnable: Boolean): Boolean
        @Throws(RfSourceException::class)
        fun setTxVGAGain(gain: Int): Boolean
        @Throws(RfSourceException::class)
        fun setRxLNAGain(gain: Int): Boolean
        @Throws(RfSourceException::class)
        fun setRxLNAAGC(agcLnaEnable: Boolean): Boolean
        @Throws(RfSourceException::class)
        fun setAmp(enable: Boolean): Boolean
        @Throws(RfSourceException::class)
        fun setAntennaPower(enable: Boolean): Boolean
        @Throws(RfSourceException::class)
        fun setPacking(packingEnable: Boolean): Boolean
        @Throws(RfSourceException::class)
        fun setRawMode(rawModeEnable: Boolean): Boolean
        @Throws(RfSourceException::class)
        fun setPacketSize(pSize: Int): Boolean

}