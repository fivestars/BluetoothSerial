package com.megster.cordova

import android.bluetooth.BluetoothAdapter
import com.megster.cordova.BluetoothSerialService.ClosedCallback
import com.megster.cordova.BluetoothSerialService.ConnectedCallback
import org.apache.cordova.*
import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.Exception


/**
 * PhoneGap Plugin for Serial Communication over Bluetooth
 */
class BluetoothSerial : CordovaPlugin() {
    private var connectCallback: CallbackContext? = null
    private var closeCallback: CallbackContext? = null
    private var dataAvailableCallback: CallbackContext? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    @Throws(JSONException::class)
    override fun execute(action: String, args: CordovaArgs, callbackContext: CallbackContext): Boolean {
        LOG.d(TAG, "action = $action")
        if (bluetoothAdapter == null) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        }
        var validAction = true
        when (action) {
            CONNECT -> {
                connect(args, callbackContext)
            }
            DISCONNECT -> {
                BluetoothSerialService.stop()
                callbackContext.success()
            }
            SEND -> {
                val data: ByteArray = args.getArrayBuffer(0)
                BluetoothSerialService.write(data)
                callbackContext.success()
            }
            LISTEN -> {
                try {
                    BluetoothSerialService.start()
                    callbackContext.success()
                } catch (e: Exception) {
                    callbackContext.error(e.toString())
                }
            }
            GET_ADDRESS -> {
                bluetoothAdapter?.run {
                    callbackContext.success(address)
                } ?: callbackContext.error("Unable to access BluetoothAdapter")
            }
            REGISTER_DATA_CALLBACK -> {
                dataAvailableCallback = callbackContext
                val result = PluginResult(PluginResult.Status.NO_RESULT)
                result.keepCallback = true
                callbackContext.sendPluginResult(result)
            }
            REGISTER_CONNECT_CALLBACK -> {
                connectCallback = callbackContext
                BluetoothSerialService.registerConnectedCallback(object : ConnectedCallback {
                    override fun connected() {
                        notifyConnectionSuccess()
                    }
                })
                val result = PluginResult(PluginResult.Status.NO_RESULT)
                result.keepCallback = true
                callbackContext.sendPluginResult(result)
            }
            REGISTER_CLOSE_CALLBACK -> {
                closeCallback = callbackContext
                BluetoothSerialService.registerClosedCallback(object : ClosedCallback {
                    override fun closed() {
                        notifyConnectionLost()
                    }
                })
                val result = PluginResult(PluginResult.Status.NO_RESULT)
                result.keepCallback = true
                callbackContext.sendPluginResult(result)
            }
            else -> {
                validAction = false
            }
        }
        return validAction
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothSerialService.stop()
    }

    @Throws(JSONException::class)
    private fun connect(args: CordovaArgs, callbackContext: CallbackContext) {
        val macAddress: String = args.getString(0)
        val device = bluetoothAdapter?.getRemoteDevice(macAddress)
        if (device != null) {
            BluetoothSerialService.connect(device)
            val result = PluginResult(PluginResult.Status.NO_RESULT)
            callbackContext.sendPluginResult(result)
        } else {
            callbackContext.error("Could not connect to $macAddress")
        }
    }

    private fun notifyConnectionLost() {
        closeCallback?.success()
    }

    private fun notifyConnectionSuccess() {
        val result = PluginResult(PluginResult.Status.OK)
        result.keepCallback = true
        connectCallback?.sendPluginResult(result)
    }

    private fun sendRawDataToSubscriber(data: ByteArray?) {
        if (data != null && data.isNotEmpty()) {
            val result = PluginResult(PluginResult.Status.OK, data)
            result.keepCallback = true
            dataAvailableCallback?.sendPluginResult(result)
        }
    }

    private fun getBluetoothMacAddress(): String? {
        var bluetoothMacAddress: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val serviceField: Field? = bluetoothAdapter?.javaClass?.getDeclaredField("mService")
                serviceField?.isAccessible = true
                val btManagerService: Any? = serviceField?.get(bluetoothAdapter)
                btManagerService?.run {
                    bluetoothMacAddress =
                            javaClass.getMethod("getAddress").invoke(btManagerService) as String
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to retrieve Bluetooth MAC Address: $e")
            }
        } else {
            bluetoothMacAddress = bluetoothAdapter?.address
        }
        return bluetoothMacAddress
    }

    companion object {
        private const val CONNECT = "connect"
        private const val LISTEN = "listen"
        private const val DISCONNECT = "disconnect"
        private const val SEND = "send"
        private const val GET_ADDRESS = "getAddress"
        private const val REGISTER_DATA_CALLBACK = "registerDataCallback"
        private const val REGISTER_CONNECT_CALLBACK = "registerConnectCallback"
        private const val REGISTER_CLOSE_CALLBACK = "registerCloseCallback"

        // Debugging
        private const val TAG = "BluetoothSerial"
    }
}
