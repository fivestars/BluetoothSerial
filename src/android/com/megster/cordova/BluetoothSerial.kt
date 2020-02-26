package com.megster.cordova

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Message
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
    private var dataAvailableCallback: CallbackContext? = null
    private var rawDataAvailableCallback: CallbackContext? = null
    private var enableBluetoothCallback: CallbackContext? = null
    private var deviceDiscoveredCallback: CallbackContext? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private val handler: Handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_READ -> {
                    buffer.append(msg.obj as String)
                    if (dataAvailableCallback != null) {
                        sendDataToSubscriber()
                    }
                }
                MESSAGE_READ_RAW -> if (rawDataAvailableCallback != null) {
                    val bytes = msg.obj as ByteArray
                    sendRawDataToSubscriber(bytes)
                }
                MESSAGE_STATE_CHANGE -> {
                    if (BuildConfig.DEBUG) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1)
                    when (msg.arg1) {
                        BluetoothSerialService.STATE_CONNECTED -> {
                            Log.i(TAG, "BluetoothSerialService.STATE_CONNECTED")
                            notifyConnectionSuccess()
                        }
                        BluetoothSerialService.STATE_CONNECTING -> Log.i(TAG, "BluetoothSerialService.STATE_CONNECTING")
                        BluetoothSerialService.STATE_LISTEN -> Log.i(TAG, "BluetoothSerialService.STATE_LISTEN")
                        BluetoothSerialService.STATE_NONE -> Log.i(TAG, "BluetoothSerialService.STATE_NONE")
                    }
                }
                MESSAGE_WRITE -> {
                }
                MESSAGE_DEVICE_NAME -> Log.i(TAG, msg.data.getString(DEVICE_NAME))
                MESSAGE_TOAST -> {
                    val message = msg.data.getString(TOAST)
                    notifyConnectionLost(message)
                }
            }
        }
    }

    private val bluetoothSerialService: BluetoothSerialService = BluetoothSerialService(handler)
    var buffer = StringBuffer()
    private var delimiter: String? = null
    private var permissionCallback: CallbackContext? = null
    @Throws(JSONException::class)
    override fun execute(action: String, args: CordovaArgs, callbackContext: CallbackContext): Boolean {
        LOG.d(TAG, "action = $action")
        if (bluetoothAdapter == null) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        }
        var validAction = true
        when (action) {
            CONNECT_INSECURE -> { // see Android docs about Insecure RFCOMM http://goo.gl/1mFjZY
                val secure = false
                connect(args, secure, callbackContext)
            }
            DISCONNECT -> {
                connectCallback = null
                bluetoothSerialService.stop()
                callbackContext.success()
            }
            SEND -> {
                val data: ByteArray = args.getArrayBuffer(0)
                bluetoothSerialService.write(data)
                callbackContext.success()
            }
            SUBSCRIBE_RAW -> {
                rawDataAvailableCallback = callbackContext
                val result = PluginResult(PluginResult.Status.NO_RESULT)
                result.keepCallback = true
                callbackContext.sendPluginResult(result)
            }
            GET_ADDRESS -> {
                bluetoothAdapter?.run {
                    callbackContext.success(address)
                } ?: callbackContext.error("Unable to access BluetoothAdapter")
            }
            else -> {
                validAction = false
            }
        }
        return validAction
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothSerialService?.stop()
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
    private fun connect(args: CordovaArgs, secure: Boolean, callbackContext: CallbackContext) {
        val macAddress: String = args.getString(0)
        val device = bluetoothAdapter!!.getRemoteDevice(macAddress)
        if (device != null) {
            connectCallback = callbackContext
            bluetoothSerialService.connect(device, secure)
            buffer.setLength(0)
            val result = PluginResult(PluginResult.Status.NO_RESULT)
            result.keepCallback = true
            callbackContext.sendPluginResult(result)
        } else {
            callbackContext.error("Could not connect to $macAddress")
        }
    }


    private fun notifyConnectionLost(error: String?) {
        if (connectCallback != null) {
            connectCallback?.error(error)
            connectCallback = null
        }
    }

    private fun notifyConnectionSuccess() {
        if (connectCallback != null) {
            val result = PluginResult(PluginResult.Status.OK)
            result.keepCallback = true
            connectCallback?.sendPluginResult(result)
        }
    }

    private fun sendRawDataToSubscriber(data: ByteArray?) {
        if (data != null && data.isNotEmpty()) {
            val result = PluginResult(PluginResult.Status.OK, data)
            result.keepCallback = true
            rawDataAvailableCallback?.sendPluginResult(result)
        }
    }

    private fun sendDataToSubscriber() {
        val data = readUntil(delimiter)
        if (data.isNotEmpty()) {
            val result = PluginResult(PluginResult.Status.OK, data)
            result.keepCallback = true
            dataAvailableCallback?.sendPluginResult(result)
            sendDataToSubscriber()
        }
    }

    private fun readUntil(c: String?): String {
        var data = ""
        val index = buffer.indexOf(c!!, 0)
        if (index > -1) {
            data = buffer.substring(0, index + c.length)
            buffer.delete(0, index + c.length)
        }
        return data
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
        private const val CONNECT_INSECURE = "connectInsecure"
        private const val DISCONNECT = "disconnect"
        private const val SEND = "write"
        private const val SUBSCRIBE_RAW = "subscribeRaw"
        private const val GET_ADDRESS = "getAddress"
        // Debugging
        private const val TAG = "BluetoothSerial"
        // Message types sent from the BluetoothSerialService Handler
        const val MESSAGE_STATE_CHANGE = 1
        const val MESSAGE_READ = 2
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
