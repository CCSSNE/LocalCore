package com.localcore.io;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.FileNotFoundException;

/**
 * 外部引用模型的文件抽象：SAF URI 打开后必须是可内存映射的本地 regular 文件，
 * 否则直接拒绝，不做任何静默拷贝回退。调用方负责关闭返回的句柄。
 */
public final class ExternalFile {
    private ExternalFile() {
    }

    public static ParcelFileDescriptor openRegularFile(ContentResolver resolver, Uri uri) throws Exception {
        // llama.cpp 在 native 层会按路径重新 open 做 mmap，SAF 授权覆盖不到那次打开，
        // 共享存储上的裸路径打开必须靠所有文件访问权限，否则 MediaProvider 直接拒绝。
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            throw new IllegalStateException(
                    "NEED_ALL_FILES_ACCESS: 直接读取外部模型需要「所有文件访问权限」，请开启后重试");
        }
        ParcelFileDescriptor handle;
        try {
            handle = resolver.openFileDescriptor(uri, "r");
        } catch (FileNotFoundException error) {
            throw new IllegalStateException("外部模型源文件不存在，可能已被删除或移动: " + uri);
        } catch (SecurityException error) {
            throw new IllegalStateException("外部模型授权丢失或被拒绝，请删除后重新引用: " + uri);
        }
        if (handle == null) throw new IllegalStateException("系统未提供外部模型文件: " + uri);
        StructStat meta;
        try {
            meta = Os.fstat(handle.getFileDescriptor());
        } catch (ErrnoException error) {
            try {
                handle.close();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("无法读取外部模型信息: " + error.getMessage());
        }
        if ((meta.st_mode & OsConstants.S_IFMT) != OsConstants.S_IFREG) {
            try {
                handle.close();
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("该位置不支持直接读取（非本地文件，无法内存映射），拒绝外部引用: " + uri);
        }
        return handle;
    }

    public static String fdPath(ParcelFileDescriptor handle) {
        return "/proc/self/fd/" + handle.getFd();
    }
}
