package com.android.grafika.other

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log


val TAG = "MY_TAG"
val VERBOSE = true


/**
 * Selects the video track, if any.
 *
 * @return the track index, or -1 if no video track is found.
 */
fun selectTrack(extractor: MediaExtractor): Int {
    // Select the first video track we find, ignore the rest.
    val numTracks = extractor.trackCount
    for (i in 0 until numTracks) {// TODO: 2017/9/14 遍历track
        val format = extractor.getTrackFormat(i)
        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime.startsWith("video/")) {// TODO: 2017/9/14 取到video的信息，返回对应下标
            if (VERBOSE) {
                Log.d(TAG, "Extractor selected track $i ($mime): $format")
            }
            return i
        }
    }

    return -1
}


