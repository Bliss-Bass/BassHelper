package moe.shizuku.manager.utils;

import android.os.Build;
import android.os.UserManager;
import android.system.Os;

public class UserHandleCompat {

    private static final int MY_USER_ID = getUserId(Os.getuid());

    public static final int PER_USER_RANGE = 100000;

    public static int getUserId(int uid) {
        return uid / PER_USER_RANGE;
    }

    public static int getAppId(int uid) {
        return uid % PER_USER_RANGE;
    }

    public static int myUserId() {
        return MY_USER_ID;
    }

    /**
     * Whether this user may start the server. User 0 never runs apps on headless system user
     * mode devices, so the foreground users there have to be able to start it.
     */
    public static boolean canStartServer() {
        if (MY_USER_ID == 0) return true;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && UserManager.isHeadlessSystemUserMode();
    }
}