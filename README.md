SDRBridge acts as a driver for the Airspy Mini and HackRF One SDR.

It works the same way as the rtl_tcp driver by [SignalWareLtd](https://github.com/signalwareltd/rtl_tcp_andro-)

The driver connects to the SDR and then sends the IQ samples through a local TCP server.
To start the driver from a client app, launch an intent :

```kotlin
val intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse("iqsrcdriver://?a=127.0.0.1&p=1234&f=$frequency&s=$samplerate"))
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


You can find the possible commands in the Commands.kt file.

When using a HackRF One, is is not possible to use the driver for transmitting ; it can only receive.

When using an Airspy Mini, the driver can only send Raw samples at the moment.

The driver allows to specifiy a custom packet size for receiving samples (both Airspy and HackRF).



