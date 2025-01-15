SDRBridge acts as a driver for the Airspy Mini and HackRF One SDR.

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


When using a HackRF One, is is not possible to use the driver for transmitting ; it can only receive.

When using an Airspy Mini, the driver can only send Raw samples at the moment.

The driver allows to specifiy a custom usb request buffer size size for receiving samples (both Airspy and HackRF) (default is 1024 * 16).

Compatible commands (more infos in [Commands.kt](https://github.com/compasseur/SDRBridge/blob/main/app/src/main/java/com/compasseur/sdrbridge/Commands.kt):
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


The SDRBridge driver app is based on the work by:
- Dennis Mantz
- Jared Boone
- Benjamin Vernoux
- Youssef Touil
- Ian Gilmour
- Michael Ossmann
- SignalWare Ltd
