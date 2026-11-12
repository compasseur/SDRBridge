SDRBridge acts as a driver for the Airspy Mini, HackRF One and RTL2832U based SDR (R820T, R820T2, R860, R828D tuners, including the RTL-SDR Blog V4).

It works the same way as the rtl_tcp driver by [SignalWareLtd](https://github.com/signalwareltd/rtl_tcp_andro-)
The driver connects to the SDR through an OTG cable and then sends the IQ samples through a local TCP server.

To start the driver from a client app, launch an intent :

```kotlin
val intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse("iqsrcdriver://?a=$address&p=$port&f=$frequency&s=$samplerate"))
intent.setClassName("com.compasseur.sdrbridge", "com.compasseur.sdrbridge.IntentHandlerActivity")
startActivityForResult(intent, 1234)
```

then handle the response this way: 

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1234) return
        if (resultCode == 1234 || resultCode == AppCompatActivity.RESULT_OK) {
            //The flow of IQ samples has started
        } else {
            if (resultCode == 0) {
                Toast.makeText(contextRef, "SDR dongle not detected", Toast.LENGTH_SHORT).show()
            } else {
                val errmsg = data!!.getStringExtra("detailed_exception_message")
                Toast.makeText(contextRef, errmsg.toString(), Toast.LENGTH_SHORT).show()
            }
        }
    }
```

After receiving an intent, the driver will look for a connected compatible SDR device and check for USB permission before connecting and starting to receive IQ samples.

When using a HackRF One, the transmit function can be used. The flow of samples is than inversed: the input stream of the driver receives the samples from the output stream of the client app.
To set the driver in transmit mode, when launching the intent, add this boolean parameter: "tx" and set it to "true".
When in transmit mode, the client app can not send commands as the output stream is already used for the flow of IQ samples.
The Airspy can not transmit.

When using an Airspy Mini, the driver can only send Raw samples.

When using an RTL-SDR, the driver sends the raw 8 bits unsigned IQ samples, preceded by the 12 bytes rtl_tcp header ("RTL0", tuner type, number of gains).
Two optional intent parameters are available: "g" (tuner gain in tenths of dB, 0 = automatic gain) and "ppm" (frequency correction).
The RTL-SDR also accepts the rtl_tcp commands (gain mode, gain, frequency correction, IF gain, AGC, direct sampling, offset tuning, xtal, gain by index, gain by percentage). Its bias-T is switched with the "set antenna power" command.
The RTL-SDR can not transmit.

The driver allows to specifiy a custom usb request buffer size size for receiving samples (both Airspy and HackRF) (default is 1024 * 16).

Compatible commands:
- set frequency
- set VGA gain
- set LNA gain
- set Mixer Gain (Airspy Mini only)
- set samplerate
- set baseband filter (HackRF only)
- set amp
- set antenna power
- set packet size
- set quit driver

more infos in [Commands.kt](https://github.com/compasseur/SDRBridge/blob/main/app/src/main/java/com/compasseur/sdrbridge/Commands.kt)



The SDRBridge driver app is based on the work by:
- Dennis Mantz
- Jared Boone
- Benjamin Vernoux
- Youssef Touil
- Ian Gilmour
- Michael Ossmann
- SignalWare Ltd
