package webauthnkit.core.ctap.ble.peripheral

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import webauthnkit.core.InvalidStateException
import webauthnkit.core.ctap.ble.BLEEvent
import webauthnkit.core.ctap.ble.peripheral.annotation.*
import webauthnkit.core.util.WAKLogger
import java.lang.reflect.Method
import java.util.*


class PeripheralService(val uuid: UUID) {

    companion object {
        val TAG = PeripheralService::class.simpleName
    }

    fun init() {
        // template method
    }

    private val characteristics: MutableMap<String, Characteristic> = mutableMapOf()

    internal fun canHandle(event: BLEEvent, uuid: String): Boolean {
        return if (characteristics.containsKey(uuid)) {
            characteristics.getValue(uuid).canHandle(event)
        } else {
            false
        }
    }

    internal fun dispatchReadRequest(req: ReadRequest, res: ReadResponse) {
        if (!canHandle(BLEEvent.READ, req.uuid)) {
            return
        }
        characteristics.getValue(req.uuid).handleReadRequest(this, req, res)
    }

    internal fun dispatchWriteRequest(req: WriteRequest, res: WriteResponse) {
        if (!canHandle(BLEEvent.WRITE, req.uuid)) {
            return
        }
        val ch = characteristics.getValue(req.uuid)
        ch.handleWriteRequest(this, req, res)
    }

    fun rememberDeviceForNotification(device: BluetoothDevice, chUUID: String) {
        characteristics[chUUID]?.rememberDeviceForNotification(device)
    }

    fun forgetDeviceForNotification(device: BluetoothDevice) {
        characteristics.values.forEach {
            it.forgetDeviceForNotification(device)
        }
    }

    internal fun createRaw(): BluetoothGattService {
        val service = BluetoothGattService(uuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY)
        characteristics.values.forEach {
            service.addCharacteristic(it.createRaw())
        }
        return service
    }


    internal fun analyzeCharacteristicsDefinition() {

        init()

        this::class.java.methods.forEach {

            val readAnnotation: OnRead? = it.getAnnotation(OnRead::class.java)
            if (readAnnotation != null) {
                WAKLogger.d(TAG, "found a method set @OnRead")
                if (validReadHandler(it)) {
                    val ch = getOrCreateCharacteristic(readAnnotation.uuid)
                    ch.addHandler(BLEEvent.READ, it)
                    ch.addProperty(BluetoothGattCharacteristic.PROPERTY_READ)
                    val secure: Secure? = it.getAnnotation(Secure::class.java)
                    if (secure != null && secure.value) {
                        ch.addPermission(BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED)
                    } else {
                        ch.addPermission(BluetoothGattCharacteristic.PERMISSION_READ)
                    }
                    ch.addPermission(BluetoothGattCharacteristic.PERMISSION_READ)
                    val notifiable: Notifiable? = it.getAnnotation(Notifiable::class.java)
                    if (notifiable != null && notifiable.value) {
                        WAKLogger.d(TAG, "set as Notifiable")
                        ch.addProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)
                    }
                } else {
                    // TODO better named exception
                    throw InvalidStateException("Method definition is invalid for @OnRead")
                }
            }

            val writeAnnotation: OnWrite? = it.getAnnotation(OnWrite::class.java)
            if (writeAnnotation != null) {
                WAKLogger.d(TAG, "found a method set @OnWrite")

                if (validWriteHandler(it)) {

                    val ch = getOrCreateCharacteristic(writeAnnotation.uuid)
                    ch.addHandler(BLEEvent.WRITE, it)

                    val secure: Secure? = it.getAnnotation(Secure::class.java)
                    if (secure != null && secure.value) {
                        ch.addPermission(BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED)
                    } else {
                        ch.addPermission(BluetoothGattCharacteristic.PERMISSION_WRITE)
                    }

                    val responseNeeded: ResponseNeeded? = it.getAnnotation(ResponseNeeded::class.java)
                    if (responseNeeded != null) {
                        if (responseNeeded.value) {
                            ch.addProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)
                        } else {
                            ch.addProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                        }
                    } else {
                        ch.addProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)
                        ch.addProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                    }

                } else {
                    // TODO better named exception
                    throw InvalidStateException("Method definition is invalid for @OnWrite")
                }
            }
        }
    }

    private fun getOrCreateCharacteristic(uuid: String): Characteristic {
        if (!characteristics.containsKey(uuid)) {
            characteristics[uuid] = Characteristic(uuid)
        }
        return characteristics[uuid]!!
    }

    private fun validReadHandler(method: Method): Boolean {
        val argTypes = method.parameterTypes
        if (argTypes.size != 2) {
            return false
        }
        return (argTypes[0] == ReadRequest::class && argTypes[1] == ReadResponse::class)
    }

    private fun validWriteHandler(method: Method): Boolean {
        val argTypes = method.parameterTypes
        if (argTypes.size != 2) {
            return false
        }
        return (argTypes[0] == WriteRequest::class && argTypes[1] == WriteResponse::class)
    }

}