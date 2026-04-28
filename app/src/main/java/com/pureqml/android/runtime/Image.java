package com.pureqml.android.runtime;

import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.Log;

import com.eclipsesource.v8.Releasable;
import com.eclipsesource.v8.V8;
import com.eclipsesource.v8.V8Array;
import com.eclipsesource.v8.V8Function;
import com.eclipsesource.v8.V8Object;
import com.pureqml.android.IExecutionEnvironment;
import com.pureqml.android.ImageLoadedCallback;
import com.pureqml.android.ImageLoader;
import com.pureqml.android.SafeRunnable;
import com.pureqml.android.TypeConverter;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executor;
import java.util.HashMap;
import java.util.Map;

public final class Image extends Element implements ImageLoadedCallback {
    private final static String TAG = "rt.Image";
    URI                         _url;
    V8Function                  _callback;
    final Paint                 _paint;

    private enum Position { LeftOrTop, Center, RightOrBottom }
    private enum Mode { Percentage, Absolute, Cover, Contain }

    static int getPosition(Position position, int imageSize, int rectSize) {
        switch(position) {
            case RightOrBottom:
                return rectSize - imageSize;
            case Center:
                return (rectSize - imageSize) / 2;
            default:
                return 0;
        }
    }

    @Override
    public void onImageLoadFailed(final URI url, final Throwable error) {
        Executor executor = _env.getExecutor();
        if (executor == null) {
            Log.d(TAG, "skipping error callback, executor is dead");
            return;
        }

        executor.execute(new SafeRunnable() {
            @Override
            public void doRun() {
                Log.w(TAG, "image load failed " + url, error);

                final V8Function callback;
                synchronized (_callbacks) {
                    callback = _callbacks.remove(url);
                }

                if (callback == null || callback.isReleased()) {
                    return;
                }

                try (V8Array args = new V8Array(_env.getRuntime())) {
                    args.push((Object) null);
                    Object r = callback.call(null, args);
                    if (r instanceof Releasable) {
                        ((Releasable) r).release();
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "error callback failed", ex);
                } finally {
                    if (!callback.isReleased()) {
                        callback.close();
                    }
                    update();
                }
            }
        });
    }

    private final class Background {
        Mode     mode       = Mode.Absolute;
        Position position   = Position.LeftOrTop;
        int      percentage = 100;
        int      size       = 0;
        boolean  repeat     = false;

        void setPosition(String value) {
            switch (value) {
                case "left":
                case "top":
                    position = Position.LeftOrTop;
                    break;
                case "center":
                    position = Position.Center;
                    break;
                case "right":
                case "bottom":
                    position = Position.RightOrBottom;
                    break;
                default:
                    Log.w(TAG, "invalid position: " + value);
                    position = Position.LeftOrTop;
                    break;
            }
        }

        void resetSize() {
            mode = Mode.Percentage;
            percentage = 100;
        }

        void setBackgroundSize(String value) {
            if (value == null) {
                resetSize();
                return;
            }

            value = value.trim();

            if (value.endsWith("%")) {
                mode = Mode.Percentage;
                try {
                    percentage = Integer.parseInt(value.substring(0, value.length() - 1), 10);
                } catch (Exception e) {
                    percentage = 100;
                    Log.w(TAG, "invalid percentage background-size: " + value, e);
                }
                return;
            }

            switch (value) {
                case "auto":
                    resetSize();
                    break;
                case "cover":
                    mode = Mode.Cover;
                    break;
                case "contain":
                    mode = Mode.Contain;
                    break;
                default:
                    try {
                        size = TypeConverter.toFontSize(value, _env.getRenderer().getDisplayMetrics());
                        mode = Mode.Absolute;
                    } catch (Exception e) {
                        resetSize();
                        Log.w(TAG, "parsing background size failed: " + value, e);
                    }
                    break;
            }
        }

        int getPosition(int imageSize, int rectSize) {
            return Image.getPosition(position, imageSize, rectSize);
        }

        boolean needClip(Background y) {
            return repeat || y.repeat || mode == Mode.Cover || y.mode == Mode.Cover;
        }

