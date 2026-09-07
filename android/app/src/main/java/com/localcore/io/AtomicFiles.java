package com.localcore.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class AtomicFiles {
    private AtomicFiles() {}

    public static void writeUtf8(File target, String value) throws IOException {
        write(target, value.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(File target, byte[] value) throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("无法创建目录: " + parent);
        }
        File candidate = new File(parent, target.getName() + ".candidate");
        try (FileOutputStream output = new FileOutputStream(candidate, false)) {
            output.write(value);
            output.getFD().sync();
        }
        move(candidate, target);
    }

    public static void move(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            throw new IOException("文件系统不支持原子激活: " + target, error);
        }
    }
}

