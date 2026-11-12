package com.compasseur.sdrbridge.rfsource

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.annotation.Keep
import com.compasseur.sdrbridge.LogParameters
import com.compasseur.sdrbridge.retuneCodeSize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Module:      RtlSdr
 * Description: The RtlSdr class represents an RTL2832U based dongle (R820T, R820T2,
 *              R860, R828D tuners, including the RTL-SDR Blog V4) and acts as
 *              abstraction layer between librtlsdr (native, see cpp/rtlsdr_jni.c)
 *              and the DriverService.
 *              The dongle is opened through UsbManager and its file descriptor is
 *              handed to librtlsdr. Samples are raw 8 bits unsigned IQ, preceded
 *              by the 12 bytes rtl_tcp dongle info header ("RTL0", tuner type,
 *              gain count).
 *
 * Copyright (C) 2024 Grégoire de Courtivron
 * based on rtl_tcp_andro: Copyright (C) 2022 by Signalware Ltd <driver@sdrtouch.com>
 * and librtlsdr: Copyright (C) 2012 by Steve Markgraf <steve@steve-m.de>
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
class RtlSdr(private val usbManager: UsbManager, private val usbDevice: UsbDevice, queueSize: Int) : RfSource {

    private var usbConnection: UsbDeviceConnection? = null
    private var handle = 0L // native rtlsdr_dev_t pointer, 0 when closed

    // Serializes the native control calls (commands from the client app and the initial
    // configuration can come from different coroutines) and protects handle
    private val deviceLock = Any()

    // Receiver Modes:
    val RTLSDR_RECEIVER_MODE_OFF: Int = 0
    val RTLSDR_RECEIVER_MODE_RECEIVE: Int = 1

    @Volatile
    private var receiverMode = RTLSDR_RECEIVER_MODE_OFF

    private var queue: ArrayBlockingQueue<ByteArray>? = null // queue that buffers samples received from the dongle
    private var bufferPool: ArrayBlockingQueue<ByteArray>? = null // spare buffers reused while receiving

    private var receiveThread: Thread? = null

    //Current settings, restored after a tuner re-initialization (see recoverSignal())
    @Volatile private var currentFrequency = 0L
    @Volatile private var currentSampleRate = 0
    @Volatile private var currentBandwidth = 0
    @Volatile private var currentGainManual = false
    @Volatile private var currentGain = 0

    //Flat signal detection and recovery, see watchForFlatSignal()
    @Volatile private var flatSince = 0L // uptime (ms) of the first flat packet, 0 = signal ok
    @Volatile private var lastSignalTime = 0L // uptime (ms) of the last packet with a signal
    private var signalLevel = 0.0 // usual mean power of the samples, see isFlat()
    @Volatile private var recovering = false
    @Volatile private var lastRecoveryEnd = 0L
    private var retunesSinceRegisterCheck = 0 // guarded by deviceLock
    private val recoveryExecutor = Executors.newSingleThreadScheduledExecutor { Thread(it, "RtlSdrRecovery").apply { isDaemon = true } }

    //Lock watchdog, see watchLock(). Guarded by deviceLock.
    private var retuneGeneration = 0L
    private var lockRetryFrequency = 0L
    private var lockRetries = 0

    @Volatile
    private var dropSamplesUntil = 0L // uptime (ms) until which received samples are dropped, see setFrequency()
    // Increased at the start of each retune, after dropSamplesUntil: a USB transfer accepted
    // before a retune and queued after it is dropped (onSamples)
    @Volatile
    private var retuneCount = 0L
    // USB transfers still dropped after a retune, see endDrop()
    private val transfersToSkip = AtomicInteger(0)
    // Queuing a packet and emptying the queue at the end of a retune don't overlap
    private val queueLock = Any()

    // startTime (in ms since 1970) and packetCounter for statistics:
    private var receiveStartTime: Long = 0
    private var receivePacketCounter: Long = 0

    private var packetSize = 1024 * 16 // Size of each USB transfer (can be customized through the intent)
    private var tunerGains = IntArray(0) // supported gains in tenths of dB

