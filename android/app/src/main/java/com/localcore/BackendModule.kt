package com.localcore

import android.content.Intent
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.localcore.runtime.RuntimeManager
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
  fun importCore(path: String, promise: Promise) {
    try {
      application.graph.exchange.importCore(android.net.Uri.fromFile(java.io.File(path)))
      promise.resolve(application.graph.exchange.currentCoreId())
    } catch (error: Exception) {
      promise.reject("IMPORT_CORE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun importModel(path: String, promise: Promise) {
    try {
      promise.resolve(application.graph.exchange.importModel(android.net.Uri.fromFile(java.io.File(path))))
    } catch (error: Exception) {
      promise.reject("IMPORT_MODEL_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun loadModel(modelId: String, promise: Promise) {
    try {
      application.graph.runtime.loadModel(modelId)
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("LOAD_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun testChat(modelId: String, prompt: String, promise: Promise) {
    try {
      val graph = application.graph
      val messages = org.json.JSONArray().put(
          org.json.JSONObject().put("role", "user").put("content", prompt))
      val result = graph.runtime.chat(messages, org.json.JSONObject(), null)
      promise.resolve(result.text)
    } catch (error: Exception) {
      promise.reject("CHAT_FAILED", error.message, error)
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
      reactContext.emitDeviceEvent(name, payload)
    } catch (error: Exception) {
      android.util.Log.w("Backend", "JS 杩愯鏃朵簨浠跺彂閫佸け璐? " + error.message)
    }
  }

  init {
    RuntimeManager.sink = RuntimeManager.Sink { name, payload -> emitRuntimeEvent(name, payload) }
  }
}


