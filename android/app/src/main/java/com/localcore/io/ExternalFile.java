package com.localcore.io;

import android.content.ContentResolver;
import android.net.Uri;
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
