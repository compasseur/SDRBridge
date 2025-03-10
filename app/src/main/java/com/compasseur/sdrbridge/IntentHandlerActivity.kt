package com.compasseur.sdrbridge

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Module:      IntentHandlerActivity
 * Description: Receives intent from client app and launchs MainActivity with a new intent
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

class IntentHandlerActivity : AppCompatActivity() {

    private var logTag = "IntentHandlerActivityTag"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.let { receivedIntent ->
            if (receivedIntent.action == Intent.ACTION_VIEW) {
                val receivedData: Uri? = receivedIntent.data

                LogParameters.appendLine("$logTag, Received: $receivedData")

                val resultIntent = Intent().apply {
                    putExtra("message", "HackRF Initialized")
                }
                setResult(1234, resultIntent) // Send result back immediately

                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("fromResultHandler", true)
                    data = receivedData
                }
                startActivity(mainIntent)
                LogParameters.appendLine("$logTag, received Intent: $intent")
                finish()
            } else {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }
    }
}