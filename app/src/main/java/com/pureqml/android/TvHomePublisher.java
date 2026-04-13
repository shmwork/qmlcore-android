package com.pureqml.android;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.tvprovider.media.tv.Channel;
import androidx.tvprovider.media.tv.PreviewProgram;
import androidx.tvprovider.media.tv.TvContractCompat;

public final class TvHomePublisher {

    private TvHomePublisher() {}

    public static long publishTestChannel(Context context) {
        Channel channel = new Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName("testest подборка")
                .setAppLinkIntentUri(Uri.parse("ufanet://ru.ufanet.iptv"))
                .build();

        Uri channelUri = context.getContentResolver().insert(
                TvContractCompat.Channels.CONTENT_URI,
                channel.toContentValues()
        );

        if (channelUri == null) {
            throw new IllegalStateException("Не удалось создать канал");
        }

        long channelId = ContentUris.parseId(channelUri);

        insertProgram(
                context,
                channelId,
                "Менталистка",
                "Фэнтези, Мелодрама",
                "content/10850",
                "movie-101",
                "https://media-test.iptv.ufanet.ru/media/program_images/9867654.jpg?h=569&w=1013"
        );

        insertProgram(
                context,
                channelId,
                "маша и медведь",
                "медведь и маша",
                "content/493510",
                "movie-102",
                "https://media-test.iptv.ufanet.ru/media/program_images/9933368.jpg?h=569&w=1013"
        );

        insertProgram(
                context,
                channelId,
                "я канал",
                "5 петербург",
                "channelLiveByLCN/5",
                "channel-202",
                "https://media-test.iptv.ufanet.ru/media/cd25d755-e9c2-4772-958a-8835265ea027.png?h=44&w=79"
        );

        TvContractCompat.requestChannelBrowsable(context, channelId);
        return channelId;
    }

    @SuppressLint("RestrictedApi")
    private static void insertProgram(
            Context context,
            long channelId,
            String title,
            String description,
            String contentCardUrl,
            String internalId,
            String posterUrl
    ) {
        Intent launchIntent = new Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("ufanet://ru.ufanet.iptv"))
                .putExtra("url", contentCardUrl);

        Uri intentUri = Uri.parse(launchIntent.toUri(Intent.URI_INTENT_SCHEME));

        PreviewProgram program = new PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewProgramColumns.TYPE_CLIP)
                .setTitle(title)
                .setDescription(description)
                .setPosterArtUri(Uri.parse(posterUrl))
                .setIntentUri(intentUri)
                .setInternalProviderId(internalId)
                .build();

        context.getContentResolver().insert(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                program.toContentValues()
        );
    }
}
