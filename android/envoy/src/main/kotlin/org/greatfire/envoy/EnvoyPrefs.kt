
package org.greatfire.envoy

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager

/*
    Stuff Envoy needs to store long term. Currently this is just URL failure
    info

    should this be updated to use the DataStore?
    https://developer.android.com/topic/libraries/architecture/datastore
*/

class EnvoyPrefs {
    companion object {
        private const val TAG = "EnvoyPrefs"
        private const val TIME_SUFFIX = "_time"
        private const val COUNT_SUFFIX = "_count"
    }

    val state = EnvoyState.getInstance()
    var sharedPreferences: SharedPreferences? = null

    // defer loading this until the context is available
    private fun getSharedPrefs(): SharedPreferences? {
        if (sharedPreferences != null) {
            return sharedPreferences
        }
        state.ctx?.let {
            sharedPreferences = PreferenceManager.getDefaultSharedPreferences(it)
        }
        return sharedPreferences
    }

    // URL test failure:
    //
    // We store the time and count of URL test failures to implement
    // a backoff policy
    //
    fun getFailureTimeForUrl(url: String): Long {
        getSharedPrefs()?.let {
            return it.getLong(url + TIME_SUFFIX, 0)
        }
        Log.e(TAG, "sharedPreferences is null")
        return 0L
    }

    fun getFailureCountForUrl(url: String): Int {
        getSharedPrefs()?.let {
            return it.getInt(url + COUNT_SUFFIX, 0)
        }
        Log.e(TAG, "sharedPreferences is null")
        return 0
    }

    fun clearUrlFailure(url: String) {
        getSharedPrefs()?.let {
            val editor = it.edit()
            editor.remove(url + TIME_SUFFIX)
            editor.remove(url + COUNT_SUFFIX)
            editor.apply()
            // Log.d(TAG, "REMOVED PREFS: " + url + TIME_SUFFIX + " / " + url + COUNT_SUFFIX)
            return
        }

        Log.e(TAG, "sharedPreferences is null")

    }

    // This is not thread safe, so be careful :)
    fun incrementUrlFailure(url: String, time: Long) {
        getSharedPrefs()?.let {
            val failureCount = getFailureCountForUrl(url)
            val editor = it.edit()
            editor.putLong(url + TIME_SUFFIX, time)
            editor.putInt(url + COUNT_SUFFIX, failureCount + 1)
            editor.apply()

            // Log.d(
            //     TAG,
            //     "SAVED PREFS: " + url + TIME_SUFFIX + " - " + time
            //             + " / " + url + COUNT_SUFFIX + " - " + (failureCount + 1)
            // )
            return
        }

        Log.e(TAG, "sharedPreferences is null")
    }

    // potential thoughts about storing URLs from network resources
    // We might also want to tell the app to stop using a URL that's
    // been blocked or disabled

    // add a new URL disovered from a newtwork resource to our pool
    fun addUrl(url: String, label: String) {
        Log.e(TAG, "TODO store URL")
    }

    // stop using a previously configured URL
    fun removeUrl(url: String) {
        Log.e(TAG, "TODO remove URL")
    }

}
