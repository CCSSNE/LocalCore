package com.localcore;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.localcore.app.AppGraph;
import com.localcore.diagnostics.EventLog;
import com.localcore.io.Jsons;
import com.localcore.resource.ResourceManager;
import com.localcore.resource.ResourceState;
import com.localcore.runtime.RuntimeManager;
import com.localcore.runtime.RuntimeState;
import com.localcore.service.BackendService;
import com.localcore.service.BackendStatus;
import com.localcore.service.BackendStatusStore;
import com.localcore.update.UpdateManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int IMPORT_CONFIG = 10;
    private static final int EXPORT_CONFIG = 11;
    private static final int EXPORT_LOG = 12;
    private static final int IMPORT_GGUF = 13;
    private static final int IMPORT_CORE = 14;
    private static final int EXPORT_CORE = 15;
    private static final int IMPORT_MMPROJ = 16;
    private static final int BACKGROUND = Color.rgb(11, 15, 20);
    private static final int SURFACE = Color.rgb(20, 27, 36);
    private static final int PRIMARY = Color.rgb(101, 214, 173);
    private static final int TEXT = Color.rgb(232, 238, 245);
    private static final int MUTED = Color.rgb(154, 168, 183);
    private static final int ERROR = Color.rgb(255, 107, 107);

    private AppGraph graph;
    private LinearLayout content;
    private TextView backendStatus;
    private TextView runtimeStatus;
    private TextView errorStatus;
    private TextView coreStatus;
    private LinearLayout resourceList;
    private LinearLayout modelList;
    private Spinner templateSpinner;
    private EditText templateEditor;
    private EditText configEditor;
    private TextView logView;
    private TextView updateStatus;
    private LinearLayout managementContainer;
    private final List<FieldBinding> fieldBindings = new ArrayList<>();
    private String templateModelId;
    private String pendingPairModelId;
    private String pendingExportCoreId;

    private final ResourceManager.Listener resourceListener = state -> ui(this::renderResources);
    private final RuntimeManager.Listener runtimeListener = state -> ui(this::renderRuntime);
    private final BackendStatusStore.Listener backendListener = state -> ui(this::renderBackend);
    private final UpdateManager.Listener updateListener = state -> ui(this::renderUpdates);
    private final EventLog.Listener eventListener = event -> ui(() -> {
        renderLastError(event);
        renderLog();
    });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        graph = ((LocalCoreApplication) getApplication()).graph();
        buildUi();
        graph.resources.addListener(resourceListener);
        graph.runtime.addListener(runtimeListener);
        graph.backend.addListener(backendListener);
        graph.events.addListener(eventListener);
        graph.updates.addListener(updateListener);
        if (state != null) templateModelId = state.getString("templateModelId");
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
        }
        refreshAll();
        loadConfigIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        loadConfigIntent(intent);
    }

    @Override
    protected void onDestroy() {
        graph.resources.removeListener(resourceListener);
        graph.runtime.removeListener(runtimeListener);
        graph.backend.removeListener(backendListener);
        graph.events.removeListener(eventListener);
        graph.updates.removeListener(updateListener);
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("templateModelId", templateModelId);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(24), dp(18), dp(48));
        scroll.addView(content, matchWrap());
        setContentView(scroll);

        TextView title = text("LocalCore", 30, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);
        TextView subtitle = text("本机 GGUF 推理后端控制台", 14, MUTED);
        subtitle.setPadding(0, dp(2), 0, dp(18));
        content.addView(subtitle);

        LinearLayout statusCard = card();
        backendStatus = text("", 15, TEXT);
        runtimeStatus = text("", 15, TEXT);
        errorStatus = text("", 13, ERROR);
        statusCard.addView(backendStatus);
        statusCard.addView(runtimeStatus);
        statusCard.addView(errorStatus);
        statusCard.addView(buttonRow(
                action("启动服务", () -> BackendService.command(this, BackendService.ACTION_START)),
                action("停止服务", () -> BackendService.command(this, BackendService.ACTION_STOP)),
                action("取消推理", () -> runAction("取消推理", () -> graph.runtime.cancel()))));
        content.addView(statusCard);

        section("核心");
        LinearLayout coreCard = card();
        coreStatus = text("", 14, TEXT);
        coreCard.addView(coreStatus);
        coreCard.addView(buttonRow(
                action("导入核心", this::beginImportCore),
                action("导出核心", this::beginExportCore),
                action("更新核心", this::updateCore)));
        content.addView(coreCard);

        section("配置生成的管理项");
        managementContainer = new LinearLayout(this);
        managementContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(managementContainer);

        section("更新");
        LinearLayout updateCard = card();
        updateStatus = text("", 13, MUTED);
        updateCard.addView(updateStatus);
        updateCard.addView(buttonRow(
                action("立即检查", () -> runAction("检查更新", () -> graph.updates.checkNow())),
                action("激活候选配置", () -> runAction("激活候选配置", () -> graph.updates.activateCandidate()))));
        content.addView(updateCard);

        section("资源");
        LinearLayout resourceCard = card();
        resourceCard.addView(buttonRow(
                action("安装全部更新", () -> runAction("安装资源", () -> {
                    BackendService.command(this, BackendService.ACTION_START);
                    graph.resources.installAllOutdated();
                })),
                action("刷新状态", this::refreshAll)));
        resourceList = new LinearLayout(this);
        resourceList.setOrientation(LinearLayout.VERTICAL);
        resourceCard.addView(resourceList);
        content.addView(resourceCard);

        section("模型");
        LinearLayout modelCard = card();
        modelCard.addView(buttonRow(
                action("导入 GGUF", this::beginImportGguf),
                action("导入 MMPROJ", () -> runAction("导入 MMPROJ", () -> {
                    JSONArray models = graph.config.current().optJSONArray("models");
                    if (models.length() == 0) {
                        toast("请先导入 GGUF 模型");
                        return;
                    }
                    if (models.length() > 1) {
                        toast("存在多个模型，请在模型行上点击 配对mmproj 指定目标");
                        return;
                    }
                    beginPairMmproj(models.optJSONObject(0).optString("id"));
                }))));
        modelList = new LinearLayout(this);
        modelList.setOrientation(LinearLayout.VERTICAL);
        modelCard.addView(modelList);
        content.addView(modelCard);

        section("聊天模板（jinja）");
        LinearLayout templateCard = card();
        templateSpinner = new Spinner(this);
        templateCard.addView(templateSpinner, matchWrap());
        templateEditor = new EditText(this);
        templateEditor.setTextColor(TEXT);
        templateEditor.setHintTextColor(MUTED);
        templateEditor.setTextSize(12);
        templateEditor.setTypeface(Typeface.MONOSPACE);
        templateEditor.setGravity(Gravity.TOP | Gravity.START);
        templateEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        templateEditor.setMinLines(10);
        templateEditor.setHorizontallyScrolling(true);
        templateEditor.setBackgroundColor(Color.rgb(8, 12, 17));
        templateEditor.setPadding(dp(12), dp(12), dp(12), dp(12));
        templateEditor.setHint("空 = 使用 GGUF 内置模板");
        templateCard.addView(templateEditor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        templateCard.addView(buttonRow(
                action("从 GGUF 读取", () -> runAction("读取内置模板", () -> {
                    if (templateModelId == null) {
                        toast("尚无模型");
                        return;
                    }
                    String modelId = templateModelId;
                    runBackground("读取内置模板", () -> {
                        String template = graph.exchange.readTemplate(modelId);
                        ui(() -> {
                            if (template == null) {
                                toast("该 GGUF 未内置聊天模板");
                                return;
                            }
                            templateEditor.setText(template);
                            toast("内置模板已填入，保存后生效");
                        });
                    });
                })),
                action("保存为该模型模板", () -> runAction("保存聊天模板", () -> {
                    if (templateModelId == null) {
                        toast("尚无模型");
                        return;
                    }
                    JSONObject next = graph.config.current();
                    JSONObject model = configModel(next, templateModelId);
                    if (model == null) throw new IllegalArgumentException("配置中不存在模型: " + templateModelId);
                    String content = templateEditor.getText().toString();
                    JSONObject template = model.optJSONObject("template");
                    if (template == null) {
                        template = new JSONObject().put("mode", "embedded");
                        model.put("template", template);
                    }
                    if (content.isEmpty()) template.remove("content");
                    else template.put("content", content);
                    graph.config.activate(next.toString());
                    configEditor.setText(graph.config.currentText());
                    refreshAll();
                    toast("模板已保存到 " + templateModelId);
                }))));
        content.addView(templateCard);

        section("统一 JSON 配置");
        LinearLayout configCard = card();
        configEditor = new EditText(this);
        configEditor.setTextColor(TEXT);
        configEditor.setHintTextColor(MUTED);
        configEditor.setTextSize(12);
        configEditor.setTypeface(Typeface.MONOSPACE);
        configEditor.setGravity(Gravity.TOP | Gravity.START);
        configEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        configEditor.setMinLines(14);
        configEditor.setHorizontallyScrolling(true);
        configEditor.setBackgroundColor(Color.rgb(8, 12, 17));
        configEditor.setPadding(dp(12), dp(12), dp(12), dp(12));
        configCard.addView(configEditor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        configCard.addView(buttonRow(
                action("原子激活", () -> runAction("激活配置", () -> {
                    graph.config.activate(configEditor.getText().toString());
                    configEditor.setText(graph.config.currentText());
                    refreshAll();
                })),
                action("导入", this::importConfig),
                action("导出", this::exportConfig)));
        content.addView(configCard);

        section("诊断事件");
        LinearLayout logCard = card();
        logView = text("", 11, MUTED);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.addView(logView);
        logCard.addView(horizontal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        logCard.addView(buttonRow(
                action("刷新日志", this::renderLog),
                action("导出日志", this::exportLog),
                action("系统设置", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))))));
        content.addView(logCard);
    }

    private void refreshAll() {
        renderBackend();
        renderRuntime();
        renderCore();
        renderResources();
        renderModels();
        renderTemplate();
        renderManagement();
        renderUpdates();
        if (!configEditor.hasFocus()) configEditor.setText(graph.config.currentText());
        renderLog();
    }

    private void renderCore() {
        if (coreStatus == null) return;
        String coreId = currentCoreId();
        if (coreId == null) {
            coreStatus.setText("尚未安装核心。点击 导入核心 选择本地 .so 文件、.zip 或 .tar.gz 核心包。");
            coreStatus.setTextColor(MUTED);
            return;
        }
        ResourceState state = graph.resources.knownState(coreId);
        String detail = coreId;
        if (state != null) {
            detail += "  ·  " + (state.version == null ? "未安装" : state.version)
                    + "  ·  " + state.status.name().toLowerCase(Locale.ROOT);
            if (state.error != null) detail += "\n" + state.error;
        }
        coreStatus.setText(detail);
        coreStatus.setTextColor(state != null && state.status == ResourceState.Status.FAILED ? ERROR : TEXT);
    }

    private void renderTemplate() {
        if (templateSpinner == null) return;
        JSONArray models = graph.config.current().optJSONArray("models");
        final List<String> ids = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            ids.add(model.optString("id"));
            labels.add(model.optString("name") + " · " + model.optString("id"));
        }
        if (templateModelId == null || !ids.contains(templateModelId)) {
            templateModelId = ids.isEmpty() ? null : ids.get(0);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        templateSpinner.setAdapter(adapter);
        templateSpinner.setSelection(templateModelId == null ? 0 : ids.indexOf(templateModelId), false);
        templateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= ids.size()) return;
                String modelId = ids.get(position);
                if (modelId.equals(templateModelId)) return;
                templateModelId = modelId;
                if (!templateEditor.hasFocus()) templateEditor.setText(templateContentOf(modelId));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        if (!templateEditor.hasFocus()) {
            templateEditor.setText(templateModelId == null ? "" : templateContentOf(templateModelId));
        }
    }

    private void beginImportGguf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream");
        startActivityForResult(intent, IMPORT_GGUF);
    }

    private void beginImportCore() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(intent, IMPORT_CORE);
    }

    private void beginExportCore() {
        runAction("导出核心", () -> {
            String coreId = currentCoreId();
            if (coreId == null) {
                toast("尚未安装核心");
                return;
            }
            pendingExportCoreId = coreId;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, coreId + ".zip");
            startActivityForResult(intent, EXPORT_CORE);
        });
    }

    private void updateCore() {
        runAction("更新核心", () -> {
            String coreId = currentCoreId();
            if (coreId == null) {
                toast("尚未安装核心");
                return;
            }
            BackendService.command(this, BackendService.ACTION_START);
            graph.resources.install(coreId);
        });
    }

    private void beginPairMmproj(String modelId) {
        pendingPairModelId = modelId;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream");
        startActivityForResult(intent, IMPORT_MMPROJ);
    }

    private String currentCoreId() {
        JSONArray resources = graph.config.current().optJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject descriptor = resources.optJSONObject(i);
            if ("core".equals(descriptor.optString("type"))) return descriptor.optString("id");
        }
        return null;
    }

    private static JSONObject configModel(JSONObject config, String modelId) {
        JSONArray models = config.optJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (modelId.equals(model.optString("id"))) return model;
        }
        return null;
    }

    private String templateContentOf(String modelId) {
        JSONObject model = configModel(graph.config.current(), modelId);
        if (model == null) return "";
        return model.optJSONObject("template").optString("content");
    }

    private void renderBackend() {
        BackendStatus status = graph.backend.current();
        backendStatus.setText(status.running ? "服务：运行中  " + status.address : "服务：已停止");
        backendStatus.setTextColor(status.running ? PRIMARY : MUTED);
        if (status.error != null) errorStatus.setText("服务错误：" + status.error);
    }

    private void renderUpdates() {
        if (updateStatus == null) return;
        UpdateManager.State state = graph.updates.state();
        String value = "更新：" + state.phase.name().toLowerCase(Locale.ROOT);
        if (state.version != null) value += "  " + state.version;
        if (state.error != null) value += "\n" + state.error;
        updateStatus.setText(value);
        updateStatus.setTextColor(state.phase == UpdateManager.Phase.FAILED ? ERROR : MUTED);
    }

    private void renderManagement() {
        if (managementContainer == null) return;
        managementContainer.removeAllViews();
        fieldBindings.clear();
        JSONObject active = graph.config.current();
        JSONArray sections = active.optJSONObject("management").optJSONArray("sections");
        for (int i = 0; i < sections.length(); i++) {
            JSONObject definition = sections.optJSONObject(i);
            LinearLayout card = card();
            TextView title = text(definition.optString("title"), 16, TEXT);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(title);
            JSONArray fields = definition.optJSONArray("fields");
            for (int j = 0; j < fields.length(); j++) {
                JSONObject field = fields.optJSONObject(j);
                String path = field.optString("path");
                Object current = pointerGet(active, path);
                card.addView(text(field.optString("label"), 12, MUTED));
                View input = managementInput(field, current);
                card.addView(input, matchWrap());
                fieldBindings.add(new FieldBinding(field, input, current));
            }
            card.addView(action("原子应用本组设置", () -> applyManagement(definition.optString("id"))));
            managementContainer.addView(card);
        }
    }

    private View managementInput(JSONObject field, Object current) {
        String control = field.optString("control");
        if ("toggle".equals(control)) {
            Switch input = new Switch(this);
            input.setChecked((Boolean) current);
            return input;
        }
        if ("select".equals(control)) {
            JSONArray options = field.optJSONArray("options");
            List<String> labels = new ArrayList<>();
            int selected = 0;
            for (int i = 0; i < options.length(); i++) {
                labels.add(String.valueOf(options.opt(i)));
                if (String.valueOf(current).equals(labels.get(i))) selected = i;
            }
            Spinner input = new Spinner(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            input.setAdapter(adapter);
            input.setSelection(selected);
            return input;
        }
        EditText input = new EditText(this);
        input.setText(String.valueOf(current));
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setSingleLine(true);
        input.setInputType("number".equals(control)
                ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return input;
    }

    private void applyManagement(String sectionId) {
        runAction("应用声明式配置", () -> {
            JSONObject candidate = graph.config.current();
            for (FieldBinding binding : fieldBindings) {
                JSONObject section = findManagementSection(candidate, sectionId);
                if (!containsField(section.optJSONArray("fields"), binding.definition.optString("path"))) continue;
                pointerSet(candidate, binding.definition.optString("path"), binding.value());
            }
            graph.config.activate(candidate.toString());
            configEditor.setText(graph.config.currentText());
            refreshAll();
        });
    }

    private static JSONObject findManagementSection(JSONObject config, String id) {
        JSONArray sections = config.optJSONObject("management").optJSONArray("sections");
        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.optJSONObject(i);
            if (id.equals(section.optString("id"))) return section;
        }
        throw new IllegalArgumentException("管理分区不存在: " + id);
    }

    private static boolean containsField(JSONArray fields, String path) {
        for (int i = 0; i < fields.length(); i++) if (path.equals(fields.optJSONObject(i).optString("path"))) return true;
        return false;
    }

    private static Object pointerGet(JSONObject root, String pointer) {
        Object cursor = root;
        String[] parts = pointer.substring(1).split("/");
        for (String encoded : parts) {
            String key = encoded.replace("~1", "/").replace("~0", "~");
            if (cursor instanceof JSONObject) cursor = ((JSONObject) cursor).opt(key);
            else if (cursor instanceof JSONArray) cursor = ((JSONArray) cursor).opt(Integer.parseInt(key));
            else throw new IllegalArgumentException("JSON Pointer 穿过非容器值: " + pointer);
            if (cursor == null) throw new IllegalArgumentException("JSON Pointer 不存在: " + pointer);
        }
        return cursor;
    }

    private static void pointerSet(JSONObject root, String pointer, Object value) throws Exception {
        Object cursor = root;
        String[] parts = pointer.substring(1).split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            String key = parts[i].replace("~1", "/").replace("~0", "~");
            cursor = cursor instanceof JSONObject ? ((JSONObject) cursor).get(key)
                    : ((JSONArray) cursor).get(Integer.parseInt(key));
        }
        String key = parts[parts.length - 1].replace("~1", "/").replace("~0", "~");
        if (cursor instanceof JSONObject) ((JSONObject) cursor).put(key, value);
        else if (cursor instanceof JSONArray) ((JSONArray) cursor).put(Integer.parseInt(key), value);
        else throw new IllegalArgumentException("JSON Pointer 父节点不是容器: " + pointer);
    }

    private void renderRuntime() {
        RuntimeState state = graph.runtime.state();
        String value = "运行时：" + state.phase.name().toLowerCase(Locale.ROOT);
        if (state.coreVersion != null) value += "  核心 " + state.coreVersion;
        if (state.modelId != null) value += "  模型 " + state.modelId;
        runtimeStatus.setText(value);
        runtimeStatus.setTextColor(state.phase == RuntimeState.Phase.ERROR ? ERROR : TEXT);
        if (state.error != null) errorStatus.setText("运行时错误：" + state.error);
        renderModels();
    }

    private void renderResources() {
        resourceList.removeAllViews();
        for (ResourceState state : graph.resources.states()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            String versions = state.version == null ? "未安装" : state.version;
            if (state.targetVersion != null && !state.targetVersion.equals(state.version)) versions += " → " + state.targetVersion;
            TextView label = text(state.id + "  ·  " + state.type + "  ·  " + versions, 14, TEXT);
            row.addView(label);
            String detail = state.status.name().toLowerCase(Locale.ROOT);
            if (state.total > 0) detail += "  " + state.downloaded + "/" + state.total;
            if (state.error != null) detail += "\n" + state.error;
            TextView status = text(detail, 12, state.status == ResourceState.Status.FAILED ? ERROR : MUTED);
            row.addView(status);
            Button install = action(state.status == ResourceState.Status.INSTALLED ? "重新安装"
                    : state.usable() ? "安装更新" : "安装/继续", () ->
                    runAction("安装资源", () -> {
                        BackendService.command(this, BackendService.ACTION_START);
                        graph.resources.install(state.id);
                    }));
            Button delete = action("删除", () -> runAction("删除资源", () -> {
                RuntimeState runtime = graph.runtime.state();
                if (state.id.equals(runtime.coreId) || modelUsesResource(runtime.modelId, state.id)) graph.runtime.unload();
                graph.resources.delete(state.id);
            }));
            if ("config".equals(state.type) && state.usable()) {
                row.addView(buttonRow(install, delete,
                        action("查看", () -> viewTextResource(state.id)),
                        action("激活配置", () -> activateConfigResource(state.id))));
            } else if ("template".equals(state.type) && state.usable()) {
                row.addView(buttonRow(install, delete, action("查看", () -> viewTextResource(state.id))));
            } else {
                row.addView(buttonRow(install, delete));
            }
            resourceList.addView(row);
        }
        if (resourceList.getChildCount() == 0) resourceList.addView(text("配置中尚无资源。可在 JSON 中注册。", 13, MUTED));
    }

    private void renderModels() {
        if (modelList == null) return;
        modelList.removeAllViews();
        JSONArray models = graph.config.current().optJSONArray("models");
        RuntimeState runtime = graph.runtime.state();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            String id = model.optString("id");
            String resourceId = model.optString("resource");
            String mmproj = model.optString("mmproj");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            row.addView(text(model.optString("name") + "  ·  " + id, 14, TEXT));
            row.addView(text((id.equals(runtime.modelId) ? "当前模型 · " + runtime.phase.name().toLowerCase(Locale.ROOT)
                    : "未加载") + (mmproj.isEmpty() ? "  ·  纯文本" : "  ·  多模态 已配对"),
                    12, id.equals(runtime.modelId) ? PRIMARY : MUTED));
            row.addView(buttonRow(
                    action("加载", () -> runBackground("加载模型", () -> graph.runtime.loadModel(id))),
                    action("卸载", () -> runAction("卸载模型", () -> graph.runtime.unload())),
                    action(mmproj.isEmpty() ? "配对mmproj" : "换mmproj", () -> beginPairMmproj(id)),
                    action("删除", () -> runAction("删除模型", () -> deleteModel(id)))));
            modelList.addView(row);
        }
        if (models.length() == 0) modelList.addView(text("尚无模型。点击 导入 GGUF 从本地选择 .gguf 文件。", 13, MUTED));
    }

    private void deleteModel(String modelId) throws Exception {
        RuntimeState runtime = graph.runtime.state();
        if (modelId.equals(runtime.modelId)) graph.runtime.unload();
        JSONObject next = graph.config.current();
        JSONObject model = configModel(next, modelId);
        if (model == null) throw new IllegalArgumentException("配置中不存在模型: " + modelId);
        JSONArray models = next.optJSONArray("models");
        JSONArray kept = new JSONArray();
        for (int i = 0; i < models.length(); i++) {
            JSONObject item = models.optJSONObject(i);
            if (!modelId.equals(item.optString("id"))) kept.put(item);
        }
        next.put("models", kept);
        graph.config.activate(next.toString());
        graph.resources.delete(model.optString("resource"));
        String mmproj = model.optString("mmproj");
        if (!mmproj.isEmpty()) graph.resources.delete(mmproj);
        refreshAll();
    }

    private void renderLog() {
        try {
            logView.setText(graph.events.readAll());
        } catch (Exception error) {
            logView.setText("读取日志失败: " + error.getMessage());
            logView.setTextColor(ERROR);
        }
    }

    private void renderLastError(JSONObject event) {
        if ("error".equals(event.optString("level"))) {
            errorStatus.setText(event.optString("component") + "：" + event.optString("message")
                    + (event.has("error") ? "\n" + event.optString("error") : ""));
        }
    }

    private void viewTextResource(String id) {
        runAction("查看资源", () -> {
            File file = graph.resources.installedFile(id);
            try (FileInputStream input = new FileInputStream(file)) {
                TextView body = text(Jsons.readUtf8(input), 12, TEXT);
                body.setTypeface(Typeface.MONOSPACE);
                body.setTextIsSelectable(true);
                body.setPadding(dp(16), dp(12), dp(16), dp(12));
                ScrollView scroll = new ScrollView(this);
                scroll.addView(body);
                new AlertDialog.Builder(this)
                        .setTitle(id)
                        .setView(scroll)
                        .setPositiveButton("关闭", null)
                        .show();
            }
        });
    }

    private void activateConfigResource(String id) {
        runAction("激活配置资源", () -> {
            File file = graph.resources.installedFile(id);
            try (FileInputStream input = new FileInputStream(file)) {
                String candidate = Jsons.readUtf8(input);
                graph.config.activate(candidate);
                configEditor.setText(graph.config.currentText());
                refreshAll();
            }
        });
    }

    private void importConfig() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        startActivityForResult(intent, IMPORT_CONFIG);
    }

    private boolean modelUsesResource(String modelId, String resourceId) {
        if (modelId == null) return false;
        JSONArray models = graph.config.current().optJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (modelId.equals(model.optString("id"))) {
                return resourceId.equals(model.optString("resource"))
                        || resourceId.equals(model.optString("mmproj"))
                        || resourceId.equals(model.optJSONObject("template").optString("resource"));
            }
        }
        return false;
    }

    private void loadConfigIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        Uri uri = intent.getData();
        runAction("打开配置", () -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IllegalStateException("系统未提供配置输入流");
                configEditor.setText(graph.config.readImport(input));
                configEditor.requestFocus();
                toast("外部配置已载入，请检查后原子激活");
            }
        });
    }

    private void exportConfig() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "localcore-config.json");
        startActivityForResult(intent, EXPORT_CONFIG);
    }

    private void exportLog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-ndjson")
                .putExtra(Intent.EXTRA_TITLE, "localcore-events.jsonl");
        startActivityForResult(intent, EXPORT_LOG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == IMPORT_GGUF) {
            runBackground("导入 GGUF 模型", () -> {
                String modelId = graph.exchange.importModel(uri);
                runBackground("读取内置模板", () -> {
                    String template = graph.exchange.readTemplate(modelId);
                    ui(() -> {
                        templateModelId = modelId;
                        refreshAll();
                        if (template != null) {
                            templateEditor.setText(template);
                            toast("模型已导入，内置模板已填入模板框");
                        } else {
                            toast("模型已导入，该文件未内置聊天模板");
                        }
                    });
                });
            });
        } else if (requestCode == IMPORT_CORE) {
            runBackground("导入核心", () -> graph.exchange.importCore(uri));
        } else if (requestCode == IMPORT_MMPROJ) {
            String modelId = pendingPairModelId;
            pendingPairModelId = null;
            runBackground("配对 MMPROJ", () -> {
                if (modelId == null) throw new IllegalStateException("配对目标已丢失");
                graph.exchange.importMmproj(uri, modelId);
            });
        } else if (requestCode == EXPORT_CORE) {
            String coreId = pendingExportCoreId;
            pendingExportCoreId = null;
            runBackground("导出核心", () -> {
                if (coreId == null) throw new IllegalStateException("导出目标已丢失");
                graph.exchange.exportCore(coreId, uri);
            });
        } else if (requestCode == IMPORT_CONFIG) {
            runAction("导入配置", () -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("系统未提供导入流");
                    configEditor.setText(graph.config.readImport(input));
                    toast("导入内容已载入，请检查后原子激活");
                }
            });
        } else if (requestCode == EXPORT_CONFIG) {
            runAction("导出配置", () -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IllegalStateException("系统未提供导出流");
                    graph.config.exportTo(output);
                }
                toast("配置已导出");
            });
        } else if (requestCode == EXPORT_LOG) {
            runAction("导出日志", () -> {
                try (InputStream input = new FileInputStream(graph.events.file());
                     OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IllegalStateException("系统未提供导出流");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.flush();
                }
                toast("诊断日志已导出");
            });
        }
    }

    private void runAction(String operation, ThrowingAction action) {
        try {
            action.run();
        } catch (Exception error) {
            graph.events.error("ui", operation + "失败", error);
            errorStatus.setText(operation + "失败：" + error.getMessage());
        }
    }

    private void runBackground(String operation, ThrowingAction action) {
        new Thread(() -> {
            try {
                action.run();
                ui(this::refreshAll);
            } catch (Exception error) {
                graph.events.error("ui", operation + "失败", error);
                ui(() -> errorStatus.setText(operation + "失败：" + error.getMessage()));
            }
        }, "localcore-ui-task").start();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackgroundColor(SURFACE);
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);
        return card;
    }

    private void section(String value) {
        TextView heading = text(value, 18, TEXT);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(16), 0, dp(8));
        content.addView(heading);
    }

    private View buttonRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        row.setPadding(0, dp(8), 0, 0);
        for (Button button : buttons) row.addView(button);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    private Button action(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.BLACK);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void ui(Runnable action) {
        runOnUiThread(action);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class FieldBinding {
        final JSONObject definition;
        final View view;
        final Object original;

        FieldBinding(JSONObject definition, View view, Object original) {
            this.definition = definition;
            this.view = view;
            this.original = original;
        }

        Object value() {
            if (view instanceof Switch) return ((Switch) view).isChecked();
            if (view instanceof Spinner) {
                int index = ((Spinner) view).getSelectedItemPosition();
                return definition.optJSONArray("options").opt(index);
            }
            String text = ((EditText) view).getText().toString();
            if (original instanceof Integer) return Integer.parseInt(text);
            if (original instanceof Long) return Long.parseLong(text);
            if (original instanceof Number) return Double.parseDouble(text);
            return text;
        }
    }
}
