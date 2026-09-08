package com.localcore

import android.os.Build
import android.os.Bundle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "LocalCore"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setupImeInsets()
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

}
