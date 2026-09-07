package com.localcore

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.localcore.runtime.RuntimeState
import com.localcore.service.BackendService

class BackendModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = "Backend"

  private val application: MainApplication
    get() = reactContext.applicationContext as MainApplication

  private var pickPromise: Promise? = null
  private var savePromise: Promise? = null
  private var saveBytes: ByteArray? = null

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
      if (requestCode == SAVE_REQUEST) {
        val promise = savePromise
        val bytes = saveBytes
        savePromise = null
        saveBytes = null
        if (promise == null) return
        val uri = intent?.data
        if (resultCode == Activity.RESULT_OK && uri != null && bytes != null) {
          try {
            reactContext.contentResolver.openOutputStream(uri, "wt")?.use {
              it.write(bytes)
              it.flush()
            } ?: throw IllegalStateException("系统未提供输出流")
            promise.resolve(uri.toString())
          } catch (error: Exception) {
            promise.reject("SAVE_FAILED", error.message, error)
          }
        } else {
          promise.reject("SAVE_CANCELLED", "未选择保存位置")
        }
        return
      }
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
  fun testChatWithImage(modelId: String, prompt: String, imageUriString: String, promise: Promise) {
    runAsync(promise, "CHAT_FAILED") {
      // MediaResolver 只接受 data:base64 或 URL 能直接打开的地址，content:// 必须先落到缓存文件再转 file://。
      val imageFile = copyUriToCache(android.net.Uri.parse(imageUriString))
      try {
        val content = org.json.JSONArray()
            .put(org.json.JSONObject().put("type", "text").put("text", prompt))
            .put(org.json.JSONObject().put("type", "image_url")
                .put("image_url", org.json.JSONObject().put("url", imageFile.toURI().toString())))
        val messages = org.json.JSONArray().put(
            org.json.JSONObject().put("role", "user").put("content", content))
        application.graph.runtime.chat(messages, org.json.JSONObject(), null).text
      } finally {
        imageFile.delete()
      }
    }
  }

  private fun copyUriToCache(uri: android.net.Uri): java.io.File {
    val directory = java.io.File(reactContext.cacheDir, "chat-images")
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw IllegalStateException("无法创建图片缓存目录: " + directory)
    }
    val target = java.io.File.createTempFile("chat-img-", ".bin", directory)
    reactContext.contentResolver.openInputStream(uri)?.use { input ->
      java.io.FileOutputStream(target).use { output ->
        input.copyTo(output)
        output.fd.sync()
      }
    } ?: throw IllegalStateException("系统未提供图片输入流")
    return target
  }

  @ReactMethod
  fun importMmproj(uriString: String, modelId: String, promise: Promise) {
    runAsync(promise, "IMPORT_MMPROJ_FAILED") {
      application.graph.exchange.importMmproj(Uri.parse(uriString), modelId)
      null
    }
  }

  @ReactMethod
  fun deleteModel(modelId: String, promise: Promise) {
    runAsync(promise, "DELETE_MODEL_FAILED") {
      val graph = application.graph
      val state = graph.runtime.state()
      if (modelId == state.modelId && state.phase == RuntimeState.Phase.MODEL_READY) {
        graph.runtime.unload()
      }
      graph.exchange.deleteModel(modelId)
      null
    }
  }

  @ReactMethod
  fun copyText(text: String, promise: Promise) {
    try {
      val clipboard = reactContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText("LocalCore", text))
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("COPY_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun exportLog(fileName: String, content: String, promise: Promise) {
    if (savePromise != null) {
      promise.reject("SAVE_BUSY", "已有导出任务进行中")
      return
    }
    val activity = reactContext.currentActivity
    if (activity == null) {
      promise.reject("NO_ACTIVITY", "没有前台界面")
      return
    }
    savePromise = promise
    saveBytes = content.toByteArray(Charsets.UTF_8)
    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
      addCategory(Intent.CATEGORY_OPENABLE)
      type = "text/plain"
      putExtra(Intent.EXTRA_TITLE, fileName)
    }
    activity.startActivityForResult(Intent.createChooser(intent, "导出日志"), SAVE_REQUEST)
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
    private const val SAVE_REQUEST = 4702
  }
}
