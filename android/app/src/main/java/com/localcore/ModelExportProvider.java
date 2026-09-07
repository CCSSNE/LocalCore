package com.localcore;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.localcore.app.LocalExchange;

import java.io.FileNotFoundException;
import java.util.List;

public final class ModelExportProvider extends ContentProvider {
    private static final String MIME = "application/octet-stream";

    public static Uri uriFor(android.content.Context context, String modelId, String role) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".model-export")
                .appendPath("model")
                .appendPath(modelId)
                .appendPath(role)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        LocalExchange.ExportFile export = resolve(uri);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = export.displayName;
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = export.file.length();
            else values[i] = null;
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        resolve(uri);
        return MIME;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("模型导出仅支持只读模式: " + mode);
        try {
            LocalExchange.ExportFile export = resolve(uri);
            return ParcelFileDescriptor.open(export.file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception error) {
            FileNotFoundException result = new FileNotFoundException(
                    "无法打开模型导出文件: " + error.getMessage());
            result.initCause(error);
            throw result;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("模型导出 Provider 不支持写入");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("模型导出 Provider 不支持删除");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("模型导出 Provider 不支持更新");
    }

    private LocalExchange.ExportFile resolve(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 3 || !"model".equals(segments.get(0))) {
            throw new IllegalArgumentException("模型导出地址无效: " + uri);
        }
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("模型导出 Provider 尚未初始化");
        android.app.Application application = (android.app.Application) context.getApplicationContext();
        if (!(application instanceof MainApplication)) {
            throw new IllegalStateException("模型导出 Provider 无法访问 LocalCore 应用图");
        }
        return ((MainApplication) application).getGraph().exchange
                .modelExportFile(segments.get(1), segments.get(2));
    }
}
