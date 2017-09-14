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

package com.android.grafika;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;


/**
 * Plays the video track from a movie file to a Surface.
 * <p>
 * TODO: needs more advanced shuttle controls (pause/resume, skip)
 */
// TODO: 2017/9/14 视频播放控件，依赖原生媒体api
public class MoviePlayer {
    private static final String TAG = "MoviePlayer";
    private static final boolean VERBOSE = true;

    // Declare this here to reduce allocations.
    // TODO: 2017/9/14 媒体信息引用
    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    // May be set/read by different threads.
    private volatile boolean mIsStopRequested;// TODO: 2017/9/14 what ？线程相关？

    private File mSourceFile;// TODO: 2017/9/14 源文件
    private Surface mOutputSurface;// TODO: 2017/9/14 输出surface并不渲染
    FrameCallback mFrameCallback;// TODO: 2017/9/14 帧回调
    private boolean mLoop;// TODO: 2017/9/14 循环开关
    private int mVideoWidth;
    private int mVideoHeight;


    /**
     * Interface to be implemented by class that manages playback UI.
     * <p>
     * Callback methods will be invoked on the UI thread.
     */// TODO: 2017/9/14 播放反馈
    public interface PlayerFeedback {
        void playbackStopped();
    }


    /**
     * Callback invoked when rendering video frames.  The MoviePlayer client must
     * provide one of these.
     */// TODO: 2017/9/14 帧回掉
    public interface FrameCallback {
        /**
         * Called immediately before the frame is rendered.
         * @param presentationTimeUsec The desired presentation time, in microseconds.
         */// TODO: 2017/9/14 渲染前
        void preRender(long presentationTimeUsec);

        /**
         * Called immediately after the frame render call returns.  The frame may not have
         * actually been rendered yet.
         * TODO: is this actually useful?
         */// TODO: 2017/9/14 渲染后
        void postRender();

        /**
         * Called after the last frame of a looped movie has been rendered.  This allows the
         * callback to adjust its expectations of the next presentation time stamp.
         */// TODO: 2017/9/14 循环重置
        void loopReset();
    }


