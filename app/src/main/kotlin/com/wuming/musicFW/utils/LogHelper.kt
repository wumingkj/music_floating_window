package com.wuming.musicFW.utils

import android.util.Log

object LogHelper {
    private const val TAG = "MusicFW"
    var isDebug = true

    fun d(message: String) {
        if (isDebug) {
            Log.d(TAG, message)
        }
    }

    fun i(message: String) {
        if (isDebug) {
            Log.i(TAG, message)
        }
    }

    fun w(message: String) {
        if (isDebug) {
            Log.w(TAG, message)
        }
    }

    fun e(message: String) {
        if (isDebug) {
            Log.e(TAG, message)
        }
    }

    fun e(message: String, throwable: Throwable?) {
        if (isDebug) {
            Log.e(TAG, message, throwable)
        }
    }
}