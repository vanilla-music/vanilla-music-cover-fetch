package com.kanedias.vanilla.coverfetch;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.widget.Toast;

import java.io.File;

import static com.kanedias.vanilla.coverfetch.PluginConstants.ACTION_LAUNCH_PLUGIN;
import static com.kanedias.vanilla.coverfetch.PluginConstants.EXTRA_PARAM_URI;

/**
 * Activity that is needed solely for requesting SAF permissions for external SD cards.
 *
 * @author  Kanedias on 17.02.17.
 */
public class SafRequestActivity extends Activity {

    private static final int SAF_TREE_REQUEST_CODE = 2;

    /**
     * File to search access for
     */
    private SharedPreferences mPrefs;


    @Override
    protected void onResume() {
        super.onResume();

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // it's Lollipop - let's request tree URI instead of nitpicking with specific files...
            // deal with file passed after request is fulfilled
            callSafRequestTree();
            return;
        }
    }

    /**
     * Call tree-picker to select root of SD card.
     * Shows a hint how to do this, continues if "ok" is clicked.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void callSafRequestTree() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.need_sd_card_access)
                .setIcon(R.drawable.icon)
                .setView(R.layout.sd_operate_instructions)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent selectFile = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        startActivityForResult(selectFile, SAF_TREE_REQUEST_CODE);
                    }
                })
                .create()
                .show();
    }

    /**
     * Mostly this is needed for SAF support. If the file is located on external SD card then android provides
     * only Storage Access Framework to be able to write anything.
     * @param requestCode our sent code
     * @param resultCode success or error
     * @param data URI-containing intent
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Intent serviceStart = new Intent(this, PluginService.class);
        serviceStart.setAction(ACTION_LAUNCH_PLUGIN);
        serviceStart.putExtras(getIntent());
        serviceStart.putExtra(PluginService.EXTRA_PARAM_SAF_P2P, data);

        if (requestCode == SAF_TREE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            saveTreeAccessForever(data);
            finish();
        }

    }

    /**
     * Saves SAF-provided tree URI forever
     * @param data intent containing tree URI in data
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void saveTreeAccessForever(Intent data) {
        Uri treeAccessUri = data.getData();
        getContentResolver().takePersistableUriPermission(treeAccessUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        mPrefs.edit().putString(PluginService.PREF_SDCARD_URI, treeAccessUri.toString()).apply();
    }

}
