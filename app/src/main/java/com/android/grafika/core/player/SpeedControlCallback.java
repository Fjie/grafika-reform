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

package com.android.grafika.core.player;

import android.util.Log;

/**
 * Movie player callback.
 * <p>
 * The goal here is to play back frames at the original rate.  This is done by introducing
 * a pause before the frame is submitted to the renderer.
 * <p>
 * This is not coordinated with VSYNC.  Since we can't control the display's refresh rate, and
 * the source material has time stamps that specify when each frame should be presented,
 * we will have to drop or repeat frames occasionally.
 * <p>
 * Thread restrictions are noted in the method descriptions.  The FrameCallback overrides should
 * only be called from the MoviePlayer.
 */// TODO: 2017/9/18  一个神奇的，控制速度的。回调
// TODO: 2017/9/22 实现思路是在取到每帧，放到纹理之间，暂停一下
// TODO: 2017/9/22 极限速度是编解码器取帧的速度，不太快，要突破需要解码取帧的时候手动丢帧
public class SpeedControlCallback implements MoviePlayer.FrameCallback {
    private static final String TAG = "SpeedControlCallback";
    private static final boolean CHECK_SLEEP_TIME = true;// TODO: 2017/9/22 检测睡眠时间开关

    private static final long ONE_MILLION = 1000000L;// TODO: 2017/9/22 1秒 1000毫秒

    private long mPrevPresentUsec;
    private long mPrevMonoUsec;
    private long mFixedFrameDurationUsec;
    private boolean mLoopReset;// TODO: 2017/9/22 这个开关有意思，实际上内部关，外部开

    /**
     * Sets a fixed playback rate.  If set, this will ignore the presentation time stamp
     * in the video file.  Must be called before playback thread starts.
     */
    public void setFixedPlaybackRate(int fps) {
        // TODO: 2017/9/22 设置每秒帧数，算出每帧需要占用的时间
        mFixedFrameDurationUsec = fps == 0 ? 0 : ONE_MILLION / fps;
    }

