SDRBridge is an Android driver app for these SDR dongles:                                                                                                                                                    
  - **HackRF One** (receive and transmit)                                                                                                    
  - **Airspy Mini** (receive)                                                                                                                
  - **RTL2832U based dongles** with R820T, R820T2, R860 or R828D tuners, including the RTL-SDR Blog V4 (receive)
 
Requires Android 10 or later.


It works the same way as the rtl_tcp driver by [SignalWare Ltd](https://github.com/signalwareltd/rtl_tcp_andro-): the driver connects to the SDR through an OTG cable, then sends the IQ samples to the client app through a local TCP server. The client app sends its commands (frequency, gains, sample rate...) through the same connection.



Starting the driver from a client app

Launch an intent:                                                                                                                                                                               
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

After receiving an intent, the driver looks for a connected compatible SDR and checks the USB permission before connecting and starting to receive IQ samples.                                        


When an SDR is plugged in, Android offers to open SDRBridge. If "Always open SDRBridge" is chosen, the USB permission is then granted automatically on every reconnection, without the permission dialog.

Intent parameters                                                                                                                            

Parameter - Description (Devices)                                                                                                            

`a` - address of the TCP server (all)
`p` - port of the TCP server (default 1234) (all)
`f` - frequency (Hz) (all)                                                                                                                   
`s` - sample rate (Hz) (all)                                                                                                                 
`ps` - USB request buffer size (default 1024 * 16) (HackRF, Airspy)                                                                          
`b` - baseband filter bandwidth (Hz) (HackRF)                                                                                                
`v` - RX VGA gain (HackRF, Airspy)                                                                                                           
`l` - RX LNA gain (HackRF, Airspy)                                                                                                           
`m` - amp enabled (`true`/`false`) (HackRF)                                                                                                  
`n` - antenna power enabled (`true`/`false`) (HackRF, Airspy)                                                                                
`tx` - transmit mode (`true`/`false`) (HackRF)                                                                                               
`txv` - TX VGA gain (HackRF)                                                                                                                 
`g` - tuner gain in tenths of dB, 0 = automatic gain (RTL-SDR)                                                                               
`ppm` - frequency correction (RTL-SDR)   

Devices                                                                                                                                      

- HackRF One: the transmit function can be used. The flow of samples is then reversed: the input stream of the driver receives the samples from the output stream of the client app. To set the driver in transmit mode, add the boolean parameter `tx=true` to the intent. In transmit mode the client app can not send commands, because the output stream is already used for the flow of IQ samples.                             
- Airspy Mini: the driver only sends raw samples. The Airspy can not transmit.  
- RTL-SDR: the driver uses librtlsdr and sends the raw 8-bit unsigned IQ samples, preceded by the 12-byte rtl_tcp header ("RTL0", tuner type, number of gains), so existing rtl_tcp clients work unchanged. It accepts the rtl_tcp commands (gain mode, gain, frequency correction, IF gain, AGC, direct sampling, offset tuning, xtal, gain by index, gain by percentage). Its bias-T is switched with the "set antenna power" command. The RTL-SDR can not transmit.

Commands

- set frequency                                                                                                                              
- set sample rate                                                                                                                            
- set VGA gain                                                                                                                               
- set LNA gain                                                                                                                               
- set mixer gain (Airspy Mini only)                                                                                                          
- set baseband filter (HackRF only)                                                                                                          
- set amp (HackRF only)                                                                                                                      
- set antenna power / bias-T                                                                                                                 
- set packet size                                                                                                                            
- rtl_tcp commands (RTL-SDR, see above)                                                                                                      
- enable retune codes (see below)                                                                                                            
- quit driver   

More infos in [Commands.kt](https://github.com/compasseur/SDRBridge/blob/main/app/src/main/java/com/compasseur/sdrbridge/Commands.kt).

-> Fast retuning and retune codes                                                                                                                                                                       
  When the frequency changes, the driver drops the samples recorded during the retune and while the tuner settles, plus the packets of the previous frequency still waiting to be sent. The client only receives samples of the new frequency, which makes frequency sweeps (spectrum scanning) fast and clean on all three dongles.                                                                          
So that the client knows exactly where the new frequency starts, it can enable -retune codes- by sending the command `0x40` (`commandEnableRetuneCodes`), preferably right after connecting. After each retune, a 16-byte code is then inserted in the IQ stream, right before the first sample of the new frequency: 

Bytes: Content
0–7: magic: `53 44 52 42 32 38 30 32`

8–11: frequency (Hz), big-endian 32-bit integer

12–15: number of the frequency command (1 for the first one received), big-endian 32-bit integer

After sending a frequency command, the client should drop the incoming samples until it receives the code with the matching number, then processes the samples that follow.                                                              

Retune codes are disabled by default, so clients that don't use them get a normal sample stream. 

The SDRBridge driver app is based on the work by:                                                                                                                                                     
- Dennis Mantz                                                                                                                               
- Jared Boone                                                                                                                                
- Benjamin Vernoux                                                                                                                           
- Youssef Touil                                                                                                                              
- Ian Gilmour                                                                                                                                
- Michael Ossmann                                                                                                                            
- SignalWare Ltd                                                                                                                             
- Steve Markgraf, Dimitri Stolnikov, Mauro Carvalho Chehab and the osmocom contributors (librtlsdr)                                          
- the libusb project 
