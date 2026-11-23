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

class BluetoothHidModule : Module() {
  private var keyboard: HidKeyboard? = null

  private val context: Context
    get() = requireNotNull(appContext.reactContext)

  private val supportsHidDevice: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
      context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)

  override fun definition() = ModuleDefinition {
    Name("BluetoothHid")

    Events("onStateChange")

    Function("isSupported") {
      return@Function supportsHidDevice
    }

    Function("start") {
      if (!supportsHidDevice) return@Function "unsupported"
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@Function "unsupported"

      val kb = keyboard ?: HidKeyboard(context) { status, device ->
        emitState(status, device)
      }.also { keyboard = it }

      return@Function kb.start()
    }

    Function("stop") {
      keyboard?.stop()
      keyboard = null
    }

    Function("getPairedDevices") {
      return@Function keyboard?.pairedDevicesJson() ?: "[]"
    }

    Function("connect") { address: String ->
      return@Function keyboard?.connect(address) ?: false
    }

    Function("disconnect") {
      keyboard?.disconnect()
    }

    Function("sendKeys") { keys: IntArray ->
      keyboard?.sendKeys(keys)
    }

    Function("requestDiscoverable") {
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
      keyboard?.stop()
      keyboard = null
    }
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
        "deviceName" to (name ?: device?.address),
        "deviceAddress" to device?.address,
      ),
    )
  }

  companion object {
    private const val DISCOVERABLE_SECONDS = 300
  }
}