    // runs on decode thread
    @Override
    public void preRender(long presentationTimeUsec) {
        // For the first frame, we grab the presentation time from the video
        // and the current monotonic clock time.  For subsequent frames, we
        // sleep for a bit to try to ensure that we're rendering frames at the
        // pace dictated by the video stream.
        //
        // If the frame rate is faster than vsync we should be dropping frames.  On
        // Android 4.4 this may not be happening.

        if (mPrevMonoUsec == 0) { // TODO: 2017/9/22 视频的第一帧
            // Latch current values, then return immediately.
            mPrevMonoUsec = System.nanoTime() / 1000;// TODO: 2017/9/22 赋个值
            mPrevPresentUsec = presentationTimeUsec;// TODO: 2017/9/22 保存第一帧时间戳
        } else {
            // TODO: 2017/9/22 先算和上一帧应该相隔的时间
            // Compute the desired time delta between the previous frame and this frame.
            long frameDelta;
            if (mLoopReset) {
                // TODO: 2017/9/22 视频循环状态下的处理，最后一帧与第一帧的关系
                // We don't get an indication of how long the last frame should appear
                // on-screen, so we just throw a reasonable value in.  We could probably
                // do better by using a previous frame duration or some sort of average;
                // for now we just use 30fps.
                mPrevPresentUsec = presentationTimeUsec - ONE_MILLION / 30;// TODO: 2017/9/22 每秒30帧情况下一帧需要的微秒数
                mLoopReset = false;
            }
            // TODO: 2017/9/22 取下帧微秒数？
            if (mFixedFrameDurationUsec != 0) {
                // Caller requested a fixed frame rate.  Ignore PTS.
                frameDelta = mFixedFrameDurationUsec;// TODO: 2017/9/22 有设置的情况下
            } else {
                // TODO: 2017/9/22 默认情况下通过传入的帧时间和上一帧传入的帧时间计算，即为视频原帧间隔
                frameDelta = presentationTimeUsec - mPrevPresentUsec;
            }

            Log.d(TAG, "preRender: frameDelta = " + frameDelta);// TODO: 2017/9/22 打印微秒数
            if (frameDelta < 0) {
                Log.w(TAG, "Weird, video times went backward");// TODO: 2017/9/22 我代码粗大事了
                frameDelta = 0;
            } else if (frameDelta == 0) {
                // This suggests a possible bug in movie generation.// TODO: 2017/9/22  视频文件粗大事了
                Log.i(TAG, "Warning: current frame and previous frame had same timestamp");
            } else if (frameDelta > 10 * ONE_MILLION) {// TODO: 2017/9/22 帧微秒大于十秒的时候，有可能是代码弄毫秒了，很贴心
                // Inter-frame times could be arbitrarily long.  For this player, we want
                // to alert the developer that their movie might have issues (maybe they
                // accidentally output timestamps in nsec rather than usec).
                Log.i(TAG, "Inter-frame pause was " + (frameDelta / ONE_MILLION) +
                        "sec, capping at 5 sec");
                frameDelta = 5 * ONE_MILLION;
            }

            // TODO: 2017/9/22 上一帧时间戳加帧微秒，就是渲染当前帧的时机
            long desiredUsec = mPrevMonoUsec + frameDelta;  // when we want to wake up
            // TODO: 2017/9/22 极限速度就是，调用这个方法时，当前时间已经大于上一帧时间戳加帧微秒数了
            long nowUsec = System.nanoTime() / 1000;
            // TODO: 2017/9/22 号外号外!
            // TODO: 2017/9/22 当速率设置高到一定数值时，根本没有进循环！极限速度，不需要睡了
            Log.d(TAG, "preRender: desiredUsec - nowUsec = " + (desiredUsec - nowUsec));
            while (nowUsec < (desiredUsec - 100) /*&& mState == RUNNING*/) {// TODO: 2017/9/22 当前时间早于渲染时间-100时循环
                // Sleep until it's time to wake up.  To be responsive to "stop" commands
                // we're going to wake up every half a second even if the sleep is supposed
                // to be longer (which should be rare).  The alternative would be
                // to interrupt the thread, but that requires more work.
                //
                // The precision of the sleep call varies widely from one device to another;
                // we may wake early or late.  Different devices will have a minimum possible
                // sleep time. If we're within 100us of the target time, we'll probably
                // overshoot if we try to sleep, so just go ahead and continue on.
                long sleepTimeUsec = desiredUsec - nowUsec;// TODO: 2017/9/22 需要睡眠的时间
                if (sleepTimeUsec > 500000) {// TODO: 2017/9/22 睡眠时间超过半秒即忽略
                    sleepTimeUsec = 500000;
                }
                try {
                    if (CHECK_SLEEP_TIME) {
                        long startNsec = System.nanoTime();
                        Thread.sleep(sleepTimeUsec / 1000, (int) (sleepTimeUsec % 1000) * 1000);// TODO: 2017/9/22 精准睡眠
                        long actualSleepNsec = System.nanoTime() - startNsec;// TODO: 2017/9/22 计算实际睡眠时间
                        Log.d(TAG, "sleep=" + sleepTimeUsec + " actual=" + (actualSleepNsec / 1000) +
                                " diff=" + (Math.abs(actualSleepNsec / 1000 - sleepTimeUsec)) +
                                " (usec)");
                    } else {
                        Thread.sleep(sleepTimeUsec / 1000, (int) (sleepTimeUsec % 1000) * 1000);
                    }
                } catch (InterruptedException ie) {
                }
                nowUsec = System.nanoTime() / 1000;// TODO: 2017/9/22 刷新循环，看看还要不要睡
            }

            // Advance times using calculated time values, not the post-sleep monotonic
            // clock time, to avoid drifting.
            // TODO: 2017/9/22 用计算的时间赋值，不用实际睡眠后的实际赋值，避免积累误差
            mPrevMonoUsec += frameDelta;
            mPrevPresentUsec += frameDelta;
        }
    }

    // runs on decode thread
    @Override
    public void postRender() {
    }

    @Override
    public void loopReset() {
        mLoopReset = true;
    }
}