    companion object {
        private const val logTag: String = "rtlsdr_android"

        // Number of USB transfers queued in libusb. They are filled one after the other,
        // so they don't add latency, they only absorb USB scheduling jitter.
        private const val numUsbTransfers: Int = 15

        // Number of packets waiting for the client app. Kept small on purpose: when the
        // client is late the oldest packets are dropped, so that the samples reaching the
        // client always reflect the latest commands (frequency, gain...).
        private const val maxQueuedPackets: Int = 8

        // Samples dropped for this long after a retune, on top of the ones received during
        // it: the samples recorded before it that are still on their way (RTL2832U buffer,
        // USB transfers) and the tuner settling (rtl_power waits 5 ms). The transfers skipped after it
        // (settlingTransfers) usually last longer anyway.
        private const val retuneDropMillis: Long = 5

        // USB transfers dropped after a retune, on top of retuneDropMillis (see endDrop()).
        // The numUsbTransfers in flight may all have been filled during the retune: on a slow
        // device they were sometimes handed over after retuneDropMillis, in a burst,
        // and their samples of the retune showed a ghost of a strong signal one step away.
        // settlingTransfers more cover the tuner settling (~5 ms with 8 KB transfers).
        private const val settlingTransfers: Int = 3

        // Safety net: with the RTL-SDR Blog librtlsdr leaving the I2C gate open, fast
        // retuning made the tuner lose part of its configuration (no signal at all while its
        // PLL kept locking). librtlsdr now closes the gate after each tuner access, which
        // fixed it; these protections remain in case the dongle still glitches (power, USB).
        // - Every retunesPerRegisterCheck retunes, the tuner registers are read back and
        //   the ones that differ from what was written are rewritten (setFrequency).
        // - If the samples are flat for flatTimeoutMs anyway, the tuner is initialized again
        //   (recoverSignal); it gets recoveryCheckMs to bring the signal back, and a failed
        //   recovery is retried after recoveryRetryMs.
        // - A packet is flat when its samples stay near the middle and its power fell below
        //   1/flatLevelRatio of the usual level (signalLevel, averaged over the packets that
        //   aren't flat with levelAveraging per packet, i.e. over a few seconds). No
        //   detection while the usual level is below minReferenceLevel: a flat tuner can't
        //   be told apart from a very weak signal then.
        // - Off: telling a tuner that lost its configuration from a quiet stretch of a loop
        //   scan (with a strong signal elsewhere in the band) by the sample level wasn't
        //   reliable, and each false recovery disturbed the scan. Since the I2C gate fix the
        //   tuner didn't lose its configuration anymore; the register check and the lock
        //   watchdog remain.
        private const val flatSignalRecovery = false
        private const val retunesPerRegisterCheck: Int = 4
        private const val flatLevelRatio: Double = 20.0
        private const val levelAveraging: Double = 0.001
        private const val minReferenceLevel: Double = 2.0
        private const val flatTimeoutMs: Long = 300
        private const val recoveryCheckMs: Long = 150
        private const val recoveryRetryMs: Long = 1000

        // Lock watchdog: a retune that leaves the tuner PLL unlocked is retried after
        // lockRetryDelayMs, up to maxLockRetries times
        private const val lockRetryDelayMs: Long = 100
        private const val maxLockRetries: Int = 5

        private val tunerNames = arrayOf("Unknown", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D")

        init {
            System.loadLibrary("rtlsdrbridge")
        }
    }

    init {
        this.queue = ArrayBlockingQueue(maxQueuedPackets.coerceAtMost(queueSize / packetSize).coerceAtLeast(2))
        this.bufferPool = ArrayBlockingQueue(maxQueuedPackets)
    }

    //Opens the device and initializes librtlsdr (baseband, tuner detection)
    override fun initializeRfSource(context: Context, callbackInterface: RfSourceCallbackInterface, device: UsbDevice, usbManager: UsbManager, queueSize: Int): Boolean {
        return try {
            val connection = usbManager.openDevice(usbDevice)
                ?: throw RfSourceException("Couldn't open RTL-SDR USB Device: openDevice() returned null!")
            synchronized(deviceLock) {
                handle = nativeOpen(connection.fileDescriptor, usbDevice.deviceName)
                tunerGains = nativeGetTunerGains(handle)
            }
            usbConnection = connection
            LogParameters.appendLine(
                "$logTag, Opened ${usbDevice.manufacturerName} ${usbDevice.productName}, tuner: ${getTunerName()}, " +
                        "${tunerGains.size} gains (${tunerGains.firstOrNull()?.div(10.0)} to ${tunerGains.lastOrNull()?.div(10.0)} dB)"
            )
            callbackInterface.onRfSourceReady(this)
            true
        } catch (e: Exception) {
            Log.e(logTag, "initializeRfSource: ${e.message}")
            LogParameters.appendLine("$logTag, Error opening RTL-SDR: ${e.message}")
            usbConnection?.close()
            usbConnection = null
            callbackInterface.onRfSourceError(e.message ?: "Couldn't open RTL-SDR")
            false
        }
    }

    private fun getTunerName(): String {
        val type = synchronized(deviceLock) { if (handle != 0L) nativeGetTunerType(handle) else 0 }
        return tunerNames.getOrElse(type) { "Unknown" }
    }

    @Throws(RfSourceException::class)
    override fun startRX(): ArrayBlockingQueue<ByteArray> {
        val queue = this.queue ?: throw RfSourceException("RTL-SDR is closed")
        queue.clear()

        // rtl_tcp dongle info header, sent once before the samples
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            put("RTL0".toByteArray(Charsets.US_ASCII))
            putInt(synchronized(deviceLock) { if (handle != 0L) nativeGetTunerType(handle) else 0 })
            putInt(tunerGains.size)
        }.array()
        queue.offer(header)

        receiverMode = RTLSDR_RECEIVER_MODE_RECEIVE

        // librtlsdr's async read blocks the calling thread for the whole session
        receiveThread = thread(name = "RtlSdrReceive") { readSamples() }

        // Reset the packet counter and start time for statistics:
        this.receiveStartTime = System.currentTimeMillis()
        this.receivePacketCounter = 0

        return queue
    }

