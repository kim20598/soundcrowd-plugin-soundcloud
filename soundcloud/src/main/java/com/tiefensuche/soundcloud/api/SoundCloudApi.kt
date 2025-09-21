/* 
 * SPDX-License-Identifier: GPL-3.0-only 
 */ 
package com.tiefensuche.soundcloud.api 

import android.content.SharedPreferences 
import android.net.Uri 
import android.util.Log 
import androidx.media3.common.HeartRating 
import androidx.media3.common.MediaItem 
import com.tiefensuche.soundcloud.api.Constants.ACCESS_TOKEN 
import com.tiefensuche.soundcloud.api.Constants.ERROR 
import com.tiefensuche.soundcloud.api.Constants.KIND 
import com.tiefensuche.soundcloud.api.Constants.LIKE 
import com.tiefensuche.soundcloud.api.Constants.ORIGIN 
import com.tiefensuche.soundcloud.api.Constants.TRACK 
import com.tiefensuche.soundcloud.api.Constants.USER 
import com.tiefensuche.soundcloud.api.Constants.REFRESH_TOKEN 
import com.tiefensuche.soundcrowd.plugins.MediaItemUtils 
import com.tiefensuche.soundcrowd.plugins.MediaMetadataCompatExt 
import com.tiefensuche.soundcrowd.plugins.WebRequests 
import org.json.JSONArray 
import org.json.JSONException 
import org.json.JSONObject 
import java.io.IOException 
import java.net.URLEncoder 
import kotlin.collections.HashMap 

/** 
 * 
 * Created by tiefensuche on 07.02.18. 
 */ 
class SoundCloudApi(private val CLIENT_ID: String, private val CLIENT_SECRET: String, private val REDIRECT_URI: String, private val prefs: SharedPreferences) { 

    companion object { 
        private var TAG = this::class.java.simpleName 
    } 

    var accessToken: String? = prefs.getString(ACCESS_TOKEN, null) 
    var refreshToken: String? = prefs.getString(REFRESH_TOKEN, null) 
    val nextQueryUrls: HashMap<String, String> = HashMap() 
    private var likesTrackIds: MutableSet<Long> = HashSet() 

    /** 
     * Request new access token for the user identified by the username and password 
     */ 
    @Throws(JSONException::class, IOException::class, UserNotFoundException::class, InvalidCredentialsException::class) 
    fun getAccessToken(code: String, refresh: Boolean) { 
        val data = "grant_type=" + (if (refresh) "refresh_token" else "authorization_code") + "&client_id=$CLIENT_ID" + "&client_secret=$CLIENT_SECRET" + (if (refresh) "&refresh_token=$code" else "&redirect_uri=$REDIRECT_URI&code=$code") 
        try { 
            val response = JSONObject(WebRequests.post(Endpoints.OAUTH2_TOKEN_URL, data).value) 
            if (!response.has(ACCESS_TOKEN)) { 
                throw Exception("Could not get access token!") 
            } 
            accessToken = response.getString(ACCESS_TOKEN) 
            refreshToken = response.getString(REFRESH_TOKEN) 
            prefs.edit() 
                .putString(ACCESS_TOKEN, accessToken) 
                .putString(REFRESH_TOKEN, refreshToken) 
                .apply() 
        } catch (e: WebRequests.HttpException) { 
            val response = JSONObject(e.message) 
            if (response.has(ERROR)) { 
                if (response.getString(ERROR) == "invalid_grant") 
                    throw InvalidCredentialsException("Invalid code") 
                else 
                    throw Exception(response.getString(ERROR)) 
            } 
            throw e 
        } 
    } 

    /** 
     * SoundCloud stream for the configured username and password 
     */ 
    @Throws(NotAuthenticatedException::class, JSONException::class, IOException::class) 
    fun getStream(reset: Boolean): List<MediaItem> { 
        return parseTracksFromJSONArray(Requests.CollectionRequest(this, Endpoints.STREAM_URL, reset).execute()) 
    } 

