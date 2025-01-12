package com.compasseur.sdrbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import com.compasseur.sdrbridge.rfsource.HackRF
import com.compasseur.sdrbridge.rfsource.RfSource
import com.compasseur.sdrbridge.rfsource.RfSourceHolder
import com.compasseur.sdrbridge.sources.Airspy
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

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
    private var address: String? = "127.0.0.1" //localhost -a
    private var serverPort = 1234 //5000 // -p

    private var frequency = 100000000L //-f
    private var samplerate = 20000000 //-s
    private var basebandFilterBandwidth = 1000000 // -b

    private var rxVgaGain = 16 // -v
    private var rxLnaGain = 8 // -l
    private var rxMixGain = 8
    private var agcLnaEnable = false
    private var agcMixEnable = false

    private var ampEnabled = false // -m
    private var antennaPowerEnabled = false // -n
    private var packingEnable = false
    //private var transceiverMode = 1 // -o

    private var packetSize = 16384

    private var commandJob: Job? = null
    private var sampleJob: Job? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        usbMANAGER = getSystemService(Context.USB_SERVICE) as UsbManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        LogParameters.appendLine("$logTag: Driver started: $intent \n${intent?.data}\n${RfSourceHolder.rfSource}")
        startForeground(NOTIFICATION_ID, notification)

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
            ampEnabled = args["m"].toBoolean()
            antennaPowerEnabled = args["n"].toBoolean()
            packetSize = args["ps"]?.toInt() ?: packetSize
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

    //Starts the server with the address and port. Wait for the client to connect
    //and then handleClientConnection
    private fun startServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(serverPort))
                }
                LogParameters.appendLine("$logTag, Server started on port $serverPort")
                while (true) {
                    val clientSocket = serverSocket?.accept()
                    LogParameters.appendLine("$logTag, Client connected: ${clientSocket?.inetAddress}")
                    clientSocket?.let { socket ->
                        handleClientConnection(socket)
                        false
                    }
                }
            } catch (e: BindException) {
                LogParameters.appendLine("$logTag, Port $serverPort is already in use, try again...")
                closeEverything()
            }catch (e: Exception) {
                LogParameters.appendLine("$logTag, Server error: ${e.message}")
                closeEverything()
            }
        }
    }

    //Establishs the input and output streams and launchs the coroutines responsible
    //for sending the IQ samples to the outputStream and receiving commands from the
    //client app through the inputStream
    private suspend fun handleClientConnection(clientSocket: Socket) = withContext(Dispatchers.IO) {
        inputStream = clientSocket.getInputStream()
        outputStream = clientSocket.getOutputStream()
        try {
            commandJob = launch(Dispatchers.IO) { listenForCommands(inputStream) }
            sampleJob = launch(Dispatchers.IO) { sendIqSamples(outputStream) }
            // Wait for both coroutines to complete or be cancelled
            joinAll(commandJob!!, sampleJob!!)
        } finally {
            LogParameters.appendLine("$logTag, Client connection closed")
            closeEverything()
        }
    }

    //Listens for commands from the client app and converts them before handling them
    private suspend fun listenForCommands(inputStream: InputStream?) {
        LogParameters.appendLine("$logTag : listenForCommands started")
        val commandBuffer = ByteArray(5)
        try {
            while (true) {
                coroutineContext.ensureActive()
                var bytesRead = 0
                while (bytesRead < 5) {

                    val result = inputStream?.read(commandBuffer, bytesRead, 5 - bytesRead) ?: 0
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
                handleCommand(command, value)
                //delay(10)
            }
        } catch (e: Exception) {
            LogParameters.appendLine("$logTag, Error receiving command: ${e.message}")
            closeEverything()
        }
    }

    //Starts the HackRF in receive mode and gets the IQ samples before offering them to the
    //output stream. No buffer: the client app is responsible for getting the samples
    //fast enough
    private suspend fun sendIqSamples(outputStream: OutputStream?) {
        try {
            LogParameters.appendLine("$logTag : sendIqSamples started")
            val masterLiveShowDataQueue = ArrayBlockingQueue<ByteArray>(2)
            val basebandFilterWidth = rfSource!!.computeBasebandFilterBandwidth(10000000)
            var byteReceivedQueue: ArrayBlockingQueue<ByteArray>? = null
            if (rfSource is HackRF) byteReceivedQueue = rfSource!!.startRX()
            rfSource!!.apply {
                setPacketSize(packetSize)
                LogParameters.appendLine("$logTag : packetSize set")
                setSampleRate(samplerate, 1)
                LogParameters.appendLine("$logTag : samplerate set")
                setFrequency(frequency)
                LogParameters.appendLine("$logTag : frequency set")
                setRxVGAGain(rxVgaGain)
                LogParameters.appendLine("$logTag : VGA gain set")
                setRxLNAGain(rxLnaGain)
                LogParameters.appendLine("$logTag : LNA gain set")
                setRxMixerGain(rxMixGain)
                LogParameters.appendLine("$logTag : Mixer Gain set")
                setPacking(packingEnable)
                LogParameters.appendLine("$logTag : packing set")
                setBasebandFilterBandwidth(basebandFilterWidth)
                LogParameters.appendLine("$logTag : filter BW set")
                setRxLNAAGC(agcLnaEnable)
                LogParameters.appendLine("$logTag : LNA AGC set")
                setRxMixerAGC(agcMixEnable)
                LogParameters.appendLine("$logTag : Mixer AGC set")
                setAmp(false)
                LogParameters.appendLine("$logTag : amp set")
                setAntennaPower(false)
                LogParameters.appendLine("$logTag : antenna power set")
                //setSampleType(sampleType)
                setRawMode(true)
                LogParameters.appendLine("$logTag : raw mode set")
            }
            if (rfSource is Airspy) byteReceivedQueue = rfSource!!.startRX()
            while (true) {
                coroutineContext.ensureActive()
                val receivedBytes: ByteArray? = byteReceivedQueue?.poll(1000, TimeUnit.MILLISECONDS)
                if (receivedBytes == null || receivedBytes.isEmpty()) break
                if (masterLiveShowDataQueue.remainingCapacity() <= 0) masterLiveShowDataQueue.poll()

                outputStream?.apply {
                    write(receivedBytes)
                    flush()
                }
            }
        } catch (e: Exception) {
            LogParameters.appendLine("$logTag, Error sending samples: ${e.message}")
            closeEverything()
        }
    }

    //After receiving a command, this function will process it
    private fun handleCommand(command: Int, value: Long) {
        val boolVal: Boolean = value != 0L
        try {
            when (command) {
                commandSetFrequency -> rfSource?.setFrequency(value)
                commandSetVgaGain -> rfSource?.setRxVGAGain(value.toInt())
                commandSetLnaGain -> rfSource?.setRxLNAGain(value.toInt())
                commandSetMixerGain -> rfSource?.setRxMixerGain(value.toInt())
                commandSetSamplerate -> rfSource?.setSampleRate(value.toInt(), 1)
                commandSetBaseBandFilter -> rfSource?.setBasebandFilterBandwidth(value.toInt())
                commandSetAmpEnable -> rfSource?.setAmp(boolVal)
                commandSetAntennaPowerEnable -> rfSource?.setAntennaPower(boolVal)
                commandSetPacketSize -> rfSource?.setPacketSize(value.toInt())
                commandSetAndroidExit -> {
                    LogParameters.appendLine("$logTag, Received EXIT command. Closing...")
                    closeEverything()
                }
                else -> {
                    LogParameters.appendLine("$logTag, Unknown command: $command - $value")
                }
            }
        } catch (e: Exception) {
            LogParameters.appendLine("$logTag, Problem sending command: $command - $value")
            closeEverything()
        }
        if (command != commandSetFrequency)LogParameters.appendLine("$logTag, handleCommand: $command - $value")
    }

    private fun closeEverything() {
        LogParameters.appendLine("$logTag, Closing driver service. closeEverything()")

        try {
            commandJob?.cancel()
            commandJob = null
            LogParameters.appendLine("$logTag, Command job canceled")

            sampleJob?.cancel()
            sampleJob = null
            LogParameters.appendLine("$logTag, Sample job canceled")

            serviceScope.cancel()
            LogParameters.appendLine("$logTag, Service scope canceled")

            serverSocket?.close()
            serverSocket = null
            LogParameters.appendLine("$logTag, Server socket closed")

            inputStream?.close()
            inputStream = null
            LogParameters.appendLine("$logTag, Input stream closed")

            outputStream?.close()
            outputStream = null
            LogParameters.appendLine("$logTag, Output stream closed")

            rfSource?.stop()
            rfSource = null
            LogParameters.appendLine("$logTag, RF source stopped")

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            LogParameters.appendLine("$logTag, Foreground service stopped")
        } catch (e: Exception) {
            LogParameters.appendLine("$logTag, Error canceling jobs or service scope: ${e.message}")
        }
    }

    override fun onDestroy() {
        closeEverything()
        super.onDestroy()
    }
}


