package expo.modules.bluetoothhid

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.os.bundleOf
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Dua cara menjadi keyboard Bluetooth:
 * - "classic": profil HID Device klasik (Android 9+), paling kompatibel dengan laptop.
 * - "ble": HID over GATT (Android 5+ dengan dukungan BLE peripheral), dipakai sebagai cadangan.
 */
class BluetoothHidModule : Module() {
  private val lock = Any()
  private var keyboard: HidKeyboard? = null
  private var ble: BleHidKeyboard? = null
  private var starting = false

  // Event dari instance lama (yang sudah dihentikan) diabaikan dengan membandingkan nomor ini.
  @Volatile private var generation = 0
  @Volatile private var transport = TRANSPORT_CLASSIC

  private val context: Context
    get() = requireNotNull(appContext.reactContext)

  private val hasBluetooth: Boolean
    get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

  private val classicSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasBluetooth

  private val bleSupported: Boolean
    get() = hasBluetooth &&
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

  override fun definition() = ModuleDefinition {
    Name("BluetoothHid")

    Events("onStateChange")

    Function("isSupported") {
      return@Function classicSupported || bleSupported
    }

    Function("start") { mode: String ->
      return@Function synchronized(lock) {
        stopAll()
        when {
          mode == "ble" -> if (bleSupported) startBle() else "unsupported"
          mode == "classic" -> if (classicSupported) startClassic(allowFallback = false) else "unsupported"
          classicSupported -> startClassic(allowFallback = bleSupported)
          bleSupported -> startBle()
          else -> "unsupported"
        }
      }
    }

    Function("stop") {
      synchronized(lock) { stopAll() }
    }

    Function("getPairedDevices") {
      return@Function keyboard?.pairedDevicesJson() ?: ble?.pairedDevicesJson() ?: "[]"
    }

    Function("connect") { address: String ->
      return@Function keyboard?.connect(address) ?: ble?.connect(address) ?: false
    }

    Function("disconnect") {
      keyboard?.disconnect()
      ble?.disconnect()
    }

    Function("sendKeys") { keys: IntArray ->
      keyboard?.sendKeys(keys)
      ble?.sendKeys(keys)
    }

    Function("requestDiscoverable") {
      // Pada BLE, HP sudah terlihat oleh laptop lewat iklan BLE.
      if (ble != null) return@Function true

      val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
      return@Function try {
        val activity = appContext.currentActivity
        if (activity != null) {
          activity.startActivity(intent)
        } else {
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          context.startActivity(intent)
        }
        true
      } catch (e: Exception) {
        false
      }
    }

    OnDestroy {
      synchronized(lock) { stopAll() }
    }
  }

  private fun stopAll() {
    generation++
    keyboard?.stop()
    keyboard = null
    ble?.stop()
    ble = null
  }

  /** Harus dipanggil di dalam lock. */
  private fun startClassic(allowFallback: Boolean): String {
    val token = ++generation
    transport = TRANSPORT_CLASSIC

    val kb = HidKeyboard(context) { status, device ->
      if (token == generation) onClassicState(status, device)
    }
    keyboard = kb

    starting = true
    val result = try {
      kb.start()
    } finally {
      starting = false
    }

    // Gagal langsung, misalnya sistem menolak membuka profil HID Device.
    if (result == "failed" && allowFallback) {
      stopAll()
      return startBle()
    }
    return result
  }

  /** Harus dipanggil di dalam lock. */
  private fun startBle(): String {
    val token = ++generation
    transport = TRANSPORT_BLE

    val k = BleHidKeyboard(context) { status, device ->
      if (token == generation) emitState(status, device)
    }
    ble = k

    val result = k.start()
    if (result != "ok") {
      k.stop()
      ble = null
    }
    return result
  }

  private fun onClassicState(status: String, device: BluetoothDevice?) {
    // Gagal belakangan, misalnya HP menolak mendaftarkan keyboard. Pindah ke BLE kalau bisa.
    if (status == "failed" && !starting && bleSupported) {
      synchronized(lock) {
        if (keyboard != null) {
          stopAll()
          val result = startBle()
          if (result != "ok") emitState("failed", null)
        }
      }
      return
    }
    emitState(status, device)
  }

  private fun emitState(status: String, device: BluetoothDevice?) {
    val name = try {
      device?.name
    } catch (e: SecurityException) {
      null
    }
    sendEvent(
      "onStateChange",
      bundleOf(
        "status" to status,
        "transport" to transport,
        "deviceName" to (name ?: device?.address),
        "deviceAddress" to device?.address,
      ),
    )
  }

  companion object {
    private const val DISCOVERABLE_SECONDS = 300
    private const val TRANSPORT_CLASSIC = "classic"
    private const val TRANSPORT_BLE = "ble"
  }
}