    override suspend fun receiveLoop() {
        readSamples()
    }

    private fun readSamples() {
        val currentHandle = synchronized(deviceLock) { handle }
        if (currentHandle == 0L) return
        LogParameters.appendLine("$logTag, Receiving with $numUsbTransfers USB transfers of $packetSize bytes")
        val result = nativeReadAsync(currentHandle, numUsbTransfers, packetSize)
        if (receiverMode == RTLSDR_RECEIVER_MODE_RECEIVE) {
            // Not stopped by us: the device was lost or the read failed
            LogParameters.appendLine("$logTag, Reading samples stopped unexpectedly ($result)")
            receiverMode = RTLSDR_RECEIVER_MODE_OFF
            // An empty packet tells the DriverService that the stream is over
            queue?.let { q ->
                q.clear()
                q.offer(ByteArray(0))
            }
        }
    }

    //Called from native code (cpp/rtlsdr_jni.c) for every USB transfer. The buffer is
    //only valid during this call.
    @Keep
    @Suppress("unused")
    private fun onSamples(samples: ByteBuffer, length: Int) {
        val queue = this.queue ?: return
        if (receiverMode != RTLSDR_RECEIVER_MODE_RECEIVE) return

        // Read before dropSamplesUntil (see startDrop())
        val retune = retuneCount
        // Every transfer handed over after a retune counts, also the ones dropped by time
        val skipped = transfersToSkip.get() > 0 && transfersToSkip.getAndDecrement() > 0
        // Recorded while retuning or settling (see setFrequency()): unusable
        if (skipped || SystemClock.uptimeMillis() < dropSamplesUntil) return

        val packet = if (length == packetSize) getBufferFromBufferPool() else ByteArray(length)
        samples.get(packet, 0, length)
        this.receivePacketCounter++
        watchForFlatSignal(packet, length)

        synchronized(queueLock) {
            // A retune started since this transfer was accepted: it holds samples of the
            // previous frequency and would reach the client after the retune
            if (retuneCount != retune) {
                returnBufferToBufferPool(packet)
                return
            }
            // Drop the oldest sample packet when the client app is late, to keep the latency
            // low (not the rtl_tcp header or a retune code: the client needs them)
            while (!queue.offer(packet)) {
                val oldest = queue.firstOrNull { it.size == packetSize }
                if (oldest == null || !queue.remove(oldest)) {
                    returnBufferToBufferPool(packet)
                    return
                }
                returnBufferToBufferPool(oldest)
            }
        }
    }

