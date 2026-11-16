/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.audio;

import com.dunctebot.sourcemanagers.DuncteBotSources;
import com.jagrosh.jmusicbot.Bot;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.getyarn.GetyarnAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.nico.NicoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.YoutubeSourceOptions;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.Music;
import dev.lavalink.youtube.clients.TvHtml5Simply;
import dev.lavalink.youtube.clients.Web;
import dev.lavalink.youtube.clients.WebEmbedded;
import dev.lavalink.youtube.clients.skeleton.Client;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class PlayerManager extends DefaultAudioPlayerManager
{
    private final Bot bot;
    
    public PlayerManager(Bot bot)
    {
        this.bot = bot;
    }
    
    public void init()
    {
        // Low memory non-allocating frame buffer for high performance & minimal GC
        this.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);

        TransformativeAudioSourceManager.createTransforms(bot.getConfig().getTransforms()).forEach(t -> registerSourceManager(t));

        // Configure modern YouTube source with remote cipher and OAuth-compatible clients
        YoutubeSourceOptions options = new YoutubeSourceOptions();
        options.setAllowSearch(true);
        String remoteCipherUrl = bot.getConfig().getRemoteCipherUrl();
        if (remoteCipherUrl != null && !remoteCipherUrl.trim().isEmpty()) {
            options.setRemoteCipher(remoteCipherUrl.trim(), null, "JMusicBot-DAVE");
            LoggerFactory.getLogger("JMusicBot").info("YouTube Remote Cipher: Configured with endpoint " + remoteCipherUrl.trim());
        }

        Client[] clients = new Client[] {
            new Music(),
            new TvHtml5Simply(),
            new AndroidVr(),
            new Web(),
            new WebEmbedded()
        };
        YoutubeAudioSourceManager yt = new YoutubeAudioSourceManager(options, clients);
        yt.setPlaylistPageCount(bot.getConfig().getMaxYTPlaylistPages());

        // YouTube OAuth 2.0 device authorization flow with auto-save
        Path tokenPath = Paths.get("youtube_oauth_token.txt");
        String ytToken = bot.getConfig().getYtOauth();
        if ((ytToken == null || ytToken.trim().isEmpty()) && Files.exists(tokenPath)) {
            try {
                ytToken = Files.readString(tokenPath).trim();
            } catch (IOException ignored) {}
        }

        if (ytToken != null && !ytToken.trim().isEmpty()) {
            yt.useOauth2(ytToken.trim(), true);
            LoggerFactory.getLogger("JMusicBot").info("YouTube OAuth: Authenticated via cached/configured refresh token.");
        } else if (bot.getConfig().useYtOauth()) {
            yt.useOauth2(null, false);
            Thread tokenSaver = new Thread(() -> {
                try {
                    while (yt.getOauth2RefreshToken() == null || yt.getOauth2RefreshToken().isEmpty()) {
                        Thread.sleep(2000);
                    }
                    Files.writeString(tokenPath, yt.getOauth2RefreshToken().trim());
                    LoggerFactory.getLogger("JMusicBot").info("YouTube OAuth: Token acquired and saved to " + tokenPath.toAbsolutePath());
                } catch (Exception ignored) {}
            }, "YouTube-OAuth-Saver");
            tokenSaver.setDaemon(true);
            tokenSaver.start();
        }

        registerSourceManager(yt);

        registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        registerSourceManager(new BandcampAudioSourceManager());
        registerSourceManager(new VimeoAudioSourceManager());
        registerSourceManager(new TwitchStreamAudioSourceManager());
        registerSourceManager(new BeamAudioSourceManager());
        registerSourceManager(new GetyarnAudioSourceManager());
        registerSourceManager(new NicoAudioSourceManager());
        registerSourceManager(new HttpAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY));

        AudioSourceManagers.registerLocalSource(this);

        DuncteBotSources.registerAll(this, "en-US");
    }
    
    public Bot getBot()
    {
        return bot;
    }
    
    public boolean hasHandler(Guild guild)
    {
        return guild.getAudioManager().getSendingHandler()!=null;
    }
    
    public AudioHandler setUpHandler(Guild guild)
    {
        AudioHandler handler;
        if(guild.getAudioManager().getSendingHandler()==null)
        {
            AudioPlayer player = createPlayer();
            player.setVolume(bot.getSettingsManager().getSettings(guild).getVolume());
            handler = new AudioHandler(this, guild, player);
            player.addListener(handler);
            guild.getAudioManager().setSendingHandler(handler);
        }
        else
            handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        return handler;
    }
}
