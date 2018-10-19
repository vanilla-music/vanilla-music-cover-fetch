/*
 * Copyright (C) 2017-2018 Oleg Chernovskiy <adonai@xaker.ru>
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

import android.net.Uri;
import android.util.Log;

import com.kanedias.vanilla.plugins.PluginUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Implementation of cover fetch engine based on CoverArtArchive API (coverartarchive.org).
 *
 * @author Oleg Chernovskiy
 *
 */
public class CoverArchiveEngine implements CoverEngine {

    private static final String USER_AGENT = "Vanilla Cover Fetch (https://github.com/vanilla-music)";

    private static final String TAG = CoverArchiveEngine.class.getSimpleName();

    @Override
    public byte[] getCover(String artistName, String albumName) {
        try {
            String releaseGroupQuery = String.format("releasegroup:%s AND artistname:%s", albumName, artistName);
            return makeApiCall(releaseGroupQuery);
        } catch (IOException e) {
            Log.w(TAG, "Couldn't connect to musicbrainz/coverartarchive REST endpoints", e);
            return null;
        } catch (JSONException e) {
            Log.w(TAG, "Couldn't transform API answer to JSON entity", e);
            return null;
        }
    }

    @Override
    public byte[] getCover(String query) {
        try {
            return makeApiCall(query);
        } catch (IOException e) {
            Log.w(TAG, "Couldn't connect to musicbrainz/coverartarchive REST endpoints", e);
            return null;
        } catch (JSONException e) {
            Log.w(TAG, "Couldn't transform API answer to JSON entity", e);
            return null;
        }
    }

    /**
     * First call
     */
    private byte[] makeApiCall(String query) throws IOException, JSONException {
        HttpsURLConnection apiCall = null;
        try {
            // build query
            // e.g. https://musicbrainz.org/ws/2/work/?query=releasegroup:new%20divide%20AND%20artist:linkin%20park&limit=3&fmt=json
            Uri link = new Uri.Builder()
                    .scheme("https")
                    .authority("musicbrainz.org")
                    .path("ws/2/" + "release-group" + '/')
                    .appendQueryParameter("query", query)
                    .appendQueryParameter("limit", "3")
                    .appendQueryParameter("fmt", "json")
                    .build();

            // construct an http request
            apiCall = (HttpsURLConnection) new URL(link.toString()).openConnection();
            apiCall.setRequestProperty("User-Agent", USER_AGENT);
            apiCall.setReadTimeout(10_000);
            apiCall.setConnectTimeout(15_000);

            // execute
            apiCall.connect();
            int response = apiCall.getResponseCode();
            if (response != HttpsURLConnection.HTTP_OK) {
                // redirects are handled internally, this is clearly an error
                return null;
            }

            InputStream is = apiCall.getInputStream();
            String reply = new String(PluginUtils.readFully(is), "UTF-8");
            JSONObject searchContent = new JSONObject(reply);
            if (!searchContent.has("release-groups"))
                return null;

            JSONArray relGroups = searchContent.getJSONArray("release-groups");
            return getFirstImage(relGroups);
        } finally {
            if (apiCall != null) {
                apiCall.disconnect();
            }
        }
    }

    /**
     * Retrieve first available image from retrieved release-groups
     *
     * @param relGroups array of release groups returned by musicbrainz API call
     * @return byte array with content of first found image for these release-groups or null if nothing found
     * @throws JSONException in case musicbrainz answer differs from wiki page
     * @throws IOException   in case of encoding/connect problems
     */
    private byte[] getFirstImage(JSONArray relGroups) throws JSONException, IOException {
        for (int i = 0; i < relGroups.length(); ++i) {
            JSONObject relGroup = relGroups.getJSONObject(i);
            String mbid = relGroup.getString("id"); // musicbrainz ID, must be present and in UUID form

            HttpURLConnection imgCall = null;
            try {
                // e.g. http://coverartarchive.org/release-group/4741866d-c3a5-47ca-944d-732c2cc9e651/front-500
                Uri imgLink = new Uri.Builder().scheme("https")
                        .authority("coverartarchive.org")
                        .path("release-group")
                        .appendPath(mbid)
                        .appendPath("front-500")
                        .build();

                imgCall = (HttpURLConnection) new URL(imgLink.toString()).openConnection();
                imgCall.setRequestProperty("User-Agent", USER_AGENT);
                imgCall.setReadTimeout(10_000);
                imgCall.setConnectTimeout(15_000);

                // execute
                imgCall.connect();
                int imgRespCode = imgCall.getResponseCode();
                if (imgRespCode != HttpsURLConnection.HTTP_OK) {
                    // redirects are handled internally, this is clearly an error
                    continue;
                }

                InputStream imgStream = imgCall.getInputStream();
                return PluginUtils.readFully(imgStream);
            } finally {
                if (imgCall != null) {
                    imgCall.disconnect();
                }
            }
        }
        return null;
    }
}