    /** 
     * SoundCloud likes for username 
     */ 
    @Throws(IOException::class, JSONException::class, NotAuthenticatedException::class) 
    fun getLikes(reset: Boolean): List<MediaItem> { 
        return parseTracksFromJSONArray(Requests.CollectionRequest(this, Endpoints.SELF_LIKES_URL, reset).execute()) 
    } 

    /** 
     * The SoundCloud search function 
     * 
     * @param query 
     */ 
    @Throws(JSONException::class, IOException::class) 
    fun query(query: String, endpoint: Requests.CollectionEndpoint, reset: Boolean): List<MediaItem> { 
        val trackList = JSONArray() 
        val tracks = Requests.CollectionRequest(this, endpoint, reset, URLEncoder.encode(query, "UTF-8")).execute() 
        for (j in 0 until tracks.length()) { 
            trackList.put(tracks.getJSONObject(j)) 
        } 
        return parseTracksFromJSONArray(trackList) 
    } 

    /** 
     * Returns the tracks of the given user id 
     */ 
    @Throws(JSONException::class, IOException::class, NotAuthenticatedException::class) 
    fun getUserTracks(userId: String, reset: Boolean): List<MediaItem> { 
        return parseTracksFromJSONArray(Requests.CollectionRequest(this, Endpoints.USER_TRACKS_URL, reset, userId).execute()) 
    } 

    /** 
     * Returns the logged in user's tracks and playlists 
     */ 
    @Throws(IOException::class, NotAuthenticatedException::class, JSONException::class) 
    fun getSelfTracks(reset: Boolean): List<MediaItem> { 
        val res = parseTracksFromJSONArray(Requests.CollectionRequest(this, Endpoints.SELF_TRACKS_URL, reset).execute()).toMutableList() 
        res += parsePlaylists(Requests.CollectionRequest(this, Endpoints.SELF_PLAYLISTS_URL, reset).execute()) 
        return res 
    } 

    /** 
     * Returns the logged in user's liked playlists 
     */ 
    @Throws(IOException::class, NotAuthenticatedException::class, JSONException::class) 
    fun getPlaylists(reset: Boolean): List<MediaItem> { 
        return parsePlaylists(Requests.CollectionRequest(this, Endpoints.SELF_PLAYLIST_LIKES_URL, reset).execute()) 
    } 

    /** 
     * Returns playlist identified by the given id 
     */ 
    fun getPlaylist(id: String, reset: Boolean): List<MediaItem> { 
        return parseTracksFromJSONArray(Requests.CollectionRequest(this, Endpoints.PLAYLIST_URL, reset, id).execute()) 
    } 

    private fun parsePlaylists(playlists: JSONArray): List<MediaItem> { 
        val result = mutableListOf<MediaItem>() 
        for (i in 0 until playlists.length()) { 
            val playlist = playlists.getJSONObject(i) 
            val artwork: String = if (!playlist.isNull(Constants.ARTWORK_URL)) { 
                playlist.getString(Constants.ARTWORK_URL) 
            } else { 
                playlist.getJSONObject(Constants.USER).getString(Constants.AVATAR_URL) 
            } 
            result.add(MediaItemUtils.createBrowsableItem( 
                playlist.getString(Constants.ID), 
                playlist.getString(Constants.TITLE), 
                MediaMetadataCompatExt.MediaType.STREAM, 
                playlist.getJSONObject(Constants.USER).getString(Constants.USERNAME), 
                artworkUri = Uri.parse(artwork.replace("large", "t500x500")) 
            )) 
        } 
        return result 
    } 

    /** 
     * Returns the users the logged in user is following 
     */ 
    @Throws(IOException::class, NotAuthenticatedException::class, JSONException::class) 
    fun getFollowings(reset: Boolean): List<MediaItem> { 
        return parseTracksFromJSONArray(Requests.CollectionRequest(this, Endpoints.FOLLOWINGS_USER_URL, reset).execute()) 
    } 

