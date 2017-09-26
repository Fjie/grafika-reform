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

package com.android.grafika.pages;

import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;

import com.android.grafika.other.MiscUtils;
import com.android.grafika.core.player.MoviePlayer;
import com.android.grafika.R;
import com.android.grafika.core.player.SpeedControlCallback;

import java.io.File;
import java.io.IOException;

/**
 * Play a movie from a file on disk.  Output goes to a TextureView.
 * <p>
 * Currently video-only.
 * <p>
 * Contrast with PlayMovieSurfaceActivity, which uses a SurfaceView.  Much of the code is
 * the same, but here we can handle the aspect ratio adjustment with a simple matrix,
 * rather than a custom layout.
 * <p>
 * TODO: investigate crash when screen is rotated while movie is playing (need
 * to have onPause() wait for playback to stop)
 */
// TODO: 2017/9/14 OpenGL播放视频
public class PlayMovieActivity extends Activity implements OnItemSelectedListener,
        TextureView.SurfaceTextureListener, MoviePlayer.PlayerFeedback {
    private static final String TAG = MainActivity.TAG;

    private TextureView mTextureView;// TODO: 2017/9/14 纹理器，直接当view用了
    private String[] mMovieFiles;// TODO: 2017/9/22 视频文件
    private int mSelectedMovie;// TODO: 2017/9/22 选中下标
    private boolean mShowStopLabel;
    // TODO: 2017/9/22 播放栈，持有的播放控件，传入视频文件和纹理器，逐帧解析视频传到纹理器
    private MoviePlayer.PlayTask mPlayTask;
    private boolean mSurfaceTextureReady = false;// TODO: 2017/9/22 初始化标记，surface相关的常用

    private final Object mStopper = new Object();   // used to signal stop
    private EditText etInputFps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_play_movie);
        initView();

        mTextureView = (TextureView) findViewById(R.id.movie_texture_view);// TODO: 2017/9/22 纹理器直接当view用了
        mTextureView.setSurfaceTextureListener(this);// TODO: 2017/9/22 给个回调

        // Populate file-selection spinner.
        Spinner spinner = (Spinner) findViewById(R.id.playMovieFile_spinner);
        // Need to create one of these fancy ArrayAdapter thingies, and specify the generic layout
        // for the widget itself.
        mMovieFiles = MiscUtils.getFiles(getFilesDir(), "*.mp4");
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mMovieFiles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner.
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(this);

        updateControls();// TODO: 2017/9/22 更新视图状态
    }


    /**
     * onClick handler for "play"/"stop" button.
     */
    public void clickPlayStop(@SuppressWarnings("unused") View unused) {
        if (mShowStopLabel) {
            Log.d(TAG, "stopping movie");
            stopPlayback();// TODO: 2017/9/22 调用停止播放，到回调中更新视图
            // Don't update the controls here -- let the task thread do it after the movie has
            // actually stopped.
            //mShowStopLabel = false; // TODO: 2017/9/22 这是一个悲伤的故事
            //updateControls();
        } else {
            if (mPlayTask != null) {
                Log.w(TAG, "movie already playing");
                return;
            }
            Log.d(TAG, "starting movie");
            // TODO: 2017/9/22 准备播放控件给播放栈
            // TODO: 2017/9/22 准备播放，配置组件给播放控件
            SpeedControlCallback callback = new SpeedControlCallback();// TODO: 2017/9/22 速度控制回调
            if (((CheckBox) findViewById(R.id.locked60fps_checkbox)).isChecked()) {
                // TODO: consider changing this to be "free running" mode
                String sFps = etInputFps.getText().toString();
                // TODO: 2017/9/22 设置速度？fps? 好像有极限，大概是cpu解析每帧的速率
                callback.setFixedPlaybackRate(TextUtils.isEmpty(sFps) ? 60:Integer.parseInt(sFps));
                // TODO: 2017/9/22 要实现极限速度需要在媒体解析层手动丢帧
            }
            SurfaceTexture st = mTextureView.getSurfaceTexture();
            Surface surface = new Surface(st);// TODO: 2017/9/22 纹理
            MoviePlayer player = null;
            try {
                // TODO: 2017/9/22 传递视频、纹理、回调给播放器
                player = new MoviePlayer(
                        new File(getFilesDir(), mMovieFiles[mSelectedMovie]), surface, callback);
            } catch (IOException ioe) {
                Log.e(TAG, "Unable to play movie", ioe);
                surface.release();
                return;
            }
            adjustAspectRatio(player.getVideoWidth(), player.getVideoHeight());

            // TODO: 2017/9/22 播放控件给播放栈
            mPlayTask = new MoviePlayer.PlayTask(player, this);
            if (((CheckBox) findViewById(R.id.loopPlayback_checkbox)).isChecked()) {
                mPlayTask.setLoopMode(true);
            }

            mShowStopLabel = true;
            updateControls();
            mPlayTask.execute();// TODO: 2017/9/22 执行
        }
    }

    /**
     * Requests stoppage if a movie is currently playing.  Does not wait for it to stop.
     */
    private void stopPlayback() {
        if (mPlayTask != null) {
            mPlayTask.requestStop();
        }
    }

    @Override   // MoviePlayer.PlayerFeedback
    public void playbackStopped() {
        Log.d(TAG, "playback stopped");
        mShowStopLabel = false;
        mPlayTask = null;
        updateControls();
    }

    /**
     * Sets the TextureView transform to preserve the aspect ratio of the video.
     */
    private void adjustAspectRatio(int videoWidth, int videoHeight) {
        int viewWidth = mTextureView.getWidth();
        int viewHeight = mTextureView.getHeight();
        double aspectRatio = (double) videoHeight / videoWidth;

        int newWidth, newHeight;
        if (viewHeight > (int) (viewWidth * aspectRatio)) {
            // limited by narrow width; restrict height
            newWidth = viewWidth;
            newHeight = (int) (viewWidth * aspectRatio);
        } else {
            // limited by short height; restrict width
            newWidth = (int) (viewHeight / aspectRatio);
            newHeight = viewHeight;
        }
        int xoff = (viewWidth - newWidth) / 2;
        int yoff = (viewHeight - newHeight) / 2;
        Log.v(TAG, "video=" + videoWidth + "x" + videoHeight +
                " view=" + viewWidth + "x" + viewHeight +
                " newView=" + newWidth + "x" + newHeight +
                " off=" + xoff + "," + yoff);

        Matrix txform = new Matrix();
        mTextureView.getTransform(txform);
        txform.setScale((float) newWidth / viewWidth, (float) newHeight / viewHeight);
        //txform.postRotate(10);          // just for fun
        txform.postTranslate(xoff, yoff);
        mTextureView.setTransform(txform);
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     */
    private void updateControls() {
        Button play = (Button) findViewById(R.id.play_stop_button);
        if (mShowStopLabel) {
            play.setText(R.string.stop_button_text);
        } else {
            play.setText(R.string.play_button_text);
        }
        play.setEnabled(mSurfaceTextureReady);

        // We don't support changes mid-play, so dim these.
        CheckBox check = (CheckBox) findViewById(R.id.locked60fps_checkbox);
        check.setEnabled(!mShowStopLabel);
        check = (CheckBox) findViewById(R.id.loopPlayback_checkbox);
        check.setEnabled(!mShowStopLabel);
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "PlayMovieActivity onResume");
        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "PlayMovieActivity onPause");
        super.onPause();
        // We're not keeping track of the state in static fields, so we need to shut the
        // playback down.  Ideally we'd preserve the state so that the player would continue
        // after a device rotation.
        //
        // We want to be sure that the player won't continue to send frames after we pause,
        // because we're tearing the view down.  So we wait for it to stop here.
        if (mPlayTask != null) {
            stopPlayback();
            mPlayTask.waitForStop();
        }
    }


    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        // There's a short delay between the start of the activity and the initialization
        // of the SurfaceTexture that backs the TextureView.  We don't want to try to
        // send a video stream to the TextureView before it has initialized, so we disable
        // the "play" button until this callback fires.
        Log.d(TAG, "SurfaceTexture ready (" + width + "x" + height + ")");
        mSurfaceTextureReady = true;// TODO: 2017/9/22 纹理器可用
        updateControls();// TODO: 2017/9/22 更新视图状态
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        // ignore
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        mSurfaceTextureReady = false;
        // assume activity is pausing, so don't need to update controls
        return true;    // caller should release ST
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // ignore
    }

    /*
     * Called when the movie Spinner gets touched.
     */
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        Spinner spinner = (Spinner) parent;
        mSelectedMovie = spinner.getSelectedItemPosition();

        Log.d(TAG, "onItemSelected: " + mSelectedMovie + " '" + mMovieFiles[mSelectedMovie] + "'");
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    private void initView() {
        etInputFps = (EditText) findViewById(R.id.et_input_fps);
    }
}
