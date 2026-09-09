package com.localcore.runtime;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

final class MediaResolver {
    static final class Prepared implements AutoCloseable {
        final JSONArray messages;
        final JSONArray paths;

        Prepared(JSONArray messages, JSONArray paths) {
            this.messages = messages;
            this.paths = paths;
        }

        @Override
        public void close() {
            for (int i = 0; i < paths.length(); i++) {
                File file = new File(paths.optString(i));
                if (file.isFile() && !file.delete()) file.deleteOnExit();
            }
        }
    }

    private static final String MARKER = "<__media__>";
    private final File cache;
    private volatile int maxImagePixels = 100000;

    MediaResolver(Context context) {
        cache = new File(context.getCacheDir(), "request-media");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw new IllegalStateException("无法创建请求媒体目录: " + cache);
        }
    }

    public void setMaxImagePixels(int pixels) {
        maxImagePixels = pixels;
    }

    Prepared prepare(JSONArray input) throws IOException {
        JSONArray messages;
        JSONArray paths = new JSONArray();
        try {
            RuntimeManager.checkRequestInterrupted();
            messages = new JSONArray(input.toString());
            for (int i = 0; i < messages.length(); i++) {
                RuntimeManager.checkRequestInterrupted();
                JSONObject message = messages.getJSONObject(i);
                Object content = message.opt("content");
                if (!(content instanceof JSONArray)) continue;
                JSONArray parts = (JSONArray) content;
                for (int j = 0; j < parts.length(); j++) {
                    RuntimeManager.checkRequestInterrupted();
                    JSONObject part = parts.getJSONObject(j);
                    if (!"image_url".equals(part.optString("type"))) continue;
                    Object image = part.get("image_url");
                    String address = image instanceof JSONObject
                            ? ((JSONObject) image).getString("url") : String.valueOf(image);
                    File file = File.createTempFile("media-", ".bin", cache);
                    paths.put(file.getAbsolutePath());
                    write(address, file);
                    parts.put(j, new JSONObject().put("type", "media_marker").put("text", MARKER));
                }
            }
            applyBudget(paths);
            RuntimeManager.checkRequestInterrupted();
            return new Prepared(messages, paths);
        } catch (Exception error) {
            for (int i = 0; i < paths.length(); i++) new File(paths.optString(i)).delete();
            RuntimeManager.checkRequestInterrupted();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("解析多模态请求失败: " + error.getMessage(), error);
        }
    }

    // 整轮图片总像素预算：超限则等比压缩，对测试端与后端 HTTP 生效。
    private void applyBudget(JSONArray paths) throws IOException {
        int budget = maxImagePixels;
        if (budget <= 0 || paths.length() == 0) return;
        long total = 0;
        int[] widths = new int[paths.length()];
        int[] heights = new int[paths.length()];
        for (int i = 0; i < paths.length(); i++) {
            RuntimeManager.checkRequestInterrupted();
            int[] size = probe(new File(paths.optString(i)));
            widths[i] = size[0];
            heights[i] = size[1];
            total += (long) size[0] * size[1];
        }
        if (total <= budget) return;
        double scale = Math.sqrt(budget / (double) total);
        for (int i = 0; i < paths.length(); i++) {
            RuntimeManager.checkRequestInterrupted();
            if (widths[i] <= 0 || heights[i] <= 0) continue;
            int targetWidth = Math.max(1, (int) (widths[i] * scale));
            int targetHeight = Math.max(1, (int) (heights[i] * scale));
            File scaled = scaleFile(new File(paths.optString(i)), targetWidth, targetHeight);
            if (scaled != null) {
                new File(paths.optString(i)).delete();
                try {
                    paths.put(i, scaled.getAbsolutePath());
                } catch (org.json.JSONException error) {
                    scaled.delete();
                    throw new IOException("更新媒体路径失败", error);
                }
            }
        }
    }

    private static int[] probe(File file) {
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return new int[]{options.outWidth, options.outHeight};
    }

    private File scaleFile(File file, int targetWidth, int targetHeight) throws IOException {
        int sample = 1;
        int[] size = probe(file);
        while ((size[0] / (sample * 2)) * (size[1] / (sample * 2)) > (long) targetWidth * targetHeight) sample *= 2;
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inSampleSize = sample;
        android.graphics.Bitmap decoded = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (decoded == null) return null;
        try {
            android.graphics.Bitmap scaled = decoded.getWidth() == targetWidth && decoded.getHeight() == targetHeight
                    ? decoded
                    : android.graphics.Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true);
            File output = File.createTempFile("media-scaled-", ".jpg", cache);
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, stream)) {
                    throw new IOException("图片压缩失败");
                }
                stream.getFD().sync();
            } catch (Exception error) {
                output.delete();
                throw error;
            }
            if (scaled != decoded) scaled.recycle();
            decoded.recycle();
            return output;
        } catch (Exception error) {
            decoded.recycle();
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("图片压缩失败: " + error.getMessage(), error);
        }
    }

    private static void write(String address, File target) throws IOException {
        RuntimeManager.checkRequestInterrupted();
        try (InputStream input = open(address); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                RuntimeManager.checkRequestInterrupted();
                output.write(buffer, 0, count);
            }
            RuntimeManager.checkRequestInterrupted();
            output.getFD().sync();
        }
    }

    private static InputStream open(String address) throws IOException {
        if (address.startsWith("data:")) {
            int comma = address.indexOf(',');
            if (comma < 0) throw new IOException("data URL 缺少数据分隔符");
            String metadata = address.substring(0, comma);
            if (!metadata.endsWith(";base64")) throw new IOException("图片 data URL 必须使用 base64");
            return new java.io.ByteArrayInputStream(Base64.decode(address.substring(comma + 1), Base64.DEFAULT));
        }
        URLConnection connection = new URL(address).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        return new BufferedInputStream(connection.getInputStream());
    }
}
