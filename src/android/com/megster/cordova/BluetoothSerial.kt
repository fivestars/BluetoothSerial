package com.megster.cordova

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import org.apache.cordova.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Field

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
                BluetoothSerialService.start()
                val result = PluginResult(PluginResult.Status.NO_RESULT)
                callbackContext.sendPluginResult(result)
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
                val result = PluginResult(PluginResult.Status.NO_RESULT)
                result.keepCallback = true
                callbackContext.sendPluginResult(result)
            }
            REGISTER_CLOSE_CALLBACK -> {
                closeCallback = callbackContext
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

    private fun notifyConnectionLost(error: String?) {
        closeCallback?.error(error)
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

    @Throws(JSONException::class)
    override fun onRequestPermissionResult(requestCode: Int, permissions: Array<String?>?,
                                           grantResults: IntArray) {

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

    var bluetoothIntentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)

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
        // Message types sent from the BluetoothSerialService Handler
        const val MESSAGE_STATE_CHANGE = 1
        const val MESSAGE_WRITE = 3
        const val MESSAGE_DEVICE_NAME = 4
        const val MESSAGE_TOAST = 5
        const val MESSAGE_READ_RAW = 6
        // Key names received from the BluetoothChatService Handler
        const val DEVICE_NAME = "device_name"
        const val TOAST = "toast"
        private const val CHECK_PERMISSIONS_REQ_CODE = 2
    }
}
