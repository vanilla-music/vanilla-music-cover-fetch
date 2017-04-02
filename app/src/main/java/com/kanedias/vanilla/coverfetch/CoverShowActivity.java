/*
 * Copyright (C) 2017 Oleg Chernovskiy <adonai@xaker.ru>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.kanedias.vanilla.coverfetch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.copied.FileProvider;
import android.support.v4.provider.DocumentFile;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.ViewSwitcher;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
import static com.kanedias.vanilla.coverfetch.PluginConstants.*;
import static com.kanedias.vanilla.coverfetch.PluginService.PREF_SDCARD_URI;
import static com.kanedias.vanilla.coverfetch.PluginService.pluginInstalled;

/**
 * Main activity of Cover Fetch plugin. This will be presented as a dialog to the user
 * if one chooses it as the requested plugin.
 * <p/>
 *
 * @see PluginService service that launches this
 *
 * @author Oleg Chernovskiy
 */
public class CoverShowActivity extends Activity {

    private static final int PERMISSIONS_REQUEST_CODE = 0;

    private SharedPreferences mPrefs;

    private ImageView mCoverImage;
    private ViewSwitcher mSwitcher;
    private Button mOkButton, mWriteButton;

    private CoverEngine mEngine = new CoverArchiveEngine();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cover_show);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        mSwitcher = (ViewSwitcher) findViewById(R.id.loading_switcher);
        mCoverImage = (ImageView) findViewById(R.id.cover_image);
        mWriteButton = (Button) findViewById(R.id.write_button);
        mOkButton = (Button) findViewById(R.id.ok_button);

        setupUI();
        handlePassedIntent(); // called in onCreate to be shown only once
    }

    private void handlePassedIntent() {
        // check if this is an answer from tag plugin
        if (TextUtils.equals(getIntent().getStringExtra(EXTRA_PARAM_P2P), P2P_READ_ART)) {
            // already checked this string in service, no need in additional checks
            Uri imgLink = getIntent().getParcelableExtra(EXTRA_PARAM_P2P_VAL);

            try {
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(imgLink, "r");
                if (pfd == null) {
                    return;
                }

                Bitmap raw = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                setCoverImage(raw);
            } catch (FileNotFoundException e) {
                Log.e(PluginConstants.LOG_TAG, "Passed Uri points to invalid fd! " + imgLink, e);
            }
            return;
        }

        mWriteButton.setVisibility(View.VISIBLE);
        mWriteButton.setOnClickListener(new SelectWriteAction());

        // we didn't receive artwork from tag plugin, try to retrieve it via artwork engine
        new ArtworkFetcher().execute(getIntent());
    }

    private void setCoverImage(Bitmap raw) {
        if (raw == null) {
            Toast.makeText(this, R.string.invalid_cover_image_format, Toast.LENGTH_LONG).show();
            return;
        }

        Drawable image = new BitmapDrawable(getResources(), raw);
        mCoverImage.setImageDrawable(image);
        mWriteButton.setEnabled(true);
        mSwitcher.showNext();
    }

    /**
     * Initialize UI elements with handlers and action listeners
     */
    private void setupUI() {
        mOkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    /**
     * External artwork fetcher (using network). Operates asynchronously, notifies dialog when finishes.
     * On no result (no artwork, couldn't fetch etc.) shows toast about this, on success updates dialog text.
     */
    private class ArtworkFetcher extends AsyncTask<Intent, Void, byte[]> {

        @Override
        protected byte[] doInBackground(Intent... params) {
            String artist = getIntent().getStringExtra(EXTRA_PARAM_SONG_ARTIST);
            String album = getIntent().getStringExtra(EXTRA_PARAM_SONG_ALBUM);
            return mEngine.getCover(artist, album);
        }

        @Override
        protected void onPostExecute(byte[] imgData) {
            if(imgData == null || imgData.length == 0) {
                // no artwork - show excuse
                finish();
                Toast.makeText(CoverShowActivity.this, R.string.cover_not_found, Toast.LENGTH_SHORT).show();
                return;
            }

            Bitmap raw = BitmapFactory.decodeByteArray(imgData, 0, imgData.length);
            setCoverImage(raw);
        }
    }

    /**
     * CLick listener for P2P integration, sends intent to write retrieved cover to local file tag
     */
    public void persistAsFile() {
        // image must be present because this button enables only after it's downloaded
        Bitmap bitmap = ((BitmapDrawable) mCoverImage.getDrawable()).getBitmap();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream);
        byte[] imgData = stream.toByteArray();

        Uri uri = null;
        try {
            File coversDir = new File(getCacheDir(), "covers");
            if (!coversDir.exists() && !coversDir.mkdir()) {
                Log.e(LOG_TAG, "Couldn't create dir for covers! Path " + getCacheDir());
                return;
            }

            // cleanup old images
            for (File oldImg : coversDir.listFiles()) {
                if (!oldImg.delete()) {
                    Log.w(LOG_TAG, "Couldn't delete old image file! Path " + oldImg);
                }
            }

            // write artwork to file
            File coverTmpFile = new File(coversDir, UUID.randomUUID().toString());
            FileOutputStream fos = new FileOutputStream(coverTmpFile);
            fos.write(imgData);
            fos.close();

            // create sharable uri
            uri = FileProvider.getUriForFile(CoverShowActivity.this, "com.kanedias.vanilla.coverfetch.fileprovider", coverTmpFile);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Couldn't share private cover image file to tag editor!", e);
        } finally {
            Intent request = new Intent(ACTION_LAUNCH_PLUGIN);
            request.setPackage(PluginService.PLUGIN_TAG_EDIT_PKG);
            request.putExtra(EXTRA_PARAM_URI, getIntent().getParcelableExtra(EXTRA_PARAM_URI));
            request.putExtra(EXTRA_PARAM_PLUGIN_APP, getApplicationInfo());
            request.putExtra(EXTRA_PARAM_P2P, P2P_WRITE_ART);
            if (uri != null) { // artwork write succeeded
                grantUriPermission(PluginService.PLUGIN_TAG_EDIT_PKG, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                request.putExtra(EXTRA_PARAM_P2P_VAL, uri);
            }
            startService(request);
        }
    }

    /**
     * Click listener for handling writing tag as folder.jpg
     */
    public void persistAsFolderJpg() {
        Uri fileUri = getIntent().getParcelableExtra(EXTRA_PARAM_URI);
        if (fileUri == null) {
            // wrong intent passed?
            return;
        }

        File mediaFile = new File(fileUri.getPath());
        if (!mediaFile.exists()) {
            // file deleted while launching intent or player db is not refreshed
            return;
        }

        File folderTarget = new File(mediaFile.getParent(), "folder.jpg");

        // image must be present because this button enables only after it's downloaded
        Bitmap bitmap = ((BitmapDrawable) mCoverImage.getDrawable()).getBitmap();
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        byte[] imgData = stream.toByteArray();

        if (CoverFetchUtils.isSafNeeded(folderTarget)) {
            if (mPrefs.contains(PREF_SDCARD_URI)) {
                // we already got the permission!
                writeThroughSaf(imgData, mediaFile, folderTarget);
                return;
            }

            // request SAF permissions in SAF activity
            Intent dialogIntent = new Intent(CoverShowActivity.this, SafRequestActivity.class);
            dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            dialogIntent.putExtras(getIntent());
            startActivity(dialogIntent);
            // it will pass us URI back after the work is done
        } else {
            writeThroughFile(imgData, mediaFile, folderTarget);
        }
    }




    /**
     * Write changes through SAF framework - the only way to do it in Android > 4.4 when working with SD card
     */
    private void writeThroughSaf(byte[] data, File original, File target) {
        Uri safUri;
        if (mPrefs.contains(PREF_SDCARD_URI)) {
            // no sorcery can allow you to gain URI to the document representing file you've been provided with
            // you have to find it again now using Document API

            // /storage/volume/Music/some.mp3 will become [storage, volume, music, some.mp3]
            List<String> pathSegments = new ArrayList<>(Arrays.asList(target.getAbsolutePath().split("/")));
            Uri allowedSdRoot = Uri.parse(mPrefs.getString(PREF_SDCARD_URI, ""));
            safUri = findInDocumentTree(DocumentFile.fromTreeUri(this, allowedSdRoot), pathSegments);
        } else {
            // user will click the button again
            return;
        }

        if (safUri == null) {
            // nothing selected or invalid file?
            Toast.makeText(this, R.string.saf_nothing_selected, Toast.LENGTH_LONG).show();
            return;
        }

        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(safUri, "rw");
            if (pfd == null) {
                // should not happen
                Log.e(LOG_TAG, "SAF provided incorrect URI!" + safUri);
                return;
            }

            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
            fos.write(data);
            fos.close();

            // rescan original file
            MediaScannerConnection.scanFile(this, new String[]{original.getAbsolutePath()}, null, null);
            Toast.makeText(this, R.string.file_written_successfully, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.saf_write_error) + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            Log.e(LOG_TAG, "Failed to write to file descriptor provided by SAF!", e);
        }
    }

    /**
     * Finds needed file through Document API for SAF. It's not optimized yet - you can still gain wrong URI on
     * files such as "/a/b/c.mp3" and "/b/a/c.mp3", but I consider it complete enough to be usable.
     * @param currentDir - document file representing current dir of search
     * @param remainingPathSegments - path segments that are left to find
     * @return URI for found file. Null if nothing found.
     */
    @Nullable
    private Uri findInDocumentTree(DocumentFile currentDir, List<String> remainingPathSegments) {
        for (DocumentFile file : currentDir.listFiles()) {
            int index = remainingPathSegments.indexOf(file.getName());
            if (index == -1) {
                continue;
            }

            if (file.isDirectory()) {
                remainingPathSegments.remove(file.getName());
                return findInDocumentTree(file, remainingPathSegments);
            }

            if (file.isFile() && index == remainingPathSegments.size() - 1) {
                // got to the last part
                return file.getUri();
            }
        }

        return null;
    }

    /**
     * Write through file-based API
     * @param data - data to write
     * @param original - original media file that was requested by user
     * @param target - target file for writing metadata into
     */
    private void writeThroughFile(byte[] data, File  original, File target) {
        try {
            FileOutputStream fos = new FileOutputStream(target);
            fos.write(data);
            fos.close();

            // rescan original file
            MediaScannerConnection.scanFile(this, new String[]{original.getAbsolutePath()}, null, null);
            Toast.makeText(this, R.string.file_written_successfully, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.error_writing_file) + e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
            Log.e(LOG_TAG, "Failed to write to file descriptor provided by SAF!", e);
        }
    }

    /**
     * Checks for permission and requests it if needed.
     * You should catch answer back in {@link #onRequestPermissionsResult(int, String[], int[])}
     * <br/>
     * (Or don't. This way request will appear forever as {@link #onResume()} will never end)
     * @param perm permission to request
     * @return true if this app had this permission prior to check, false otherwise.
     */
    private boolean checkAndRequestPermissions(String perm) {
        if (!CoverFetchUtils.havePermissions(this, perm)  && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{perm}, PERMISSIONS_REQUEST_CODE);
            return false;
        }
        return true;
    }

    private class SelectWriteAction implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            List<String> actions = new ArrayList<>();
            actions.add(getString(R.string.write_to_folder));

            // if tag editor is installed, show `write to tag` button
            if (pluginInstalled(CoverShowActivity.this, PluginService.PLUGIN_TAG_EDIT_PKG)) {
                actions.add(getString(R.string.write_to_file));
            }

            new AlertDialog.Builder(CoverShowActivity.this)
                    .setItems(actions.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            switch (which) {
                                case 0: // to folder
                                    // onResume will fire both on first launch and on return from permission request
                                    if (!checkAndRequestPermissions(WRITE_EXTERNAL_STORAGE)) {
                                        return;
                                    }

                                    persistAsFolderJpg();
                                    break;
                                case 1: // to file
                                    persistAsFile();
                                    break;
                            }
                        }
                    }).create().show();
        }
    }
}