        void merge(Background y, Rect dst, Rect src) {
            int containerLeft = dst.left;
            int containerTop = dst.top;
            int containerWidth = dst.width();
            int containerHeight = dst.height();

            int drawWidth = containerWidth;
            int drawHeight = containerHeight;

            switch (mode) {
                case Percentage:
                    drawWidth = (containerWidth * percentage) / 100;
                    break;
                case Absolute:
                    drawWidth = size;
                    break;
                case Contain:
                case Cover: {
                    float wx = 1.0f * containerWidth / src.width();
                    float hx = 1.0f * containerHeight / src.height();
                    float scale = mode == Mode.Contain ? Math.min(wx, hx) : Math.max(wx, hx);
                    drawWidth = Math.round(src.width() * scale);
                    drawHeight = Math.round(src.height() * scale);
                    break;
                }
            }

            switch (y.mode) {
                case Percentage:
                    drawHeight = (containerHeight * y.percentage) / 100;
                    break;
                case Absolute:
                    drawHeight = y.size;
                    break;
                case Contain:
                case Cover: {
                    float wx = 1.0f * containerWidth / src.width();
                    float hx = 1.0f * containerHeight / src.height();
                    float scale = y.mode == Mode.Contain ? Math.min(wx, hx) : Math.max(wx, hx);
                    drawWidth = Math.round(src.width() * scale);
                    drawHeight = Math.round(src.height() * scale);
                    break;
                }
            }

            int left = containerLeft + getPosition(drawWidth, containerWidth);
            int top = containerTop + y.getPosition(drawHeight, containerHeight);

            dst.left = left;
            dst.top = top;
            dst.right = left + drawWidth;
            dst.bottom = top + drawHeight;
        }
    }

    final Background _backgroundX = new Background();
    final Background _backgroundY = new Background();

    public Image(IExecutionEnvironment env) {
        super(env);
        _paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        _paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
    }

    @Override
    public void discard() {
        super.discard();
        if (_url != null) {
            _env.getImageLoader().unsubscribe(_url, this);
            _url = null;
        }
        setCallback(null);
    }

    public void load(String name, final V8Function callback) {
        if (!name.contains("://"))
            name = "file:///" + name;

        // Find and fix duplicated '?' entries
        {
            int questionMarkPos = name.indexOf('?');
            if (questionMarkPos >= 0 && name.indexOf('?', questionMarkPos + 1) >= 0) {
                Log.w(TAG, "duplicate '?' characters in url");
                name = name.subSequence(0, questionMarkPos + 1) +
                        name.substring(questionMarkPos + 1).replace('?', '&');
                Log.v(TAG, "rewritten url: " + name);
            }
        }
        ImageLoader loader = _env.getImageLoader();
        if (_url != null) {
            loader.unsubscribe(_url, this);
            _url = null;
        }
        try {
            _url = new URI(name);
        } catch (URISyntaxException e) {
            Log.e(TAG, "invalid url", e);
            V8 v8 = _env.getRuntime();

            V8Array args = new V8Array(v8);
            args.push((Object)null);
            Object r = callback.call(null, args); //indicate error
            if (r instanceof Releasable)
                ((Releasable)r).release();
            callback.close();
            args.close();
            return;
        }
        // Log.v(TAG, "loading " + _url);
        setCallback(callback);
        loader.subscribe(_url, this);
    }

    private static final String regexWS = "\\s+";
    private int _backgroundColor = 0x00000000; // transparent
    private boolean _hasBackgroundColor = false;

    @Override
    protected void setStyle(String name, Object value) {
        switch(name) {
            case "image-rendering":
                _paint.setFilterBitmap(!value.equals("pixelated"));
                break;
            case "background-image":
                break;
            case "background-position-x":
                _backgroundX.setPosition(value.toString());
                break;
            case "background-position-y":
                _backgroundY.setPosition(value.toString());
                break;
            case "background-position": {
                String[] pos = value.toString().trim().split(regexWS);
                if (pos.length == 1) {
                    _backgroundX.setPosition(pos[0]);
                    _backgroundY.setPosition(pos[0]);
                } else if (pos.length >= 2) {
                    _backgroundX.setPosition(pos[0]);
                    _backgroundY.setPosition(pos[1]);
                } else {
                    Log.w(TAG, "malformed background-position: " + value);
                }
                break;
            }
            case "background-size": {
                String[] size = value.toString().split(regexWS);
                if (size.length == 1) {
                    _backgroundX.setBackgroundSize(value.toString());
                    _backgroundY.setBackgroundSize(value.toString());
                } else if (size.length >= 2) {
                    _backgroundX.setBackgroundSize(size[0]);
                    _backgroundY.setBackgroundSize(size[1]);
                    if (size.length > 2)
                        Log.w(TAG, "skipping background-size tail " + value);
                } else
                    Log.w(TAG, "malformed background-size: " + value);
                break;
            }
            case "background-repeat": {
                String repeat = value.toString();
                if (repeat.equals("no-repeat")) {
                    _backgroundX.repeat = _backgroundY.repeat = false;
                } else {
                    String[] size = value.toString().split(regexWS);
                    for (String s : size) {
                        switch (s) {
                            case "repeat-x":
                                _backgroundX.repeat = true;
                                break;
                            case "repeat-y":
                                _backgroundY.repeat = true;
                                break;
                            default:
                                Log.w(TAG, "Unhandled background-repeat value " + s);
                        }
                    }
                }
                break;
            }
            case "background-color": {
                try {
                    _backgroundColor = android.graphics.Color.parseColor(value.toString());
                    _hasBackgroundColor = true;
                } catch (Exception e) {
                    _hasBackgroundColor = false;
                    Log.w(TAG, "invalid background-color: " + value, e);
                }
                break;
            }
            default:
                super.setStyle(name, value);
                return;
        }
        update();
    }

