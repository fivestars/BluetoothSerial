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

    private val bluetoothSerialService: BluetoothSerialService = BluetoothSerialService()
    var buffer = StringBuffer()
    private var permissionCallback: CallbackContext? = null
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
                bluetoothSerialService.stop()
                callbackContext.success()
            }
            SEND -> {
                val data: ByteArray = args.getArrayBuffer(0)
                bluetoothSerialService.write(data)
                callbackContext.success()
            }
            LISTEN -> {
                bluetoothSerialService.start()
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
        bluetoothSerialService.stop()
    }

    @Throws(JSONException::class)
    private fun discoverUnpairedDevices(callbackContext: CallbackContext?) {
        val ddc: CallbackContext? = deviceDiscoveredCallback
        val discoverReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            private val unpairedDevices = JSONArray()
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                if (BluetoothDevice.ACTION_FOUND == action) {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    try {
                        val o = deviceToJSON(device)
                        unpairedDevices.put(o)
                        if (ddc != null) {
                            val res = PluginResult(PluginResult.Status.OK, o)
                            res.keepCallback = true
                            ddc.sendPluginResult(res)
                        }
                    } catch (e: JSONException) { // This shouldn't happen, log and ignore
                        Log.e(TAG, "Problem converting device to JSON", e)
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action) {
                    callbackContext?.success(unpairedDevices)
                    cordova.activity.unregisterReceiver(this)
                }
            }
        }
        val activity: Activity = cordova.activity
        activity.registerReceiver(discoverReceiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        activity.registerReceiver(discoverReceiver, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED))
        bluetoothAdapter!!.startDiscovery()
    }

    @Throws(JSONException::class)
    private fun deviceToJSON(device: BluetoothDevice): JSONObject {
        val json = JSONObject()
        json.put("name", device.name)
        json.put("address", device.address)
        json.put("id", device.address)
        if (device.bluetoothClass != null) {
            json.put("class", device.bluetoothClass.deviceClass)
        }
        return json
    }

    @Throws(JSONException::class)
    private fun connect(args: CordovaArgs, callbackContext: CallbackContext) {
        val macAddress: String = args.getString(0)
        val device = bluetoothAdapter!!.getRemoteDevice(macAddress)
        if (device != null) {
            bluetoothSerialService.connect(device)
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
            onDataAvailableCallback?.sendPluginResult(result)
        }
    }

    @Throws(JSONException::class)
    override fun onRequestPermissionResult(requestCode: Int, permissions: Array<String?>?,
                                           grantResults: IntArray) {
        for (result in grantResults) {
            if (result == PackageManager.PERMISSION_DENIED) {
                LOG.d(TAG, "User *rejected* location permission")
                permissionCallback?.sendPluginResult(PluginResult(
                        PluginResult.Status.ERROR,
                        "Location permission is required to discover unpaired devices.")
                )
                return
            }
        }
        when (requestCode) {
            CHECK_PERMISSIONS_REQ_CODE -> {
                LOG.d(TAG, "User granted location permission")
                discoverUnpairedDevices(permissionCallback)
            }
        }
    }

    private val bluetoothStatusReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> {
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> {
                    }
                    BluetoothAdapter.STATE_ON -> {
                        // Bluetooth has been on
                        Log.d(TAG, "User enabled Bluetooth")
                        if (enableBluetoothCallback != null) {
                            enableBluetoothCallback?.success()
                        }
                        cleanUpEnableBluetooth()
                    }
                    BluetoothAdapter.STATE_TURNING_ON -> {
                    }
                    else -> {
                        Log.d(TAG, "Error enabling bluetooth")
                        if (enableBluetoothCallback != null) {
                            enableBluetoothCallback?.error("Error enabling bluetooth")
                        }
                        cleanUpEnableBluetooth()
                    }
                }
            }
        }
    }

    private fun cleanUpEnableBluetooth() {
        enableBluetoothCallback = null
        val activity: Activity = cordova.activity
        activity.unregisterReceiver(bluetoothStatusReceiver)
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
        // actions
        private const val CONNECT = "connectInsecure"
        private const val LISTEN = "subscribeRaw"
        private const val DISCONNECT = "disconnect"
        private const val SEND = "write"
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