    //A packet is flat when nearly all its samples are within 2 steps of the middle (127.5)
    //and its power has collapsed compared with the usual level (signalLevel). A tuner that
    //lost its configuration gives a mean power around 0.4, when a working one gives ~100 or
    //more; with a low gain, a working tuner can also stay near the middle, but its level
    //doesn't suddenly drop.
    private fun isFlat(packet: ByteArray, length: Int): Boolean {
        val maxOutliers = length / 1000
        var outliers = 0
        var sum = 0L
        for (i in 0 until length) {
            val v = packet[i].toInt() and 0xff
            if (v < 126 || v > 129) outliers++
            val d = v * 2 - 255 // 2 * (sample - 127.5)
            sum += d * d
        }
        val power = sum / 4.0 / length.coerceAtLeast(1)
        val nearMiddle = outliers <= maxOutliers
        val reference = signalLevel
        val flat = nearMiddle && reference >= minReferenceLevel && power < reference / flatLevelRatio
        // The usual level follows the packets that aren't flat
        if (!flat) signalLevel = if (reference == 0.0) power else reference + (power - reference) * levelAveraging
        return flat
    }

    //Called for every packet sent to the client: starts recoverSignal() when the samples
    //have been flat for flatTimeoutMs
    private fun watchForFlatSignal(packet: ByteArray, length: Int) {
        if (!flatSignalRecovery) return
        val now = SystemClock.uptimeMillis()
        if (!isFlat(packet, length)) {
            flatSince = 0L
            lastSignalTime = now
            return
        }
        if (flatSince == 0L) flatSince = now
        if (now - flatSince >= flatTimeoutMs && !recovering && now - lastRecoveryEnd >= recoveryRetryMs) {
            recovering = true
            recoveryExecutor.execute { recoverSignal() }
        }
    }

    //Initializes the tuner again (with the current settings restored): when the samples are
    //flat, this is what brings the signal back (restarting the RTL2832U DC/IQ correction or
    //a soft reset of the demodulator didn't)
    private fun recoverSignal() {
        try {
            if (receiverMode != RTLSDR_RECEIVER_MODE_RECEIVE) return
            val flatFor = SystemClock.uptimeMillis() - flatSince
            val start = SystemClock.uptimeMillis()
            val done = reinitTuner()
            Thread.sleep(recoveryCheckMs)
            val recovered = lastSignalTime > start
            val result = "Recovery: samples flat for $flatFor ms, tuner re-initialized (done: $done) -> ${if (recovered) "signal back" else "still flat"}"
            Log.i(logTag, result)
            LogParameters.appendLine("$logTag, $result")
        } catch (e: InterruptedException) {
            return
        } catch (e: Exception) {
            Log.e(logTag, "Recovery error: ${e.message}")
        } finally {
            lastRecoveryEnd = SystemClock.uptimeMillis()
            flatSince = 0L
            recovering = false
        }
    }

    //Initializes the tuner again, then restores the current settings (in the order of the
    //DriverService initial configuration). The samples are dropped meanwhile, as during a
    //retune: until the settings are restored, the tuner runs with its default ones (gain).
    private fun reinitTuner(): Boolean = synchronized(deviceLock) {
        if (handle == 0L) return false
        startDrop()
        try {
            if (nativeReinitTuner(handle) < 0) return false
            if (currentSampleRate > 0) setSampleRate(currentSampleRate, 1)
            if (currentBandwidth > 0) setBasebandFilterBandwidth(currentBandwidth)
            setTunerGainMode(currentGainManual)
            if (currentGainManual) setTunerGain(currentGain)
            if (currentFrequency > 0) setFrequency(currentFrequency) else true
        } finally {
            endDrop()
            dropQueuedSamples()
        }
    }

