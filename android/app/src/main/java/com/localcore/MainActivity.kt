package com.localcore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "LocalCore"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
          Toast.makeText(this, "未授予通知权限，后台服务运行时通知栏可能不显示", Toast.LENGTH_LONG).show()
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setupImeInsets()
    checkNotificationPermission()
  }

  // targetSdk 36 在安卓 15+ 强制 edge-to-edge，adjustResize 失效：
  // 根布局按 IME 高度垫底，键盘顶起输入栏。29 以下走系统 adjustResize。
  private fun setupImeInsets() {
    val root = findViewById<android.view.View>(android.R.id.content)
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
      val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
      view.setPadding(0, 0, 0, ime.bottom + bars.bottom)
      insets
    }
  }

  private fun checkNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED) {
      return
    }
    if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
      Toast.makeText(this, "开启后端服务需要通知权限用于前台保活", Toast.LENGTH_LONG).show()
    }
    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
}
