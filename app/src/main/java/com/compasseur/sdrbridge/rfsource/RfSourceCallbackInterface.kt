package com.compasseur.sdrbridge.rfsource

/**
 * <h1>HackRF USB Library for Android</h1>
 *
 * Module:      RfSourceCallbackInterface
 * Description: This Interface declares a callback method to return a
 * 				HackRF instance to the application after it was opened
 * 				by the initialization routine (asynchronous process
 * 				because it includes requesting the USB permissions)
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2024 Grégoire de Courtivron
 * Copyright (C) 2014 Dennis Mantz
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

interface RfSourceCallbackInterface {

    /**
     * Called by initHackrf() after the device is ready to be used.
     *
     * @param sourcerf Instance of the HackRF that provides access to the device
     */
    fun onRfSourceReady(sourcerf: RfSource)

    /**
     * Called if there was an error when accessing the device.
     *
     * @param message Reason for the Error
     */
    fun onRfSourceError(message: String)
}
