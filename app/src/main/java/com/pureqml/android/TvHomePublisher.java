package com.pureqml.android;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.tvprovider.media.tv.Channel;
import androidx.tvprovider.media.tv.PreviewProgram;
import androidx.tvprovider.media.tv.TvContractCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Публикует подборку ТВ-каналов на домашний экран Android TV
 * (preview channel через TvProvider).
 *
 * Вызывается из QML (channelManager.getChannels) через бридж
 * fd.updateTvHomeChannels(json) после каждого запроса списка каналов.
 *
 * Формат элемента json-массива:
 * {
 *     "id": 30,                        // id канала
 *     "title": "channelName",          // название
 *     "description": "desc",           // описание (может быть пустым)
 *     "icon": "https://.../icon.png",  // постер 16:9
 *     "deeplink": "channelLiveByID/30",// параметр "url" для диплинка приложения
 *     "isAdult": 0                     // 1 если контент 18+
 * }
 */
public final class TvHomePublisher {

    private static final String TAG = "TvHomePublisher";

    private static final String PREFS_NAME = "tv_home_publisher";
    private static final String KEY_CHANNEL_ID = "channel_id";
    private static final String KEY_BROWSABLE_REQUESTED = "browsable_requested";

    private static final String CHANNEL_DISPLAY_NAME = "ТВ каналы";
    private static final String DEEPLINK_BASE = "ufanet://ru.ufanet.iptv";

    // false после первой неудачной попытки (например, нет TvProvider на телефоне),
    // чтобы не дёргать ContentResolver при каждом обновлении списка каналов
    private static boolean _supported = true;

    private TvHomePublisher() {}

    public static void publishChannelsJson(Context context, String json) {
        if (!_supported)
            return;
        try {
            JSONArray items = new JSONArray(json);
            publishChannels(context.getApplicationContext(), items);
        } catch (Exception e) {
            Log.e(TAG, "failed to publish channels, disabling publisher", e);
            _supported = false;
        }
    }

    private static void publishChannels(Context context, JSONArray items) {
        ContentResolver resolver = context.getContentResolver();

        long channelId = ensureChannel(context, resolver);
        if (channelId < 0)
            return;

        // Полностью заменяем программы подборки актуальным списком каналов
        deleteExistingPrograms(resolver, channelId);

        int inserted = 0;
        for (int i = 0; i < items.length(); ++i) {
            JSONObject item = items.optJSONObject(i);
            if (item == null)
                continue;
            insertProgram(context, resolver, channelId, item);
            ++inserted;
        }
        Log.i(TAG, "published " + inserted + " programs to channel " + channelId);
    }

    /**
     * Удаляет все программы нашей подборки.
     * TvProvider запрещает selection для preview_program (SecurityException:
     * "Selection not allowed"), поэтому запрашиваем id всех программ без selection
     * (провайдер сам ограничивает выдачу пакетом приложения) и удаляем каждую
     * программу по ее собственному Uri.
     */
    private static void deleteExistingPrograms(ContentResolver resolver, long channelId) {
        List<Long> programIds = new ArrayList<>();
        Cursor cursor = resolver.query(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                new String[]{
                        TvContractCompat.PreviewPrograms._ID,
                        TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID
                },
                null, null, null
        );
        if (cursor != null) {
            try {
                while (cursor.moveToNext()) {
                    if (cursor.getLong(1) == channelId)
                        programIds.add(cursor.getLong(0));
                }
            } finally {
                cursor.close();
            }
        }
        for (long id : programIds) {
            resolver.delete(
                    ContentUris.withAppendedId(TvContractCompat.PreviewPrograms.CONTENT_URI, id),
                    null, null
            );
        }
    }

    /**
     * Возвращает id существующей подборки или создает новую.
     * Заодно удаляет устаревшие каналы приложения (в т.ч. тестовые из черновых сборок).
     */
    private static long ensureChannel(Context context, ContentResolver resolver) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long channelId = prefs.getLong(KEY_CHANNEL_ID, -1);

        // Приложение видит только собственные каналы в TvProvider
        List<Long> existingIds = new ArrayList<>();
        Cursor cursor = resolver.query(
                TvContractCompat.Channels.CONTENT_URI,
                new String[]{TvContractCompat.Channels._ID},
                null, null, null
        );
        if (cursor != null) {
            try {
                while (cursor.moveToNext())
                    existingIds.add(cursor.getLong(0));
            } finally {
                cursor.close();
            }
        }

        if (channelId >= 0 && existingIds.contains(channelId))
            return channelId;

        // Наш канал не найден: чистим все оставшиеся каналы приложения и создаем заново
        for (long id : existingIds) {
            resolver.delete(
                    ContentUris.withAppendedId(TvContractCompat.Channels.CONTENT_URI, id),
                    null, null
            );
        }

        Channel channel = new Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(CHANNEL_DISPLAY_NAME)
                .setAppLinkIntentUri(Uri.parse(DEEPLINK_BASE))
                .build();

        Uri channelUri = resolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
        );
        if (channelUri == null) {
            Log.e(TAG, "failed to create home channel");
            return -1;
        }

        channelId = ContentUris.parseId(channelUri);
        prefs.edit().putLong(KEY_CHANNEL_ID, channelId).apply();

        // Просим систему показать подборку на домашнем экране.
        // Диалог показывается один раз за установку, чтобы не донимать пользователя,
        // если он сознательно удалил подборку с домашнего экрана.
        if (!prefs.getBoolean(KEY_BROWSABLE_REQUESTED, false)) {
            Log.i(TAG, "requesting channel " + channelId + " browsable");
            TvContractCompat.requestChannelBrowsable(context, channelId);
            prefs.edit().putBoolean(KEY_BROWSABLE_REQUESTED, true).apply();
        }
        return channelId;
    }

    @SuppressLint("RestrictedApi")
    private static void insertProgram(Context context, ContentResolver resolver, long channelId, JSONObject item) {
        long channelContentId = item.optLong("id");
        String title = item.optString("title");
        String description = item.optString("description");
        String icon = item.optString("icon");
        String deeplink = item.optString("deeplink");
        int isAdult = item.optInt("isAdult");

        Intent launchIntent = new Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse(DEEPLINK_BASE))
                .setPackage(context.getPackageName())
                .putExtra("url", deeplink)
                .putExtra("is_adult", String.valueOf(isAdult));

        Uri intentUri = Uri.parse(launchIntent.toUri(Intent.URI_INTENT_SCHEME));

        PreviewProgram.Builder builder = new PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewProgramColumns.TYPE_CHANNEL)
                .setTitle(title)
                .setDescription(description)
                .setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9)
                .setIntentUri(intentUri)
                .setInternalProviderId(String.valueOf(channelContentId));

        if (!TextUtils.isEmpty(icon))
            builder.setPosterArtUri(Uri.parse(icon));

        resolver.insert(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                builder.build().toContentValues()
        );
    }
}
