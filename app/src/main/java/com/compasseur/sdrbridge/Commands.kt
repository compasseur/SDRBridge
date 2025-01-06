package com.compasseur.sdrbridge

/**
 * Module:      Commands.kt
 * Description: List of possible commands for the RfSource.
 * 				Most commands are compatible with the rtl-tcp andro
 * 				driver for RTL2832U / R820T2-R860 SDR dongles.
 * 				Somme commands are HackRF or Airspy specific.
 *
 * Copyright (C) 2024 Grégoire de Courtivron
 * Copyright (C) 2022 by Signalware Ltd <driver@sdrtouch.com>
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

//From rtl_tcp andro
const val commandSetFrequency = 0x01  // 1
const val commandSetSamplerate = 0x02  // 2
const val commandSetGainMode = 0x03  // 3
const val commandSetGain = 0x04  // 4
const val commandSetFreqCorrection = 0x05  // 5
const val commandSetIfTunerGain = 0x06  // 6
const val commandSetTestMode = 0x07  // 7
const val commandSetAgcMode = 0x08  // 8
const val commandSetDirectSampling = 0x09  // 9
const val commandSetOffsetTuning = 0x0a  // 10
const val commandSetRtlXtal = 0x0b  // 11
const val commandSetTunerXtal = 0x0c  // 12
const val commandSetTunerGainById = 0x0d  // 13
const val commandSetAndroidExit = 0x7e  // 126
const val commandSetAndroidGainByPercentage = 0x7f  // 127
const val commandSetAndroidEnable16BitsSigned = 0x80  // 128

//SDR Play commands
const val commandSetAntenna = 0x1f  // 31
const val commandSetLnaState = 0x20  // 32
const val commandSetIfGainR = 0x21  // 33
const val commandSetAgc = 0x22  // 34
const val commandSetAgcSetPoint = 0x23  // 35
const val commandSetBiasT = 0x25  // 37
const val commandSetMixerGain = 0x31  // 49

//HackRF One
const val commandSetTransceiverMode = 0x26  // 38
const val commandSetBaseBandFilter = 0x27  // 39
const val commandSetAmpEnable = 0x28  // 40
const val commandSetLnaGain = 0x29  // 41
const val commandSetVgaGain = 0x30  // 48
const val commandSetAntennaPowerEnable = 0x32  // 50

const val commandSetPacketSize = 0x33 // 51