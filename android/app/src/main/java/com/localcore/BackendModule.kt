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
import com.localcore.service.BackendService

class BackendModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = "Backend"

  private val application: MainApplication
    get() = reactContext.applicationContext as MainApplication

  private var pickPromise: Promise? = null

  private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

  private fun runAsync(promise: Promise, tag: String, block: () -> Any?) {
    executor.execute {
      try {
        promise.resolve(block())
      } catch (t: Throwable) {
        android.util.Log.e("Backend", tag, t)
        promise.reject(tag, t.message, t)
      }
    }
  }

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
    val activity = reactContext.currentActivity
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
    runAsync(promise, "IMPORT_CORE_FAILED") {
      application.graph.exchange.importCore(Uri.parse(uriString))
      application.graph.exchange.currentCoreId()
    }
  }

  @ReactMethod
  fun importModel(uriString: String, promise: Promise) {
    runAsync(promise, "IMPORT_MODEL_FAILED") {
      application.graph.exchange.importModel(Uri.parse(uriString))
    }
  }

  @ReactMethod
  fun loadModel(modelId: String, promise: Promise) {
    runAsync(promise, "LOAD_FAILED") {
      application.graph.runtime.loadModel(modelId)
      null
    }
  }

  @ReactMethod
  fun testChat(modelId: String, prompt: String, promise: Promise) {
    runAsync(promise, "CHAT_FAILED") {
      val messages = org.json.JSONArray().put(
          org.json.JSONObject().put("role", "user").put("content", prompt))
      application.graph.runtime.chat(messages, org.json.JSONObject(), null).text
    }
  }

  @ReactMethod
  fun importMmproj(uriString: String, modelId: String, promise: Promise) {
    runAsync(promise, "IMPORT_MMPROJ_FAILED") {
      application.graph.exchange.importMmproj(Uri.parse(uriString), modelId)
      null
    }
  }

  @ReactMethod
  fun checkCoreUpdate(promise: Promise) {
    try {
      application.graph.updates.checkNow()
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("CORE_UPDATE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun getBackendState(promise: Promise) {
    try {
      val graph = application.graph
      val state = org.json.JSONObject()
      state.put("backend", org.json.JSONObject(graph.backend.current().toString()))
      state.put("runtime", org.json.JSONObject(graph.runtime.state().toString()))
      state.put("coreUpdate", org.json.JSONObject(graph.updates.state().toString()))
      state.put("config", graph.config.current())
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

  companion object {
    private const val PICK_REQUEST = 4701
  }
}
