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

package com.android.grafika.core.generate;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.surface_love_video_code.WindowSurface;
import com.android.grafika.pages.MainActivity;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Base class for generated movies.
 */// TODO: 2017/9/25 造视频，基础类基础方法
public abstract class GeneratedMovie implements Content {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    private static final int IFRAME_INTERVAL = 5;

    // set by sub-class to indicate that the movie has been generated
    // TODO: remove this now?
    protected boolean mMovieReady = false;// TODO: 2017/9/25 初始化开关

    // "live" state during recording
    private MediaCodec.BufferInfo mBufferInfo;// TODO: 2017/9/25 媒体信息buf
    private MediaCodec mEncoder;// TODO: 2017/9/25 编解码器
    private MediaMuxer mMuxer;// TODO: 2017/9/25 音视频混合器
    private EglCore mEglCore;// TODO: 2017/9/25 大肘子
    private WindowSurface mInputSurface;// TODO: 2017/9/25 大肘子
    private int mTrackIndex;// TODO: 2017/9/26 Track位置，开始混编的时候取到，很重要的东西好像
    private boolean mMuxerStarted;// TODO: 2017/9/26 开始混编开关

    /**
     * Creates the movie content.  Usually called from an async task thread.
     */// TODO: 2017/9/26 在这里构建好视频的所有帧内容
    public abstract void create(File outputFile, ContentManager.ProgressUpdater prog);

    /**
     * Returns true if the codec has a software implementation.
     */// TODO: 2017/9/26 判断软编码
    private static boolean isSoftwareCodec(MediaCodec codec) {
        String codecName = codec.getCodecInfo().getName();

        return ("OMX.google.h264.encoder".equals(codecName));
    }

    /**
     * Prepares the video encoder, muxer, and an EGL input surface.
     */// TODO: 2017/9/26 传参配置主要组件，子类在构建帧之前调用
    protected void prepareEncoder(String mimeType, int width, int height, int bitRate,
            int framesPerSecond, File outputFile) throws IOException {
        mBufferInfo = new MediaCodec.BufferInfo();

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        // TODO: 2017/9/26 配置视频格式
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);// TODO: 2017/9/26 算是解码的格式？
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, framesPerSecond);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        // TODO: 2017/9/26 配置编解码器
        mEncoder = MediaCodec.createEncoderByType(mimeType);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Log.v(TAG, "encoder is " + mEncoder.getCodecInfo().getName());
        Surface surface;
        try {
            surface = mEncoder.createInputSurface();// TODO: 2017/9/26 取出编解码器的surface
            // TODO: 2017/9/26 编解码器和surface相爱相杀
        } catch (IllegalStateException ise) {
            // This is generally the first time we ever try to encode something through a
            // Surface, so specialize the message a bit if we can guess at why it's failing.
            // TODO: failure message should come out of strings.xml for i18n
            if (isSoftwareCodec(mEncoder)) {
                throw new RuntimeException("Can't use input surface with software codec: " +mEncoder.getCodecInfo().getName(),ise);
            } else {
                throw new RuntimeException("Failed to create input surface", ise);
            }
        }
        mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE);
        mInputSurface = new WindowSurface(mEglCore, surface, true);// TODO: 2017/9/26 gl工具和surface绑定，间接和编解码器联系
        mInputSurface.makeCurrent();
        mEncoder.start();

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        if (VERBOSE) Log.d(TAG, "output will go to " + outputFile);
        // TODO: 2017/9/26 配置输出文件和输出格式
        mMuxer = new MediaMuxer(outputFile.toString(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mTrackIndex = -1;// TODO: 2017/9/26 Track位置
        mMuxerStarted = false;
    }

    /**
     * Releases encoder resources.  May be called after partial / failed initialization.
     */// TODO: 2017/9/26 各种释放
    protected void releaseEncoder() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }

    /**
     * Submits a frame to the encoder.
     *
     * @param presentationTimeNsec The presentation time stamp, in nanoseconds.
     */// TODO: 2017/9/26 有猫腻，egl相关
    // TODO: 2017/9/26 造视频的时候，每造一帧先提交，搞些骚操作
    protected void submitFrame(long presentationTimeNsec) {
        // The eglSwapBuffers call will block if the input is full, which would be bad if
        // it stayed full until we dequeued an output buffer (which we can't do, since we're
        // stuck here).  So long as the caller fully drains the encoder before supplying
        // additional input, the system guarantees that we can supply another frame
        // without blocking.
        // TODO: 2017/9/26 骚操作要研究
        mInputSurface.setPresentationTime(presentationTimeNsec);// TODO: 2017/9/26 设置surface帧渲染停留时间
        mInputSurface.swapBuffers();// TODO: 2017/9/26 相当于把缓冲的帧发送到渲染？
    }

    /**
     * Extracts all pending data from the encoder.
     * <p>
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the encoder, and then iterate until we see EOS on the output.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     */// TODO: 2017/9/26 编码器所有数据取到混编器
    protected void drainEncoder(boolean endOfStream) {
        final int TIMEOUT_USEC = 10000;
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            // TODO: 2017/9/26 传递end信号？
            mEncoder.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers(); // TODO: 2017/9/26 编解码器取buf
        while (true) {// TODO: 2017/9/26 循环搞事情
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            // TODO: 2017/9/26 各种状态
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();// TODO: 2017/9/26 不需编码直接给？
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once // TODO: 2017/9/26 有且只有一次的状况（第一次）
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");// TODO: 2017/9/26 你代码有毒！
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();// TODO: 2017/9/26 这个是混编输出的编码
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                mTrackIndex = mMuxer.addTrack(newFormat);
                mMuxer.start();// TODO: 2017/9/26 第一次循环时启动
                mMuxerStarted = true;// TODO: 2017/9/26 混编启动
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];// TODO: 2017/9/26 编码状态作下标取buf?
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    // TODO: 2017/9/26 格式改变会影响到混编器Muxer所以手动忽略掉？
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;// TODO: 2017/9/26 丢掉缓冲区？
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo
                    encodedData.position(mBufferInfo.offset);// TODO: 2017/9/26 挪一节
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);// TODO: 2017/9/26 ？？

                    // TODO: 2017/9/26 根据track下标写数据进muxer，前面已经为muxer配置好了输出file
                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer");
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }
}
