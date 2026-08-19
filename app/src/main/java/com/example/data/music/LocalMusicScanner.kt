package com.example.data.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.data.models.MusicTrackItem
import com.example.data.models.MusicSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans the device's local music library via MediaStore.
 * Returns a list of [MusicTrackItem]s with real metadata and
 * content/album-art URIs for playback and display.
 */
object LocalMusicScanner {

    /**
     * Query MediaStore for all music files on external storage.
     * Runs on [Dispatchers.IO]; safe to call from any coroutine.
     */
    suspend fun scanLocalTracks(context: Context): List<MusicTrackItem> =
        withContext(Dispatchers.IO) {
            val tracks = mutableListOf<MusicTrackItem>()
            val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            runCatching {
                context.contentResolver.query(
                    uri, projection, selection, null, sortOrder
                )
            }.getOrNull()?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol) ?: "Unknown Track"
                    val artist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val album = cursor.getString(albumCol) ?: "Unknown Album"
                    val durationMs = cursor.getInt(durationCol)
                    val albumId = cursor.getLong(albumIdCol)

                    val contentUri = ContentUris.withAppendedId(uri, id)
                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"), albumId
                    )

                    // Deterministic fallback colour per album for placeholder tint.
                    val fallbackColor = (0xFF1E3A8A.toInt() or
                        (album.hashCode() and 0x00FFFFFF))

                    tracks.add(
                        MusicTrackItem(
                            id = "local-$id",
                            title = title,
                            artist = artist,
                            durationSec = durationMs / 1000,
                            source = MusicSourceType.LOCAL,
                            albumCoverHex = fallbackColor.toLong() and 0xFFFFFFFFL,
                            contentUri = contentUri.toString(),
                            albumArtUri = albumArtUri.toString()
                        )
                    )
                }
            }

            tracks
        }
}
