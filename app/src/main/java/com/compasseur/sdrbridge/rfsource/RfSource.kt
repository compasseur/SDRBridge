package com.compasseur.sdrbridge.rfsource

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.concurrent.ArrayBlockingQueue

interface RfSource {
        fun initializeRfSource(context: Context, callbackInterface: RfSourceCallbackInterface, device: UsbDevice, usbManager: UsbManager, queueSize: Int): Boolean
        fun setSampleRate(sampRate: Int, divider: Int): Boolean
        fun sendUsbRequest(endpoint: Int, request: Int, value: Int, index: Int, buffer: ByteArray?): Int
        fun startRX(): ArrayBlockingQueue<ByteArray>
        fun startTX(): ArrayBlockingQueue<ByteArray>
        fun stop()
        suspend fun receiveLoop()
        suspend fun transmitLoop()
        fun setFrequency(frequency: Long): Boolean
        fun computeBasebandFilterBandwidth(sampRate: Int): Int
        fun setBasebandFilterBandwidth(bandwidth: Int): Boolean
        fun setRxVGAGain(gain: Int): Boolean
        fun setRxMixerGain(gain: Int): Boolean
        fun setRxMixerAGC(agcMixerEnable: Boolean): Boolean
        fun setTxVGAGain(gain: Int): Boolean
        fun setRxLNAGain(gain: Int): Boolean
        fun setRxLNAAGC(agcLnaEnable: Boolean): Boolean
        fun setAmp(enable: Boolean): Boolean
        fun setAntennaPower(enable: Boolean): Boolean
        fun setPacking(packingEnable: Boolean): Boolean
        fun setRawMode(rawModeEnable: Boolean): Boolean
        fun setPacketSize(pSize: Int): Boolean
        fun getBufferFromBufferPool(): ByteArray

}