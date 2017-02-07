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
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.support.copied.FileProvider;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.ViewSwitcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

import static com.kanedias.vanilla.coverfetch.PluginConstants.*;
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

    private ImageView mCoverImage;
    private ViewSwitcher mSwitcher;
    private Button mOkButton, mWriteFileButton;

    private CoverEngine mEngine = new CoverArchiveEngine();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cover_show);

        mSwitcher = (ViewSwitcher) findViewById(R.id.loading_switcher);
        mCoverImage = (ImageView) findViewById(R.id.cover_image);
        mWriteFileButton = (Button) findViewById(R.id.write_file_button);
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

        // if tag editor is installed, show `write to tag` button
        if (pluginInstalled(this, PluginService.PLUGIN_TAG_EDIT_PKG)) {
            mWriteFileButton.setVisibility(View.VISIBLE);
            mWriteFileButton.setOnClickListener(new CoverToTagSender());
        }

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
        mWriteFileButton.setEnabled(true);
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
    private class CoverToTagSender implements View.OnClickListener {

        @Override
        public void onClick(View v) {
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
                e.printStackTrace();
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
                mWriteFileButton.setVisibility(View.GONE);
            }
        }
    }
}
