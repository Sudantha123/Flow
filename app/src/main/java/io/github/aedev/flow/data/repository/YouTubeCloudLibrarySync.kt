package io.github.aedev.flow.data.repository

import android.util.Log
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.PlaylistItem

object YouTubeCloudLibrarySync {
    suspend fun refreshPlaylists(playlistRepository: PlaylistRepository) {
        if (!YouTube.useLoginForBrowse || YouTube.cookie.isNullOrBlank()) return

        runCatching {
            val page = YouTube.library("FEmusic_library").getOrThrow()
            page.items.filterIsInstance<PlaylistItem>().forEach { playlist ->
                playlistRepository.saveExternalMusicPlaylist(
                    id = playlist.id,
                    name = playlist.title,
                    description = "",
                    thumbnailUrl = playlist.thumbnail,
                )
            }
            Log.d("YouTubeCloudSync", "Synced ${page.items.filterIsInstance<PlaylistItem>().size} cloud playlists")
        }.onFailure {
            Log.w("YouTubeCloudSync", "Cloud playlist sync failed", it)
        }
    }
}
