package com.wzz.better_entity_render.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

public class OSHelper {
    private static final String OS_NAME;
    private static final String VENDOR;
    private static final boolean IS_WINDOWS;
    private static final boolean IS_LINUX;
    private static final boolean IS_MAC;
    private static final boolean IS_ANDROID;
    private static final boolean IS_ADMIN;

    static {
        OS_NAME = System.getProperty("os.name", "").toLowerCase();
        VENDOR = System.getProperty("java.vendor", "").toLowerCase();
        IS_WINDOWS = OS_NAME.contains("win");
        IS_LINUX = OS_NAME.contains("nux") || OS_NAME.contains("nix");
        IS_MAC = OS_NAME.contains("mac");
        String vm = System.getProperty("java.vm.name", "").toLowerCase();
        if (vm.contains("dalvik") || vm.contains("art"))  {
            IS_ANDROID = true;
        } else IS_ANDROID = new File("/system/build.prop").exists();
        boolean admin = false;
        if (IS_WINDOWS) {
            try {
                File hosts = new File("C:\\Windows\\System32\\drivers\\etc\\hosts");
                admin = hosts.canWrite();
            } catch (Throwable ignored) {
            }
        }
        IS_ADMIN = admin;
    }

    public static String getOSName() {
        return OS_NAME;
    }

    public static String getVendor() {
        return VENDOR;
    }

    public static boolean isWindows() {
        return IS_WINDOWS;
    }

    public static boolean isWindowsAdmin() {
        if (!isWindows())
            return false;
        try {
            Process process = Runtime.getRuntime().exec("net session");
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isUnixRoot() {
        if (isWindows())
            return false;
        String name = System.getProperty("user.name");
        if ("root".equals(name)) {
            return true;
        }
        try {
            Process process = Runtime.getRuntime().exec("id -u");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String uid = reader.readLine();
            return "0".equals(uid);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isLinux() {
        return IS_LINUX;
    }

    public static boolean isMac() {
        return IS_MAC;
    }

    public static boolean isAndroid() {
        return IS_ANDROID;
    }

    public static boolean isAdmin() {
        return IS_ADMIN;
    }
}