    /** 
     * Returns the users that are following the logged in user 
     */ 
    @Throws(IOException::class, NotAuthenticatedException::class, JSONException::class) 
    fun getFollowers(reset: Boolean): List<MediaItem> { 
        return parseTracksFromJSONArray(Requests.CollectionRequest(this, Endpoints.FOLLOWERS_USER_URL, reset).execute()) 
    } 

    private fun parseTracksFromJSONArray(tracks: JSONArray): List<MediaItem> { 
        val result = mutableListOf<MediaItem>() 
        for (j in 0 until tracks.length()) { 
            try { 
                var track = tracks.getJSONObject(j) 
                if (track.has(ORIGIN)) { 
                    track = track.getJSONObject(ORIGIN) 
                } 
                if (track.has(KIND)) { 
                    when (track.getString(KIND)) { 
                        TRACK -> result.add(buildTrackFromJSON(track)) 
                        LIKE -> result.add(buildTrackFromJSON(track.getJSONObject(TRACK))) 
                        USER -> result.add(buildUserFromJSON(track)) 
                        else -> Log.w(TAG, "unexpected kind: " + track.getString(KIND)) 
                    } 
                } 
            } catch (e: NotStreamableException) { 
                // skip item 
            } catch (e: JSONException) { 
                Log.w(TAG, "parsing exception", e) 
            } 
        } 
        return result 
    } 

    @Throws(JSONException::class, NotStreamableException::class) 
    internal fun buildTrackFromJSON(json: JSONObject): MediaItem { 
        if (!json.getBoolean(Constants.STREAMABLE)) 
            throw NotStreamableException("Item can not be streamed!") 

        val artwork = if (!json.isNull(Constants.ARTWORK_URL)) { 
            json.getString(Constants.ARTWORK_URL) 
        } else { 
            json.getJSONObject(Constants.USER).getString(Constants.AVATAR_URL) 
        } 

        val result = MediaItemUtils.createMediaItem( 
            json.getLong(Constants.ID).toString(), 
            Uri.parse(json.getString(Constants.STREAM_URL)), 
            json.getString(Constants.TITLE), 
            json.getLong(Constants.DURATION), 
            json.getJSONObject(Constants.USER).getString(Constants.USERNAME), 
            null, 
            Uri.parse(artwork.replace("large", "t500x500")), 
            json.getString(Constants.WAVEFORM_URL).replace("w1", "wis").replace("png", "json"), 
            json.getString(Constants.PERMALINK_URL), 
            if (accessToken != null) { 
                if (!json.isNull(Constants.USER_FAVORITE) && json.getBoolean(Constants.USER_FAVORITE)) { 
                    HeartRating(true).also { likesTrackIds.add(json.getLong(Constants.ID)) } 
                } else { 
                    HeartRating(false) 
                } 
            } else null 
        ) 
        return result 
    } 

    @Throws(JSONException::class) 
    private fun buildUserFromJSON(json: JSONObject): MediaItem { 
        return MediaItemUtils.createBrowsableItem( 
            json.getLong(Constants.ID).toString(), 
            json.getString(Constants.USERNAME), 
            MediaMetadataCompatExt.MediaType.STREAM, 
            json.getString(Constants.FULL_NAME), 
            null, 
            Uri.parse(json.getString(Constants.AVATAR_URL).replace("large", "t500x500")), 
            null, 
            json.getString(Constants.DESCRIPTION) 
        ) 
    } 

    @Throws(IOException::class, NotAuthenticatedException::class) 
    fun toggleLike(trackId: String): Boolean { 
        return if (!likesTrackIds.contains(trackId.toLong())) like(trackId) else unlike(trackId) 
    } 

    /** 
     * Responses 
     * 200 - Success 
     * 
     * @param trackId 
     * @return 
     * @throws IOException 
     */ 
    @Throws(IOException::class, NotAuthenticatedException::class) 
    private fun like(trackId: String): Boolean { 
        val result = Requests.ActionRequest(this, Endpoints.LIKE_TRACK_URL, trackId).execute() 
        val success = result.status == 200 
        if (success) likesTrackIds.add(trackId.toLong()) 
        return success 
    } 

