package com.kanedias.vanilla.coverfetch;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;

/**
 * Common routines needed for operation of plugin
 *
 * @author Oleg Chernovskiy
 */

public class CoverFetchUtils {

    /**
     * Check if Android Storage Access Framework routines apply here
     * @return true if document seems to be SAF-accessible only, false otherwise
     */
    public static boolean isSafNeeded(File file) {
        // on external SD card after KitKat this will return false
        return !file.canWrite() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
    }

    /**
     * Checks if all required permissions have been granted
     *
     * @param context The context to use
     * @return boolean true if all permissions have been granded
     */
    public static boolean havePermissions(Context context, String perm) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        } // else: granted during installation
        return true;
    }

}
