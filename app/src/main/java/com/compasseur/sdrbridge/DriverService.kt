package com.compasseur.sdrbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import com.compasseur.sdrbridge.rfsource.HackRF
import com.compasseur.sdrbridge.rfsource.RfSource
import com.compasseur.sdrbridge.rfsource.RfSourceHolder
import com.compasseur.sdrbridge.rfsource.Airspy
import com.compasseur.sdrbridge.rfsource.RtlSdr
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Arrays
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.nio.ByteBuffer

/**
 * Module:      DriverService
 * Description: Manages the connection between the SDR dongle and the client app.
 * 				Also responsible for the foreground service notification
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

class DriverService : Service() {

    private val logTag = "DriverServiceTag"
    private val CHANNEL_ID = "DriverServiceChannel"
    private val NOTIFICATION_ID = 1
    private lateinit var usbMANAGER: UsbManager

    private var rfSource: RfSource? = null

    private var serverSocket: ServerSocket? = null
    private var address: String? = "127.0.0.1" // -a
    private var serverPort = 1234 // -p

    private var frequency = 100000000L // -f
    private var samplerate = 20000000 // -s
    private var basebandFilterBandwidth = 1000000 // -b

    private var txVgaGain = 47 // -v
    private var rxVgaGain = 16 // -v
    private var rxLnaGain = 8 // -l
    private var rxMixGain = 8
    private var agcLnaEnable = false
    private var agcMixEnable = false
    private var rtlTunerGain = 0 // -g RTL-SDR tuner gain in tenths of dB, 0 = automatic
    private var rtlPpm = 0 // -ppm RTL-SDR frequency correction

    private var ampEnabled = false // -m
    private var antennaPowerEnabled = false // -n
    private var packingEnable = false

    private var transmitIsEnabled = false

    private var packetSize = 16384 // -ps

    private var commandJob: Job? = null
    private var applyCommandsJob: Job? = null
    private var receiveJob: Job? = null

    /*Commands received from the client app, waiting to be applied by applyCommands().
    Only the latest frequency is kept: when the client retunes faster than the SDR
    can, the intermediate frequencies are skipped instead of accumulating. Kept with the
    number of its frequency command, for its retune code (see retuneCode())*/
    private val pendingFrequency = AtomicReference<Pair<Long, Int>?>(null)
    private val pendingCommands = ConcurrentLinkedQueue<Pair<Int, Long>>()
    private val commandsAvailable = Channel<Unit>(Channel.CONFLATED)
    //Set by commandEnableRetuneCodes, see retuneCode()
    @Volatile private var retuneCodesEnabled = false
    private var frequencyCommandsReceived = 0
    //Completed by sendIqSamples() once the SDR is set up and receiving, see applyCommands()
    private var receiveStarted = CompletableDeferred<Unit>()
    private var transmitJob: Job? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var driverIsrunning = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        usbMANAGER = getSystemService(Context.USB_SERVICE) as UsbManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "ACTION_STOP_DRIVER") {
                LogParameters.appendLine("$logTag, Received stop action from MainActivity")
                closeEverything()
            }
        else {
            val notification = createNotification()
            LogParameters.appendLine("$logTag, Driver started: $intent \n${intent?.data}\n${RfSourceHolder.rfSource}")
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)

            rfSource = RfSourceHolder.rfSource
            intent?.data?.let { uri ->
                val args = parseUriParameters(uri)
                address = args["a"]
                serverPort = args["p"]?.toInt() ?: 1234
                frequency = args["f"]?.toLong() ?: frequency
                samplerate = args["s"]?.toInt() ?: samplerate
                basebandFilterBandwidth = args["b"]?.toInt() ?: basebandFilterBandwidth
                rxVgaGain = args["v"]?.toInt() ?: rxVgaGain
                rxLnaGain = args["l"]?.toInt() ?: rxLnaGain
                txVgaGain = args["txv"]?.toInt() ?: txVgaGain
                ampEnabled = args["m"].toBoolean()
                antennaPowerEnabled = args["n"].toBoolean()
                packetSize = args["ps"]?.toInt() ?: packetSize
                transmitIsEnabled = args["tx"].toBoolean()
                rtlTunerGain = args["g"]?.toInt() ?: rtlTunerGain
                rtlPpm = args["ppm"]?.toInt() ?: rtlPpm
                LogParameters.appendLine(
                    "$logTag, Received parameters from intent : " +
                            "address: $address\n" +
                            "port: $serverPort\n" +
                            "frequency: $frequency\n" +
                            "samplerate: $samplerate\n" +
                            "packetSize: $packetSize"
                )
            }
            rfSource?.let {
                LogParameters.appendLine("$logTag, Starting with: $rfSource")
                startServer()
            }
        }
        return START_STICKY // Ensures the service stays running
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SDR Bridge Running")
            .setContentText("Receiving IQ samples...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(Color.DKGRAY)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Driver Service Channel",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun parseUriParameters(uri: Uri): Map<String, String> {
        val params = mutableMapOf<String, String>()
        uri.queryParameterNames.forEach { param ->
            params[param] = uri.getQueryParameter(param) ?: ""
        }
        return params
    }

    //Starts the server with the address and port. Wait for the client to connect and then handleClientConnection
    private fun startServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(serverPort))
                }
                LogParameters.appendLine("$logTag, Server started on port $serverPort")
                while (serverSocket?.isClosed == false) {
                    val clientSocket = serverSocket?.accept() ?: break
                    LogParameters.appendLine("$logTag, Client connected: ${clientSocket?.inetAddress}")
                    handleClientConnection(clientSocket)
                }

            } catch (e: BindException) {
                LogParameters.appendLine("$logTag, Port $serverPort is already in use, try again...")
                closeEverything()
            } catch (e: Exception) {
                LogParameters.appendLine("$logTag, Server error: ${e.message}")
                closeEverything()
            }
        }
    }

    //Establishs the input and output streams and launchs the coroutines responsible
    //for sending the IQ samples to the outputStream and receiving commands from the
    //client app through the inputStream. When used as a transmitter, the HackRF receives
    //the samples through the inputstream and can not receive commands at the same time.
    private suspend fun handleClientConnection(clientSocket: Socket) = withContext(Dispatchers.IO) {
        try {
            clientSocket.tcpNoDelay = true
            retuneCodesEnabled = false
            frequencyCommandsReceived = 0
            receiveStarted = CompletableDeferred()
            inputStream = clientSocket.getInputStream()
            outputStream = clientSocket.getOutputStream()
            driverIsrunning = true
            if (transmitIsEnabled) {
                transmitJob = launch(Dispatchers.IO) { receiveIqSamples(inputStream) }
                transmitJob?.join()
            } else {
                commandJob = launch(Dispatchers.IO) { listenForCommands(inputStream) }
                applyCommandsJob = launch(Dispatchers.IO) { applyCommands() }
                receiveJob = launch(Dispatchers.IO) { sendIqSamples(outputStream) }
                // Wait for both coroutines to complete or be cancelled
                joinAll(commandJob!!, receiveJob!!)
            }

        } catch (e: IOException) {
            LogParameters.appendLine("$logTag, Client disconnected: ${e.message}")
        } finally {
            LogParameters.appendLine("$logTag, Client connection closed")
            closeEverything()
        }
    }

    private suspend fun receiveIqSamples(inputStream: InputStream?) {
        LogParameters.appendLine("$logTag, ReceiveIqSamples started")
        var i = 0
        val basebandFilterWidth = rfSource?.computeBasebandFilterBandwidth(basebandFilterBandwidth) ?: samplerate
        val queue = rfSource?.startTX()
        ///////////////////
        LogParameters.appendLine("$logTag, rfsource: $rfSource")
        ///////////////////
        try {
            rfSource?.apply {
                setPacketSize(packetSize)
                LogParameters.appendLine("$logTag, packetSize set")
                setSampleRate(samplerate, 1)
                LogParameters.appendLine("$logTag, samplerate set")
                setFrequency(frequency)
                LogParameters.appendLine("$logTag, frequency set")
                setBasebandFilterBandwidth(basebandFilterWidth)
                LogParameters.appendLine("$logTag, filter BW set")
                setTxVGAGain(txVgaGain)
                LogParameters.appendLine("$logTag, TX VGA gain set")
                setAmp(ampEnabled)
                LogParameters.appendLine("$logTag, amp set")
                setAntennaPower(antennaPowerEnabled)
                LogParameters.appendLine("$logTag, antenna power set")
            }
            if (queue == null) {
                LogParameters.appendLine("$logTag, Error: TX queue is null!")
                return
            }
            while (driverIsrunning) {
                i++
                val packet = rfSource?.getBufferFromBufferPool() ?: ByteArray(packetSize)
                val bytesRead = inputStream?.read(packet, 0, packet.size) ?: 0
                if (bytesRead == -1) {
                    LogParameters.appendLine("$logTag, InputStream closed by client. Stopping.")
                    break
                }

                // If we read fewer bytes than expected, fill the rest with zero
                if (bytesRead < packet.size) {
                    Arrays.fill(packet, bytesRead, packet.size, 0.toByte())
                }

                // Offer to TX queue
                if (!queue.offer(packet, 2000, TimeUnit.MILLISECONDS)) {
                    LogParameters.appendLine("$logTag, TX queue full. Dropping packet.")
                }
            }
        } catch (e: Exception) {
            LogParameters.appendLine("$logTag, Error receiving samples: ${e.message}")
        }finally {
            closeEverything()
        }
    }

    //Listens for commands from the client app and converts them before handing them to
    //applyCommands(). Reading never waits for a command to be applied
    private suspend fun listenForCommands(inputStream: InputStream?) {
        LogParameters.appendLine("$logTag, listenForCommands started")
        val commandBuffer = ByteArray(5)
        try {
            //while (true) {
            while (driverIsrunning) {
                var bytesRead = 0
                while (bytesRead < 5) {
                    val result = inputStream?.read(commandBuffer, bytesRead, 5 - bytesRead) ?: -1
                    if (result == -1) {
                        LogParameters.appendLine("$logTag, End of stream reached")
                        return // Exit the loop on end of stream
                    }
                    bytesRead += result
                }
                val command = commandBuffer[0].toUByte().toInt()
                val value = ((commandBuffer[1].toUByte().toLong() shl 24) or
                        (commandBuffer[2].toUByte().toLong() shl 16) or
                        (commandBuffer[3].toUByte().toLong() shl 8) or
                        (commandBuffer[4].toUByte().toLong()))
                if (command == commandSetFrequency) {
                    frequencyCommandsReceived++
                    pendingFrequency.set(value to frequencyCommandsReceived)
                } else {
                    pendingCommands.add(command to value)
                }
                commandsAvailable.trySend(Unit)
            }
        } catch (e: Exception) {
            LogParameters.appendLine("$logTag, Error receiving command: ${e.message}")
        } finally {
            LogParameters.appendLine("$logTag, Listen for commands closed")
            closeEverything()
        }
    }

    //Applies the commands received by listenForCommands(), one at a time: first the other
    //commands in the order they were received, then only the latest frequency. Not before
    //the SDR is set up and receiving: its setup frequency would replace the client's, and
    //starting to receive empties the queue, retune code included
    private suspend fun applyCommands() {
        receiveStarted.await()
        for (signal in commandsAvailable) {
            while (true) {
                val (command, value) = pendingCommands.poll() ?: break
                handleCommand(command, value)
            }
            val frequency = pendingFrequency.getAndSet(null)
            if (frequency != null) handleCommand(commandSetFrequency, frequency.first, frequency.second)
        }
    }

    //Starts the SDR in receive mode and gets the IQ samples before offering them to the
    //output stream. No buffer: the client app is responsible for getting the samples
    //fast enough
    private suspend fun sendIqSamples(outputStream: OutputStream?) {
        try {
            LogParameters.appendLine("$logTag, sendIqSamples started")
            val basebandFilterWidth = rfSource?.computeBasebandFilterBandwidth(basebandFilterBandwidth) ?: samplerate
            var byteReceivedQueue: ArrayBlockingQueue<ByteArray>? = null
            if (rfSource is HackRF) byteReceivedQueue = rfSource?.startRX()
            rfSource?.apply {
                setPacketSize(packetSize)
                LogParameters.appendLine("$logTag, packetSize set")
                setSampleRate(samplerate, 1)
                LogParameters.appendLine("$logTag, samplerate set")
                setFrequency(frequency)
                LogParameters.appendLine("$logTag, frequency set")
                setRxVGAGain(rxVgaGain)
                LogParameters.appendLine("$logTag, VGA gain set")
                setRxLNAGain(rxLnaGain)
                LogParameters.appendLine("$logTag, LNA gain set")
                setRxMixerGain(rxMixGain)
                LogParameters.appendLine("$logTag, Mixer Gain set")
                setPacking(packingEnable)
                LogParameters.appendLine("$logTag, packing set")
                setBasebandFilterBandwidth(basebandFilterWidth)
                LogParameters.appendLine("$logTag, filter BW set")
                setRxLNAAGC(agcLnaEnable)
                LogParameters.appendLine("$logTag, LNA AGC set")
                setRxMixerAGC(agcMixEnable)
                LogParameters.appendLine("$logTag, Mixer AGC set")
                setAmp(ampEnabled)
                LogParameters.appendLine("$logTag, amp set")
                setAntennaPower(antennaPowerEnabled)
                LogParameters.appendLine("$logTag, antenna power set")
                //setSampleType(sampleType)
                setRawMode(true)
                LogParameters.appendLine("$logTag, raw mode set")
            }
            if (rfSource is RtlSdr) {
                rfSource?.apply {
                    if (rtlPpm != 0) setFreqCorrection(rtlPpm)
                    setTunerGainMode(rtlTunerGain != 0)
                    if (rtlTunerGain != 0) setTunerGain(rtlTunerGain)
                }
                LogParameters.appendLine("$logTag, RTL-SDR ppm: $rtlPpm, tuner gain: ${if (rtlTunerGain == 0) "auto" else rtlTunerGain / 10.0}")
            }
            if (rfSource is Airspy || rfSource is RtlSdr) byteReceivedQueue = rfSource?.startRX()
            receiveStarted.complete(Unit)
            while (driverIsrunning) {

                val receivedBytes: ByteArray? = byteReceivedQueue?.poll(5000, TimeUnit.MILLISECONDS) //1000
                //LogParameters.appendLine("$logTag, ReceivedBytes: ${receivedBytes?.size}")
                if (receivedBytes == null || receivedBytes.isEmpty()) {
                    LogParameters.appendLine("$logTag, ReceivedBytes is null or empty: ${receivedBytes?.size}")
                    break
                }

                outputStream?.apply {
                    write(receivedBytes)
                    flush()
                }
            }
        } catch (e: Exception) {
            LogParameters.appendLine("$logTag, Error sending samples: ${e.message}")
        } finally {
            closeEverything()
        }
    }

    //After receiving a command, this function will process it
    //retuneNumber: number of the frequency command (commandSetFrequency only)
    private fun handleCommand(command: Int, value: Long, retuneNumber: Int = 0) {
        val boolVal: Boolean = value != 0L
        try {
            val result = when (command) {
                commandSetFrequency -> "Frequency set to $value: ${rfSource?.setFrequency(value, if (retuneCodesEnabled) retuneCode(value, retuneNumber) else null)}"
                commandEnableRetuneCodes -> {
                    retuneCodesEnabled = true
                    "Retune codes enabled"
                }
                commandSetGainMode -> "Gain mode set to $value: ${rfSource?.setTunerGainMode(boolVal)}"
                commandSetGain -> "Gain set to $value: ${rfSource?.setTunerGain(value.toInt())}"
                commandSetFreqCorrection -> "Frequency correction set to $value: ${rfSource?.setFreqCorrection(value.toInt())}"
                commandSetIfTunerGain -> "IF gain set to $value: ${rfSource?.setTunerIfGain((value shr 16).toInt(), (value and 0xffff).toInt().toShort().toInt())}"
                commandSetTestMode -> "Test mode set to $value: ${rfSource?.setTestMode(boolVal)}"
                commandSetAgcMode -> "AGC mode set to $value: ${rfSource?.setAgcMode(boolVal)}"
                commandSetDirectSampling -> "Direct sampling set to $value: ${rfSource?.setDirectSampling(value.toInt())}"
                commandSetOffsetTuning -> "Offset tuning set to $value: ${rfSource?.setOffsetTuning(boolVal)}"
                commandSetRtlXtal -> "RTL xtal set to $value: ${rfSource?.setXtalFreq(value.toInt(), 0)}"
                commandSetTunerXtal -> "Tuner xtal set to $value: ${rfSource?.setXtalFreq(0, value.toInt())}"
                commandSetTunerGainById -> "Gain index set to $value: ${rfSource?.setTunerGainByIndex(value.toInt())}"
                commandSetAndroidGainByPercentage -> "Gain percentage set to $value: ${rfSource?.setTunerGainByPercentage(value.toInt())}"
                commandSetVgaGain -> "VGA gain set to $value: ${rfSource?.setRxVGAGain(value.toInt())}"
                commandSetLnaGain -> "LNA gain set to $value: ${rfSource?.setRxLNAGain(value.toInt())}"
                commandSetMixerGain -> "Mixer gain set to $value: ${rfSource?.setRxMixerGain(value.toInt())}"
                commandSetSamplerate -> "Sample rate set to $value: ${rfSource?.setSampleRate(value.toInt(), 1)}"
                commandSetBaseBandFilter -> "Baseband filter set to $value: ${rfSource?.setBasebandFilterBandwidth(value.toInt())}"
                commandSetAmpEnable -> "Amp enabled: $value: ${rfSource?.setAmp(boolVal)}"
                commandSetAntennaPowerEnable -> "Antenna power enabled: $value: ${rfSource?.setAntennaPower(boolVal)}"
                commandSetPacketSize -> "Packet size set to $value: ${rfSource?.setPacketSize(value.toInt())}"
                commandSetAndroidExit -> {
                    closeEverything()
                    "Received EXIT command. Closing..."
                }
                else -> "Unknown command: $command - $value"
            }
            if (command != commandSetFrequency) LogParameters.appendLine("$logTag, $result")
        } catch (e: Exception) {
            LogParameters.appendLine("$logTag, Problem sending command: $command - $value")
            closeEverything()
        }
    }

    //Retune code put in the IQ stream right before the first sample of the new frequency, for
    //a client that sent commandEnableRetuneCodes: after a frequency command, the client drops
    //the samples until the code with the number of that command (see Commands.kt). Without
    //it, other rtl_tcp clients get the samples only
    private fun retuneCode(frequency: Long, number: Int): ByteArray =
        ByteBuffer.allocate(retuneCodeSize)
            .put(retuneCodeMagic)
            .putInt(frequency.toInt())
            .putInt(number)
            .array()

    private fun closeEverything() {
        if (driverIsrunning) {
            driverIsrunning = false
            LogParameters.appendLine("$logTag, Closing driver service.")
            try {
                rfSource?.stop()
                rfSource = null

                inputStream?.close()
                inputStream = null

                outputStream?.close()
                outputStream = null

                serverSocket?.close()
                serverSocket = null

                commandJob?.cancel()
                commandJob = null

                applyCommandsJob?.cancel()
                applyCommandsJob = null

                receiveJob?.cancel()
                receiveJob = null

                transmitJob?.cancel()
                transmitJob = null

                serviceScope.cancel()

                LogParameters.appendLine("$logTag, Everything closed")

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                LogParameters.appendLine("$logTag, Foreground service stopped")

            } catch (e: Exception) {
                LogParameters.appendLine("$logTag, Error canceling jobs or service scope: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        closeEverything()
        super.onDestroy()
    }
}


