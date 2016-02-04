/*
 * Copyright (C) 2016 Brian Wernick
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

package com.devbrackets.android.exomedia;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.DrawableRes;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.VideoView;

import com.devbrackets.android.exomedia.core.builder.DashRenderBuilder;
import com.devbrackets.android.exomedia.core.builder.HlsRenderBuilder;
import com.devbrackets.android.exomedia.core.builder.RenderBuilder;
import com.devbrackets.android.exomedia.core.builder.SmoothStreamRenderBuilder;
import com.devbrackets.android.exomedia.core.exoplayer.EMExoPlayer;
import com.devbrackets.android.exomedia.core.listener.ExoPlayerListener;
import com.devbrackets.android.exomedia.type.MediaSourceType;
import com.devbrackets.android.exomedia.util.EMDeviceUtil;
import com.devbrackets.android.exomedia.util.MediaType;
import com.devbrackets.android.exomedia.util.Repeater;
import com.devbrackets.android.exomedia.util.StopWatch;
import com.devbrackets.android.exomedia.widget.DefaultControls;
import com.devbrackets.android.exomedia.widget.DefaultControlsLeanback;
import com.devbrackets.android.exomedia.widget.DefaultControlsMobile;
import com.devbrackets.android.exomedia.widget.VideoTextureView;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.audio.AudioCapabilitiesReceiver;

/**
 * This is a support VideoView that will use the standard VideoView on devices below
 * JellyBean.  On devices with JellyBean and up we will use the ExoPlayer in order to
 * better support HLS streaming and full 1080p video resolutions which the VideoView
 * struggles with, and in some cases crashes.
 * <p>
 * To an external user this view should have the same APIs used with the standard VideoView
 * to help with quick implementations.
 */
@SuppressWarnings("UnusedDeclaration")
public class EMVideoView extends RelativeLayout implements AudioCapabilitiesReceiver.Listener, VideoTextureView.OnSizeChangeListener {
    private static final String TAG = EMVideoView.class.getSimpleName();
    private static final String USER_AGENT_FORMAT = "EMVideoView %s / Android %s / %s";

    private View shutterTop;
    private View shutterBottom;
    private View shutterRight;
    private View shutterLeft;

    private ImageView previewImageView;

    private TouchVideoView videoView;
    private VideoTextureView exoVideoTextureView;
    private EMExoPlayer emExoPlayer;

    protected DefaultControls defaultControls;
    protected Repeater pollRepeater = new Repeater();
    private StopWatch overriddenPositionStopWatch = new StopWatch();

    private AudioCapabilities audioCapabilities;
    private AudioCapabilitiesReceiver audioCapabilitiesReceiver;

    private boolean useExo = false;
    private int overriddenDuration = -1;
    private int positionOffset = 0;
    private boolean overridePosition = false;

    protected MuxNotifier muxNotifier = new MuxNotifier();
    private EMListenerMux listenerMux;
    private boolean playRequested = false;
    private boolean releaseOnDetachFromWindow = true;

    private Uri videoUri;

    public EMVideoView(Context context) {
        super(context);
        setup(context, null);
    }

    public EMVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setup(context, attrs);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public EMVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setup(context, attrs);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public EMVideoView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setup(context, attrs);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        pause();