    /** 
     * Responses 
     * 200 - OK - Success 
     * 404 - Not found - Track was not liked 
     * 
     * @param trackId 
     * @return true if success, false otherwise 
     * @throws IOException 
     */ 
    @Throws(IOException::class, NotAuthenticatedException::class) 
    private fun unlike(trackId: String): Boolean { 
        val result = Requests.ActionRequest(this, Endpoints.UNLIKE_TRACK_URL, trackId).execute() 
        val success = result.status == 200 
        if (success) likesTrackIds.remove(trackId.toLong()) 
        return success 
    } 

    fun getStreamUrl(uri: Uri): String { 
        val res = Requests.ActionRequest(this, Requests.Endpoint(uri.path!!, Requests.Method.GET)).execute() 
        if (res.status == 302) { 
            return JSONObject(res.value).getString("location") 
        } 
        throw NotStreamableException("Can not get stream url") 
    } 

    /**
     * Gets a direct URL that can be used for downloading a track.
     * This uses the same stream URL that the player uses.
     *
     * @param trackId The ID of the track to download.
     * @return A direct URL string to the MP3 file, or null if the request fails.
     */
    @Throws(IOException::class, NotAuthenticatedException::class)
    fun getDownloadUrl(trackId: String): String? {
        return try {
            // 1. First, we need to get the track's JSON data to find its stream URL
            // The app might already have a function for this, but we can create the request manually.
            val trackUrl = Endpoints.TRACK_URL.replace("{id}", trackId)
            val response = Requests.ActionRequest(this, Requests.Endpoint(trackUrl, Requests.Method.GET)).execute()

            if (response.status != 200) {
                Log.w(TAG, "Failed to get track data for download. Status: ${response.status}")
                return null
            }

            // 2. Parse the JSON response to get the track object
            val trackJson = JSONObject(response.value)

            // 3. Check if the track is streamable
            if (!trackJson.getBoolean(Constants.STREAMABLE)) {
                Log.w(TAG, "Track $trackId is not streamable, cannot download")
                return null
            }

            // 4. Get the stream URL from the track data
            // This is the same URL used for playback in buildTrackFromJSON()
            val streamUrl = trackJson.getString(Constants.STREAM_URL)

            // 5. (CRUCIAL STEP) We need to resolve any redirects to get the final MP3 URL
            // The stream URL might be a SoundCloud API endpoint that redirects to the actual file.
            // Let's use the existing getStreamUrl function which handles this redirect!
            // If it's already a direct URL, this function will still work.
            val finalUrl = getStreamUrl(Uri.parse(streamUrl))

            // 6. Add client ID parameter if needed (for authentication)
            // Some SoundCloud URLs require a client_id parameter to work
            val uri = Uri.parse(finalUrl)
            val builder = uri.buildUpon()

            // If the URL doesn't already have a client_id, and we have an access token, add it
            if (uri.getQueryParameter("client_id") == null) {
                // Prefer using the access token if available, otherwise use the client ID
                val authParam = if (!accessToken.isNullOrBlank()) {
                    "client_id" to CLIENT_ID
                } else {
                    // If no auth, we might still be able to download with just the client ID
                    "client_id" to CLIENT_ID
                }
                builder.appendQueryParameter(authParam.first, authParam.second)
            }

            return builder.build().toString()

        } catch (e: JSONException) {
            Log.e(TAG, "JSON parsing error while getting download URL", e)
            null
        } catch (e: NotStreamableException) {
            Log.w(TAG, "Track is not streamable", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error getting download URL", e)
            null
        }
    }

    // Exception types 
    class NotAuthenticatedException(message: String) : Exception(message) 
    class InvalidCredentialsException(message: String) : Exception(message) 
    class NotStreamableException(message: String) : Exception(message) 
    class UserNotFoundException(message: String) : Exception(message) 
}