    override fun getBufferFromBufferPool(): ByteArray {
        return bufferPool?.poll() ?: ByteArray(packetSize)
    }

    private fun returnBufferToBufferPool(buffer: ByteArray) {
        if (buffer.size == packetSize) {
            bufferPool?.offer(buffer)
        }
    }

    override fun stop() {
        receiverMode = RTLSDR_RECEIVER_MODE_OFF
        recoveryExecutor.shutdownNow()
        try {
            synchronized(deviceLock) {
                if (handle != 0L) nativeCancelAsync(handle)
            }
            receiveThread?.join(2000)
            synchronized(deviceLock) {
                if (handle != 0L) {
                    if (receiveThread?.isAlive == true) {
                        // Closing while librtlsdr is still reading would crash: leak it instead
                        LogParameters.appendLine("$logTag, Receive thread did not stop, device left open")
                    } else {
                        nativeClose(handle)
                    }
                    handle = 0L
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error stopping RTL-SDR: ${e.message}")
        }
        receiveThread = null

        try {
            usbConnection?.close()
        } catch (e: Exception) {
            Log.e(logTag, "Error closing USB connection: ${e.message}")
        }
        usbConnection = null

        queue?.clear()
        bufferPool?.clear()
        queue = null
        bufferPool = null
    }

    //Runs a native control call if the device is open. Returns true on success (librtlsdr
    //returns 0 or a positive value on success, a negative error code otherwise)
    private inline fun control(name: String, call: (Long) -> Int): Boolean {
        val result = synchronized(deviceLock) {
            if (handle == 0L) return false
            call(handle)
        }
        if (result < 0) Log.w(logTag, "$name failed: $result")
        return result >= 0
    }

    //Retunes and makes sure the client app only gets samples of the new frequency: the
    //samples recorded while retuning and for retuneDropMillis after are dropped (onSamples),
    //and the packets of the previous frequency still waiting in the queue. The retune code,
    //queued before the drop ends, tells the client where the new frequency starts.
    // The lock watchdog's retunes send none.
    override fun setFrequency(frequency: Long, retuneCode: ByteArray?): Boolean {
        return control("setFrequency") {
            startDrop()
            val checkRegisters = ++retunesSinceRegisterCheck >= retunesPerRegisterCheck
            var report: String? = null
            val result = if (checkRegisters) {
                // Retune and check the tuner registers in the same I2C gate opening
                retunesSinceRegisterCheck = 0
                val retuneResult = IntArray(1)
                report = nativeSetCenterFreqAndVerify(it, frequency, retuneResult)
                retuneResult[0]
            } else {
                nativeSetCenterFreq(it, frequency)
            }
            if (result >= 0) currentFrequency = frequency
            // Nothing is queued until endDrop(): the retune code comes right before the new samples
            dropQueuedSamples(if (result >= 0) retuneCode else null)
            endDrop()
            report?.let { logRegisterCheck("retune to $frequency Hz: $it") }
            if (result >= 0) watchLock(it, frequency)
            result
        }
    }

    //Lock watchdog: when a retune leaves the tuner PLL unlocked (librtlsdr already checked
    //and programmed it twice), the samples show the signals at wrong, drifting
    //frequencies. Retune again lockRetryDelayMs later, unless another retune came in
    //between (the loop scan retunes faster than that), up to maxLockRetries times.
    //Called with deviceLock held.
    private fun watchLock(handle: Long, frequency: Long) {
        val generation = ++retuneGeneration
        if (frequency != lockRetryFrequency) {
            lockRetryFrequency = frequency
            lockRetries = 0
        }
        if (nativeTunerLocked(handle) != 0) {
            if (lockRetries > 0) logLock("PLL locked at $frequency Hz after $lockRetries retries")
            lockRetries = 0
            return
        }
        if (lockRetries >= maxLockRetries) {
            logLock("PLL still not locked at $frequency Hz after $lockRetries retries, giving up")
            return
        }
        lockRetries++
        logLock("PLL not locked at $frequency Hz, retuning in $lockRetryDelayMs ms (retry $lockRetries)")
        try {
            recoveryExecutor.schedule({
                synchronized(deviceLock) {
                    if (retuneGeneration == generation) setFrequency(frequency)
                }
            }, lockRetryDelayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // stopped
        }
    }

    private fun logLock(message: String) {
        Log.i(logTag, message)
        LogParameters.appendLine("$logTag, $message")
    }

    //Logs what the tuner register check (r82xx_verify_registers() in librtlsdr) found: the
    //read size and ignored registers on the first check, then the registers it rewrote
    private fun logRegisterCheck(report: String) {
        Log.i(logTag, "Tuner registers: $report")
        LogParameters.appendLine("$logTag, Tuner registers: $report")
    }

    //Empties the queue of its sample packets, but keeps the rtl_tcp header and the end of
    //stream marker (the packets that don't have the size of a USB transfer), then queues
    //retuneCode. A retune code still waiting there is dropped: the client waits for the code
    //of its latest frequency command
    private fun dropQueuedSamples(retuneCode: ByteArray? = null) {
        val queue = this.queue ?: return
        synchronized(queueLock) {
            val kept = ArrayList<ByteArray>(1)
            while (true) {
                val packet = queue.poll() ?: break
                if (packet.size == packetSize) returnBufferToBufferPool(packet)
                else if (packet.size != retuneCodeSize) kept.add(packet)
            }
            kept.forEach { queue.offer(it) }
            retuneCode?.let { queue.offer(it) }
        }
    }

    //Start of a retune: the samples are dropped from now on (onSamples). retuneCount is
    //increased after dropSamplesUntil, so that a transfer that read the old retuneCount
    //is either dropped by dropSamplesUntil or by the retuneCount check before queuing.
    //The queued packets (previous frequency) are dropped now too: a send loop held up during
    //the retune would otherwise still send them, and the client could take its pause for the
    //retune gap
    private fun startDrop() {
        dropSamplesUntil = Long.MAX_VALUE
        retuneCount++
        dropQueuedSamples()
    }

    //End of a retune: the samples are still dropped for retuneDropMillis, and until
    //numUsbTransfers + settlingTransfers more transfers were handed over, whichever comes
    //last. transfersToSkip is set before dropSamplesUntil, so no transfer gets between them.
    private fun endDrop() {
        transfersToSkip.set(numUsbTransfers + settlingTransfers)
        dropSamplesUntil = SystemClock.uptimeMillis() + retuneDropMillis
    }

    override fun setSampleRate(sampRate: Int, divider: Int): Boolean = control("setSampleRate") {
        nativeSetSampleRate(it, sampRate).also { r -> if (r >= 0) currentSampleRate = sampRate }
    }

    //0 lets librtlsdr choose the tuner IF filter bandwidth from the sample rate. The -b
    //bandwidth of the intent is meant for the HackRF: a narrower filter on the RTL-SDR only
    //attenuates the edges of the spectrum
    override fun computeBasebandFilterBandwidth(sampRate: Int): Int = 0

    override fun setBasebandFilterBandwidth(bandwidth: Int): Boolean = control("setTunerBandwidth") {
        nativeSetTunerBandwidth(it, bandwidth).also { r -> if (r >= 0) currentBandwidth = bandwidth }
    }

    override fun setTunerGainMode(manual: Boolean): Boolean = control("setTunerGainMode") {
        nativeSetTunerGainMode(it, if (manual) 1 else 0).also { r -> if (r >= 0) currentGainManual = manual }
    }

    override fun setTunerGain(gain: Int): Boolean = control("setTunerGain") {
        nativeSetTunerGain(it, gain).also { r -> if (r >= 0) currentGain = gain }
    }

    override fun setTunerGainByIndex(index: Int): Boolean {
        val gain = tunerGains.getOrNull(index) ?: return false
        return setTunerGain(gain)
    }

    override fun setTunerGainByPercentage(percent: Int): Boolean {
        if (tunerGains.isEmpty()) return false
        val index = (percent * tunerGains.size / 100).coerceIn(0, tunerGains.size - 1)
        return setTunerGain(tunerGains[index])
    }

    override fun setTunerIfGain(stage: Int, gain: Int): Boolean = control("setTunerIfGain") { nativeSetTunerIfGain(it, stage, gain) }

    override fun setFreqCorrection(ppm: Int): Boolean = control("setFreqCorrection") { nativeSetFreqCorrection(it, ppm) }

    override fun setAgcMode(enable: Boolean): Boolean = control("setAgcMode") { nativeSetAgcMode(it, if (enable) 1 else 0) }

    override fun setDirectSampling(mode: Int): Boolean = control("setDirectSampling") { nativeSetDirectSampling(it, mode) }

    override fun setOffsetTuning(enable: Boolean): Boolean = control("setOffsetTuning") { nativeSetOffsetTuning(it, if (enable) 1 else 0) }

    override fun setTestMode(enable: Boolean): Boolean = control("setTestMode") { nativeSetTestMode(it, if (enable) 1 else 0) }

    override fun setXtalFreq(rtlFreq: Int, tunerFreq: Int): Boolean = control("setXtalFreq") { nativeSetXtalFreq(it, rtlFreq, tunerFreq) }

    //Bias-T
    override fun setAntennaPower(enable: Boolean): Boolean = control("setBiasTee") { nativeSetBiasTee(it, if (enable) 1 else 0) }

    //The USB transfer size is fixed once receiving: librtlsdr allocates its transfers at start
    override fun setPacketSize(pSize: Int): Boolean {
        if (receiverMode == RTLSDR_RECEIVER_MODE_RECEIVE) return false
        // librtlsdr needs a multiple of 512 bytes
        packetSize = (pSize / 512).coerceAtLeast(1) * 512
        bufferPool?.clear()
        return true
    }

    override fun setRawMode(rawModeEnable: Boolean): Boolean = true

    override fun setPacking(packingEnable: Boolean): Boolean = false

    override fun setRxVGAGain(gain: Int): Boolean = false

    override fun setRxMixerGain(gain: Int): Boolean = false

    override fun setRxMixerAGC(agcMixerEnable: Boolean): Boolean = false

    override fun setRxLNAGain(gain: Int): Boolean = false

    override fun setRxLNAAGC(agcLnaEnable: Boolean): Boolean = false

    override fun setAmp(enable: Boolean): Boolean = false

    override fun setTxVGAGain(gain: Int): Boolean = false

    override fun startTX(): ArrayBlockingQueue<ByteArray> {
        throw RfSourceException("RTL-SDR can not transmit")
    }

    override suspend fun transmitLoop() {}

    override fun sendUsbRequest(endpoint: Int, request: Int, value: Int, index: Int, buffer: ByteArray?): Int = -1

    private external fun nativeOpen(fd: Int, devicePath: String): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeGetTunerType(handle: Long): Int
    private external fun nativeGetTunerGains(handle: Long): IntArray
    private external fun nativeSetCenterFreq(handle: Long, frequency: Long): Int
    private external fun nativeSetSampleRate(handle: Long, rate: Int): Int
    private external fun nativeSetTunerGainMode(handle: Long, manual: Int): Int
    private external fun nativeSetTunerGain(handle: Long, gain: Int): Int
    private external fun nativeSetTunerIfGain(handle: Long, stage: Int, gain: Int): Int
    private external fun nativeSetTunerBandwidth(handle: Long, bandwidth: Int): Int
    private external fun nativeSetFreqCorrection(handle: Long, ppm: Int): Int
    private external fun nativeSetAgcMode(handle: Long, on: Int): Int
    private external fun nativeSetDirectSampling(handle: Long, mode: Int): Int
    private external fun nativeSetOffsetTuning(handle: Long, on: Int): Int
    private external fun nativeSetTestMode(handle: Long, on: Int): Int
    private external fun nativeSetXtalFreq(handle: Long, rtlFreq: Int, tunerFreq: Int): Int
    private external fun nativeSetBiasTee(handle: Long, on: Int): Int
    private external fun nativeReadAsync(handle: Long, bufNum: Int, bufLen: Int): Int
    private external fun nativeCancelAsync(handle: Long): Int
    private external fun nativeSetCenterFreqAndVerify(handle: Long, frequency: Long, result: IntArray): String?
    private external fun nativeReinitTuner(handle: Long): Int
    private external fun nativeTunerLocked(handle: Long): Int
}