        if (releaseOnDetachFromWindow) {
            release();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        //Makes sure the shutters are the correct size
        getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
            public void onGlobalLayout() {
                if (useExo) {
                    muxNotifier.updateVideoShutters(getWidth(), getHeight(), exoVideoTextureView.getWidth(), exoVideoTextureView
                            .getHeight());
                    getViewTreeObserver().removeOnGlobalLayoutListener(this);
                } else {
                    muxNotifier.updateVideoShutters(getWidth(), getHeight(), videoView.getWidth(), videoView.getHeight());
                    //noinspection deprecation
                    getViewTreeObserver().removeGlobalOnLayoutListener(this);
                }
            }
        });

        forceLayout();
        invalidate();
    }

    @Override
    public void onVideoSurfaceSizeChange(int width, int height) {
        muxNotifier.updateVideoShutters(getWidth(), getHeight(), width, height);
    }

    @Override
    public void setOnTouchListener(OnTouchListener listener) {
        if (exoVideoTextureView != null) {
            exoVideoTextureView.setOnTouchListener(listener);
        }

        if (videoView != null) {
            videoView.setOnTouchListener(listener);
        }

        //Sets the onTouch listener for the shutters
        shutterLeft.setOnTouchListener(listener);
        shutterRight.setOnTouchListener(listener);
        shutterTop.setOnTouchListener(listener);
        shutterBottom.setOnTouchListener(listener);

        super.setOnTouchListener(listener);
    }

    @Override
    public void onAudioCapabilitiesChanged(AudioCapabilities audioCapabilities) {
        if (!audioCapabilities.equals(this.audioCapabilities)) {
            this.audioCapabilities = audioCapabilities;
        }
    }

    private void setup(Context context, @Nullable AttributeSet attrs) {
        useExo = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && EMDeviceUtil.isDeviceCTSCompliant();

        //TODO: we need this in the DefaultControls
//        pollRepeater.setRepeatListener(new Repeater.RepeatListener() {
//            @Override
//            public void onRepeat() {
//                currentMediaProgressEvent.update(getCurrentPosition(), getBufferPercentage(), getDuration());
//
//                if (defaultControls != null) {
//                    defaultControls.setProgressEvent(currentMediaProgressEvent);
//                }
//            }
//        });

        initView(context);
        readAttributes(context, attrs);
    }

    /**
     * Reads the attributes associated with this view, setting any values found
     *
     * @param context The context to retrieve the styled attributes with
     * @param attrs The {@link AttributeSet} to retrieve the values from
     */
    private void readAttributes(Context context, @Nullable AttributeSet attrs) {
        if (attrs == null || isInEditMode()) {
            return;
        }

        TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.EMVideoView);
        if (typedArray == null) {
            return;
        }

        //Updates the DefaultControls
        boolean enableDefaultControls = typedArray.getBoolean(R.styleable.EMVideoView_defaultControlsEnabled, false);
        setDefaultControlsEnabled(enableDefaultControls);

        typedArray.recycle();
    }

    private void initView(Context context) {
        if (useExo) {
            View.inflate(context, R.layout.exomedia_exo_view_layout, this);
        } else {
            View.inflate(context, R.layout.exomedia_video_view_layout, this);
        }

        shutterBottom = findViewById(R.id.exomedia_video_shutter_bottom);
        shutterTop = findViewById(R.id.exomedia_video_shutter_top);
        shutterLeft = findViewById(R.id.exomedia_video_shutter_left);
        shutterRight = findViewById(R.id.exomedia_video_shutter_right);

        previewImageView = (ImageView) findViewById(R.id.exomedia_video_preview_image);

        exoVideoTextureView = (VideoTextureView) findViewById(R.id.exomedia_exo_video_surface);
        videoView = (TouchVideoView) findViewById(R.id.exomedia_android_video_view);

        //If we are using the exo player set it up
        if (exoVideoTextureView != null) {
            setupExoPlayer();
        } else {
            setupVideoView();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void setupExoPlayer() {
        audioCapabilitiesReceiver = new AudioCapabilitiesReceiver(getContext().getApplicationContext(), this);
        audioCapabilitiesReceiver.register();
        emExoPlayer = new EMExoPlayer(null);

        //Sets the internal listener
        listenerMux = new EMListenerMux(muxNotifier);
        emExoPlayer.addListener(listenerMux);
        emExoPlayer.setMetadataListener(null);
        exoVideoTextureView.setSurfaceTextureListener(new EMExoVideoSurfaceTextureListener());
        exoVideoTextureView.setOnSizeChangeListener(this);
    }

    private void setupVideoView() {
        listenerMux = new EMListenerMux(muxNotifier);
        videoView.setOnCompletionListener(listenerMux);
        videoView.setOnPreparedListener(listenerMux);
        videoView.setOnErrorListener(listenerMux);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            videoView.setOnInfoListener(listenerMux);
        }
    }

    /**
     * Creates and returns the correct render builder for the specified VideoType and uri.
     *
     * @param renderType The RenderType to use for creating the correct RenderBuilder
     * @param uri The video's Uri
     * @param defaultMediaType  The MediaType to use when auto-detection fails
     * @return The appropriate RenderBuilder
     */
    private RenderBuilder getRendererBuilder(MediaSourceType renderType, Uri uri, MediaType defaultMediaType) {
        switch (renderType) {
            case HLS:
                return new HlsRenderBuilder(getContext().getApplicationContext(), getUserAgent(), uri.toString());
            case DASH:
                return new DashRenderBuilder(getContext().getApplicationContext(), getUserAgent(), uri.toString());
            case SMOOTH_STREAM:
                return new SmoothStreamRenderBuilder(getContext().getApplicationContext(), getUserAgent(), uri.toString());
            default:
                return new RenderBuilder(getContext().getApplicationContext(), getUserAgent(), uri.toString());
        }
    }

    /**
     * <b><em>WARNING:</em></b> Use of this method may cause memory leaks.
     * <p>
     * Enables or disables the automatic release when the EMVideoView is detached
     * from the window.  Normally this is expected to release all resources used
     * by calling {@link #release()}.  If <code>releaseOnDetach</code> is disabled
     * then {@link #release()} will need to be manually called.
     *
     * @param releaseOnDetach False to disable the automatic release in {@link #onDetachedFromWindow()}
     */
    public void setReleaseOnDetachFromWindow(boolean releaseOnDetach) {
        this.releaseOnDetachFromWindow = releaseOnDetach;
    }

    /**
     * Stops the playback and releases all resources attached to this
     * EMVideoView.  This should not be called manually unless
     * {@link #setReleaseOnDetachFromWindow(boolean)} has been set.
     */
    public void release() {
        defaultControls = null;
        stopPlayback();
        overriddenPositionStopWatch.stop();

        if (emExoPlayer != null) {
            emExoPlayer.release();
        }

        if (audioCapabilitiesReceiver != null) {
            audioCapabilitiesReceiver.unregister();
            audioCapabilitiesReceiver = null;
        }
    }

    /**
     * Retrieves the user agent that the EMVideoView will use when communicating
     * with media servers
     *
     * @return The String user agent for the EMVideoView
     */
    public String getUserAgent() {
        return String.format(USER_AGENT_FORMAT, BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", Build.VERSION.RELEASE, Build.MODEL);
    }

    /**
     * Sets an image that will be visible only when the video is loading.
     *
     * @param drawable The drawable to use for the preview image
     */
    public void setPreviewImage(@Nullable Drawable drawable) {
        if (previewImageView != null) {
            previewImageView.setImageDrawable(drawable);
        }
    }

    /**
     * Sets an image that will be visible only when the video is loading.
     *
     * @param resourceId The resourceId representing the preview image
     */
    public void setPreviewImage(@DrawableRes int resourceId) {
        if (previewImageView != null) {
            previewImageView.setImageResource(resourceId);
        }
    }

    /**
     * Sets an image that will be visible only when the video is loading.
     *
     * @param bitmap The bitmap to use for the preview image
     */
    public void setPreviewImage(@Nullable Bitmap bitmap) {
        if (previewImageView != null) {
            previewImageView.setImageBitmap(bitmap);
        }
    }

    /**
     * Sets an image that will be visible only when the video is loading.
     *
     * @param uri The Uri pointing to the preview image
     */
    public void setPreviewImage(@Nullable Uri uri) {
        if (previewImageView != null) {
            previewImageView.setImageURI(uri);
        }
    }

    /**
     * Gets the preview ImageView for use with image loading libraries.
     *
     * @return the preview ImageView
     */
    public ImageView getPreviewImageView() {
        return previewImageView;
    }

    /**
     * Sets the color for the video shutters (the black bars above and below the video)
     *
     * @param color The color
     */
    public void setShutterColor(int color) {
        if (shutterTop != null) {
            shutterTop.setBackgroundColor(color);
        }

        if (shutterBottom != null) {
            shutterBottom.setBackgroundColor(color);
        }

        if (shutterLeft != null) {
            shutterLeft.setBackgroundColor(color);
        }

        if (shutterRight != null) {
            shutterRight.setBackgroundColor(color);
        }
    }

    /***********************************
     * Start of the media control APIs *
     ***********************************/


    /**
     * Enables and disables the media control overlay for the video view.  This can
     * also be specified in the layout XML with the attribute <code>defaultControlsEnabled</code>
     *
     * @param enabled Weather the default video controls are enabled (default: false)
     */
    public void setDefaultControlsEnabled(boolean enabled) {
        if (defaultControls == null && enabled) {
            defaultControls = EMDeviceUtil.isDeviceTV(getContext()) ? new DefaultControlsLeanback(getContext()) : new DefaultControlsMobile(getContext());
            defaultControls.setVideoView(this);

            addView(defaultControls);
        } else if (defaultControls != null && !enabled) {
            removeView(defaultControls);
            defaultControls = null;
        }

        //Sets the onTouch listener to show the default controls
        TouchListener listener = new TouchListener(getContext());
        setOnTouchListener(enabled ? listener : null);
    }

    /**
     * Requests the DefaultControls to become visible.  This should only be called after
     * {@link #setDefaultControlsEnabled(boolean)}.
     */
    public void showDefaultControls() {
        if (defaultControls != null) {
            defaultControls.show();

            if (isPlaying()) {
                defaultControls.hideDelayed(DefaultControls.DEFAULT_CONTROL_HIDE_DELAY);
            }
        }
    }

    /**
     * Retrieves the default controls being used by this view.
     * If the default controls haven't been enabled with {@link #setDefaultControlsEnabled(boolean)}
     * or through the XML attribute <code>defaultControlsEnabled</code> this will return
     * null
     *
     * @return The default controls being used by this view or null
     */
    @Nullable
    public DefaultControls getDefaultControls() {
        return defaultControls;
    }

    /**
     * *************************************
     * Start of the standard VideoView APIs *
     * **************************************
     */

    /**
     * Sets the Uri location for the video to play.  If the media format cannot be determine
     * MP4 will be assumed.  You can also manually specify the media format with
     * {@link #setVideoURI(Uri, MediaType)}
     *
     * @param uri The video's Uri
     */
    public void setVideoURI(Uri uri) {
        setVideoURI(uri, MediaType.MP4);
    }

    /**
     * Sets the Uri location for the video to play
     *
     * @param uri              The video's Uri
     * @param defaultMediaType The MediaType to use when auto-detection fails
     */
    public void setVideoURI(Uri uri, MediaType defaultMediaType) {
        RenderBuilder builder = null;
        if(uri != null) {
            builder = getRendererBuilder(MediaSourceType.get(uri), uri, defaultMediaType);
        }

        setVideoURI(uri, builder);
    }

    /**
     * Sets the Uri location for the video to play
     *
     * @param uri              The video's Uri
     * @param renderBuilder    RenderBuilder that should be used
     */
    public void setVideoURI(Uri uri, RenderBuilder renderBuilder) {
        videoUri = uri;

        if (!useExo) {
            videoView.setVideoURI(uri);
        } else {
            if (uri == null) {
                emExoPlayer.replaceRenderBuilder(null);
            } else {
                emExoPlayer.replaceRenderBuilder(renderBuilder);
                listenerMux.setNotifiedCompleted(false);
            }

            //Makes sure the listeners get the onPrepared callback
            listenerMux.setNotifiedPrepared(false);
            emExoPlayer.seekTo(0);
        }

        if (defaultControls != null) {
            defaultControls.restartLoading();
        }
    }

    /**
     * Sets the path to the video.  This path can be a web address (e.g. http://) or
     * an absolute local path (e.g. file://)
     *
     * @param path The path to the video
     */
    public void setVideoPath(String path) {
        setVideoURI(Uri.parse(path));
    }

    /**
     * Retrieves the current Video URI.  If this hasn't been set with {@link #setVideoURI(android.net.Uri)}
     * or {@link #setVideoPath(String)} then null will be returned.
     *
     * @return The current video URI or null
     */
    @Nullable
    public Uri getVideoUri() {
        return videoUri;
    }

    /**
     * Sets the volume level for devices that support
     * the ExoPlayer (JellyBean or greater).
     *
     * @param volume The volume range [0.0 - 1.0]
     * @return True if the volume was set
     */
    public boolean setVolume(float volume) {
        if (useExo) {
            emExoPlayer.setVolume(volume);
            return true;
        }

        return false;
    }

    /**
     * Stops the current video playback and resets the listener states
     * so that we receive the callbacks for events like onPrepared
     */
    public void reset() {
        stopPlayback();
        setVideoURI(null);
    }

    /**
     * Moves the current video progress to the specified location.
     *
     * @param milliSeconds The time to move the playback to
     */
    public void seekTo(int milliSeconds) {
        if (!useExo) {
            videoView.seekTo(milliSeconds);
        } else {
            emExoPlayer.seekTo(milliSeconds);
        }
    }

    /**
     * Returns if a video is currently in playback
     *
     * @return True if a video is playing
     */
    public boolean isPlaying() {
        if (!useExo) {
            return videoView.isPlaying();
        }

        return emExoPlayer.getPlayWhenReady();
    }

    /**
     * Starts the playback for the video specified in {@link #setVideoURI(android.net.Uri)}
     * or {@link #setVideoPath(String)}.  This should be called after the VideoView is correctly
     * prepared (see {@link #setOnPreparedListener(android.media.MediaPlayer.OnPreparedListener)})
     */
    public void start() {
        if (!useExo) {
            videoView.start();
        } else {
            emExoPlayer.setPlayWhenReady(true);
        }

        if (defaultControls != null) {
            defaultControls.updatePlayPauseImage(true);
            defaultControls.hideDelayed(DefaultControls.DEFAULT_CONTROL_HIDE_DELAY);
        }

        playRequested = true;

        listenerMux.setNotifiedCompleted(false);
    }

    /**
     * If a video is currently in playback, it will be paused
     */
    public void pause() {
        if (!useExo) {
            videoView.pause();
        } else {
            emExoPlayer.setPlayWhenReady(false);
        }

        if (defaultControls != null) {
            defaultControls.updatePlayPauseImage(false);
            defaultControls.show();
        }

        playRequested = false;
    }

    /**
     * If a video is currently in playback then the playback will be stopped
     */
    public void stopPlayback() {
        if (!useExo) {
            videoView.stopPlayback();
        } else {
            emExoPlayer.setPlayWhenReady(false);
        }

        if (defaultControls != null) {
            defaultControls.updatePlayPauseImage(false);
            defaultControls.show();
        }

        playRequested = false;
    }

    /**
     * If a video is currently in playback then the playback will be suspended
     */
    public void suspend() {
        if (!useExo) {
            videoView.suspend();
        } else {
            emExoPlayer.release();
        }

        if (defaultControls != null) {
            defaultControls.updatePlayPauseImage(false);
            defaultControls.show();
        }

        playRequested = false;
    }

    /**
     * Retrieves the duration of the current audio item.  This should only be called after
     * the item is prepared (see {@link #setOnPreparedListener(android.media.MediaPlayer.OnPreparedListener)}).
     * If {@link #overrideDuration(int)} is set then that value will be returned.
     *
     * @return The millisecond duration of the video
     */
    public long getDuration() {
        if (overriddenDuration >= 0) {
            return overriddenDuration;
        }

        if (!listenerMux.isPrepared()) {
            return 0;
        }

        if (!useExo) {
            return videoView.getDuration();
        }

        return emExoPlayer.getDuration();
    }

    /**
     * Setting this will override the duration that the item may actually be.  This method should
     * only be used when the item doesn't return the correct duration such as with audio streams.
     * This only overrides the current audio item.
     *
     * @param duration The duration for the current media item or &lt; 0 to disable
     */
    public void overrideDuration(int duration) {
        overriddenDuration = duration;
    }

    /**
     * Retrieves the current position of the audio playback.  If an audio item is not currently
     * in playback then the value will be 0.  This should only be called after the item is
     * prepared (see {@link #setOnPreparedListener(android.media.MediaPlayer.OnPreparedListener)})
     *
     * @return The millisecond value for the current position
     */
    public long getCurrentPosition() {
        if (overridePosition) {
            return positionOffset + overriddenPositionStopWatch.getTime();
        }

        if (!listenerMux.isPrepared()) {
            return 0;
        }

        if (!useExo) {
            return positionOffset + videoView.getCurrentPosition();
        }

        return positionOffset + emExoPlayer.getCurrentPosition();
    }

    /**
     * Sets the amount of time to change the return value from {@link #getCurrentPosition()}.
     * This value will be reset when a new audio item is selected.
     *
     * @param offset The millisecond value to offset the position
     */
    public void setPositionOffset(int offset) {
        positionOffset = offset;
    }

    /**
     * Restarts the audio position to the start if the position is being overridden (see {@link #overridePosition(boolean)}).
     * This will be the value specified with {@link #setPositionOffset(int)} or 0 if it hasn't been set.
     */
    public void restartOverridePosition() {
        overriddenPositionStopWatch.reset();
    }

    /**
     * Sets if the audio position should be overridden, allowing the time to be restarted at will.  This
     * is useful for streaming audio where the audio doesn't have breaks between songs.
     *
     * @param override True if the position should be overridden
     */
    public void overridePosition(boolean override) {
        if (override) {
            overriddenPositionStopWatch.start();
        } else {
            overriddenPositionStopWatch.stop();
        }

        overridePosition = override;
    }

    /**
     * Retrieves the current buffer percent of the video.  If a video is not currently
     * prepared or buffering the value will be 0.  This should only be called after the video is
     * prepared (see {@link #setOnPreparedListener(android.media.MediaPlayer.OnPreparedListener)})
     *
     * @return The integer percent that is buffered [0, 100] inclusive
     */
    public int getBufferPercentage() {
        if (!useExo) {
            return videoView.getBufferPercentage();
        }

        return emExoPlayer.getBufferedPercentage();
    }

    /**
     * Sets the listener to inform of any exoPlayer events
     *
     * @param listener The listener
     */
    public void addExoPlayerListener(ExoPlayerListener listener) {
        listenerMux.addExoPlayerListener(listener);
    }

    /**
     * Removes the specified listener for the ExoPlayer.
     *
     * @param listener The listener to remove
     */
    public void removeExoPlayerListener(ExoPlayerListener listener) {
        listenerMux.removeExoPlayerListener(listener);
    }


    /**
     * Sets the listener to inform of VideoPlayer prepared events
     *
     * @param listener The listener
     */
    public void setOnPreparedListener(MediaPlayer.OnPreparedListener listener) {
        listenerMux.setOnPreparedListener(listener);
    }

    /**
     * Sets the listener to inform of VideoPlayer completion events
     *
     * @param listener The listener
     */
    public void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
        listenerMux.setOnCompletionListener(listener);
    }

    /**
     * Sets the listener to inform of playback errors
     *
     * @param listener The listener
     */
    public void setOnErrorListener(MediaPlayer.OnErrorListener listener) {
        listenerMux.setOnErrorListener(listener);
    }

    /**
     * Sets the listener to inform of media information events.
     *
     * @param listener The listener
     */
    public void setOnInfoListener(MediaPlayer.OnInfoListener listener) {
        listenerMux.setOnInfoListener(listener);
    }

    /**
     * Performs the functionality to stop the progress polling, and stop any other
     * procedures from running that we no longer need.
     */
    private void onPlaybackEnded() {
        stopPlayback();
        pollRepeater.stop();
    }

    private class MuxNotifier extends EMListenerMux.EMListenerMuxNotifier {
        @Override
        public boolean shouldNotifyCompletion(long endLeeway) {
            return getCurrentPosition() + endLeeway >= getDuration();
        }

        @Override
        public void onExoPlayerError(Exception e) {
            if (emExoPlayer != null) {
                emExoPlayer.forcePrepare();
            }
        }

        @Override
        public void onMediaPlaybackEnded() {
            onPlaybackEnded();
        }

        @Override
        public void onVideoSizeChanged(int width, int height, float pixelWidthHeightRatio) {
            //Makes sure we have the correct aspect ratio
            float videoAspectRatio = height == 0 ? 1 : (width * pixelWidthHeightRatio) / height;
            exoVideoTextureView.setAspectRatio(videoAspectRatio);

            //Since the ExoPlayer will occasionally return an unscaled video size, we will make sure
            // we are using scaled values when updating the shutters
            if (width < getWidth() && height < getHeight()) {
                width = getWidth();
                height = (int)(width / videoAspectRatio);
                if (height > getHeight()) {
                    height = getHeight();
                    width = (int)(height * videoAspectRatio);
                }
            }

            updateVideoShutters(getWidth(), getHeight(), width, height);
        }

        @Override
        public void onPrepared() {
            if (defaultControls != null) {
                defaultControls.setDuration(getDuration());
                defaultControls.loadCompleted();
            }
        }

        @Override
        public void onPreviewImageStateChanged(boolean toVisible) {
            if (previewImageView != null) {
                previewImageView.setVisibility(toVisible ? View.VISIBLE : View.GONE);
            }
        }

        public void updateVideoShutters(int viewWidth, int viewHeight, int videoWidth, int videoHeight) {
            //Sets the horizontal shutter (top and bottom) sizes
            int shutterHeight = calculateVerticalShutterSize(viewHeight, videoHeight);
            if (shutterTop != null) {
                shutterTop.getLayoutParams().height = shutterHeight;
                shutterTop.requestLayout();
            }

            if (shutterBottom != null) {
                shutterBottom.getLayoutParams().height = shutterHeight;
                shutterBottom.requestLayout();
            }

            //Sets the vertical shutter (left and right) sizes
            int shutterWidth = calculateSideShutterSize(viewWidth, videoWidth);
            if (shutterLeft != null) {
                shutterLeft.getLayoutParams().width = shutterWidth;
                shutterLeft.requestLayout();
            }

            if (shutterRight != null) {
                shutterRight.getLayoutParams().width = shutterWidth;
                shutterRight.requestLayout();
            }
        }

        private int calculateVerticalShutterSize(int viewHeight, int videoHeight) {
            int shutterSize = (viewHeight - videoHeight) / 2;
            return (viewHeight - videoHeight) % 2 == 0 ? shutterSize : shutterSize +1;
        }

        private int calculateSideShutterSize(int viewWidth, int videoWidth) {
            int shutterSize = (viewWidth - videoWidth) / 2;
            return (viewWidth - videoWidth) % 2 == 0 ? shutterSize : shutterSize +1;
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private class EMExoVideoSurfaceTextureListener implements TextureView.SurfaceTextureListener {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            if (emExoPlayer != null) {
                emExoPlayer.setSurface(new Surface(surface));
                if (playRequested) {
                    emExoPlayer.setPlayWhenReady(true);
                }
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Purposefully left blank
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            if (emExoPlayer != null) {
                emExoPlayer.blockingClearSurface();
            }

            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            // Purposefully left blank
        }
    }

    /**
     * Monitors the view click events to show the default controls if they are enabled.
     */
    private class TouchListener extends GestureDetector.SimpleOnGestureListener implements OnTouchListener {
        private GestureDetector gestureDetector;

        public TouchListener(Context context) {
            gestureDetector = new GestureDetector(context, this);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            gestureDetector.onTouchEvent(event);
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (defaultControls != null) {
                defaultControls.show();

                if (isPlaying()) {
                    defaultControls.hideDelayed(DefaultControls.DEFAULT_CONTROL_HIDE_DELAY);
                }
            }

            return true;
        }
    }

    /**
     * Since the default Android VideoView will consume the touch events
     * without calling super
     */
    public static class TouchVideoView extends VideoView {
        private OnTouchListener touchListener;

        public TouchVideoView(Context context) {
            super(context);
        }

        public TouchVideoView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public TouchVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        public TouchVideoView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            boolean flag = false;
            if (touchListener != null) {
                flag = touchListener.onTouch(this, ev);
            }

            return flag || super.onTouchEvent(ev);
        }

        @Override
        public void setOnTouchListener(OnTouchListener listener) {
            touchListener = listener;
            super.setOnTouchListener(listener);
        }
    }
}
