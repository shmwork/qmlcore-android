package com.pureqml.android.runtime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextPaint;
import android.util.Log;
import android.util.TypedValue;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextRenderer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import com.eclipsesource.v8.V8;
import com.eclipsesource.v8.V8Array;
import com.eclipsesource.v8.V8Function;
import com.eclipsesource.v8.V8Object;
import com.pureqml.android.ComputedStyle;
import com.pureqml.android.IExecutionEnvironment;
import com.pureqml.android.IResource;
import com.pureqml.android.SafeRunnable;
import com.pureqml.android.TypeConverter;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static androidx.media3.common.C.TIME_UNSET;


public final class VideoPlayer extends BaseObject implements IResource {

    private static final class DeferredCallback implements SurfaceHolder.Callback {
        final Handler handler;
        final SurfaceHolder.Callback callback;

        DeferredCallback(Handler handler, SurfaceHolder.Callback callback) {
            this.handler = handler;
            this.callback = callback;
        }

        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            handler.post(new SafeRunnable() {
                @Override
                protected void doRun() {
                    callback.surfaceCreated(holder);
                }
            });
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            handler.post(new SafeRunnable() {
                @Override
                protected void doRun() {
                    callback.surfaceChanged(holder, format, width, height);
                }
            });
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            handler.post(new SafeRunnable() {
                @Override
                protected void doRun() {
                    callback.surfaceDestroyed(holder);
                }
            });
        }
    }

    private final static class DeferredSurfaceHolder implements SurfaceHolder {
        final Handler handler;
        final SurfaceHolder surfaceHolder;
        final Map<Callback, DeferredCallback> callbacks;

        DeferredSurfaceHolder(Handler handler, SurfaceHolder surfaceHolder) {
            this.handler = handler;
            this.surfaceHolder = surfaceHolder;
            this.callbacks = new HashMap<>();
        }

        @Override
        public void addCallback(Callback callback) {
            DeferredCallback deferredCallback = new DeferredCallback(handler, callback);
            synchronized (this) {
                callbacks.put(callback, deferredCallback);
            }
            surfaceHolder.addCallback(deferredCallback);
        }

        @Override
        public void removeCallback(Callback callback) {
            surfaceHolder.removeCallback(callback);
            DeferredCallback deferredCallback;
            synchronized (this) {
                deferredCallback = callbacks.get(callback);
            }
            surfaceHolder.removeCallback(deferredCallback);
        }

        @Override
        public boolean isCreating() {
            return surfaceHolder.isCreating();
        }

        @Deprecated
        @Override
        public void setType(int type) {
        }

        @Override
        public void setFixedSize(int width, int height) {
            surfaceHolder.setFixedSize(width, height);
        }

        @Override
        public void setSizeFromLayout() {
            surfaceHolder.setSizeFromLayout();
        }

        @Override
        public void setFormat(int format) {
            surfaceHolder.setFormat(format);
        }

        @Override
        public void setKeepScreenOn(boolean screenOn) {
            surfaceHolder.setKeepScreenOn(screenOn);
        }

        @Override
        public Canvas lockCanvas() {
            return surfaceHolder.lockCanvas();
        }

        @Override
        public Canvas lockCanvas(Rect dirty) {
            return surfaceHolder.lockCanvas(dirty);
        }

        @Override
        public void unlockCanvasAndPost(Canvas canvas) {
            surfaceHolder.unlockCanvasAndPost(canvas);
        }

        @Override
        public Rect getSurfaceFrame() {
            return surfaceHolder.getSurfaceFrame();
        }

        @Override
        public Surface getSurface() {
            return surfaceHolder.getSurface();
        }
    }

    @UnstableApi
    private class CustomTextOutput implements TextOutput {
        public CustomTextOutput() {
        }

        @Override
        public void onCues(@NonNull CueGroup cueGroup) {
            if (paintDelegate != null)
                paintDelegate.setCue(cueGroup);
        }
    }

    @UnstableApi
    private static class CustomRenderersFactory extends DefaultRenderersFactory {
        private final TextOutput customTextOutput;

        public CustomRenderersFactory(Context context, TextOutput textOutput) {
            super(context);
            this.customTextOutput = textOutput;
        }

        @Override
        protected void buildTextRenderers(@NonNull Context context, @NonNull TextOutput output, @NonNull Looper outputLooper, int extensionRendererMode, ArrayList<Renderer> out) {
            out.add(new TextRenderer(customTextOutput, outputLooper));
        }
    }

    private static final String TAG = "VideoPlayer";
    private static final int PollingInterval = 500; //ms
    private static final Object INSTANCES_LOCK = new Object();
    private static final List<WeakReference<VideoPlayer>> INSTANCES = new ArrayList<>();
    private static volatile boolean preferSoftwareDecoder = false;
    private static volatile boolean softwareOutputActive = false;
    private static final boolean NEEDS_SOFTWARE_UI_OVERLAY = deviceNeedsSoftwareUiOverlay();
    private volatile boolean usingSoftwareDecoder = false;
    private volatile boolean recreatingPlayer = false;

    private ExoPlayer player;
    private final SurfaceView           surfaceView;
    private final ViewHolder<?>         viewHolder;
    private final HandlerThread         playerThread;
    private final Handler               handler;
    private final Timeline.Period       period;

    //this is persistent state
    private Rect                        rect;
    private int                         videoWidth = 0;
    private int                         videoHeight = 0;
    private String                      source;
    private volatile boolean            playerVisible = true;
    private boolean                     autoplay = false;
    private boolean                     paused = false;
    private volatile boolean            stopped = false;
    private Runnable                    pollingTask = null;
    private int                         pollingGeneration = 0;

    private Tracks                      tracks = null;
    private long                        lastEmittedDurationMs = TIME_UNSET;
    private boolean                     audioDisabledDueToTrackLimit = false;
    private int                         audioRecoverAttempts = 0;
    private boolean                     recoveringAudio = false;

    //exoplayer flags
    private int                         hlsExtractorFlags = 0;
    private boolean                     exposeCea608WhenMissingDeclarations = true;
    private final static float          defaultTextSizeSP = 22;

    private static class PaintDelegate implements Element.PaintDelegate {
        final Context context;
        final Element ui;
        CueGroup cueGroup;

        PaintDelegate(Context context, Element ui) {
            this.context = context;
            this.ui = ui;
        }

        @Override
        public void paint(PaintState state) {
            if (cueGroup == null || cueGroup.cues.isEmpty())
                return;
            Rect rect = ui.getRect();
            float textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    defaultTextSizeSP, context.getResources().getDisplayMetrics());
            float lineHeight = textSize * ComputedStyle.DefaultLineHeight;
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.LINEAR_TEXT_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            paint.setTextSize(textSize);
            for(Cue cue : cueGroup.cues) {
                if (cue.text == null && cue.bitmap == null)
                    continue;

                String[] text = cue.text != null? cue.text.toString().split("\n"): new String[0];
                float anchorPos;
                if (cue.position != Cue.DIMEN_UNSET) {
                    anchorPos = rect.left + cue.position * rect.width();
                } else {
                    switch(cue.positionAnchor) {
                        case Cue.ANCHOR_TYPE_START:
                            anchorPos = rect.left;
                            break;
                        case Cue.ANCHOR_TYPE_END:
                            anchorPos = rect.right;
                            break;
                        case Cue.ANCHOR_TYPE_MIDDLE:
                        case Cue.TYPE_UNSET:
                        default:
                            anchorPos = rect.left + rect.width() / 2.0f;
                            break;
                    }
                }

                float linePos;
                switch(cue.lineAnchor) {
                    case Cue.ANCHOR_TYPE_START:
                        linePos = rect.top;
                        break;
                    case Cue.ANCHOR_TYPE_END:
                        linePos = rect.bottom;
                        break;
                    case Cue.ANCHOR_TYPE_MIDDLE:
                    case Cue.TYPE_UNSET:
                    default:
                        linePos = rect.top + rect.height() / 2.0f;
                        break;
                }

                switch(cue.lineType) {
                    case Cue.LINE_TYPE_FRACTION: {
                        linePos += cue.line * rect.height();
                        break;
                    }
                    case Cue.LINE_TYPE_NUMBER: {
                        int lineCount = Math.round(rect.height() / lineHeight);
                        int lineIdx = (int)cue.line;
                        if (lineIdx < 0) {
                            lineIdx += lineCount;
                            // HLS subtitles often have line number -1 (last)
                            // Correct it so the last line would be the last one on the screen.
                            if (text.length > 1)
                                lineIdx -= text.length - 1;
                        }
                        linePos = rect.top + (float)(lineIdx * rect.height()) / lineCount;
                        break;
                    }
                    case Cue.TYPE_UNSET:
                        break;
                }

                // vertical layouts are not supported
                float x = anchorPos;
                float y = linePos;

                if (cue.text != null) {
                    if (cue.textAlignment != null) {
                        switch (cue.textAlignment) {
                            case ALIGN_NORMAL:
                                paint.setTextAlign(Paint.Align.LEFT);
                                break;
                            case ALIGN_OPPOSITE:
                                paint.setTextAlign(Paint.Align.RIGHT);
                                break;
                            case ALIGN_CENTER:
                            default:
                                paint.setTextAlign(Paint.Align.CENTER);
                                break;
                        }
                    }
                    for(String line : text) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(Color.BLACK);
                        state.drawText(line, x, y, paint);
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(Color.WHITE);
                        state.drawText(line, x, y, paint);
                        y += lineHeight;
                    }
                } else {
                    Rect dstRect = new Rect((int)x, (int)y, (int)(x + cue.bitmap.getWidth()), (int)(y + cue.bitmap.getHeight()));
                    state.drawBitmap(cue.bitmap, null, dstRect, paint);
                }
            }
        }

        void setCue(@NonNull CueGroup cueGroup) {
            Log.v(TAG, "onCues " + cueGroup.cues.size());
            this.cueGroup = cueGroup;
            ui.update();
        }
    }
    PaintDelegate                       paintDelegate;

    public VideoPlayer(IExecutionEnvironment env, Element ui) {
        super(env);

        if (ui != null) {
            paintDelegate = new PaintDelegate(env.getContext(), ui);
            ui.setPaintDelegate(paintDelegate);
        }

        playerThread = new HandlerThread("VideoPlayer");
        playerThread.start();
        handler = new Handler(playerThread.getLooper());

        Context context = env.getContext();
        surfaceView = new SurfaceView(context);
        viewHolder = new ViewHolder<>(surfaceView);

        period = new Timeline.Period();

        _env.register(this);
        synchronized (INSTANCES_LOCK) {
            INSTANCES.add(new WeakReference<>(this));
        }

        acquireResource();
    }

    public static void setSoftwareDecoder(boolean enable) {
        Log.i(TAG, "setSoftwareDecoder " + enable);
        if (preferSoftwareDecoder == enable)
            return;
        preferSoftwareDecoder = enable;

        List<VideoPlayer> players = new ArrayList<>();
        synchronized (INSTANCES_LOCK) {
            Iterator<WeakReference<VideoPlayer>> it = INSTANCES.iterator();
            while (it.hasNext()) {
                VideoPlayer instance = it.next().get();
                if (instance == null)
                    it.remove();
                else
                    players.add(instance);
            }
        }
        for (VideoPlayer instance : players)
            instance.scheduleDecoderModeUpdate();
    }

    public static boolean isSoftwareDecoderRendering() {
        return softwareOutputActive && NEEDS_SOFTWARE_UI_OVERLAY;
    }

    private static boolean deviceNeedsSoftwareUiOverlay() {
        String identity = (Build.HARDWARE + " " + Build.BOARD + " " + Build.MANUFACTURER
                + " " + Build.DEVICE + " " + Build.PRODUCT).toLowerCase();
        return identity.contains("meson") || identity.contains("amlogic");
    }

    private void updateSoftwareOutputActive() {
        if (recreatingPlayer)
            return;
        boolean next = false;
        synchronized (INSTANCES_LOCK) {
            Iterator<WeakReference<VideoPlayer>> it = INSTANCES.iterator();
            while (it.hasNext()) {
                VideoPlayer instance = it.next().get();
                if (instance == null) {
                    it.remove();
                    continue;
                }
                if (instance.usingSoftwareDecoder
                        && instance.player != null
                        && instance.playerVisible
                        && !instance.stopped
                        && !instance.recreatingPlayer)
                    next = true;
            }
        }
        if (softwareOutputActive == next)
            return;
        softwareOutputActive = next;
        Log.i(TAG, "software decoder output active=" + next
                + ", uiOverlay=" + NEEDS_SOFTWARE_UI_OVERLAY);
        if (_env != null && !next)
            _env.requestRepaint();
    }

    public static boolean isSoftwareDecoder() {
        return preferSoftwareDecoder;
    }

    private boolean isOnPlayerThread() {
        return Looper.myLooper() == handler.getLooper();
    }

    private static boolean isVodUrl(String url) {
        if (url == null || url.isEmpty())
            return false;
        String value = url.toLowerCase();
        return value.contains("/vod/") || value.contains("vod:") || value.contains("hls-vod");
    }

    private boolean isVodSource() {
        if (isVodUrl(source))
            return true;
        if (player == null || !isOnPlayerThread())
            return false;
        try {
            if (player.isCurrentMediaItemLive())
                return false;
            long duration = player.getDuration();
            return duration != TIME_UNSET && duration > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isPlaceholderUrl(String url) {
        if (url == null || url.isEmpty())
            return true;
        String value = url.toLowerCase();
        return value.contains("black.mp4")
                || value.startsWith("asset://")
                || value.startsWith("android.resource://")
                || value.startsWith("res/");
    }

    private boolean isPlaceholderSource() {
        return isPlaceholderUrl(source);
    }

    private boolean shouldUseSoftwareDecoder(int width, int height) {
        if (!preferSoftwareDecoder || isVodSource())
            return false;
        if (width <= 0 || height <= 0)
            return usingSoftwareDecoder;
        return width <= 1280 && height <= 720;
    }

    private boolean shouldCreateWithSoftwareDecoder() {
        if (!preferSoftwareDecoder || isVodUrl(source) || isPlaceholderSource())
            return false;
        if (videoWidth > 0 && videoHeight > 0)
            return videoWidth <= 1280 && videoHeight <= 720;
        return usingSoftwareDecoder;
    }

    private Format getHighestAvailableVideoFormat() {
        if (tracks == null)
            return null;
        Format highest = null;
        int highestPixels = 0;
        for (Tracks.Group trackGroup : tracks.getGroups()) {
            if (trackGroup.getType() != C.TRACK_TYPE_VIDEO)
                continue;
            for (int i = 0; i < trackGroup.length; i++) {
                Format format = trackGroup.getTrackFormat(i);
                if (format.width <= 0 || format.height <= 0)
                    continue;
                int pixels = format.width * format.height;
                if (pixels > highestPixels) {
                    highestPixels = pixels;
                    highest = format;
                }
            }
        }
        return highest;
    }

    private int[] getDecisionVideoSize() {
        int width = 0;
        int height = 0;
        Format highest = getHighestAvailableVideoFormat();
        if (highest != null && highest.width > 0 && highest.height > 0) {
            width = highest.width;
            height = highest.height;
        }
        if (videoWidth > 0 && videoHeight > 0 && videoWidth * videoHeight > width * height) {
            width = videoWidth;
            height = videoHeight;
        }
        return new int[] { width, height };
    }

    private void scheduleDecoderModeUpdate() {
        handler.post(new SafeRunnable() {
            @Override
            protected void doRun() {
                applyDecoderModeIfNeeded();
            }
        });
    }

    private void applyDecoderModeIfNeeded() {
        if (!isOnPlayerThread()) {
            scheduleDecoderModeUpdate();
            return;
        }
        if (player == null || recreatingPlayer)
            return;

        if (!preferSoftwareDecoder) {
            if (usingSoftwareDecoder)
                recreateWithDecoderMode(false);
            return;
        }
        if (isPlaceholderSource())
            return;

        int[] size = getDecisionVideoSize();
        int width = size[0];
        int height = size[1];
        if (width <= 0 || height <= 0)
            return;

        boolean shouldUseSoftware = shouldUseSoftwareDecoder(width, height);
        Log.i(TAG, "applyDecoderModeIfNeeded: format="
                + width + "x" + height
                + ", vod=" + isVodSource()
                + ", software=" + shouldUseSoftware
                + ", current=" + usingSoftwareDecoder);
        if (shouldUseSoftware == usingSoftwareDecoder)
            return;
        recreateWithDecoderMode(shouldUseSoftware);
    }

    private void recreateWithDecoderMode(boolean software) {
        if (!isOnPlayerThread()) {
            handler.post(new SafeRunnable() {
                @Override
                protected void doRun() {
                    recreateWithDecoderMode(software);
                }
            });
            return;
        }
        if (player == null || recreatingPlayer)
            return;

        final String sourceAtRecreate = source;
        final long positionMs;
        final boolean shouldPlay;
        try {
            positionMs = player.getCurrentPosition();
            shouldPlay = player.getPlayWhenReady();
        } catch (RuntimeException e) {
            Log.w(TAG, "recreateWithDecoderMode: cannot read player state", e);
            return;
        }

        recreatingPlayer = true;
        paused = !shouldPlay;
        usingSoftwareDecoder = software;
        try {
            releaseResourceImpl();
            acquireResourceImpl();
        } catch (RuntimeException e) {
            recreatingPlayer = false;
            Log.e(TAG, "recreateWithDecoderMode failed", e);
            return;
        }
        handler.post(new SafeRunnable() {
            @Override
            protected void doRun() {
                recreatingPlayer = false;
                if (stopped) {
                    releasePlayerKeepingDecoderMode();
                    return;
                }
                updateSoftwareOutputActive();
                if (player == null)
                    return;
                boolean sameSource = sourceAtRecreate != null && sourceAtRecreate.equals(source);
                boolean live = source != null && source.contains(".m3u8") && !isVodUrl(source);
                if (sameSource && !live && positionMs > 1000)
                    player.seekTo(positionMs);
                player.setPlayWhenReady(shouldPlay);
            }
        });
    }

    public void emit(String name, Object ... args) {
        ExecutorService executor = _env.getExecutor();
        if (executor == null) {
            Log.w(TAG, "no executor, skipping event " + name);
            return;
        }
        executor.execute(new SafeRunnable() {
            @Override
            public void doRun() {
                VideoPlayer.this.emit(null, name, args);
            }
        });
    }

    private void pollPosition() {
        ExoPlayer player = this.player;
        if (player == null)
            return;
        if (!isOnPlayerThread()) {
            handler.post(new SafeRunnable() {
                @Override
                protected void doRun() {
                    pollPosition();
                }
            });
            return;
        }

        int playbackState = player.getPlaybackState();
        if (stopped || playbackState == Player.STATE_IDLE)
            return;

        long position = player.getCurrentPosition();
        Timeline currentTimeline = player.getCurrentTimeline();
        if (!currentTimeline.isEmpty()) {
            position -= currentTimeline.getPeriod(player.getCurrentPeriodIndex(), period)
                    .getPositionInWindowMs();
        }
        final long duration = player.getDuration();
        if (duration == TIME_UNSET)
            return;
        Log.v(TAG, "emitting position " + position + " / " + duration);
        emit("timeupdate", position / 1000.0);
        if (duration != lastEmittedDurationMs) {
            lastEmittedDurationMs = duration;
            emit("durationchange", duration / 1000.0);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private static boolean isAudioSinkInitFailure(PlaybackException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof AudioSink.InitializationException)
                return true;
            cause = cause.getCause();
        }
        return false;
    }

    @OptIn(markerClass = UnstableApi.class)
    private static boolean isBehindLiveWindow(PlaybackException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof BehindLiveWindowException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void acquireResourceImpl() {
        acquireResourceImpl(true);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void acquireResourceImpl(boolean loadSource) {
        if (player != null)
            return;
        if (stopped && loadSource)
            return;

        if (!recreatingPlayer)
            usingSoftwareDecoder = shouldCreateWithSoftwareDecoder();

        Context context = _env.getContext();
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBufferDurationsMs(1000, 50000, 1000, 1000)
                .build();

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
        trackSelector.setParameters(
                trackSelector.buildUponParameters()
                        .setExceedRendererCapabilitiesIfNecessary(true)
                        .setAllowVideoMixedMimeTypeAdaptiveness(true)
                        .setAllowAudioMixedMimeTypeAdaptiveness(true)
                        .setAllowVideoNonSeamlessAdaptiveness(true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, /* disabled= */ true)
        );

        CustomRenderersFactory renderersFactory = new CustomRenderersFactory(context, new CustomTextOutput());
        renderersFactory.setMediaCodecSelector(
                usingSoftwareDecoder
                        ? (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
                            if (mimeType != null && mimeType.startsWith("video/")) {
                                return MediaCodecSelector.PREFER_SOFTWARE.getDecoderInfos(
                                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                            }
                            return MediaCodecSelector.DEFAULT.getDecoderInfos(
                                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                        }
                        : MediaCodecSelector.DEFAULT);
        renderersFactory.setEnableDecoderFallback(true);
        Log.i(TAG, "creating ExoPlayer, softwareDecoder=" + usingSoftwareDecoder);

        player = new ExoPlayer.Builder(context)
                .setTrackSelector(trackSelector)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setLooper(handler.getLooper())
                .build();

        player.setVideoSurfaceHolder(
                new DeferredSurfaceHolder(
                        new Handler(handler.getLooper()),
                        surfaceView.getHolder()));

        player.addListener(new Player.Listener() {
            @Override
            public void onIsLoadingChanged(boolean isLoading) {
                Log.d(TAG, "onLoadingChanged " + isLoading);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Log.d(TAG, "onPlayerStateChanged " + playbackState);
                VideoPlayer.this.emit("stateChanged", playbackState);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.d(TAG, "onPlayerError " + error);
                if (isBehindLiveWindow(error)) {
                    VideoPlayer.this.emit("error", error.toString());
                    handler.post(new SafeRunnable() {
                        @Override
                        protected void doRun() {
                            Log.i(TAG, "restarting player");
                            releaseResourceImpl();
                            acquireResourceImpl();
                        }
                    });
                    return;
                }
                if (isAudioSinkInitFailure(error)) {
                    if (audioDisabledDueToTrackLimit || recoveringAudio || recreatingPlayer)
                        return;
                    recoveringAudio = true;
                    handler.post(new SafeRunnable() {
                        @Override
                        protected void doRun() {
                            try {
                                recoverFromAudioTrackFailure();
                            } finally {
                                recoveringAudio = false;
                            }
                        }
                    });
                    return;
                }
                VideoPlayer.this.emit("error", error.toString());
            }

            @Override
            public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
                Log.d(TAG, "onPositionDiscontinuity " + reason);
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    Log.d(TAG, "onSeekProcessed");
                    VideoPlayer.this.emit("seeked");
                }
            }

            @Override
            public void onPlaybackParametersChanged(@NonNull PlaybackParameters playbackParameters) {
                Log.d(TAG, "onPlaybackParametersChanged " + playbackParameters);
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                Log.v(TAG, "onIsPlayingChanged " + isPlaying);
                VideoPlayer.this.emit("pause", !isPlaying);
                paused = !isPlaying;
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                Log.v(TAG, "onVideoSizeChanged " + videoSize.width + "x" + videoSize.height + ", par: " + videoSize.pixelWidthHeightRatio);
                videoWidth = (int)(videoSize.width * videoSize.pixelWidthHeightRatio);
                videoHeight = videoSize.height;
                handler.post(new SafeRunnable() {
                    @Override
                    public void doRun() {
                        updateGeometry();
                        applyDecoderModeIfNeeded();
                    }});
            }

            @Override
            public void onSurfaceSizeChanged(int width, int height) {
                Log.v(TAG, "onSurfaceSizeChanged " + width + "x" + height);
            }

            @Override
            public void onRenderedFirstFrame() {
                Log.v(TAG, "onRenderedFirstFrame");
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                Log.v(TAG, "onTracksChanged");
                int groupIdx = 0;
                for (Tracks.Group trackGroup : tracks.getGroups()) {
                    Log.d(TAG, "TrackGroup type: " + trackGroup.getType() + ", id: " + trackGroup.getMediaTrackGroup().id);
                    for (int i = 0; i < trackGroup.length; i++) {
                        // Individual track information.
                        boolean isSupported = trackGroup.isTrackSupported(i);
                        boolean isSelected = trackGroup.isTrackSelected(i);
                        Format trackFormat = trackGroup.getTrackFormat(i);
                        Log.d(TAG, "track[" + groupIdx + "." + i + "]: supported: " + isSupported +", selected: " + isSelected + ", format: " + trackFormat);
                    }
                    ++groupIdx;
                }
                VideoPlayer.this.tracks = tracks;
                applyDecoderModeIfNeeded();
            }
        });

        updateGeometry();
        setVisibility(playerVisible);
        player.setPlayWhenReady(autoplay);
        if (loadSource && source != null)
            applySourceOnPlayerThread(source);

        final int generation = ++pollingGeneration;
        pollingTask = new SafeRunnable() {
            @Override
            protected void doRun() {
                if (generation != pollingGeneration)
                    return;
                VideoPlayer.this.pollPosition();
                if (generation == pollingGeneration)
                    handler.postDelayed(this, PollingInterval);
            }
        };
        handler.postDelayed(pollingTask, PollingInterval);
        updateSoftwareOutputActive();
    }

    private void releasePlayerInstance() {
        if (player == null)
            return;
        try {
            player.setPlayWhenReady(false);
            player.stop();
            player.clearMediaItems();
        } catch (RuntimeException e) {
            Log.w(TAG, "player stop/clear failed", e);
        }
        try {
            player.setVideoSurfaceHolder(null);
        } catch (RuntimeException e) {
            Log.w(TAG, "clear video surface failed", e);
        }
        player.release();
        player = null;
    }

    // Release ExoPlayer so AudioFlinger returns AudioTrack slots. Keep decoder size/mode.
    private void releasePlayerKeepingDecoderMode() {
        pollingGeneration++;
        if (pollingTask != null) {
            handler.removeCallbacks(pollingTask);
            pollingTask = null;
        }
        audioDisabledDueToTrackLimit = false;
        tracks = null;
        lastEmittedDurationMs = TIME_UNSET;
        if (player != null)
            Log.i(TAG, "releasing player to free AudioTrack");
        releasePlayerInstance();
        updateSoftwareOutputActive();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void recoverFromAudioTrackFailure() {
        if (stopped || recreatingPlayer)
            return;
        if (audioRecoverAttempts < 1) {
            Log.w(TAG, "AudioTrack init failed, recreating player to free audio tracks");
            audioRecoverAttempts++;
            boolean play = !paused;
            releasePlayerKeepingDecoderMode();
            stopped = false;
            acquireResourceImpl(true);
            if (player == null)
                return;
            PlaybackException pending = player.getPlayerError();
            if (pending == null || !isAudioSinkInitFailure(pending)) {
                player.setPlayWhenReady(play);
                return;
            }
        }
        if (player == null || audioDisabledDueToTrackLimit)
            return;
        Log.w(TAG, "AudioTrack init failed, continuing without audio");
        audioDisabledDueToTrackLimit = true;
        try {
            player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters()
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .build());
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (RuntimeException e) {
            Log.e(TAG, "failed to recover from AudioTrack error", e);
        }
    }

    private void releaseResourceImpl() {
        pollingGeneration++;
        if (pollingTask != null) {
            handler.removeCallbacks(pollingTask);
            pollingTask = null;
        }
        if (player != null) {
            releasePlayerInstance();
            if (!recreatingPlayer) {
                videoWidth = 0;
                videoHeight = 0;
                usingSoftwareDecoder = false;
                tracks = null;
                lastEmittedDurationMs = TIME_UNSET;
                audioDisabledDueToTrackLimit = false;
                audioRecoverAttempts = 0;
            }
        }
        updateSoftwareOutputActive();
    }

    public void setupDrm(String type, V8Object options, V8Function callback, V8Function error) {
        Log.i(TAG, "Player.SetupDRM " + type);
    }

    public void stop() {
        Log.i(TAG, "Player.stop");
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                paused = true;
                stopped = true;
                VideoPlayer.this.emit("pause", true);
                if (!recreatingPlayer)
                    releasePlayerKeepingDecoderMode();
            }
        });
    }

    @OptIn(markerClass = {UnstableApi.class, UnstableApi.class})
    public void setSource(String url) {
        Log.i(TAG, "Player.setSource " + url);
        source = url;
        if (isOnPlayerThread()) {
            applySourceOnPlayerThread(url);
        } else {
            handler.post(new SafeRunnable() {
                @Override
                protected void doRun() {
                    applySourceOnPlayerThread(url);
                }
            });
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void applySourceOnPlayerThread(String url) {
        if (url == null || url.isEmpty() || isPlaceholderUrl(url)) {
            paused = true;
            stopped = true;
            if (!recreatingPlayer)
                releasePlayerKeepingDecoderMode();
            return;
        }

        if (!recoveringAudio)
            audioRecoverAttempts = 0;

        if (player != null && !recreatingPlayer && !recoveringAudio)
            releasePlayerKeepingDecoderMode();

        if (player == null)
            acquireResourceImpl(false);

        if (player == null)
            return;

        if (usingSoftwareDecoder && isVodUrl(url)) {
            recreateWithDecoderMode(false);
            return;
        }

        stopped = false;
        if (audioDisabledDueToTrackLimit) {
            audioDisabledDueToTrackLimit = false;
            try {
                player.setTrackSelectionParameters(
                        player.getTrackSelectionParameters()
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .build());
            } catch (RuntimeException e) {
                Log.w(TAG, "re-enable audio failed", e);
            }
        }

        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(_env.getContext());
        BaseMediaSource mediaSource;
        if (url.contains(".m3u8")) { //FIXME: add proper content type here
            HlsMediaSource.Factory factory = new HlsMediaSource.Factory(dataSourceFactory);
            factory.setExtractorFactory(new DefaultHlsExtractorFactory(hlsExtractorFlags, exposeCea608WhenMissingDeclarations))
                    .setAllowChunklessPreparation(true);
            mediaSource = factory.createMediaSource(MediaItem.fromUri(Uri.parse(url)));
        } else {
            ProgressiveMediaSource.Factory factory = new ProgressiveMediaSource.Factory(dataSourceFactory);
            mediaSource = factory.createMediaSource(MediaItem.fromUri(Uri.parse(url)));
        }

        mediaSource.addEventListener(handler, new MediaSourceEventListener() {
            @Override
            public void onLoadError(int windowIndex, @Nullable MediaSource.MediaPeriodId mediaPeriodId, @NonNull LoadEventInfo loadEventInfo, @NonNull MediaLoadData mediaLoadData, @NonNull IOException error, boolean wasCanceled) {
                Log.w(TAG, "onLoadError");
            }
        });

        player.setMediaSource(mediaSource, true);
        player.prepare();
        updateSoftwareOutputActive();
        Log.i(TAG, "Player.setSource exited");
    }

    public void setLoop(boolean loop) {
        Log.i(TAG, "Player.setLoop " + loop);
    }

    public void setBackgroundColor(String color) {
        Log.i(TAG, "Player.setBackground " + color);
    }

    public void play() {
        Log.i(TAG, "Player.play");
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                if (stopped) {
                    stopped = false;
                    if (source != null)
                        applySourceOnPlayerThread(source);
                    paused = false;
                    VideoPlayer.this.emit("pause", false);
                    if (player != null)
                        player.setPlayWhenReady(true);
                    updateSoftwareOutputActive();
                    return;
                }
                if (paused) {
                    paused = false;
                    VideoPlayer.this.emit("pause", false);
                    if (player != null)
                        player.setPlayWhenReady(true);
                    updateSoftwareOutputActive();
                } else {
                    Log.i(TAG, "ignoring play on non-paused stream");
                }
            }
        });
    }

    public void pause() {
        Log.i(TAG, "Player.pause");
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                if (!paused)
                {
                    paused = true;
                    VideoPlayer.this.emit("pause", true);
                    if (player != null)
                        player.setPlayWhenReady(false);
                }
                else
                    Log.i(TAG, "ignoring pause on paused stream");
            }
        });
    }

    public void seek(int pos) {
        Log.i(TAG, "Player.seek " + pos);
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                if (player == null)
                    return;
                long newPos = player.getCurrentPosition() + pos * 1000L;
                VideoPlayer.this.emit("timeupdate", newPos / 1000.0);
                player.seekTo(newPos);
            }
        });
    }

    public void seekTo(int pos) {
        Log.i(TAG, "Player.seekTo " + pos);
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                //FIXME: save position if resources reacquired
                VideoPlayer.this.emit("timeupdate", pos);

                if (player != null)
                    player.seekTo(pos * 1000L);
            }
        });
    }

    public void setOption(String name, Object value) {
        Log.i(TAG, "Player.setOption " + name + " : " + value);
        handler.post(new SafeRunnable() {
            @OptIn(markerClass = UnstableApi.class)
            @Override
            public void doRun() {
                switch (name) {
                    case "autoplay":
                        autoplay = TypeConverter.toBoolean(value);
                        if (player != null)
                            player.setPlayWhenReady(autoplay);
                        break;
                    case "detectAccessUnits":
                        setHlsExtractorFlag(DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS, TypeConverter.toBoolean(value));
                        break;
                    case "allowNonIdrKeyframes":
                        setHlsExtractorFlag(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, TypeConverter.toBoolean(value));
                        break;
                    case "enableHdmvDtsAudioStreams":
                        setHlsExtractorFlag(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS, TypeConverter.toBoolean(value));
                        break;
                    case "ignoreAacStream":
                        setHlsExtractorFlag(DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM, TypeConverter.toBoolean(value));
                        break;
                    case "ignoreH264Stream":
                        setHlsExtractorFlag(DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM, TypeConverter.toBoolean(value));
                        break;
                    case "ignoreSpliceInfoStream":
                        setHlsExtractorFlag(DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM, TypeConverter.toBoolean(value));
                        break;
                    case "exposeCea608WhenMissingDeclarations":
                        exposeCea608WhenMissingDeclarations = TypeConverter.toBoolean(value);
                        break;
                    default:
                        Log.w(TAG, "ignoring option " + name);
                        break;
                }
            }
        });
    }

    public Object getVideoTracks() {
        return new V8Array(_env.getRuntime());
    }

    public Object getAudioTracks() {
        return new V8Array(_env.getRuntime());
    }

    public void setAudioTrack(String trackId) {
        Log.i(TAG, "Player.setAudioTrack " + trackId);
    }

    public void setVideoTrack(String trackId) {
        Log.i(TAG, "Player.setVideoTrack " + trackId);
    }

    public void setVolume(int volume) {
        Log.i(TAG, "Player.setVolume " + volume);
    }

    public void setMute(boolean muted) {
        Log.i(TAG, "Player.setMute " + muted);
    }

    public void setRect(int l, int t, int r, int b) {
        setRect(new Rect(l, t, r, b));
    }

    public Object getSubtitles() {
        Log.v(TAG, "getSubtitles()");
        V8 v8 = _env.getRuntime();
        V8Array subs = new V8Array(v8);
        if (tracks == null) {
            Log.w(TAG, "no tracks registered, wait for onTracksChanged event");
            return subs;
        }

        int groupIdx = 0;
        for (Tracks.Group trackGroup : tracks.getGroups()) {
            if (trackGroup.getType() != C.TRACK_TYPE_TEXT) {
                ++groupIdx;
                continue;
            }
            for (int i = 0; i < trackGroup.length; i++) {
                // Individual track information.
                boolean isSupported = trackGroup.isTrackSupported(i);
                boolean isSelected = trackGroup.isTrackSelected(i);
                Format trackFormat = trackGroup.getTrackFormat(i);
                if (!isSupported) {
                    continue;
                }
                Log.d(TAG, "track[" + groupIdx + "." + i + "]: selected: " + isSelected + ", format: " + trackFormat);
                V8Object track = new V8Object(v8);
                track.add("id", groupIdx + "." + i);
                track.add("active", isSelected);
                track.add("language", trackFormat.language);
                track.add("label", trackFormat.label);
                subs.push(track);
            }
            ++groupIdx;
        }
        return subs;
    }

    private void hideSubtitles() {
        Log.i(TAG, "hideSubtitles");
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                if (player == null)
                    return;
                player.setTrackSelectionParameters(
                        player.getTrackSelectionParameters()
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, /* disabled= */ true)
                                .build());
            }
        });
    }

    public void setSubtitles(String trackId) {
        Log.d(TAG, "setSubtitles " + trackId);
        if (trackId == null) {
            hideSubtitles();
            return;
        }
        String[] groupAndId = trackId.split("\\.");
        if (groupAndId.length != 2)
            throw new RuntimeException("invalid trackId format");
        if (tracks == null)
            return;

        int groupId = Integer.parseInt(groupAndId[0]);
        int trackIdx = Integer.parseInt(groupAndId[1]);
        Tracks.Group trackGroup = tracks.getGroups().get(groupId);
        Format trackFormat = trackGroup.getTrackFormat(trackIdx);
        Log.i(TAG, "setSubtitles, group " + groupId + ", track id: " + trackIdx + ", language: " + trackFormat.language + ", label: " + trackFormat.label);
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                if (player == null)
                    return;
                player.setTrackSelectionParameters(
                        player.getTrackSelectionParameters()
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, /* disabled= */ false)
                                .setOverrideForType(new TrackSelectionOverride(
                                        trackGroup.getMediaTrackGroup(), trackIdx
                                ))
                                .build());
            }
        });
    }

    public void setHlsExtractorFlag(int flag, boolean flagSwitcher) {
        hlsExtractorFlags = flagSwitcher ? hlsExtractorFlags | flag : hlsExtractorFlags &~ flag;
    }

    private void updateGeometry() {
        if (rect == null) {
            Log.v(TAG, "updateGeometry skipped, rect is null");
            return;
        }

        Rect surfaceGeometry = _env.getSurfaceGeometry();
        //if surface geometry defined and rectangle less than surface geometry, set Z on top
        boolean onTop = surfaceGeometry != null && !rect.contains(surfaceGeometry); //we use original rect here (no AR)
        surfaceView.setZOrderOnTop(onTop);

        if (videoWidth > 0 && videoHeight > 0) {
            float scaleX = 1.0f * rect.width() / videoWidth;
            float scaleY = 1.0f * rect.height() / videoHeight;
            float scale = Math.min(scaleX, scaleY); //always fit
            Log.v(TAG, "aspect ratio scale: " + scale);
            int newWidth = (int)(scale * videoWidth);
            int newHeight = (int)(scale * videoHeight);
            int x = rect.left + (rect.width() - newWidth) / 2;
            int y = rect.top + (rect.height() - newHeight) / 2;
            Rect videoRect = new Rect(x, y, x + newWidth, y + newHeight);
            Log.v(TAG, "corrected video rect: " + videoRect);
            viewHolder.setRect(_env.getRootView(), videoRect);
        }
        else
            viewHolder.setRect(_env.getRootView(), rect);
    }

    private void setRect(Rect rect) {
        Log.i(TAG, "Player.setRect " + rect);
        this.rect = rect;
        updateGeometry();
    }

    public void setVisibility(boolean visible) {
        playerVisible = visible;
        Log.i(TAG, "Player.setVisibility " + visible);
        viewHolder.update(_env.getRootView(), visible);
        handler.post(new SafeRunnable() {
            @Override
            protected void doRun() {
                updateSoftwareOutputActive();
            }
        });
    }

    @Override
    public void acquireResource() {
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                VideoPlayer.this.acquireResourceImpl();
            }
        });
    }

    @Override
    public void releaseResource() {
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                VideoPlayer.this.releaseResourceImpl();
            }
        });
    }

    @Override
    public void discard() {
        super.discard();
        viewHolder.discard(_env.getRootView());
        handler.post(new SafeRunnable() {
            @Override
            protected void doRun() {
                releaseResourceImpl();
                playerThread.quitSafely();
            }
        });
        synchronized (INSTANCES_LOCK) {
            Iterator<WeakReference<VideoPlayer>> it = INSTANCES.iterator();
            while (it.hasNext()) {
                VideoPlayer instance = it.next().get();
                if (instance == null || instance == this)
                    it.remove();
            }
        }
    }
}
