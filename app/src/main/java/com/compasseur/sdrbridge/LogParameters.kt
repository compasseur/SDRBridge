package com.compasseur.sdrbridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList


/**
 * Module:      LogParameters
 * Description: Manages Log messages to be displayed from MainActivity
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

object LogParameters {
    private val logList = CopyOnWriteArrayList<String>() // Thread-safe collection
    private const val MAX_LOG_ENTRIES = 100

    private val _logFlow = MutableStateFlow("")
    val logFlow: StateFlow<String> get() = _logFlow

    // Append a new line to the log and notify the observer (UI)
    fun appendLine(message: String) {
        if (logList.size >= MAX_LOG_ENTRIES) {
            logList.removeAt(0) // Remove the oldest entry if the list is too long
        }
        logList.add("\u25CF $message")
        _logFlow.value = logList.joinToString(separator = "\n")
    }

    fun clearLog(){
        logList.clear()
        _logFlow.value = ""
    }
}