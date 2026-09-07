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
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ArrayAdapter;
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
    private static final int IMPORT_MODEL = 13;
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
    private LinearLayout resourceList;
    private LinearLayout modelList;
    private EditText configEditor;
    private TextView logView;
    private TextView updateStatus;
    private LinearLayout managementContainer;
    private final List<FieldBinding> fieldBindings = new ArrayList<>();
    private String pendingImportResourceId;

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
        if (state != null) pendingImportResourceId = state.getString("pendingImportResourceId");
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
        state.putString("pendingImportResourceId", pendingImportResourceId);
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
        modelList = card();
        content.addView(modelList);

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
        renderResources();
        renderModels();
        renderManagement();
        renderUpdates();
        if (!configEditor.hasFocus()) configEditor.setText(graph.config.currentText());
        renderLog();
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
            } else if ("model".equals(state.type)) {
                row.addView(buttonRow(install, action("导入本地 GGUF", () -> beginModelImport(state.id)), delete));
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
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            row.addView(text(model.optString("name") + "  ·  " + id, 14, TEXT));
            row.addView(text(id.equals(runtime.modelId) ? "当前模型 · " + runtime.phase.name().toLowerCase(Locale.ROOT)
                    : "未加载", 12, id.equals(runtime.modelId) ? PRIMARY : MUTED));
            row.addView(buttonRow(
                    action("加载", () -> runBackground("加载模型", () -> graph.runtime.loadModel(id))),
                    action("卸载", () -> runAction("卸载模型", () -> graph.runtime.unload()))));
            modelList.addView(row);
        }
        if (models.length() == 0) modelList.addView(text("配置中尚无模型。", 13, MUTED));
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

    private void beginModelImport(String id) {
        pendingImportResourceId = id;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream");
        startActivityForResult(intent, IMPORT_MODEL);
    }

    private boolean modelUsesResource(String modelId, String resourceId) {
        if (modelId == null) return false;
        JSONArray models = graph.config.current().optJSONArray("models");
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (modelId.equals(model.optString("id"))) {
                return resourceId.equals(model.optString("resource"))
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
        runAction("文档操作", () -> {
            if (requestCode == IMPORT_CONFIG) {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("系统未提供导入流");
                    configEditor.setText(graph.config.readImport(input));
                    toast("导入内容已载入，请检查后原子激活");
                }
            } else if (requestCode == IMPORT_MODEL) {
                String id = pendingImportResourceId;
                pendingImportResourceId = null;
                if (id == null) throw new IllegalStateException("模型导入目标已丢失");
                runBackground("导入本地模型", () -> {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IllegalStateException("系统未提供模型输入流");
                        graph.resources.importResource(id, input);
                    }
                });
            } else if (requestCode == EXPORT_CONFIG) {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IllegalStateException("系统未提供导出流");
                    graph.config.exportTo(output);
                }
                toast("配置已导出");
            } else if (requestCode == EXPORT_LOG) {
                try (InputStream input = new FileInputStream(graph.events.file());
                     OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IllegalStateException("系统未提供导出流");
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.flush();
                }
                toast("诊断日志已导出");
            }
        });
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
