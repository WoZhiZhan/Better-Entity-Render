package com.wzz.better_entity_render.util;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LoaderUtil  {
    private static final Map<String, String> loadedLibraries = new ConcurrentHashMap<>();

    public static void load(String name) {
        loadNative("/" + name);
    }

    public static String loadNative(String name) {
        if (!OSHelper.isWindows())
            return "";
        if (loadedLibraries.containsKey(name)) {
            return loadedLibraries.get(name);
        }
        char[] chars = {0x2E, 0x64, 0x6C, 0x6C};
        String full = new String(chars);
        if (name.endsWith(full)) name = name.replace(full, "");
        try (InputStream inputStream = LoaderUtil.class.getResourceAsStream(name + full)) {
            if (inputStream == null) {
                throw new UnsatisfiedLinkError("Native resource not found: " + name + full);
            }
            int lastSlashIndex = name.lastIndexOf('/');
            File tempFile = File.createTempFile(name.substring(lastSlashIndex + 1), full);
            Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tempFile.deleteOnExit();
            Runtime.getRuntime().load(tempFile.getAbsolutePath());
            loadedLibraries.put(name, tempFile.getAbsolutePath());
            return tempFile.getAbsolutePath();
        } catch (Throwable ex) {
            System.err.println("Failed to load native library: " + name + " " + ex.getMessage());
            return null;
        }
    }
}