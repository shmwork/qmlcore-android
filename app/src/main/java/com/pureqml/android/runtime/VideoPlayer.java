package com.pureqml.android.runtime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.Layout;
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
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.text.SubtitleDecoderFactory;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static volatile boolean useSystemPlayer = false;

    private ExoPlayer player;
    private SystemMediaPlayerBackend systemPlayer;
    private final SurfaceView           surfaceView;
    private final ViewHolder<?>         viewHolder;
    private final Handler               handler;
    private final Timeline.Period       period;

    //this is persistent state
    private Rect                        rect;
    private int                         videoWidth = 0;
    private int                         videoHeight = 0;
    private String                      source;
    private boolean                     playerVisible = true;
    private boolean                     autoplay = false;
    private boolean                     paused = false;
    private boolean                     stopped = false;
    private Runnable                    pollingTask = null;

    private Tracks                      tracks = null;

    //exoplayer flags
    private int                         hlsExtractorFlags = 0;
    private boolean                     exposeCea608WhenMissingDeclarations = true;
    private final static float          defaultTextSizeSP = 31;

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

            // Настройка фона
            Paint backgroundPaint = new Paint();
            backgroundPaint.setColor(Color.argb(150, 0, 0, 0)); // Полупрозрачный черный
            backgroundPaint.setStyle(Paint.Style.FILL);
            float backgroundPadding = textSize * 0.2f; // Отступы вокруг текста

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

                    // Рисуем фон для всех строк субтитров
                    // Сначала вычисляем размеры текста
                    float maxWidth = 0;
                    float totalHeight = text.length * lineHeight;
                    float[] lineWidths = new float[text.length];
                    for (int i = 0; i < text.length; i++) {
                        lineWidths[i] = paint.measureText(text[i]);
                        if (lineWidths[i] > maxWidth) maxWidth = lineWidths[i];
                    }
                    
                    // Вычисляем позицию для фона
                    float bgLeft, bgRight;
                    switch(paint.getTextAlign()) {
                        case LEFT:
                            bgLeft = x - backgroundPadding;
                            bgRight = x + maxWidth + backgroundPadding;
                            break;
                        case RIGHT:
                            bgLeft = x - maxWidth - backgroundPadding;
                            bgRight = x + backgroundPadding;
                            break;
                        case CENTER:
                        default:
                            bgLeft = x - maxWidth/2 - backgroundPadding;
                            bgRight = x + maxWidth/2 + backgroundPadding;
                            break;
                    }
                    
                    // y is the first-line baseline; descenders (р, у, g, p) go below it.
                    Paint.FontMetrics fm = paint.getFontMetrics();
                    float outlinePad = 3f / 2f;
                    float lastBaseline = y + (text.length - 1) * lineHeight;
                    float bgTop = y + fm.ascent - outlinePad - backgroundPadding;
                    float bgBottom = lastBaseline + fm.descent + outlinePad + backgroundPadding;

                    // HLS last-line cues sit one row from the bottom. Double that gap
                    // so the whole block (background + text) sits twice as high.
                    float gapBelow = rect.bottom - bgBottom;
                    if (gapBelow > 0 && gapBelow < lineHeight * 2.5f) {
                        y -= gapBelow;
                        bgTop -= gapBelow;
                        bgBottom -= gapBelow;
                    }
                    
                    // Рисуем прямоугольник фона с закругленными углами
                    RectF bgRect = new RectF(bgLeft, bgTop, bgRight, bgBottom);
                    state.drawRoundRect(bgRect, 8f, 8f, backgroundPaint);
                    

                    for(String line : text) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(3f);
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

        HandlerThread thread = new HandlerThread(this.toString());
        thread.start();
        handler = new Handler(thread.getLooper());

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
        Log.i(TAG, "setSoftwareDecoder useSystemPlayer=" + enable);
        if (useSystemPlayer == enable)
            return;
        useSystemPlayer = enable;

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
            instance.recreateWithCurrentBackend();
    }

    public static boolean isSoftwareDecoder() {
        return useSystemPlayer;
    }

    private void recreateWithCurrentBackend() {
        handler.post(new SafeRunnable() {
            @Override
            protected void doRun() {
                long positionMs = 0;
                boolean playWhenReady = autoplay && !paused;
                if (player != null) {
                    positionMs = player.getCurrentPosition();
                    playWhenReady = player.getPlayWhenReady();
                } else if (systemPlayer != null && systemPlayer.isActive()) {
                    positionMs = systemPlayer.getCurrentPosition();
                    playWhenReady = systemPlayer.isPlaying() || playWhenReady;
                }
                releaseResourceImpl();
                paused = !playWhenReady;
                acquireResourceImpl();
                final long restorePosition = positionMs;
                final boolean restorePlayWhenReady = playWhenReady;
                handler.post(new SafeRunnable() {
                    @Override
                    protected void doRun() {
                        if (player != null) {
                            if (restorePosition > 0)
                                player.seekTo(restorePosition);
                            player.setPlayWhenReady(restorePlayWhenReady);
                        } else if (systemPlayer != null && systemPlayer.isActive()) {
                            if (restorePosition > 0)
                                systemPlayer.seekTo(restorePosition);
                            if (restorePlayWhenReady)
                                systemPlayer.play();
                            else
                                systemPlayer.pause();
                        }
                    }
                });
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
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                if (systemPlayer != null && systemPlayer.isActive()) {
                    if (!systemPlayer.isPrepared())
                        return;
                    long position = systemPlayer.getCurrentPosition();
                    long duration = systemPlayer.getDuration();
                    systemPlayer.updateSubtitleCues(position);
                    systemPlayer.checkWatchdog(position);
                    Log.v(TAG, "emitting position " + position + " / " + duration);
                    VideoPlayer.this.emit("timeupdate", position / 1000.0);
                    if (duration > 0) {
                        VideoPlayer.this.emit("durationchange", duration / 1000.0);
                    }
                    return;
                }

                ExoPlayer player = VideoPlayer.this.player;
                if (player == null)
                    return;

                long position = player.getCurrentPosition();
                Timeline currentTimeline = player.getCurrentTimeline();
                if (!currentTimeline.isEmpty()) {
                    position -= currentTimeline.getPeriod(player.getCurrentPeriodIndex(), period)
                            .getPositionInWindowMs();
                }
                final long duration = player.getDuration();
                if (duration != TIME_UNSET) {
                    Log.v(TAG, "emitting position " + position + " / " + duration);
                    VideoPlayer.this.emit("timeupdate",position / 1000.0);
                    VideoPlayer.this.emit("durationchange", duration / 1000.0);
                }
            }
        });
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
        if (useSystemPlayer) {
            acquireMediaPlayerImpl();
            return;
        }
        if (player != null)
            return;

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

        player = new ExoPlayer.Builder(context)
                .setTrackSelector(trackSelector)
                .setRenderersFactory(new CustomRenderersFactory(context, new CustomTextOutput()))
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
                VideoPlayer.this.emit("error", error.toString());
                if (isBehindLiveWindow(error)) {
                    Log.i(TAG, "restarting player");
                    releaseResource();
                    acquireResource();
                }
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
            }
        });

        updateGeometry();
        setVisibility(playerVisible);
        player.setPlayWhenReady(autoplay);
        if (source != null)
            setSource(source);

        startPolling();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void acquireMediaPlayerImpl() {
        if (systemPlayer != null && systemPlayer.isActive())
            return;

        Log.i(TAG, "creating Android MediaPlayer (NuPlayer)");
        if (systemPlayer == null) {
            systemPlayer = new SystemMediaPlayerBackend(_env.getContext(), surfaceView, new SystemMediaPlayerBackend.Callbacks() {
                @Override
                public void emit(String name, Object... args) {
                    VideoPlayer.this.emit(name, args);
                }

                @Override
                public void onVideoSize(int width, int height) {
                    videoWidth = width;
                    videoHeight = height;
                    handler.post(new SafeRunnable() {
                        @Override
                        protected void doRun() {
                            updateGeometry();
                        }
                    });
                }

                @Override
                public void onTimedText(CharSequence text) {
                    if (paintDelegate == null)
                        return;
                    if (text == null || text.length() == 0) {
                        paintDelegate.setCue(new CueGroup(Collections.emptyList(), 0));
                        return;
                    }
                    Cue cue = new Cue.Builder()
                            .setText(text)
                            .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setLine(-1f, Cue.LINE_TYPE_NUMBER)
                            .setLineAnchor(Cue.ANCHOR_TYPE_END)
                            .build();
                    paintDelegate.setCue(new CueGroup(Collections.singletonList(cue), 0));
                }

                @Override
                public Handler handler() {
                    return handler;
                }

                @Override
                public ExecutorService executor() {
                    return _env.getExecutor();
                }
            });
        }
        updateGeometry();
        setVisibility(playerVisible);
        systemPlayer.acquire(source, !paused);
        startPolling();
    }

    private void startPolling() {
        if (pollingTask != null)
            return;
        pollingTask = new SafeRunnable() {
            @Override
            protected void doRun() {
                VideoPlayer.this.pollPosition();
                if (pollingTask != null) {
                    handler.postDelayed(pollingTask, PollingInterval);
                }
            }
        };
        handler.postDelayed(pollingTask, PollingInterval);
    }

    private void releaseResourceImpl() {
        pollingTask = null;
        if (player != null) {
            player.setVideoSurfaceView(null);
            player.release();
            player = null;
        }
        if (systemPlayer != null) {
            systemPlayer.release();
        }
        videoWidth = 0;
        videoHeight = 0;
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
                VideoPlayer.this.emit("pause", true);
                if (systemPlayer != null && systemPlayer.isActive()) {
                    systemPlayer.stop();
                    stopped = true;
                    return;
                }
                if (player != null) {
                    player.stop();
                    stopped = true;
                }
            }
        });
    }

    @OptIn(markerClass = {UnstableApi.class, UnstableApi.class})
    public void setSource(String url) {
        Log.i(TAG, "Player.setSource " + url);
        source = url;
        if (useSystemPlayer) {
            handler.post(new SafeRunnable() {
                @Override
                public void doRun() {
                    if (systemPlayer == null || !systemPlayer.isActive())
                        return;
                    if (source == null || source.isEmpty()) {
                        systemPlayer.stop();
                        return;
                    }
                    stopped = false;
                    videoWidth = 0;
                    videoHeight = 0;
                    systemPlayer.setSource(source);
                }
            });
            return;
        }
        if (player == null)
            return;

        if (source == null || source.isEmpty()) {
            stop();
            return;
        }

        stopped = false;

        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(_env.getContext());

        SubtitleDecoderFactory subtitleDecoderFactory = SubtitleDecoderFactory.DEFAULT;
        BaseMediaSource source;
        if (url.contains(".m3u8")) { //FIXME: add proper content type here
            HlsMediaSource.Factory factory = new HlsMediaSource.Factory(dataSourceFactory);
            factory.setExtractorFactory(new DefaultHlsExtractorFactory(hlsExtractorFlags, exposeCea608WhenMissingDeclarations))
                    .setAllowChunklessPreparation(true);
            source = factory.createMediaSource(MediaItem.fromUri(Uri.parse(url)));
        } else {
            ProgressiveMediaSource.Factory factory = new ProgressiveMediaSource.Factory(dataSourceFactory);
            source = factory.createMediaSource(MediaItem.fromUri(Uri.parse(url)));
        }

        source.addEventListener(handler, new MediaSourceEventListener() {
            @Override
            public void onLoadError(int windowIndex, @Nullable MediaSource.MediaPeriodId mediaPeriodId, @NonNull LoadEventInfo loadEventInfo, @NonNull MediaLoadData mediaLoadData, @NonNull IOException error, boolean wasCanceled) {
                Log.w(TAG, "onLoadError");
                // VideoPlayer.this.emit("error", "Source load error: " + error.getLocalizedMessage());
            }
        });

        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
                if (player == null)
                    return;
                player.setMediaSource(source, true);
                player.prepare();
                Log.i(TAG, "Player.setSource exited");
            }
        });
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
                if (paused) {
                    paused = false;
                    VideoPlayer.this.emit("pause", false);
                    if (systemPlayer != null && systemPlayer.isActive())
                        systemPlayer.play();
                    else if (player != null)
                        player.setPlayWhenReady(true);
                } else if (stopped) {
                    stopped = false;
                    setSource(source);
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
                    if (systemPlayer != null && systemPlayer.isActive())
                        systemPlayer.pause();
                    else if (player != null)
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
                long newPos;
                if (systemPlayer != null && systemPlayer.isActive()) {
                    newPos = systemPlayer.getCurrentPosition() + pos * 1000L;
                    VideoPlayer.this.emit("timeupdate", newPos / 1000.0);
                    systemPlayer.seekTo(newPos);
                    return;
                }
                if (player == null)
                    return;
                newPos = player.getCurrentPosition() + pos * 1000L;
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
                VideoPlayer.this.emit("timeupdate", pos);
                long positionMs = pos * 1000L;
                if (systemPlayer != null && systemPlayer.isActive())
                    systemPlayer.seekTo(positionMs);
                else if (player != null)
                    player.seekTo(positionMs);
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
                        if (systemPlayer != null && systemPlayer.isActive()) {
                            if (autoplay)
                                systemPlayer.play();
                            else
                                systemPlayer.pause();
                        } else if (player != null)
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
        if (useSystemPlayer) {
            // SystemMediaPlayerBackend currently does not expose parsed subtitle tracks
            return subs;
        }
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
                if (systemPlayer != null && systemPlayer.isActive()) {
                    systemPlayer.setSubtitleTrack(null);
                    return;
                }
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
        if (useSystemPlayer) {
            handler.post(new SafeRunnable() {
                @Override
                public void doRun() {
                    if (systemPlayer != null)
                        systemPlayer.setSubtitleTrack(trackId);
                }
            });
            return;
        }
        if (player == null)
            return;
        if (trackId == null) {
            hideSubtitles();
            return;
        }
        String[] groupAndId = trackId.split("\\.");
        if (groupAndId.length != 2)
            throw new RuntimeException("invalid trackId format");

        int groupId = Integer.parseInt(groupAndId[0]);
        int trackIdx = Integer.parseInt(groupAndId[1]);
        Tracks.Group trackGroup = tracks.getGroups().get(groupId);
        Format trackFormat = trackGroup.getTrackFormat(trackIdx);
        Log.i(TAG, "setSubtitles, group " + groupId + ", track id: " + trackIdx + ", language: " + trackFormat.language + ", label: " + trackFormat.label);
        handler.post(new SafeRunnable() {
            @Override
            public void doRun() {
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
        releaseResource();
        synchronized (INSTANCES_LOCK) {
            Iterator<WeakReference<VideoPlayer>> it = INSTANCES.iterator();
            while (it.hasNext()) {
                VideoPlayer instance = it.next().get();
                if (instance == null || instance == this)
                    it.remove();
            }
        }
    }

    private static final class SystemMediaPlayerBackend {
        interface Callbacks {
            void emit(String name, Object... args);
            void onVideoSize(int width, int height);
            void onTimedText(CharSequence text);
            Handler handler();
            ExecutorService executor();
        }

        static final class SubtitleTrack {
            String id;
            String language;
            String label;
            String uri;
            int nativeIndex = -1;
            boolean active;
        }

        private static final class VttCue {
            long startMs;
            long endMs;
            String text;
        }

        private static final String TAG = "SystemMediaPlayer";
        private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");
        private static final int MAX_SUBTITLE_SEGMENTS = 20;
        private static final long SUBTITLE_REFRESH_MS = 3000;

        private final Context context;
        private final SurfaceView surfaceView;
        private final Callbacks callbacks;
        private MediaPlayer mediaPlayer;
        private String source;
        private boolean playWhenReady;
        private long pendingSeekMs = -1;
        private boolean surfaceCallbackRegistered;
        private volatile boolean surfaceValid;
        private boolean prepared;
        private boolean initialized;
        private boolean hasRenderedFrame;
        private int prepareGeneration;
        private final List<SubtitleTrack> subtitleTracks = new ArrayList<>();
        private long watchdogLastPosition = -1;
        private long watchdogLastTimeMs = -1;

        private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                surfaceValid = holder.getSurface() != null && holder.getSurface().isValid();
                callbacks.handler().post(new SafeRunnable() {
                    @Override
                    protected void doRun() {
                        if (mediaPlayer == null)
                            return;
                        if (prepared) {
                            attachDisplay(holder);
                            return;
                        }
                        attachSurfaceAndPrepare();
                    }
                });
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                surfaceValid = false;
                MediaPlayer player = mediaPlayer;
                if (player != null) {
                    try {
                        player.setDisplay(null);
                    } catch (Exception ignored) {
                    }
                }
            }
        };

        SystemMediaPlayerBackend(Context context, SurfaceView surfaceView, Callbacks callbacks) {
            this.context = context;
            this.surfaceView = surfaceView;
            this.callbacks = callbacks;
        }

        void acquire(String source, boolean playWhenReady) {
            if (mediaPlayer != null)
                return;
            this.source = source;
            this.playWhenReady = playWhenReady;
            mediaPlayer = new MediaPlayer();
            prepared = false;
            initialized = false;
            hasRenderedFrame = false;
            Surface surface = surfaceView.getHolder().getSurface();
            surfaceValid = surface != null && surface.isValid();
            registerSurfaceCallback();
            if (source != null && !source.isEmpty())
                setSource(source);
        }

        void release() {
            unregisterSurfaceCallback();
            prepared = false;
            initialized = false;
            ++prepareGeneration;
            pendingSeekMs = -1;
            hasRenderedFrame = false;
            synchronized (subtitleTracks) {
                subtitleTracks.clear();
            }
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.setDisplay(null);
                    mediaPlayer.reset();
                    mediaPlayer.release();
                } catch (Exception e) {
                    Log.w(TAG, "release", e);
                }
                mediaPlayer = null;
            }
        }

        boolean isActive() {
            return mediaPlayer != null;
        }

        boolean isPrepared() {
            return prepared;
        }

        List<SubtitleTrack> copySubtitleTracks() {
            synchronized (subtitleTracks) {
                return new ArrayList<>(subtitleTracks);
            }
        }

        void setSource(String url) {
            source = url;
            prepared = false;
            hasRenderedFrame = false;
            pendingSeekMs = -1;
            if (mediaPlayer == null)
                return;
            if (url == null || url.isEmpty() || isPlaceholderUrl(url)) {
                synchronized (subtitleTracks) {
                    subtitleTracks.clear();
                }
                try {
                    if (initialized)
                        mediaPlayer.reset();
                } catch (IllegalStateException ignored) {
                }
                initialized = false;
                callbacks.emit("stateChanged", Player.STATE_IDLE);
                return;
            }
            attachSurfaceAndPrepare();
        }

        void play() {
            playWhenReady = true;
            startIfReady();
        }

        void pause() {
            playWhenReady = false;
            if (mediaPlayer == null || !prepared)
                return;
            try {
                if (mediaPlayer.isPlaying())
                    mediaPlayer.pause();
            } catch (IllegalStateException e) {
                Log.w(TAG, "pause", e);
            }
        }

        void stop() {
            playWhenReady = false;
            hasRenderedFrame = false;
            if (mediaPlayer == null || !prepared) {
                prepared = false;
                return;
            }
            prepared = false;
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException e) {
                try {
                    mediaPlayer.reset();
                    initialized = false;
                } catch (IllegalStateException ignored) {
                }
            }
            callbacks.emit("stateChanged", Player.STATE_IDLE);
        }

        void seekTo(long positionMs) {
            if (mediaPlayer == null)
                return;
            if (!prepared || !hasRenderedFrame) {
                pendingSeekMs = positionMs;
                return;
            }
            try {
                mediaPlayer.seekTo((int) Math.max(0, positionMs));
            } catch (IllegalStateException e) {
                Log.w(TAG, "seekTo", e);
                pendingSeekMs = positionMs;
            }
        }

        long getCurrentPosition() {
            if (mediaPlayer == null || !prepared)
                return 0;
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                return 0;
            }
        }

        void checkWatchdog(long currentPositionMs) {
            if (!playWhenReady || !hasRenderedFrame || mediaPlayer == null) {
                watchdogLastPosition = -1;
                return;
            }
            if (!isPlaying()) {
                // Not playing could mean buffering. If it's buffering for 15s, we can also restart.
                // But let's only strictly trigger if we are supposed to be playing.
                long now = android.os.SystemClock.elapsedRealtime();
                if (watchdogLastPosition == -2) {
                    if (watchdogLastTimeMs > 0 && now - watchdogLastTimeMs > 15000) {
                        Log.w(TAG, "MediaPlayer stuck in buffering/stopped state for 15s. Emitting error.");
                        callbacks.emit("error", "MediaPlayer hung (15s timeout)");
                        watchdogLastTimeMs = now;
                    }
                } else {
                    watchdogLastPosition = -2;
                    watchdogLastTimeMs = now;
                }
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (currentPositionMs == watchdogLastPosition) {
                if (watchdogLastTimeMs > 0 && now - watchdogLastTimeMs > 10000) {
                    Log.w(TAG, "MediaPlayer hung (position didn't advance for 10s). Emitting error to restart.");
                    callbacks.emit("error", "MediaPlayer hung (10s timeout)");
                    watchdogLastTimeMs = now;
                }
            } else {
                watchdogLastPosition = currentPositionMs;
                watchdogLastTimeMs = now;
            }
        }

        long getDuration() {
            if (mediaPlayer == null || !prepared)
                return -1;
            try {
                return mediaPlayer.getDuration();
            } catch (IllegalStateException e) {
                return -1;
            }
        }

        boolean isPlaying() {
            if (mediaPlayer == null || !prepared)
                return false;
            try {
                return mediaPlayer.isPlaying();
            } catch (IllegalStateException e) {
                return false;
            }
        }

        private void applyAudio() {
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build());
        }

        private void bindListeners(int generation) {
            mediaPlayer.setOnPreparedListener(mp -> postOnPlayerThread(generation, () -> {
                prepared = true;
                collectNativeSubtitleTracks();
                callbacks.emit("stateChanged", Player.STATE_READY);
                startIfReady();
                callbacks.handler().postDelayed(new SafeRunnable() {
                    @Override
                    protected void doRun() {
                        if (generation != prepareGeneration || mediaPlayer == null)
                            return;
                        hasRenderedFrame = true;
                        applyPendingSeek();
                    }
                }, 1200);
            }));
            mediaPlayer.setOnCompletionListener(mp -> postOnPlayerThread(generation, () -> {
                callbacks.emit("stateChanged", Player.STATE_ENDED);
                callbacks.emit("pause", true);
            }));
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                postOnPlayerThread(generation, () -> {
                    prepared = false;
                    if (what == -38 || isPlaceholderUrl(source))
                        return;
                    String message = "MediaPlayer error what=" + what + " extra=" + extra;
                    Log.e(TAG, message);
                    callbacks.emit("error", message);
                });
                return true;
            });
            mediaPlayer.setOnInfoListener((mp, what, extra) -> {
                postOnPlayerThread(generation, () -> {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        callbacks.emit("stateChanged", Player.STATE_BUFFERING);
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                            || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
                            hasRenderedFrame = true;
                        applyPendingSeek();
                        callbacks.emit("stateChanged", Player.STATE_READY);
                    }
                });
                return false;
            });
            mediaPlayer.setOnVideoSizeChangedListener((mp, width, height) ->
                    postOnPlayerThread(generation, () -> callbacks.onVideoSize(width, height)));
            mediaPlayer.setOnSeekCompleteListener(mp ->
                    postOnPlayerThread(generation, () -> callbacks.emit("seeked")));
            mediaPlayer.setOnTimedTextListener((mp, timedText) -> postOnPlayerThread(generation, () -> {
                CharSequence text = timedText != null ? timedText.getText() : "";
                callbacks.onTimedText(text != null ? text : "");
            }));
        }

        private void postOnPlayerThread(int generation, Runnable action) {
            callbacks.handler().post(new SafeRunnable() {
                @Override
                protected void doRun() {
                    if (generation != prepareGeneration || mediaPlayer == null)
                        return;
                    action.run();
                }
            });
        }

        private void registerSurfaceCallback() {
            if (surfaceCallbackRegistered)
                return;
            surfaceView.getHolder().addCallback(surfaceCallback);
            surfaceCallbackRegistered = true;
        }

        private void unregisterSurfaceCallback() {
            if (!surfaceCallbackRegistered)
                return;
            surfaceView.getHolder().removeCallback(surfaceCallback);
            surfaceCallbackRegistered = false;
        }

        private void attachSurfaceAndPrepare() {
            if (mediaPlayer == null || source == null || source.isEmpty() || isPlaceholderUrl(source))
                return;
            SurfaceHolder holder = surfaceView.getHolder();
            Surface surface = holder.getSurface();
            surfaceValid = surface != null && surface.isValid();
            if (!surfaceValid) {
                callbacks.emit("stateChanged", Player.STATE_BUFFERING);
                return;
            }
            try {
                if (initialized) {
                    mediaPlayer.reset();
                }
                initialized = true;
                prepared = false;
                hasRenderedFrame = false;
                int generation = ++prepareGeneration;
                bindListeners(generation);
                applyAudio();
                mediaPlayer.setScreenOnWhilePlaying(true);
                if (!attachDisplay(holder))
                    return;
                mediaPlayer.setDataSource(context, Uri.parse(source));
                callbacks.emit("stateChanged", Player.STATE_BUFFERING);
                mediaPlayer.prepareAsync();
            } catch (IOException | IllegalStateException | IllegalArgumentException | SecurityException e) {
                Log.e(TAG, "prepare failed", e);
                if (e instanceof IllegalArgumentException) {
                    Log.w(TAG, "skipping error emit for invalid surface/source");
                    return;
                }
                if (!isPlaceholderUrl(source))
                    callbacks.emit("error", e.toString());
            }
        }

        private boolean attachDisplay(SurfaceHolder holder) {
            if (mediaPlayer == null)
                return false;
            Surface surface = holder.getSurface();
            if (surface == null || !surface.isValid() || !surfaceValid)
                return false;
            try {
                mediaPlayer.setDisplay(holder);
                return true;
            } catch (IllegalArgumentException | IllegalStateException e) {
                Log.w(TAG, "setDisplay skipped", e);
                return false;
            }
        }

        private void applyPendingSeek() {
            if (mediaPlayer == null || !prepared || pendingSeekMs < 0)
                return;
            try {
                mediaPlayer.seekTo((int) pendingSeekMs);
            } catch (IllegalStateException e) {
                Log.w(TAG, "pending seek", e);
                return;
            }
            pendingSeekMs = -1;
        }

        private void startIfReady() {
            if (mediaPlayer == null || !prepared || !playWhenReady)
                return;
            try {
                mediaPlayer.start();
                callbacks.emit("pause", false);
            } catch (IllegalStateException e) {
                Log.w(TAG, "start", e);
            }
        }

        void setSubtitleTrack(String trackId) {
            if (trackId == null || "off".equals(trackId) || mediaPlayer == null) {
                deselectNativeTextTracks();
                return;
            }

            SubtitleTrack selected = null;
            synchronized (subtitleTracks) {
                for (SubtitleTrack track : subtitleTracks) {
                    if (trackId.equals(track.id)) {
                        track.active = true;
                        selected = track;
                        break;
                    }
                }
            }
            if (selected == null)
                return;
            if (selected.nativeIndex >= 0) {
                try {
                    mediaPlayer.selectTrack(selected.nativeIndex);
                } catch (Exception e) {
                    Log.w(TAG, "selectTrack", e);
                }
            }
        }

        void updateSubtitleCues(long positionMs) {
            // Stub: system player does not support client-side subtitles due to HLS server packaging
        }

        private void deselectNativeTextTracks() {
            if (mediaPlayer == null || !prepared)
                return;
            try {
                MediaPlayer.TrackInfo[] infos = mediaPlayer.getTrackInfo();
                for (int i = 0; i < infos.length; i++) {
                    int type = infos[i].getTrackType();
                    if (type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT
                            && type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE)
                        continue;
                    try {
                        mediaPlayer.deselectTrack(i);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "deselectTrack", e);
            }
        }

        private void collectNativeSubtitleTracks() {
            if (mediaPlayer == null)
                return;
            try {
                MediaPlayer.TrackInfo[] infos = mediaPlayer.getTrackInfo();
                synchronized (subtitleTracks) {
                    for (int i = 0; i < infos.length; i++) {
                        int type = infos[i].getTrackType();
                        if (type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT
                                && type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE)
                            continue;
                        SubtitleTrack track = new SubtitleTrack();
                        track.id = "native." + i;
                        track.nativeIndex = i;
                        track.language = normalizeLanguage(infos[i].getLanguage());
                        track.label = track.language;
                        subtitleTracks.add(track);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "getTrackInfo", e);
            }
        }

        private static String normalizeLanguage(String language) {
            if (language == null)
                return "";
            String lang = language.trim().toLowerCase(Locale.US);
            if (lang.startsWith("ru"))
                return "ru";
            if (lang.startsWith("en"))
                return "en";
            if ("und".equals(lang) || "unknown".equals(lang))
                return "";
            int dash = lang.indexOf('-');
            return dash > 0 ? lang.substring(0, dash) : lang;
        }

        private static String resolveUrl(String base, String ref) {
            try {
                URL resolved = new URL(new URL(base), ref);
                String result = resolved.toString();
                int query = base.indexOf('?');
                if (query >= 0 && !ref.contains("?") && !result.contains("?"))
                    result += base.substring(query);
                return result;
            } catch (Exception e) {
                return ref;
            }
        }

        private static String fetchText(String url) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1)
                    output.write(buffer, 0, read);
                return output.toString(StandardCharsets.UTF_8.name());
            } finally {
                connection.disconnect();
            }
        }

        private static boolean isPlaceholderUrl(String url) {
            if (url == null || url.isEmpty())
                return true;
            String value = url.toLowerCase();
            return value.contains("black.mp4")
                    || value.startsWith("android.resource://")
                    || value.startsWith("asset://")
                    || value.startsWith("res/");
        }
    }
}