    /**
     * Constructs a MoviePlayer.
     *
     * @param sourceFile The video file to open.
     * @param outputSurface The Surface where frames will be sent.
     * @param frameCallback Callback object, used to pace output.
     * @throws IOException
     */// TODO: 2017/9/14 构造器传视频、输出的surface（关键）、帧回调
    public MoviePlayer(File sourceFile, Surface outputSurface, FrameCallback frameCallback)
            throws IOException {
        mSourceFile = sourceFile;
        mOutputSurface = outputSurface;
        mFrameCallback = frameCallback;

        // Pop the file open and pull out the video characteristics.
        // TODO: consider leaving the extractor open.  Should be able to just seek back to
        //       the start after each iteration of play.  Need to rearrange the API a bit --
        //       currently play() is taking an all-in-one open+work+release approach.
        // TODO: 2017/9/14 简单初始化
        MediaExtractor extractor = null;// TODO: 2017/9/14 媒体取样器，取帧的
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(sourceFile.toString());
            // TODO: 2017/9/14 取信息判断是否为视频文件
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + mSourceFile);
            }
            // TODO: 2017/9/14 调用底层方法取信息？
            extractor.selectTrack(trackIndex);// TODO: 2017/9/14 这行几个意思？

            MediaFormat format = extractor.getTrackFormat(trackIndex);// TODO: 2017/9/14 根据下标取信息省点事儿
            mVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            mVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (VERBOSE) {
                Log.d(TAG, "Video size is " + mVideoWidth + "x" + mVideoHeight);
            }
        } finally {
            if (extractor != null) {
                extractor.release();
            }
        }
    }



    /**
     * Decodes the video stream, sending frames to the surface.
     * <p>
     * Does not return until video playback is complete, or we get a "stop" signal from
     * frameCallback.
     */// TODO: 2017/9/14  播放，类似初始化操作
    public void play() throws IOException {
        // TODO: 2017/9/14 取样器编解码器
        MediaExtractor extractor = null;
        MediaCodec decoder = null;

        // The MediaExtractor error messages aren't very useful.  Check to see if the input
        // file exists so we can throw a better one if it's not there.
        if (!mSourceFile.canRead()) {
            throw new FileNotFoundException("Unable to read " + mSourceFile);
        }

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(mSourceFile.toString());
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + mSourceFile);
            }
            extractor.selectTrack(trackIndex);

            MediaFormat format = extractor.getTrackFormat(trackIndex);

            // Create a MediaCodec decoder, and configure it with the MediaFormat from the
            // extractor.  It's very important to use the format from the extractor because
            // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
            String mime = format.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);// TODO: 2017/9/14 根据取样到的编码类型设置编解码器
            decoder.configure(format, mOutputSurface, null, 0);// TODO: 2017/9/14 surface 给到编解码器
            decoder.start();

            doExtract(extractor, trackIndex, decoder, mFrameCallback);// TODO: 2017/9/14 开始提取
        } finally {
            // release everything we grabbed
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }



    /**
     * Work loop.  We execute here until we run out of video or are told to stop.
     */
    private void doExtract(MediaExtractor extractor, int trackIndex, MediaCodec decoder,
            FrameCallback frameCallback) {
        // TODO: 2017/9/14 作者的心路历程
        // We need to strike a balance between providing input and reading output that
        // operates efficiently without delays on the output side.
        //
        // To avoid delays on the output side, we need to keep the codec's input buffers
        // fed.  There can be significant latency between submitting frame N to the decoder
        // and receiving frame N on the output, so we need to stay ahead of the game.
        //
        // Many video decoders seem to want several frames of video before they start
        // producing output -- one implementation wanted four before it appeared to
        // configure itself.  We need to provide a bunch of input frames up front, and try
        // to keep the queue full as we go.
        //
        // (Note it's possible for the encoded data to be written to the stream out of order,
        // so we can't generally submit a single frame and wait for it to appear.)
        //
        // We can't just fixate on the input side though.  If we spend too much time trying
        // to stuff the input, we might miss a presentation deadline.  At 60Hz we have 16.7ms
        // between frames, so sleeping for 10ms would eat up a significant fraction of the
        // time allowed.  (Most video is at 30Hz or less, so for most content we'll have
        // significantly longer.)  Waiting for output is okay, but sleeping on availability
        // of input buffers is unwise if we need to be providing output on a regular schedule.
        //
        //
        // In some situations, startup latency may be a concern.  To minimize startup time,
        // we'd want to stuff the input full as quickly as possible.  This turns out to be
        // somewhat complicated, as the codec may still be starting up and will refuse to
        // accept input.  Removing the timeout from dequeueInputBuffer() results in spinning
        // on the CPU.
        //
        // If you have tight startup latency requirements, it would probably be best to
        // "prime the pump" with a sequence of frames that aren't actually shown (e.g.
        // grab the first 10 NAL units and shove them through, then rewind to the start of
        // the first key frame).
        //
        // The actual latency seems to depend on strongly on the nature of the video (e.g.
        // resolution).
        //
        //
        // One conceptually nice approach is to loop on the input side to ensure that the codec
        // always has all the input it can handle.  After submitting a buffer, we immediately
        // check to see if it will accept another.  We can use a short timeout so we don't
        // miss a presentation deadline.  On the output side we only check once, with a longer
        // timeout, then return to the outer loop to see if the codec is hungry for more input.
        //
        // In practice, every call to check for available buffers involves a lot of message-
        // passing between threads and processes.  Setting a very brief timeout doesn't
        // exactly work because the overhead required to determine that no buffer is available
        // is substantial.  On one device, the "clever" approach caused significantly greater
        // and more highly variable startup latency.
        //
        // The code below takes a very simple-minded approach that works, but carries a risk
        // of occasionally running out of output.  A more sophisticated approach might
        // detect an output timeout and use that as a signal to try to enqueue several input
        // buffers on the next iteration.
        //
        // If you want to experiment, set the VERBOSE flag to true and watch the behavior
        // in logcat.  Use "logcat -v threadtime" to see sub-second timing.

        final int TIMEOUT_USEC = 10000;
        ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();// TODO: 2017/9/14 获取编解码的缓冲区
        int inputChunk = 0;
        long firstInputTimeNsec = -1;

        boolean outputDone = false;
        boolean inputDone = false;
        while (!outputDone) {// TODO: 2017/9/14 循环输出
            if (VERBOSE) Log.d(TAG, "loop");
            if (mIsStopRequested) {
                Log.d(TAG, "Stop requested");
                return;
            }

            // Feed more data to the decoder.
            // TODO: 2017/9/14 输入开始！
            if (!inputDone) {
                // TODO: 2017/9/14 先指挥编解码器出列一个buf，并返回其下标
                int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (inputBufIndex >= 0) {// TODO: 2017/9/14 出列成功
                    if (firstInputTimeNsec == -1) {
                        firstInputTimeNsec = System.nanoTime();// TODO: 2017/9/14 第一次先赋值，诡异的时间戳
                    }
                    ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];// TODO: 2017/9/14 根据下标取buf
                    // Read the sample data into the ByteBuffer.  This neither respects nor
                    // updates inputBuf's position, limit, etc.
                    int chunkSize = extractor.readSampleData(inputBuf, 0);// TODO: 2017/9/14 传入buf获取块大小
                    if (chunkSize < 0) {// TODO: 2017/9/14 输入结束
                        // End of stream -- send empty frame with EOS flag set.
                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                        if (VERBOSE) Log.d(TAG, "sent input EOS");
                    } else {
                        if (extractor.getSampleTrackIndex() != trackIndex) {// TODO: 2017/9/14 样本轨迹下标不等于文件头下标？
                            Log.w(TAG, "WEIRD: got sample from track " +// TODO: 2017/9/14 说明取样成功？
                                    extractor.getSampleTrackIndex() + ", expected " + trackIndex);
                        }
                        long presentationTimeUs = extractor.getSampleTime();// TODO: 2017/9/14 获取当前取样的时间戳
                        decoder.queueInputBuffer(inputBufIndex, 0, chunkSize,
                                presentationTimeUs, 0 /*flags*/);
                        if (VERBOSE) {
                            Log.d(TAG, "submitted frame " + inputChunk + " to dec, size=" +
                                    chunkSize);
                        }
                        inputChunk++;// TODO: 2017/9/14 成功取了一块儿
                        extractor.advance();// TODO: 2017/9/14 指挥取样器走一步
                    }
                } else {
                    if (VERBOSE) Log.d(TAG, "input buffer not available");
                }
            }

            // TODO: 2017/9/14 输出开始
            if (!outputDone) {
                // TODO: 2017/9/14 出列一个输出buf
                int decoderStatus = decoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
                // TODO: 2017/9/14 判断各自状态
                if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (VERBOSE) Log.d(TAG, "no output from decoder available");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not important for us, since we're using Surface
                    if (VERBOSE) Log.d(TAG, "decoder output buffers changed");
                } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    if (VERBOSE) Log.d(TAG, "decoder output format changed: " + newFormat);
                } else if (decoderStatus < 0) {// TODO: 2017/9/14 粗大事了
                    throw new RuntimeException(
                            "unexpected result from decoder.dequeueOutputBuffer: " +
                                    decoderStatus);
                } else { // decoderStatus >= 0
                    if (firstInputTimeNsec != 0) {
                        // Log the delay from the first buffer of input to the first buffer
                        // of output.
                        long nowNsec = System.nanoTime();
                        Log.d(TAG, "startup lag " + ((nowNsec-firstInputTimeNsec) / 1000000.0) + " ms");
                        firstInputTimeNsec = 0;// TODO: 2017/9/14 刷新标记？
                    }
                    boolean doLoop = false;
                    if (VERBOSE) Log.d(TAG, "surface decoder given buffer " + decoderStatus +" (size=" + mBufferInfo.size + ")");
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        if (VERBOSE) Log.d(TAG, "output EOS");
                        if (mLoop) {
                            doLoop = true;
                        } else {
                            outputDone = true;
                        }
                    }

                    boolean doRender = (mBufferInfo.size != 0);

                    // As soon as we call releaseOutputBuffer, the buffer will be forwarded
                    // to SurfaceTexture to convert to a texture.  We can't control when it
                    // appears on-screen, but we can manage the pace at which we release
                    // the buffers.
                    if (doRender && frameCallback != null) {
                        frameCallback.preRender(mBufferInfo.presentationTimeUs);
                    }
                    decoder.releaseOutputBuffer(decoderStatus, doRender);
                    if (doRender && frameCallback != null) {
                        frameCallback.postRender();
                    }

                    if (doLoop) {
                        Log.d(TAG, "Reached EOS, looping");
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        inputDone = false;
                        decoder.flush();    // reset decoder state
                        frameCallback.loopReset();
                    }
                }
            }
        }
    }



    /**
     * Selects the video track, if any.
     *
     * @return the track index, or -1 if no video track is found.
     */// TODO: 2017/9/14 不太懂，取某种媒体信息
    private static int selectTrack(MediaExtractor extractor) {
        // Select the first video track we find, ignore the rest.
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {// TODO: 2017/9/14 遍历track
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {// TODO: 2017/9/14 取到video的信息，返回对应下标
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }

        return -1;
    }

    /**
     * Thread helper for video playback.
     * <p>
     * The PlayerFeedback callbacks will execute on the thread that creates the object,
     * assuming that thread has a looper.  Otherwise, they will execute on the main looper.
     */
    public static class PlayTask implements Runnable {
        private static final int MSG_PLAY_STOPPED = 0;

        private MoviePlayer mPlayer;
        private PlayerFeedback mFeedback;
        private boolean mDoLoop;
        private Thread mThread;
        private LocalHandler mLocalHandler;

        private final Object mStopLock = new Object();
        private boolean mStopped = false;

        /**
         * Prepares new PlayTask.
         *
         * @param player The player object, configured with control and output.
         * @param feedback UI feedback object.
         */
        public PlayTask(MoviePlayer player, PlayerFeedback feedback) {
            mPlayer = player;
            mFeedback = feedback;

            mLocalHandler = new LocalHandler();
        }

        /**
         * Sets the loop mode.  If true, playback will loop forever.
         */
        public void setLoopMode(boolean loopMode) {
            mDoLoop = loopMode;
        }

        /**
         * Creates a new thread, and starts execution of the player.
         */
        public void execute() {
            mPlayer.setLoopMode(mDoLoop);
            mThread = new Thread(this, "Movie Player");
            mThread.start();
        }

        /**
         * Requests that the player stop.
         * <p>
         * Called from arbitrary thread.
         */
        public void requestStop() {
            mPlayer.requestStop();
        }

        /**
         * Wait for the player to stop.
         * <p>
         * Called from any thread other than the PlayTask thread.
         */
        public void waitForStop() {
            synchronized (mStopLock) {
                while (!mStopped) {
                    try {
                        mStopLock.wait();
                    } catch (InterruptedException ie) {
                        // discard
                    }
                }
            }
        }

        @Override
        public void run() {
            try {
                mPlayer.play();
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            } finally {
                // tell anybody waiting on us that we're done
                synchronized (mStopLock) {
                    mStopped = true;
                    mStopLock.notifyAll();
                }

                // Send message through Handler so it runs on the right thread.
                mLocalHandler.sendMessage(
                        mLocalHandler.obtainMessage(MSG_PLAY_STOPPED, mFeedback));
            }
        }

        private static class LocalHandler extends Handler {
            @Override
            public void handleMessage(Message msg) {
                int what = msg.what;

                switch (what) {
                    case MSG_PLAY_STOPPED:
                        PlayerFeedback fb = (PlayerFeedback) msg.obj;
                        fb.playbackStopped();
                        break;
                    default:
                        throw new RuntimeException("Unknown msg " + what);
                }
            }
        }
    }


    /**
     * Returns the width, in pixels, of the video.
     */
    public int getVideoWidth() {
        return mVideoWidth;
    }

    /**
     * Returns the height, in pixels, of the video.
     */
    public int getVideoHeight() {
        return mVideoHeight;
    }

    /**
     * Sets the loop mode.  If true, playback will loop forever.
     */
    public void setLoopMode(boolean loopMode) {
        mLoop = loopMode;
    }

    /**
     * Asks the player to stop.  Returns without waiting for playback to halt.
     * <p>
     * Called from arbitrary thread.
     */
    public void requestStop() {
        mIsStopRequested = true;
    }
}
