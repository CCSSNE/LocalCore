package com.localcore.io;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class GgufMeta {
    private static final String CHAT_TEMPLATE = "tokenizer.chat_template";
    private static final String GENERAL_ARCHITECTURE = "general.architecture";
    private static final String LEGACY_CONTEXT_LENGTH = "llama.context_length";
    private static final int TYPE_STRING = 8;
    private static final int TYPE_ARRAY = 9;

    private GgufMeta() {
    }

    public static String chatTemplate(File file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            return parseChatTemplate(input, file.getName());
        }
    }

    public static String chatTemplate(InputStream stream, String name) throws IOException {
        DataInputStream input = stream instanceof DataInputStream ? (DataInputStream) stream
                : new DataInputStream(stream instanceof BufferedInputStream ? stream
                        : new BufferedInputStream(stream, 1 << 16));
        return parseChatTemplate(input, name);
    }

    private static String parseChatTemplate(DataInputStream input, String name) throws IOException {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                throw new IOException("不是 GGUF 文件: " + name);
            }
            readU32(input);
            readU64(input);
            long pairs = readU64(input);
            for (long i = 0; i < pairs; i++) {
                String key = readString(input);
                int type = (int) readU32(input);
                if (CHAT_TEMPLATE.equals(key) && type == TYPE_STRING) return readString(input);
                if (CHAT_TEMPLATE.equals(key) && type == TYPE_ARRAY) {
                    int elementType = (int) readU32(input);
                    long count = readU64(input);
                    if (elementType == TYPE_STRING && count > 0) {
                        String first = readString(input);
                        skipArray(input, elementType, count - 1);
                        return first;
                    }
                    skipArray(input, elementType, count);
                    return null;
                }
                skipValue(input, type);
            }
            return null;
    }

    // 先找 {arch}.context_length（现代规范），再找 llama.context_length（老规范）。
    // 都没有或不是整数标量时返回 -1，由调用方决定回退值。
    public static long contextLength(File file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                throw new IOException("不是 GGUF 文件: " + file.getName());
            }
            readU32(input);
            readU64(input);
            long pairs = readU64(input);
            String architecture = null;
            Long archContext = null;
            Long legacyContext = null;
            for (long i = 0; i < pairs; i++) {
                String key = readString(input);
                int type = (int) readU32(input);
                if (GENERAL_ARCHITECTURE.equals(key) && type == TYPE_STRING) {
                    architecture = readString(input);
                    continue;
                }
                if (key.endsWith(".context_length")) {
                    Long value = readIntValue(input, type);
                    if (value != null) {
                        if (LEGACY_CONTEXT_LENGTH.equals(key)) legacyContext = value;
                        else archContext = value;
                    }
                    continue;
                }
                skipValue(input, type);
            }
            if (architecture != null && archContext == null) {
                Long retry = readArchContext(file, architecture);
                if (retry != null) archContext = retry;
            }
            if (archContext != null) return archContext;
            if (legacyContext != null) return legacyContext;
            return -1;
        }
    }

    // 架构键可能出现在 general.architecture 之后，扫第二遍兜底。只读文件头，不碰权重。
    private static Long readArchContext(File file, String architecture) throws IOException {
        String wanted = architecture + ".context_length";
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') return null;
            readU32(input);
            readU64(input);
            long pairs = readU64(input);
            for (long i = 0; i < pairs; i++) {
                String key = readString(input);
                int type = (int) readU32(input);
                if (!wanted.equals(key)) {
                    skipValue(input, type);
                    continue;
                }
                return readIntValue(input, type);
            }
            return null;
        }
    }

    private static Long readIntValue(DataInputStream input, int type) throws IOException {
        switch (type) {
            case 0: return (long) input.readUnsignedByte();
            case 1: return (long) input.readByte();
            case 2: return (long) Short.reverseBytes(input.readShort()) & 0xffffL;
            case 3: return (long) Short.reverseBytes(input.readShort());
            case 4: return readU32(input);
            case 5: return (long) Integer.reverseBytes(input.readInt());
            case 6: return (long) Float.intBitsToFloat(Integer.reverseBytes(input.readInt()));
            case 10: return readU64(input);
            case 11: return Long.reverseBytes(input.readLong());
            default: return null;
        }
    }

    private static void skipValue(DataInputStream input, int type) throws IOException {        switch (type) {
            case 0: case 1: case 7: skip(input, 1); return;
            case 2: case 3: skip(input, 2); return;
            case 4: case 5: case 6: skip(input, 4); return;
            case 10: case 11: case 12: skip(input, 8); return;
            case TYPE_STRING: skip(input, readU64(input)); return;
            case TYPE_ARRAY: skipArray(input, (int) readU32(input), readU64(input)); return;
            default: throw new IOException("GGUF 值类型未知: " + type);
        }
    }

    private static void skipArray(DataInputStream input, int elementType, long count) throws IOException {
        if (elementType == TYPE_ARRAY) {
            for (long i = 0; i < count; i++) skipValue(input, elementType);
            return;
        }
        if (elementType == TYPE_STRING) {
            for (long i = 0; i < count; i++) skip(input, readU64(input));
            return;
        }
        int width = fixedSize(elementType);
        long total;
        try {
            total = Math.multiplyExact(width, count);
        } catch (ArithmeticException error) {
            throw new IOException("GGUF 数组长度超出可寻址范围", error);
        }
        skip(input, total);
    }

    private static int fixedSize(int type) throws IOException {
        switch (type) {
            case 0: case 1: case 7: return 1;
            case 2: case 3: return 2;
            case 4: case 5: case 6: return 4;
            case 10: case 11: case 12: return 8;
            default: throw new IOException("GGUF 值类型未知: " + type);
        }
    }

    private static long readU32(DataInputStream input) throws IOException {
        return (long) Integer.reverseBytes(input.readInt()) & 0xffffffffL;
    }

    private static long readU64(DataInputStream input) throws IOException {
        return Long.reverseBytes(input.readLong());
    }

    private static String readString(DataInputStream input) throws IOException {
        long length = readU64(input);
        if (length < 0 || length > Integer.MAX_VALUE) throw new IOException("GGUF 字符串长度异常: " + length);
        byte[] bytes = new byte[(int) length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void skip(DataInputStream input, long count) throws IOException {
        while (count > 0) {
            long moved = input.skip(count);
            if (moved > 0) {
                count -= moved;
                continue;
            }
            int next = input.read();
            if (next == -1) throw new EOFException("GGUF 文件提前结束");
            count -= 1;
        }
    }
}
