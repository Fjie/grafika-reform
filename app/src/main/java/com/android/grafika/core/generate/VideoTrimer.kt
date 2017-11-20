/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika.core.generate

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.Surface
import com.android.grafika.other.VERBOSE
import com.android.grafika.other.selectTrack
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException


class VideoTrimer
/**
 * Constructs a MoviePlayer.
 *
 * @param sourceFile The video file to open.
 * @param outputSurface The Surface where frames will be sent.
 * @param frameCallback Callback object, used to pace output.
 * @throws IOException
 */
@Throws(IOException::class)
constructor(private val mSourceFile: File, private val mOutputSurface: Surface, private var mFrameCallback: FrameCallback) {

    private val mBufferInfo = MediaCodec.BufferInfo()
    // May be set/read by different threads.
    @Volatile private var mIsStopRequested: Boolean = false
    private var mLoop: Boolean = false
    /**
     * Returns the width, in pixels, of the video.
     */
    var videoWidth: Int = 0
    /**
     * Returns the height, in pixels, of the video.
     */
    var videoHeight: Int = 0

    interface PlayerFeedback {
        fun playbackStopped()
    }

    // TODO: 2017/9/14 帧回掉
    interface FrameCallback {
        /**
         * Called immediately before the frame is rendered.
         * @param presentationTimeUsec The desired presentation time, in microseconds.
         */
        // TODO: 2017/9/14 渲染前
        fun preRender(presentationTimeUsec: Long)

        /**
         * Called immediately after the frame render call returns.  The frame may not have
         * actually been rendered yet.
         * TODO: is this actually useful?
         */
        // TODO: 2017/9/14 渲染后
        fun postRender()

        /**
         * Called after the last frame of a looped movie has been rendered.  This allows the
         * callback to adjust its expectations of the next presentation time stamp.
         */
        // TODO: 2017/9/14 循环重置
        fun loopReset()
    }


    init {

        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(mSourceFile.toString())

            val trackIndex = selectTrack(extractor)
            if (trackIndex < 0) {
                throw RuntimeException("No video track found in " + mSourceFile)
            }
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            if (VERBOSE) {
                Log.d(TAG, "Video size is " + videoWidth + "x" + videoHeight)
            }
        } finally {
            if (extractor != null) {
                extractor.release()
            }
        }
    }

    @Throws(IOException::class)
    fun play() {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null

        if (!mSourceFile.canRead()) {
            throw FileNotFoundException("Unable to read " + mSourceFile)
        }

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(mSourceFile.toString())
            val trackIndex = selectTrack(extractor)
            if (trackIndex < 0) {
                throw RuntimeException("No video track found in " + mSourceFile)
            }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)
            decoder = MediaCodec.createDecoderByType(mime)
            decoder!!.configure(format, mOutputSurface, null, 0)
            decoder.start()

            doExtract(extractor, trackIndex, decoder, mFrameCallback)
        } finally {
            if (decoder != null) {
                decoder.stop()
                decoder.release()
            }
            if (extractor != null) {
                extractor.release()
            }
        }
    }

    private fun doExtract(extractor: MediaExtractor, trackIndex: Int, decoder: MediaCodec,
                          frameCallback: FrameCallback?) {

        val TIMEOUT_USEC = 10000
        val decoderInputBuffers = decoder.inputBuffers
        var inputChunk = 0
        var firstInputTimeNsec: Long = -1

        var outputDone = false
        var inputDone = false

        while (!outputDone) {
            if (VERBOSE) Log.d(TAG, "loop")
            if (mIsStopRequested) {
                Log.d(TAG, "Stop requested")
                return
            }

            // Feed more data to the decoder.
            // TODO: 2017/9/14 输入开始！
            if (!inputDone) {
                // TODO: 2017/9/14 先指挥编解码器出列一个buf，并返回其下标
                val inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC.toLong())
                if (inputBufIndex >= 0) {// TODO: 2017/9/14 出列成功
                    if (firstInputTimeNsec == -1L) {
                        firstInputTimeNsec = System.nanoTime()// TODO: 2017/9/14 第一次先赋值，诡异的时间戳
                    }
                    val inputBuf = decoderInputBuffers[inputBufIndex]// TODO: 2017/9/14 根据下标取buf
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuf's position, limit, etc.
                    val chunkSize = extractor.readSampleData(inputBuf, 0)// TODO: 2017/9/14 传入buf获取块大小
                    if (chunkSize < 0) {// TODO: 2017/9/14 输入结束
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                        if (VERBOSE) Log.d(TAG, "sent input EOS")
                    } else {
                        if (extractor.sampleTrackIndex != trackIndex) {// TODO: 2017/9/14 样本轨迹下标不等于文件头下标？
                            Log.w(TAG, "WEIRD: got sample from track " + // TODO: 2017/9/14 说明取样成功？
                                    extractor.sampleTrackIndex + ", expected " + trackIndex)
                        }
                        val presentationTimeUs = extractor.sampleTime// TODO: 2017/9/14 获取当前取样的时间戳
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize,
                                presentationTimeUs, 0 /*flags*/)
                        if (VERBOSE) {
                            Log.d(TAG, "submitted frame " + inputChunk + " to dec, size=" +
                                    chunkSize)
                        }
                        inputChunk++// TODO: 2017/9/14 成功取了一块儿
                        extractor.advance()// TODO: 2017/9/14 指挥取样器走一步
                    }
                } else {
                    if (VERBOSE) Log.d(TAG, "input buffer not available")
                }
            }

            // TODO: 2017/9/14 输出开始
            if (!outputDone) {
                // TODO: 2017/9/14 出列一个输出buf
                val decoderStatus = decoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC.toLong())
                // TODO: 2017/9/14 判断各自状态
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (VERBOSE) Log.d(TAG, "no output from decoder available")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not important for us, since we're using Surface
                    if (VERBOSE) Log.d(TAG, "decoder output buffers changed")
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    if (VERBOSE) Log.d(TAG, "decoder output format changed: " + newFormat)
                } else if (decoderStatus < 0) {// TODO: 2017/9/14 粗大事了
                    throw RuntimeException(
                            "unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus)
                } else { // decoderStatus >= 0
                    if (firstInputTimeNsec != 0L) {
                        // Log the delay from the first buffer of input to the first buffer
                        // of output.
                        val nowNsec = System.nanoTime()
                        Log.d(TAG, "startup lag " + (nowNsec - firstInputTimeNsec) / 1000000.0 + " ms")
                        firstInputTimeNsec = 0// TODO: 2017/9/14 刷新标记？
                    }
                    var doLoop = false
                    if (VERBOSE) Log.d(TAG, "surface decoder given buffer " + decoderStatus + " (size=" + mBufferInfo.size + ")")
                    if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        if (VERBOSE) Log.d(TAG, "output EOS")
                        if (mLoop) {
                            doLoop = true
                        } else {
                            outputDone = true
                        }
                    }

                    val doRender = mBufferInfo.size != 0// TODO: 2017/9/14 buf尺寸不为0做渲染

                    if (doRender && frameCallback != null) {
                        // TODO: 2017/9/22 号外号外，这里传入视频帧携带的微秒信息，用于计算视频原速率
                        frameCallback.preRender(mBufferInfo.presentationTimeUs)// TODO: 2017/9/18 输出回调渲染前
                    }
                    // TODO: 2017/9/18 通知解码器释放数据，如果doRender为true意味着为解码器配置了surface，解码器会先将数据放到surface
                    /*If you configured the codec with an * output surface, setting {@code render} to {@code true}*/
                    decoder.releaseOutputBuffer(decoderStatus, doRender)
                    if (doRender && frameCallback != null) {
                        frameCallback.postRender()// TODO: 2017/9/18 渲染后
                    }

                    if (doLoop) {
                        Log.d(TAG, "Reached EOS, looping")
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)// TODO: 2017/9/18 seek到0？
                        inputDone = false
                        decoder.flush()    // reset decoder state
                        frameCallback!!.loopReset()
                    }
                }
            }
        }
    }


    /**
     * Thread helper for video playback.
     *
     *
     * The PlayerFeedback callbacks will execute on the thread that creates the object,
     * assuming that thread has a looper.  Otherwise, they will execute on the main looper.
     */
    // TODO: 2017/9/21 播放栈
    class PlayTask
    /**
     * Prepares new PlayTask.
     *
     * @param player The player object, configured with control and output.
     * @param feedback UI feedback object.
     */
    // TODO: 2017/9/22 构造器传入播放控件和回调
    (private val mPlayer: VideoTrimer// TODO: 2017/9/21 播放控件
     , private val mFeedback: PlayerFeedback// TODO: 2017/9/21 回调
    ) : Runnable {
        private var mDoLoop: Boolean = false// TODO: 2017/9/21 循环开关
        private var mThread: Thread? = null
        private val mLocalHandler: LocalHandler

        private val mStopLock = Any()
        private var mStopped = false

        init {
            mLocalHandler = LocalHandler()
        }

        fun setLoopMode(loopMode: Boolean) {
            mDoLoop = loopMode
        }

        /**
         * Creates a new thread, and starts execution of the player.
         */
        fun execute() {
            mPlayer.setLoopMode(mDoLoop)// TODO: 2017/9/22 状态转移
            mThread = Thread(this, "Movie Player")
            mThread!!.start()
        }

        /**
         * Requests that the player stop.
         *
         *
         * Called from arbitrary thread.
         */
        fun requestStop() {
            mPlayer.requestStop()
        }// TODO: 2017/9/22 状态转移

        /**
         * Wait for the player to stop.
         *
         *
         * Called from any thread other than the PlayTask thread.
         */
        fun waitForStop() {
            synchronized(mStopLock) {
                while (!mStopped) {
                    try {
                        (mStopLock as Object).wait()// TODO: 2017/9/22 线程阻塞
                    } catch (ie: InterruptedException) {
                        // discard
                    }

                }
            }
        }

        override fun run() {
            try {
                mPlayer.play()// TODO: 2017/9/22 播放ing
            } catch (ioe: IOException) {
                throw RuntimeException(ioe)
            } finally {
                // tell anybody waiting on us that we're done
                synchronized(mStopLock) {
                    mStopped = true
                    (mStopLock as Object).notifyAll()
                }

                // Send message through Handler so it runs on the right thread.
                mLocalHandler.sendMessage(
                        mLocalHandler.obtainMessage(MSG_PLAY_STOPPED, mFeedback))
            }
        }

        private class LocalHandler : Handler() {
            override fun handleMessage(msg: Message) {
                val what = msg.what

                when (what) {
                    MSG_PLAY_STOPPED -> {
                        val fb = msg.obj as PlayerFeedback
                        fb.playbackStopped()// TODO: 2017/9/22 主线程回调
                    }
                    else -> throw RuntimeException("Unknown msg " + what)
                }
            }
        }

        companion object {
            private val MSG_PLAY_STOPPED = 0
        }
    }

    /**
     * Sets the loop mode.  If true, playback will loop forever.
     */
    fun setLoopMode(loopMode: Boolean) {
        mLoop = loopMode
    }

    /**
     * Asks the player to stop.  Returns without waiting for playback to halt.
     *
     *
     * Called from arbitrary thread.
     */
    fun requestStop() {
        mIsStopRequested = true
    }

    companion object {
        private val TAG = "MoviePlayer"
    }
}
