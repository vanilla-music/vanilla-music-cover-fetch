package com.kanedias.vanilla.coverfetch;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
            // e.g. https://musicbrainz.org/ws/2/release-group/?query=releasegroup:new%20divide%20AND%20artist:linkin%20park&limit=3&fmt=json
            Uri link = new Uri.Builder()
                    .scheme("https")
                    .authority("musicbrainz.org")
                    .path("ws/2/release-group/")
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
            String reply = new String(readIt(is), "UTF-8");
            JSONObject searchContent = new JSONObject(reply);
            if (!searchContent.has("release-groups"))
                return null;

            JSONArray relGroups = searchContent.getJSONArray("release-groups");
            return getFirstImage(relGroups);
        } finally {
            if(apiCall != null) {
                apiCall.disconnect();
            }
        }
    }

    /**
     * Retrieve first available image from retrieved release-groups
     * @param relGroups array of release groups returned by musicbrainz API call
     * @return byte array with content of first found image for these release-groups or null if nothing found
     * @throws JSONException in case musicbrainz answer differs from wiki page
     * @throws IOException in case of encoding/connect problems
     */
    private byte[] getFirstImage(JSONArray relGroups) throws JSONException, IOException {
        for (int i = 0; i < relGroups.length(); ++i) {
            JSONObject relGroup = relGroups.getJSONObject(i);
            String mbid = relGroup.getString("id"); // musicbrainz ID, must be present and in UUID form

            HttpURLConnection imgCall = null;
            try {
                // e.g. http://coverartarchive.org/release-group/4741866d-c3a5-47ca-944d-732c2cc9e651/front-500
                Uri imgLink = new Uri.Builder().scheme("http")
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
                return readIt(imgStream);
            } finally {
                if(imgCall != null) {
                    imgCall.disconnect();
                }
            }
        }
        return null;
    }

    // Reads an InputStream fully to byte array
    private byte[] readIt(InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            baos.write(buffer, 0, count);
        }

        return baos.toByteArray();
    }

}
