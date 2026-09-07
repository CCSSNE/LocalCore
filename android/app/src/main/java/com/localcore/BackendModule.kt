package com.localcore

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.BaseActivityEventListener
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

  private var pickPromise: Promise? = null

  private val pickListener: ActivityEventListener = object : BaseActivityEventListener() {
    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, intent: Intent?) {
      if (requestCode != PICK_REQUEST) return
      val promise = pickPromise
      pickPromise = null
      if (promise == null) return
      val uri = intent?.data
      if (resultCode == Activity.RESULT_OK && uri != null) {
        val value = takePersistable(uri)
        promise.resolve(value)
      } else {
        promise.reject("PICK_CANCELLED", "未选择文件")
      }
    }
  }

  init {
    reactContext.addActivityEventListener(pickListener)
    RuntimeManager.sink = RuntimeManager.Sink { name, payload -> emitRuntimeEvent(name, payload) }
  }

  private fun takePersistable(uri: Uri): String {
    try {
      val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
      reactContext.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (ignored: Exception) {
    }
    return uri.toString()
  }

  @ReactMethod
  fun pickFile(promise: Promise) {
    if (pickPromise != null) {
      promise.reject("PICK_BUSY", "已有选择任务进行中")
      return
    }
    val activity = currentActivity
    if (activity == null) {
      promise.reject("NO_ACTIVITY", "没有前台界面")
      return
    }
    pickPromise = promise
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
      addCategory(Intent.CATEGORY_OPENABLE)
      setType("*/*")
      putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
    }
    activity.startActivityForResult(Intent.createChooser(intent, "选择文件"), PICK_REQUEST)
  }

  @ReactMethod
  fun importCore(uriString: String, promise: Promise) {
    try {
      application.graph.exchange.importCore(Uri.parse(uriString))
      promise.resolve(application.graph.exchange.currentCoreId())
    } catch (error: Exception) {
      promise.reject("IMPORT_CORE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun importModel(uriString: String, promise: Promise) {
    try {
      promise.resolve(application.graph.exchange.importModel(Uri.parse(uriString)))
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
      val messages = org.json.JSONArray().put(
          org.json.JSONObject().put("role", "user").put("content", prompt))
      val result = application.graph.runtime.chat(messages, org.json.JSONObject(), null)
      promise.resolve(result.text)
    } catch (error: Exception) {
      promise.reject("CHAT_FAILED", error.message, error)
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
      android.util.Log.w("Backend", "JS 运行时事件发送失败: " + error.message)
    }
  }

  companion object {
    private const val PICK_REQUEST = 4701
  }
}
