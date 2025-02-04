package com.compasseur.sdrbridge.rfsource

import android.util.Log
import java.lang.Exception
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class AirspyInt16Converter(
    sampleType: Int, packingEnabled: Boolean, inputQueue: ArrayBlockingQueue<ByteArray>,
    inputReturnQueue: ArrayBlockingQueue<ByteArray>, outputQueue: ArrayBlockingQueue<ShortArray>,
    outputPoolQueue: ArrayBlockingQueue<ShortArray>
) : Thread() {
    private val LOGTAG: String = "AirspyInt16Converter"
    private var stopRequested = false
    private var sampleType = -1
    private var packingEnabled = false
    private val inputQueue: ArrayBlockingQueue<ByteArray> // Queue from which the input samples are taken
    private val inputReturnQueue: ArrayBlockingQueue<ByteArray> // Queue to return the used input buffers to the pool
    private val outputQueue: ArrayBlockingQueue<ShortArray> // Queue to deliver the converted samples
    private val outputPoolQueue: ArrayBlockingQueue<ShortArray> // Queue from which the output buffers are taken
    private var len = 0
    private var firIndex = 0
    private var delayIndex = 0
    private var oldX: Short = 0
    private var oldY: Short = 0
    private var oldE = 0
    private val firQueue: IntArray
    private val delayLine: ShortArray

    /**
     * Constructor for the int16 Converter
     * @param sampleType        Desired sample type of the output samples (Airspy.AIRSPY_SAMPLE_INT16_IQ, *_INT16_REAL or *_UINT16_REAL
     * @param packingEnabled    Indicates if the input samples are packed
     * @param inputQueue        Queue from which the input samples are taken
     * @param inputReturnQueue    Queue to return the used input buffers to the pool
     * @param outputQueue        Queue to deliver the converted samples
     * @param outputPoolQueue    Queue from which the output buffers are taken
     * @throws Exception if the sample type does not match a int16 based type
     */
    init {
        if (sampleType != Airspy.AIRSPY_SAMPLE_INT16_IQ && sampleType != Airspy.AIRSPY_SAMPLE_INT16_REAL && sampleType != Airspy.AIRSPY_SAMPLE_UINT16_REAL) {
            Log.e(LOGTAG, "constructor: Invalid sample type: $sampleType")
            throw Exception("Invalid sample type: $sampleType")
        }
        this.sampleType = sampleType
        this.packingEnabled = packingEnabled
        this.inputQueue = inputQueue
        this.inputReturnQueue = inputReturnQueue
        this.outputQueue = outputQueue
        this.outputPoolQueue = outputPoolQueue
        this.len = HB_KERNEL_INT16.size
        this.delayLine = ShortArray(this.len / 2)
        this.firQueue = IntArray(this.len * SIZE_FACTOR)
    }

    fun requestStop() {
        this.stopRequested = true
    }

    private fun firInterleaved(samples: ShortArray) {
        var acc: Int

        var i = 0
        while (i < samples.size) {
            firQueue[firIndex] = samples[i].toInt()
            acc = 0

            // Auto vectorization works on VS2012, VS2013 and GCC
            for (j in 0 until len) {
                acc += HB_KERNEL_INT16.get(j) * firQueue[firIndex + j]
            }

            if (--firIndex < 0) {
                firIndex = len * (SIZE_FACTOR - 1)
                System.arraycopy(firQueue, 0, firQueue, firIndex + 1, len - 1)
            }

            samples[i] = (acc shr 15).toShort()
            i += 2
        }
    }

    private fun delayInterleaved(samples: ShortArray, offset: Int) {
        val halfLen = len shr 1
        var res: Short

        var i = offset
        while (i < samples.size) {
            res = delayLine[delayIndex]
            delayLine[delayIndex] = samples[i]
            samples[i] = res

            if (++delayIndex >= halfLen) {
                delayIndex = 0
            }
            i += 2
        }
    }

    private fun removeDC(samples: ShortArray) {
        var u: Int
        var x: Short
        var y: Short
        var w: Short
        var s: Short

        for (i in samples.indices) {
            x = samples[i]
            w = (x - oldX).toShort()
            u = oldE + oldY * 32100
            s = (u shr 15).toShort()
            y = (w + s).toShort()
            oldE = u - (s.toInt() shl 15)
            oldX = x
            oldY = y
            samples[i] = y
        }
    }

    private fun translateFs4(samples: ShortArray) {
        var i = 0
        while (i < samples.size) {
            samples[i] = (-samples[i]).toShort()
            samples[i + 1] = (-samples[i + 1] shr 1).toShort()
            //samples[i + 2] = samples[i + 2];
            samples[i + 3] = (samples[i + 3].toInt() shr 1).toShort()
            i += 4
        }

        firInterleaved(samples)
        delayInterleaved(samples, 1)
    }

    fun processSamplesInt16(samples: ShortArray) {
        removeDC(samples)
        translateFs4(samples)
    }

    override fun run() {
        var inputBuffer: ByteArray?
        var origInputBuffer: ByteArray?
        var packingBuffer: ByteArray? = null
        var outputBuffer: ShortArray? = null

        while (!stopRequested) {
            // First we get a fresh set of input and output buffers from the queues:
            try {
                outputBuffer = outputPoolQueue.poll(1000, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                // Note: If the output buffer pool (filled by the user) is empty and the timeout is hit,
                // we just wait again. After some time the Airspy class will stop because its usbQueue
                // will run full.
                Log.e(LOGTAG, "run: Interrupted while waiting for buffers in the output pool. Lets wait for another round...")
                continue
            }
            if (outputBuffer == null) {
                Log.e(LOGTAG, "run: No output buffers available in the pool. Let's query it again...")
                continue
            }

            try {
                origInputBuffer = inputQueue.poll(1000, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Log.e(LOGTAG, "run: Interrpted while waiting for buffers in the input queue. Stop!")
                stopRequested = true
                continue
            }
            if (origInputBuffer == null) {
                Log.e(LOGTAG, "run: No input buffers available in the queue. Stop!")
                stopRequested = true
                continue
            }

            // Maybe unpack the samples first:
            /*if (packingEnabled) {
                val unpackedLength = origInputBuffer.size * 4 / 3
                if (packingBuffer == null || packingBuffer.size != unpackedLength) packingBuffer = ByteArray(unpackedLength)
                Airspy.unpackSamples(origInputBuffer, packingBuffer, unpackedLength)
                inputBuffer = packingBuffer
            } else {
                inputBuffer = origInputBuffer
            }*/
            inputBuffer = origInputBuffer

            when (sampleType) {
                Airspy.AIRSPY_SAMPLE_INT16_IQ -> {
                    convertSamplesInt16(inputBuffer, outputBuffer, outputBuffer.size)
                    processSamplesInt16(outputBuffer)
                }

                Airspy.AIRSPY_SAMPLE_INT16_REAL -> convertSamplesInt16(inputBuffer, outputBuffer, outputBuffer.size)
                Airspy.AIRSPY_SAMPLE_UINT16_REAL -> convertSamplesUint16(inputBuffer, outputBuffer, outputBuffer.size)
            }
            // Finally we return the buffers to the corresponding queues:
            inputReturnQueue.offer(origInputBuffer)
            outputQueue.offer(outputBuffer)
        }
    }

    companion object {
        private const val LOGTAG = "AirspyInt16Converter"
        private const val SIZE_FACTOR = 16

        // Hilbert kernel with zeros removed:
        val HB_KERNEL_INT16: ShortArray = shortArrayOf(
            -33,
            56,
            -100,
            166,
            -259,
            389,
            -571,
            829,
            -1220,
            1885,
            -3353,
            10389,
            10389,
            -3353,
            1885,
            -1220,
            829,
            -571,
            389,
            -259,
            166,
            -100,
            56,
            -33
        )

        /**
         * Converts a byte array (little endian, unsigned-12bit-integer) to a short array (signed-16bit-integer)
         *
         * @param src   input samples (little endian, unsigned-12bit-integer); min. twice the size of output
         * @param dest  output samples (signed-16bit-integer); min. of size 'count'
         * @param count number of samples to process
         */
        fun convertSamplesInt16(src: ByteArray, dest: ShortArray, count: Int) {
            if (src.size < 2 * count || dest.size < count) {
                Log.e(LOGTAG, "convertSamplesInt16: input buffers have invalid length: src=" + src.size + " dest=" + dest.size)
                return
            }
            for (i in 0 until count) {
                /*   src[2i+1] src[2i]
				 *  [--------|--------]
				 *      (xxxx xxxxxxxx) -2048
				 *                      << 4
				 *  [xxxxxxxx|xxxxxxxx] dest[i]
				 */
                dest[i] = (((((src[2 * i + 1].toInt() and 0x0F) shl 8) + (src[2 * i].toInt() and 0xFF)) - 2048) shl 4).toShort()
            }
        }

        /**
         * Converts a byte array (little endian, unsigned-12bit-integer) to a short array (unsigned-16bit-integer)
         *
         * @param src   input samples (little endian, unsigned-12bit-integer); min. twice the size of output
         * @param dest  output samples (unsigned-16bit-integer); min. of size 'count'
         * @param count number of samples to process
         */
        fun convertSamplesUint16(src: ByteArray, dest: ShortArray, count: Int) {
            if (src.size < 2 * count || dest.size < count) {
                Log.e(LOGTAG, "convertSamplesUint16: input buffers have invalid length: src=" + src.size + " dest=" + dest.size)
                return
            }
            for (i in 0 until count) {
                /*   src[2i+1] src[2i]
				 *  [--------|--------]
				 *      (xxxx xxxxxxxx)
				 *                      << 4
				 *  [xxxxxxxx|xxxxxxxx] dest[i]
				 */
                dest[i] = ((((src[2 * i + 1].toInt() and 0x0F) shl 8) + (src[2 * i].toInt() and 0xFF)) shl 4).toShort()
            }
        }



    }
}