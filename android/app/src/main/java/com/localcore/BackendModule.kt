package com.localcore

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.localcore.runtime.RuntimeManager
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
      val outcome = application.graph.exchange.importModel(Uri.parse(uriString))
      try {
        if (org.json.JSONObject(outcome).optBoolean("contextFallback", false)) {
          val activity = reactContext.currentActivity
          activity?.runOnUiThread {
            android.widget.Toast.makeText(reactContext,
                "模型未声明上下文长度，已回退 200k", android.widget.Toast.LENGTH_LONG).show()
          }
        }
      } catch (ignored: Exception) {
      }
      outcome
    }
  }

  @ReactMethod
  fun importModelExternal(uriString: String, promise: Promise) {
    runAsync(promise, "IMPORT_EXTERNAL_FAILED") {
      application.graph.exchange.importExternalModel(Uri.parse(uriString))
    }
  }

  @ReactMethod
  fun openAllFilesAccessSettings(promise: Promise) {
    val context = reactApplicationContext
    val packageUri = android.net.Uri.parse("package:" + context.packageName)
    val targets = listOf(
      android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
      android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    for (intent in targets) {
      try {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        promise.resolve(true)
        return
      } catch (ignored: Exception) {
      }
    }
    promise.reject("SETTINGS_FAILED", "无法打开所有文件访问权限设置页")
  }

  @ReactMethod
  fun loadModel(modelId: String, promise: Promise) {
    runAsync(promise, "LOAD_FAILED") {
      application.graph.runtime.loadModel(modelId)
      null
    }
  }

  @ReactMethod
  fun chatStream(modelId: String, prompt: String, imageUriString: String?, maxImagePixels: Int?, promise: Promise) {
    val images = if (imageUriString == null) emptyList() else listOf(imageUriString)
    runAsync(promise, "CHAT_FAILED") {
      streamChatWithUris(modelId, prompt, images, maxImagePixels)
    }
  }

  @ReactMethod
  fun chatStreamMulti(modelId: String, prompt: String, imageUris: ReadableArray?, maxImagePixels: Int?, promise: Promise) {
    val images = mutableListOf<String>()
    if (imageUris != null) {
      for (i in 0 until imageUris.size()) {
        val value = imageUris.getString(i)
        if (!value.isNullOrEmpty()) images.add(value)
      }
    }
    runAsync(promise, "CHAT_FAILED") {
      streamChatWithUris(modelId, prompt, images, maxImagePixels)
    }
  }

  @ReactMethod
  fun stopChat(promise: Promise) {
    // 推理占着单线程 executor，直调 cancel 才能即时中断；经 runAsync 会排队到推理结束后，无意义。
    try {
      application.graph.runtime.cancel()
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("STOP_CHAT_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun prepareChatImage(uriString: String, maxImagePixels: Int?, promise: Promise) {
    runAsync(promise, "PREPARE_IMAGE_FAILED") {
      val source = Uri.parse(uriString) ?: throw IllegalArgumentException("图片地址无效")
      val directory = storedImageDir()
      val raw = copyUriToFile(source, directory, "chat-stored-")
      try {
        if (maxImagePixels == null || maxImagePixels <= 0) return@runAsync raw.toURI().toString()
        val prepared = downscaleIfNeeded(raw, maxImagePixels)
        if (prepared.note != null) emitStage(prepared.note)
        prepared.file.toURI().toString()
      } catch (error: Exception) {
        try {
          if (raw.isFile) raw.delete()
        } catch (_: Exception) {
        }
        throw error
      }
    }
  }

  @ReactMethod
  fun deleteStoredImage(uriString: String, promise: Promise) {
    runAsync(promise, "DELETE_IMAGE_FAILED") {
      val uri = Uri.parse(uriString) ?: throw IllegalArgumentException("图片地址无效")
      if (uri.scheme != "file") throw IllegalArgumentException("非存入图片，无需删除: " + uriString)
      val path = uri.path ?: throw IllegalArgumentException("图片路径无效")
      val target = java.io.File(path)
      val directory = storedImageDir()
      val dirPath = directory.canonicalPath + java.io.File.separator
      val targetPath = try {
        target.canonicalPath
      } catch (_: Exception) {
        target.absolutePath
      }
      if (!targetPath.startsWith(dirPath)) throw IllegalArgumentException("拒绝删除存图目录之外的文件")
      if (target.isFile && !target.delete()) throw IllegalStateException("图片删除失败: " + targetPath)
      null
    }
  }

  private fun storedImageDir(): java.io.File {
    val directory = java.io.File(reactContext.filesDir, "chat-images")
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw IllegalStateException("无法创建图片存入目录: " + directory)
    }
    return directory
  }

  private fun streamChatWithUris(modelId: String, prompt: String, imageUris: List<String>, maxPixels: Int?): String {
    if (imageUris.isEmpty()) {
      return streamChat(modelId, prompt, null)
    }
    // MediaResolver 只接受 data:base64 或 URL 能直接打开的地址，content:// 必须先落到缓存文件再转 file://。
    // 压缩只走测试端这条路，后端 HTTP 服务与模型导入不受影响。
    // 存入文件已在选择时按预算压缩，这里再走一次拷贝+压缩作为统一安全网（已达标会直接复用，不二次损伤）。
    emitStage("图片拷贝开始(共" + imageUris.size + "张)")
    val cached = imageUris.map { copyUriToCache(Uri.parse(it), maxPixels) }
    try {
      for (item in cached) {
        if (item.note != null) emitStage(item.note)
      }
      emitStage("图片拷贝完成(共" + cached.size + "张)")
      return streamChatWithUrls(modelId, prompt, cached.map { it.file.toURI().toString() })
    } finally {
      for (item in cached) {
        item.file.delete()
      }
    }
  }

  private fun streamChat(modelId: String, prompt: String, imageUrl: String?): String {
    return streamChatWithUrls(modelId, prompt, if (imageUrl == null) emptyList() else listOf(imageUrl))
  }

  private fun streamChatWithUrls(modelId: String, prompt: String, imageUrls: List<String>): String {
    val messages = if (imageUrls.isEmpty()) {
      org.json.JSONArray().put(
          org.json.JSONObject().put("role", "user").put("content", prompt))
    } else {
      val content = org.json.JSONArray()
          .put(org.json.JSONObject().put("type", "text").put("text", prompt))
      for (url in imageUrls) {
        content.put(org.json.JSONObject().put("type", "image_url")
            .put("image_url", org.json.JSONObject().put("url", url)))
      }
      org.json.JSONArray().put(
          org.json.JSONObject().put("role", "user").put("content", content))
    }
    emitStage("消息组装完成")
    // 单飞行：JS 侧 busy 锁保证同一时间只有一个流，无需 id 分流。
    val emitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
    val result = application.graph.runtime.chat(messages, org.json.JSONObject(),
        RuntimeManager.TokenConsumer { token ->
          emitter.emit("LocalCoreChatToken", token)
          true
        },
        RuntimeManager.StageListener { stage -> emitter.emit("LocalCoreChatStage", stage) },
        null,
        RuntimeManager.Progress2Listener { phase, doneTokens, totalTokens, elapsedMs ->
          // Bridgeless 下 fromJavaArgs 吃不下 LinkedHashMap，必须用 createMap。
          val payload = Arguments.createMap()
          payload.putString("phase", phase)
          payload.putInt("done", doneTokens)
          payload.putInt("total", totalTokens)
          payload.putDouble("elapsedMs", elapsedMs.toDouble())
          android.util.Log.i("LocalCoreProgress", "phase=$phase done=$doneTokens total=$totalTokens elapsedMs=$elapsedMs")
          emitter.emit("LocalCoreChatProgress", payload)
        })
    return org.json.JSONObject()
        .put("text", result.text)
        .put("promptTokens", result.promptTokens)
        .put("completionTokens", result.completionTokens)
        .put("ttftMs", result.ttftMs)
        .put("llmMs", result.llmMs)
        .put("generation", result.generation)
        .toString()
  }

  private fun emitStage(text: String) {
    try {
      reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
          .emit("LocalCoreChatStage", text)
    } catch (ignored: Exception) {
    }
  }

  private data class CachedImage(val file: java.io.File, val note: String?)

  private fun copyUriToFile(uri: android.net.Uri, directory: java.io.File, prefix: String): java.io.File {
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw IllegalStateException("无法创建图片目录: " + directory)
    }
    val target = java.io.File.createTempFile(prefix, ".bin", directory)
    reactContext.contentResolver.openInputStream(uri)?.use { input ->
      java.io.FileOutputStream(target).use { output ->
        input.copyTo(output)
        output.fd.sync()
      }
    } ?: throw IllegalStateException("系统未提供图片输入流")
    return target
  }

  private fun copyUriToCache(uri: android.net.Uri, maxPixels: Int?): CachedImage {
    val directory = java.io.File(reactContext.cacheDir, "chat-images")
    val target = copyUriToFile(uri, directory, "chat-img-")
    if (maxPixels == null || maxPixels <= 0) return CachedImage(target, null)
    return downscaleIfNeeded(target, maxPixels)
  }

  private fun downscaleIfNeeded(file: java.io.File, maxPixels: Int): CachedImage {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return CachedImage(file, null)
    if (width.toLong() * height.toLong() <= maxPixels) {
      return CachedImage(file, "图片" + width + "x" + height + "无需压缩")
    }
    val scale = kotlin.math.sqrt(maxPixels.toDouble() / (width.toLong() * height.toLong()))
    val targetWidth = maxOf(1, (width * scale).toInt())
    val targetHeight = maxOf(1, (height * scale).toInt())
    var sample = 1
    while ((width / (sample * 2)) * (height / (sample * 2)) > maxPixels) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        ?: return CachedImage(file, null)
    var output: java.io.File? = null
    try {
      val scaled = if (decoded.width == targetWidth && decoded.height == targetHeight) decoded
      else android.graphics.Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
      output = java.io.File.createTempFile("chat-img-scaled-", ".jpg", file.parentFile)
      java.io.FileOutputStream(output).use { stream ->
        if (!scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, stream)) {
          throw IllegalStateException("图片压缩失败")
        }
        stream.fd.sync()
      }
      if (scaled !== decoded) scaled.recycle()
      decoded.recycle()
      file.delete()
      return CachedImage(output, "图片" + width + "x" + height + "压缩到" + targetWidth + "x" + targetHeight)
    } catch (error: Exception) {
      try {
        decoded.recycle()
      } catch (_: Exception) {
      }
      try {
        val pending = output
        if (pending != null && pending.isFile) pending.delete()
      } catch (_: Exception) {
      }
      throw error
    }
  }

  @ReactMethod
  fun unloadModel(promise: Promise) {
    runAsync(promise, "UNLOAD_FAILED") {
      application.graph.runtime.unload()
      null
    }
  }

  @ReactMethod
  fun getModelTemplate(modelId: String, promise: Promise) {
    runAsync(promise, "TEMPLATE_FAILED") {
      application.graph.exchange.modelTemplate(modelId)
    }
  }

  @ReactMethod
  fun setModelTemplate(modelId: String, text: String, promise: Promise) {
    runAsync(promise, "TEMPLATE_FAILED") {
      application.graph.exchange.setModelTemplate(modelId, text)
      null
    }
  }

  @ReactMethod
  fun getEmbeddedTemplate(modelId: String, promise: Promise) {
    runAsync(promise, "TEMPLATE_FAILED") {
      application.graph.exchange.readTemplate(modelId)
    }
  }

  @ReactMethod
  fun getModelSettings(modelId: String, promise: Promise) {
    runAsync(promise, "SETTINGS_FAILED") {
      application.graph.exchange.modelSettings(modelId)
    }
  }

  @ReactMethod
  fun setModelSettings(modelId: String, loadJson: String, inferenceJson: String, promise: Promise) {
    runAsync(promise, "SETTINGS_FAILED") {
      application.graph.exchange.setModelSettings(modelId, loadJson, inferenceJson)
      null
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
  fun downloadHfModel(requestJson: String, promise: Promise) {
    runAsync(promise, "DOWNLOAD_HF_MODEL_FAILED") {
      application.graph.exchange.downloadHfModel(requestJson)
    }
  }

  @ReactMethod
  fun downloadHfProjection(requestJson: String, modelId: String, promise: Promise) {
    runAsync(promise, "DOWNLOAD_HF_MMPROJ_FAILED") {
      application.graph.exchange.downloadHfProjection(requestJson, modelId)
    }
  }

  @ReactMethod
  fun cancelResourceDownload(resourceId: String, promise: Promise) {
    runAsync(promise, "CANCEL_RESOURCE_DOWNLOAD_FAILED") {
      application.graph.resources.cancel(resourceId)
      null
    }
  }

  @ReactMethod
  fun setModelDownloadSource(sourceId: String, promise: Promise) {
    runAsync(promise, "SET_MODEL_DOWNLOAD_SOURCE_FAILED") {
      application.graph.exchange.setModelDownloadSource(sourceId)
      null
    }
  }

  @ReactMethod
  fun exportModel(modelId: String, promise: Promise) {
    executor.execute {
      try {
        val activity = reactContext.currentActivity ?: throw IllegalStateException("没有前台界面")
        val files = application.graph.exchange.modelExportFiles(modelId)
        val uris = ArrayList<Uri>(files.size)
        files.forEach { file ->
          uris.add(ModelExportProvider.uriFor(reactContext, modelId, file.role))
        }
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
          type = "application/octet-stream"
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
          else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
          clipData = ClipData.newRawUri(files[0].displayName, uris[0]).apply {
            for (index in 1 until uris.size) addItem(ClipData.Item(uris[index]))
          }
        }
        val chooser = Intent.createChooser(send, "导出模型").apply {
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.runOnUiThread {
          try {
            activity.startActivity(chooser)
            application.graph.events.info("resource", "已打开模型导出分享器 $modelId，共 ${uris.size} 个文件")
            promise.resolve("已打开系统分享器（${uris.size} 个文件）")
          } catch (error: Throwable) {
            android.util.Log.e("Backend", "EXPORT_MODEL_FAILED", error)
            promise.reject("EXPORT_MODEL_FAILED", error.message, error)
          }
        }
      } catch (error: Throwable) {
        android.util.Log.e("Backend", "EXPORT_MODEL_FAILED", error)
        promise.reject("EXPORT_MODEL_FAILED", error.message, error)
      }
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
  fun saveImage(imageUriString: String, promise: Promise) {
    runAsync(promise, "SAVE_IMAGE_FAILED") {
      val source = Uri.parse(imageUriString)
      val mime = reactContext.contentResolver.getType(source) ?: "image/jpeg"
      val extension = when {
        mime.endsWith("png") -> "png"
        mime.endsWith("webp") -> "webp"
        mime.endsWith("gif") -> "gif"
        else -> "jpg"
      }
      if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
        val activity = reactContext.currentActivity
        if (activity != null && activity.checkSelfPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
          activity.requestPermissions(
              arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), NOTIFICATION_REQUEST_CODE)
        }
      }
      val name = "localcore-" + System.currentTimeMillis() + "." + extension
      val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LocalCore")
          put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
      }
      val collection = android.provider.MediaStore.Images.Media.getContentUri(
          android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
      val target = reactContext.contentResolver.insert(collection, values)
          ?: throw IllegalStateException("系统未提供图片保存位置")
      try {
        reactContext.contentResolver.openInputStream(source)?.use { input ->
          reactContext.contentResolver.openOutputStream(target)?.use { output ->
            input.copyTo(output)
            output.flush()
          } ?: throw IllegalStateException("系统未提供图片输出流")
        } ?: throw IllegalStateException("系统未提供图片输入流")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
          val finished = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
          }
          reactContext.contentResolver.update(target, finished, null, null)
        }
        name
      } catch (error: Exception) {
        try {
          reactContext.contentResolver.delete(target, null, null)
        } catch (ignored: Exception) {
        }
        throw error
      }
    }
  }

  @ReactMethod
  fun setImageBudget(pixels: Int, promise: Promise) {
    try {
      if (pixels <= 0) throw IllegalArgumentException("分辨率预算必须大于0")
      application.graph.runtime.setMaxImagePixels(pixels)
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("BUDGET_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun setHotParams(paramsJson: String, promise: Promise) {
    try {
      application.graph.runtime.setHotDefaults(paramsJson)
      promise.resolve(null)
    } catch (error: Exception) {
      promise.reject("HOT_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun getHotSettings(promise: Promise) {
    runAsync(promise, "HOT_SETTINGS_FAILED") {
      application.graph.exchange.hotSettings()
    }
  }

  @ReactMethod
  fun setHotSettings(paramsJson: String, promise: Promise) {
    runAsync(promise, "HOT_SETTINGS_FAILED") {
      application.graph.exchange.setHotSettings(paramsJson)
    }
  }

  @ReactMethod
  fun getServerSettings(promise: Promise) {
    runAsync(promise, "SERVER_SETTINGS_FAILED") {
      application.graph.exchange.serverSettings()
    }
  }

  @ReactMethod
  fun setServerSettings(settingsJson: String, promise: Promise) {
    runAsync(promise, "SERVER_SETTINGS_FAILED") {
      application.graph.exchange.setServerSettings(settingsJson)
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
      val resourceStates = org.json.JSONArray()
      graph.resources.states().forEach { resourceStates.put(it.toJson()) }
      state.put("resourceStates", resourceStates)
      state.put("config", graph.config.current())
      promise.resolve(state.toString())
    } catch (error: Exception) {
      promise.reject("STATE_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun startService(promise: Promise) {
    try {
      android.util.Log.i("LocalCoreBackend", "JS startService called(仅投递启动指令, 真正就绪看service分阶段日志)")
      requestNotificationPermissionIfNeeded()
      BackendService.command(reactContext, BackendService.ACTION_START)
      android.util.Log.i("LocalCoreBackend", "JS startService command已投递, 等待后台线程完成绑定")
      promise.resolve(null)
    } catch (error: Exception) {
      android.util.Log.e("LocalCoreBackend", "JS startService投递失败", error)
      promise.reject("START_FAILED", error.message, error)
    }
  }

  private fun requestNotificationPermissionIfNeeded() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
    val activity = reactContext.currentActivity ?: return
    if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED) {
      return
    }
    activity.requestPermissions(
        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST_CODE)
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
  fun isBatteryWhitelisted(promise: Promise) {
    try {
      val power = reactApplicationContext.getSystemService(android.os.PowerManager::class.java)
      promise.resolve(power != null && power.isIgnoringBatteryOptimizations(reactApplicationContext.packageName))
    } catch (error: Exception) {
      promise.reject("BATTERY_CHECK_FAILED", error.message, error)
    }
  }

  @ReactMethod
  fun openBatterySettings(promise: Promise) {
    val context = reactApplicationContext
    val packageUri = android.net.Uri.parse("package:" + context.packageName)
    val targets = listOf(
      android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri),
      android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    for (intent in targets) {
      try {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        promise.resolve(true)
        return
      } catch (ignored: Exception) {
      }
    }
    promise.reject("SETTINGS_FAILED", "无法打开电池优化设置页")
  }

  companion object {
    private const val PICK_REQUEST = 4701
    private const val SAVE_REQUEST = 4702
    private const val NOTIFICATION_REQUEST_CODE = 4801
  }
}
