package webauthnkit.core.ctap.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import webauthnkit.core.authenticator.internal.InternalAuthenticator
import webauthnkit.core.ctap.CTAPCommandType
import webauthnkit.core.ctap.ble.frame.FrameBuffer
import webauthnkit.core.ctap.ble.frame.FrameSplitter
import webauthnkit.core.ctap.ble.peripheral.*
import webauthnkit.core.ctap.ble.peripheral.annotation.*
import webauthnkit.core.util.CBORReader
import webauthnkit.core.util.CBORWriter
import webauthnkit.core.util.WAKLogger
import java.util.*

interface BLEFIDOServiceListener {
    fun onConnected(address: String)
    fun onDisconnected(address: String)
    fun onClosed()
}

@ExperimentalCoroutinesApi
@ExperimentalUnsignedTypes
class BLEFIDOService(
    private val context:       Context,
    private val authenticator: InternalAuthenticator,
    private val listener:      BLEFIDOServiceListener?
) {

    var timeoutSeconds: Long = 60
    var lockedByDevice: String? = null

    companion object {
        val TAG = BLEFIDOService::class.simpleName
    }

    private val peripheralListener = object: PeripheralListener {

        override fun onAdvertiseSuccess(settingsInEffect: AdvertiseSettings) {
            WAKLogger.d(TAG, "onAdvertiseSuccess")
        }

        override fun onAdvertiseFailure(errorCode: Int) {
            WAKLogger.d(TAG, "onAdvertiseFailure: $errorCode")
            close()
        }

        override fun onConnected(address: String) {
            WAKLogger.d(TAG, "onConnected: $address")

            if (lockedByDevice == null) {
                lockedByDevice = address
                listener?.onConnected(address)
            } else {
                WAKLogger.d(TAG, "onConnected: this device is already locked by $lockedByDevice")
            }
        }

        override fun onDisconnected(address: String) {
            WAKLogger.d(TAG, "onDisconnected: $address")

            if (isLockedBy(address)) {
                listener?.onDisconnected(address)
                close()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            WAKLogger.d(TAG, "onMtuChanged")
            // TODO only for current session
        }
    }

    private fun isLockedBy(deviceAddress: String): Boolean {
        return (lockedByDevice != null && lockedByDevice == deviceAddress)
    }

    private var timer: Timer? = null

    fun start() {
        this.peripheral = createPeripheral()
        peripheral!!.start()
    }

    fun stop() {
        close()
    }

    private fun startTimer() {
        WAKLogger.d(TAG, "startTimer")
        stopTimer()
        timer = Timer()
        timer!!.schedule(object: TimerTask(){
            override fun run() {
                timer = null
                onTimeout()
            }
        }, timeoutSeconds)
    }

    private fun stopTimer() {
        WAKLogger.d(TAG, "stopTimer")
        timer?.cancel()
        timer = null
    }

    private fun onTimeout() {
        WAKLogger.d(TAG, "onTimeout")
        stopTimer()
        closeByBLEError(BLEErrorType.ReqTimeout)
    }

    private fun handleCommand(command: BLECommandType, data: ByteArray) {
        when (command) {
            BLECommandType.Cancel    -> { handleBLECancel()        }
            BLECommandType.MSG       -> { handleBLEMSG(data)       }
            BLECommandType.Error     -> { handleBLEError(data)     }
            BLECommandType.KeepAlive -> { handleBLEKeepAlive(data) }
            BLECommandType.Ping      -> { handleBLEPing(data)      }
        }
    }

    private fun handleBLECancel() {
        WAKLogger.d(TAG, "handleBLE: Cancel")
    }

    private fun handleBLEKeepAlive(value: ByteArray) {
        WAKLogger.d(TAG, "handleBLE: KeepAlive")
        WAKLogger.d(TAG, "should be authenticator -> client")
        closeByBLEError(BLEErrorType.InvalidCmd)
    }

    private fun handleBLEMSG(value: ByteArray) {

        WAKLogger.d(TAG, "handleBLE: MSG")

        if (value.isEmpty()) {
            closeByBLEError(BLEErrorType.InvalidLen)
            return
        }

        val command = value[0].toInt()

        when (command) {

            CTAPCommandType.MakeCredential.rawValue -> {
                if (value.size < 2) {
                    closeByBLEError(BLEErrorType.InvalidLen)
                    return
                }
                handleCTAPMakeCredential(value.sliceArray(0..value.size))
            }

            CTAPCommandType.GetAssertion.rawValue -> {
                if (value.size < 2) {
                    closeByBLEError(BLEErrorType.InvalidLen)
                    return
                }
                handleCTAPGetAssertion(value.sliceArray(0..value.size))
            }

            CTAPCommandType.GetNextAssertion.rawValue -> {
                handleCTAPGetNextAssertion()
            }

            CTAPCommandType.ClientPIN.rawValue -> {
                if (value.size < 2) {
                    closeByBLEError(BLEErrorType.InvalidLen)
                    return
                }
                handleCTAPClientPIN(value.sliceArray(0..value.size))
            }

            CTAPCommandType.GetInfo.rawValue -> {
                handleCTAPGetInfo()
            }

            CTAPCommandType.Reset.rawValue -> {
                handleCTAPReset()
            }

            else -> {
                handleCTAPUnsupportedCommand()
            }
        }
    }

    private fun handleBLEError(value: ByteArray) {
        WAKLogger.d(TAG, "handleBLE: Error")
        closeByBLEError(BLEErrorType.InvalidCmd)
    }

    private fun handleBLEPing(value: ByteArray) {
        WAKLogger.d(TAG, "handleBLE: Ping")
    }

    private fun handleCTAPMakeCredential(value: ByteArray) {
        WAKLogger.d(TAG, "handleCTAP: MakeCredential")
        val params = CBORReader(value).readStringKeyMap()
        if (params == null) {
            closeByBLEError(BLEErrorType.InvalidPar)
            return
        }
    }

    private fun handleCTAPGetAssertion(value: ByteArray) {
        WAKLogger.d(TAG, "handleCTAP: GetAssertion")
        val params = CBORReader(value).readStringKeyMap()
        if (params == null) {
            closeByBLEError(BLEErrorType.InvalidPar)
            return
        }
    }

    private fun handleCTAPGetInfo() {
        WAKLogger.d(TAG, "handleCTAP: GetInfo")

        val info: MutableMap<String, Any> = mutableMapOf()

        info["versions"] = "FIDO_2_0"

        info["aaguid"] = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val options: MutableMap<String, Any> = mutableMapOf()
        options["plat"] = false
        options["rk"]   = true
        options["up"]   = true
        options["uv"]   = true

        info["options"] = options

        // info["maxMsgSize"] = maxMsgSize
        // info["extensions"]  // currently this library doesn't support any extensions

        val cbor = CBORWriter().putStringKeyMap(info).compute()
        handleResponse(BLECommandType.MSG, cbor)
    }

    private var peripheral: Peripheral? = null

    private fun handleResponse(command: BLECommandType, value: ByteArray) {
        val (first, rest) =
            FrameSplitter(maxPacketDataSize = 20).split(command, value)

        sendResultAsNotification(first.toByteArray())

        rest.forEach {
            // TODO delay(50ms)
            sendResultAsNotification(it.toByteArray())
        }
    }

    private fun sendResultAsNotification(value: ByteArray) {
        peripheral?.notifyValue(
                "0xFFFD",
                "F1D0FFF1-DEAA-ECEE-B42F-C9BA7ED623BB",
                 value
        )
    }

    private fun handleCTAPClientPIN(value: ByteArray) {
        WAKLogger.d(TAG, "handleCTAP: ClientPIN")
        WAKLogger.d(TAG, "This feature is not supported on this library. Better verification methods are supported on authenticator side.")
        handleCTAPUnsupportedCommand()
    }

    private fun handleCTAPReset() {
        WAKLogger.d(TAG, "handleCTAP: Reset")
    }

    private fun handleCTAPGetNextAssertion() {
        WAKLogger.d(TAG, "handleCTAP: GetNextAssertion")
        WAKLogger.d(TAG, "This feature is not supported on this library. 'Key Selection' is done on authenticator side.")
        handleCTAPUnsupportedCommand()
    }

    private fun handleCTAPUnsupportedCommand() {
        WAKLogger.d(TAG, "handleCTAP: Unsupported Command")
        closeByBLEError(BLEErrorType.InvalidCmd)
    }

    private fun closeByBLEError(error: BLEErrorType) {
        val b1 = (error.rawValue and 0x0000_ff00).shr(8).toByte()
        val b2= (error.rawValue and 0x0000_00ff).toByte()
        val value = byteArrayOf(b1, b2)

        val (first, rest) =
            FrameSplitter(maxPacketDataSize = 20).split(BLECommandType.Error, value)

        sendResultAsNotification(first.toByteArray())

        rest.forEach {
            // TODO delay(50ms)
            sendResultAsNotification(it.toByteArray())
        }

        // delay 10ms
        close()
    }

    var closed = false

    private fun close() {

        WAKLogger.d(TAG, "close")

        if (closed) {
            WAKLogger.d(TAG, "already closed")
            return
        }

        stopTimer()

        listener?.onClosed()
    }

    private var frameBuffer = FrameBuffer()

    private fun createPeripheral(): Peripheral {

        val service = object: PeripheralService("0xFFFD") {

            @OnWrite("F1D0FFF1-DEAA-ECEE-B42F-C9BA7ED623BB")
            @ResponseNeeded(true)
            @Secure(true)
            fun controlPoint(req: WriteRequest, res: WriteResponse) {
                WAKLogger.d(TAG, "@Write: controlPoint")

                if (!isLockedBy(req.device.address)) {
                    WAKLogger.d(TAG, "@Write: unbound device")
                    res.status = BluetoothGatt.GATT_FAILURE
                    return
                }

                GlobalScope.launch {

                    val error = frameBuffer.putFragment(req.value)

                    error?.let {
                        frameBuffer.clear()
                        closeByBLEError(it)
                        return@launch
                    }

                    if (frameBuffer.isDone()) {
                        handleCommand(frameBuffer.getCommand(), frameBuffer.getData())
                        frameBuffer.clear()
                    }

                }

            }

            @OnRead("F1D0FFF1-DEAA-ECEE-B42F-C9BA7ED623BB")
            @Notifiable(true)
            @Secure(true)
            fun status(req: ReadRequest, res: ReadResponse) {
                WAKLogger.d(TAG, "@Read: status")
                WAKLogger.d(TAG, "This characteristic is just for notification")
                res.status = BluetoothGatt.GATT_FAILURE
                return
            }

            @OnRead("F1D0FFF3-DEAA-ECEE-B42F-C9BA7ED623BB")
            @Secure(true)
            fun controlPointLength(req: ReadRequest, res: ReadResponse) {
                WAKLogger.d(TAG, "@Read: controlPointLength")
                if (!isLockedBy(req.device.address)) {
                    WAKLogger.d(TAG, "@Write: unbound device")
                    res.status = BluetoothGatt.GATT_FAILURE
                    return
                }
                // TODO obtain MTU
            }

            @OnWrite("F1D0FFF4-DEAA-ECEE-B42F-C9BA7ED623BB")
            @ResponseNeeded(true)
            @Secure(true)
            fun serviceRevisionBitFieldWrite(req: WriteRequest, res: WriteResponse) {
                WAKLogger.d(TAG, "@Write: serviceRevisionBitField")

                if (!isLockedBy(req.device.address)) {
                    WAKLogger.d(TAG, "@Write: unbound device")
                    res.status = BluetoothGatt.GATT_FAILURE
                    return
                }

                if (req.value.size == 1 && req.value[0].toInt() == 0x20) {
                    res.status = BluetoothGatt.GATT_SUCCESS
                } else {
                    WAKLogger.d(TAG, "@unsupported bitfield")
                    res.status = BluetoothGatt.GATT_FAILURE
                }
            }

            @OnRead("F1D0FFF4-DEAA-ECEE-B42F-C9BA7ED623BB")
            @Secure(true)
            fun serviceRevisionBitFieldRead(req: ReadRequest, res: ReadResponse) {
                WAKLogger.d(TAG, "@Read: serviceRevisionBitField")

                if (!isLockedBy(req.device.address)) {
                    WAKLogger.d(TAG, "@Write: unbound device")
                    res.status = BluetoothGatt.GATT_FAILURE
                    return
                }

                /*
                 Support Version Bitfield

                 Bit 7: U2F 1.1
                 Bit 6: U2F 1.2
                 Bit 5: FIDO2
                 Bit 4: Reserved
                 Bit 3: Reserved
                 Bit 2: Reserved
                 Bit 1: Reserved
                 Bit 0: Reserved

                 This library support only FIDO2, so the bitfield is 0b0010_0000 (0x20)
                 */
                res.write(byteArrayOf(0x20.toByte()))
            }

        }

        return Peripheral(context, service, peripheralListener)
    }

}