    @Override
    public void onImageLoaded(final URI url, final Bitmap bitmap) {
        Executor executor = _env.getExecutor();
        if (executor == null) {
            Log.d(TAG, "skipping callback, executor is dead");
            return;
        }
        executor.execute(new SafeRunnable() {
            @Override
            public void doRun() {
                Log.v(TAG, "on image loaded " + url + ", current url: " + _url);

                final V8Function callback;
                synchronized (_callbacks) {
                    callback = _callbacks.get(url);
                }

                if (callback == null || callback.isReleased()) {
                    return;
                }

                try (V8Array args = new V8Array(_env.getRuntime())) {
                    if (bitmap != null) {
                        V8Object metrics = new V8Object(_env.getRuntime());
                        metrics.add("width", bitmap.getWidth());
                        metrics.add("height", bitmap.getHeight());
                        args.push(metrics);
                        metrics.close();
                    } else {
                        args.push((Object) null); // <-- Отдаём null для ошибки
                    }

                    try {
                        _env.invokeVoidCallback(callback, null, args);
                    } catch (Exception ex) {
                        Log.w(TAG, "callback failed: ", ex);
                    }
                } finally {
                    if (!callback.isReleased()) {
                        callback.close();
                    }
                    update(); // <-- Принудительно обновляем состояние
                }
            }
        });
    }

    @Override
    public void paintElementSpecificBeforeChildren(PaintState state) {
        Rect dst = getDstRect(state);

        if (_hasBackgroundColor) {
            Paint bg = new Paint();
            bg.setColor(_backgroundColor);
            state.drawRect(dst, bg);
        }

        if (_url == null) {
            return;
        }

        Bitmap bitmap = null;
        try {
            bitmap = _env.getImageLoader().getBitmap(_url);
        } catch (Exception ex) {
            Log.w(TAG, "image loading failed", ex);
        }

        if (bitmap == null) {
            Paint bg = new Paint();
            bg.setColor(_backgroundColor);
            state.drawRect(dst, bg);
            return;
        }

        _paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        Paint paint = patchAlpha(_paint, 255, state.opacity);
        if (paint == null) {
            return;
        }

        Rect src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        Rect drawDst = new Rect(dst);
        _backgroundX.merge(_backgroundY, drawDst, src);

        boolean repeatX = _backgroundX.repeat;
        boolean repeatY = _backgroundY.repeat;

        if (!repeatX && !repeatY) {
            state.drawBitmap(bitmap, src, drawDst, paint);
            return;
        }

        int tileW = drawDst.width();
        int tileH = drawDst.height();
        if (tileW <= 0 || tileH <= 0) {
            Log.w(TAG, "invalid tile size: " + drawDst);
            return;
        }

        state.save();
        try {
            if (!state.clipRect(dst)) {
                return;
            }

            int startX = repeatX ? dst.left : drawDst.left;
            int startY = repeatY ? dst.top : drawDst.top;

            int endX = repeatX ? dst.right : drawDst.right;
            int endY = repeatY ? dst.bottom : drawDst.bottom;

            for (int y = startY; y < endY; y += tileH) {
                for (int x = startX; x < endX; x += tileW) {
                    Rect tileDst = new Rect(x, y, x + tileW, y + tileH);
                    state.drawBitmap(bitmap, src, tileDst, paint);
                    if (!repeatX) break;
                }
                if (!repeatY) break;
            }
        } finally {
            state.restore();
        }
    }
}
