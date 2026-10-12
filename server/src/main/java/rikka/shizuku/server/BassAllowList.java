package rikka.shizuku.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import rikka.hidden.compat.PackageManagerApis;

/**
 * The only client packages Bass Helper serves. They are allowed without a prompt; any other app
 * gets no binder, cannot attach and has its permission requests refused.
 */
public final class BassAllowList {

    /** Clients receive the binder at {@code <package>.basshelper}, so stock Shizuku's provider is left alone. */
    public static final String CLIENT_PROVIDER_SUFFIX = ".basshelper";

    private static final Set<String> PACKAGES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "cu.axel.smartdock",
            "org.ax86.linuxservices",
            "org.gamelauncher",
            "com.ax86.logger",
            "com.navotpala.ax86docs",
            "app.gamenative"
    )));

    private BassAllowList() {
    }

    public static Set<String> packages() {
        return PACKAGES;
    }

    public static boolean contains(String packageName) {
        return PACKAGES.contains(packageName);
    }

    public static boolean allowsUid(int uid) {
        for (String packageName : PackageManagerApis.getPackagesForUidNoThrow(uid)) {
            if (contains(packageName)) {
                return true;
            }
        }
        return false;
    }
}
