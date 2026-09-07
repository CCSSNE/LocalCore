package com.localcore.io;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Archives {
    private static final int BLOCK = 512;

    private Archives() {
    }

    public static boolean isZip(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read() == 'P' && input.read() == 'K';
        }
    }

    public static boolean isGzip(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            return input.read() == 0x1f && input.read() == 0x8b;
        }
    }

    public static boolean isTar(File file) throws IOException {
        try (InputStream input = openArchive(file)) {
            byte[] header = new byte[BLOCK];
            if (input.read(header) != BLOCK) return false;
            return matches(header, 257, "ustar");
        }
    }

    public static List<String> zipEntries(File file) throws IOException {
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) entries.add(entry.getName());
            }
        }
        return entries;
    }

    public static List<String> tarEntries(File file) throws IOException {
        List<String> entries = new ArrayList<>();
        try (InputStream input = new BufferedInputStream(openArchive(file))) {
            byte[] header = new byte[BLOCK];
            while (true) {
                if (!readFully(input, header)) break;
                if (isZeroBlock(header)) break;
                if (!matches(header, 257, "ustar")) throw new IOException("无效的 tar 归档头");
                long size = size(header);
                if (header[156] == '0' || header[156] == 0) entries.add(name(header));
                long skip = ((size + BLOCK - 1) / BLOCK) * BLOCK;
                long remaining = skip;
                byte[] sink = new byte[64 * 1024];
                while (remaining > 0) {
                    long read = input.skip(remaining);
                    if (read <= 0) {
                        if (input.read(sink, 0, (int) Math.min(remaining, sink.length)) < 0) break;
                    }
                    remaining -= read;
                }
            }
        }
        return entries;
    }

    public static void unzip(File archive, File target) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File output = new File(target, entry.getName());
                if (entry.isDirectory()) {
                    ensureDirectory(output);
                } else {
                    ensureDirectory(output.getParentFile());
                    writeFile(zip, output);
                }
            }
        }
    }

    public static void untar(File archive, File target) throws IOException {
        try (InputStream input = new BufferedInputStream(openArchive(archive))) {
            byte[] header = new byte[BLOCK];
            while (true) {
                if (!readFully(input, header)) break;
                if (isZeroBlock(header)) break;
                if (!matches(header, 257, "ustar")) throw new IOException("无效的 tar 归档头");
                long size = size(header);
                boolean regular = header[156] == '0' || header[156] == 0;
                File output = new File(target, name(header));
                if (!regular) {
                    skipFully(input, size);
                    continue;
                }
                ensureDirectory(output.getParentFile());
                long remaining = size;
                try (FileOutputStream out = new FileOutputStream(output)) {
                    byte[] buffer = new byte[64 * 1024];
                    while (remaining > 0) {
                        int chunk = input.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                        if (chunk < 0) throw new IOException("tar 数据提前结束");
                        out.write(buffer, 0, chunk);
                        remaining -= chunk;
                    }
                    out.getFD().sync();
                }
                skipFully(input, ((size + BLOCK - 1) / BLOCK) * BLOCK - size);
            }
        }
    }

    public static void ensureDirectory(File directory) throws IOException {
        if (directory == null) return;
        if (directory.isDirectory()) return;
        if (directory.exists()) throw new IOException("路径被文件占用: " + directory);
        if (!directory.mkdirs()) throw new IOException("无法创建目录: " + directory);
    }

    private static InputStream openArchive(File file) throws IOException {
        return isGzip(file) ? new GZIPInputStream(new BufferedInputStream(new FileInputStream(file)))
                : new BufferedInputStream(new FileInputStream(file));
    }

    private static void writeFile(InputStream input, File output) throws IOException {
        try (FileOutputStream out = new FileOutputStream(output)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            out.getFD().sync();
        }
    }

    private static String name(byte[] header) {
        StringBuilder value = new StringBuilder();
        appendField(value, header, 0, 100);
        appendField(value, header, 345, 155);
        return value.toString();
    }

    private static void appendField(StringBuilder builder, byte[] header, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (header[i] == 0) break;
            builder.append((char) (header[i] & 0xff));
        }
    }

    private static long size(byte[] header) {
        long value = 0;
        for (int i = 124; i < 136; i++) {
            byte item = header[i];
            if (item == ' ' || item == 0) continue;
            value = value * 8 + (item - '0');
        }
        return value;
    }

    private static boolean matches(byte[] header, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (header[offset + i] != text.charAt(i)) return false;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] header) {
        for (byte item : header) {
            if (item != 0) return false;
        }
        return true;
    }

    private static boolean readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) return offset == 0;
            offset += count;
        }
        return true;
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) return;
                skipped = 1;
            }
            remaining -= skipped;
        }
    }
}
