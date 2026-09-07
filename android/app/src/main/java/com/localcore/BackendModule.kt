package com.localcore

import android.content.Intent
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.localcore.service.BackendService

class BackendModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = "Backend"

  private val application: MainApplication
    get() = reactContext.applicationContext as MainApplication

  @ReactMethod
  fun startService(promise: Promise) {
    try {
      BackendService.command(reactContext, BackendService.ACTION_START)
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("START_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun stopService(promise: Promise) {
    try {
      BackendService.command(reactContext, BackendService.ACTION_STOP)
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("STOP_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun getBackendState(promise: Promise) {
    try {
      val graph = application.graph
      val state = java.util.HashMap<String, Any>()
      state["backend"] = graph.backend.current().toString()
      state["runtime"] = graph.runtime.state().toString()
      state["config"] = graph.config.current().toString()
      promise.resolve(state.toString())
    } catch (error: Exception) {
      promise.reject("STATE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun preloadCore(soPath: String, promise: Promise) {
    try {
      System.load(soPath)
      promise.resolve(null)
    } catch (error: Throwable) {
      promise.reject("PRELOAD_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun reply(requestId: Double, payload: String) {
    RuntimeManager.onReply(requestId.toInt(), payload)
  }

  fun emitRuntimeEvent(name: String, payload: String) {
    try {
      reactContext
          .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
          .emit(name, payload)
    } catch (error: Exception) {
      android.util.Log.w("Backend", "JS 运行时事件发送失败: " + error.message)
    }
  }

  init {
    RuntimeManager.sink = { name, payload -> emitRuntimeEvent(name, payload) }
  }
}
