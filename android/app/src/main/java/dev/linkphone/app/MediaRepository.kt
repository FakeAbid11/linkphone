package dev.linkphone.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/** Read-only access to the MediaStore images and videos. */
class MediaRepository(private val context: Context) {

    enum class Kind { IMAGE, VIDEO }

    data class Album(
        val id: String,
        val name: String,
        val count: Int,
        val coverPhotoId: String,
        val kind: Kind
    )

    data class Photo(
        val id: String,
        val albumId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val dateTaken: Long,
        val width: Int,
        val height: Int,
        val kind: Kind,
        val durationMs: Long = 0L
    )

    data class Page(val photos: List<Photo>, val hasMore: Boolean)

    data class EncodedImage(val mime: String, val bytes: ByteArray)

    private val resolver = context.contentResolver

    fun hasPermission(kind: Kind): Boolean = hasFullAccess(kind) || isPartial(kind)

    fun hasFullAccess(kind: Kind): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> granted(kind.mediaPermission)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun isPartial(kind: Kind): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !granted(kind.mediaPermission) &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    fun albums(kind: Kind): List<Album> {
        data class MutableAlbum(
            val id: String,
            val name: String,
            var count: Int,
            val coverPhotoId: String
        )

        val found = LinkedHashMap<String, MutableAlbum>()
        var allCount = 0
        var allCover = ""

        resolver.query(
            collection(kind),
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
            ),
            null,
            null,
            sortOrder()
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val bucketIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val nameIndex =
                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            var scanned = 0

            while (cursor.moveToNext() && scanned < MAX_ALBUM_SCAN) {
                scanned += 1
                val photoId = cursor.getLong(idIndex).toString()
                val bucketId = cursor.getString(bucketIndex).orEmpty().ifBlank { "other" }
                val name = cursor.getString(nameIndex).orEmpty().ifBlank { "Other" }
                if (allCover.isEmpty()) allCover = photoId
                allCount += 1

                val album = found[bucketId]
                if (album == null) {
                    found[bucketId] = MutableAlbum(bucketId, name, 1, photoId)
                } else {
                    album.count += 1
                }
            }
        }

        if (allCount == 0) return emptyList()
        val allName = if (kind == Kind.VIDEO) "All videos" else "All photos"
        return listOf(Album(ALL_PHOTOS, allName, allCount, allCover, kind)) +
            found.values.map { Album(it.id, it.name, it.count, it.coverPhotoId, kind) }
    }

    fun photos(kind: Kind, albumId: String, offset: Int, limit: Int): Page {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val selection =
            if (albumId == ALL_PHOTOS) null else "${MediaStore.MediaColumns.BUCKET_ID} = ?"
        val args = if (selection == null) null else arrayOf(albumId)
        val out = ArrayList<Photo>(safeLimit + 1)

        resolver.query(collection(kind), projection(kind), selection, args, sortOrder())?.use { cursor ->
            if (safeOffset > 0 && !cursor.moveToPosition(safeOffset - 1)) return@use

            while (cursor.moveToNext() && out.size <= safeLimit) {
                val id = cursor.string(MediaStore.MediaColumns._ID)
                if (id.isEmpty()) continue
                val bucketId = cursor.string(MediaStore.MediaColumns.BUCKET_ID).ifBlank { "other" }
                val taken = cursor.long(MediaStore.MediaColumns.DATE_TAKEN)
                    .takeIf { it > 0 }
                    ?: (cursor.long(MediaStore.MediaColumns.DATE_ADDED) * 1000L)
                out.add(
                    Photo(
                        id = id,
                        albumId = bucketId,
                        name = cursor.string(MediaStore.MediaColumns.DISPLAY_NAME).ifBlank {
                            if (kind == Kind.VIDEO) "Video-$id" else "Photo-$id"
                        },
                        mime = cursor.string(MediaStore.MediaColumns.MIME_TYPE).ifBlank {
                            if (kind == Kind.VIDEO) "video/mp4" else "image/jpeg"
                        },
                        size = cursor.long(MediaStore.MediaColumns.SIZE).coerceAtLeast(0),
                        dateTaken = taken.coerceAtLeast(0),
                        width = cursor.int(MediaStore.MediaColumns.WIDTH).coerceAtLeast(0),
                        height = cursor.int(MediaStore.MediaColumns.HEIGHT).coerceAtLeast(0),
                        kind = kind,
                        durationMs = if (kind == Kind.VIDEO) {
                            cursor.long(MediaStore.Video.Media.DURATION).coerceAtLeast(0)
                        } else {
                            0L
                        }
                    )
                )
            }
        }

        val hasMore = out.size > safeLimit
        if (hasMore) out.removeAt(out.lastIndex)
        return Page(out, hasMore)
    }

    fun thumbnail(kind: Kind, photoId: String): EncodedImage? = encode(kind, photoId, 360, 78)

    fun preview(kind: Kind, photoId: String): EncodedImage? = encode(kind, photoId, 1600, 88)

    fun uri(kind: Kind, photoId: String): Uri? = photoId.toLongOrNull()?.let {
        ContentUris.withAppendedId(collection(kind), it)
    }

    private fun encode(kind: Kind, photoId: String, maxSize: Int, quality: Int): EncodedImage? {
        val uri = uri(kind, photoId) ?: return null
        val bitmap = resolver.loadThumbnail(uri, Size(maxSize, maxSize), null)
        return try {
            val bytes = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
                output.toByteArray()
            }
            EncodedImage("image/jpeg", bytes)
        } finally {
            bitmap.recycle()
        }
    }

    private fun collection(kind: Kind): Uri = if (kind == Kind.VIDEO) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun projection(kind: Kind): Array<String> = if (kind == Kind.VIDEO) {
        VIDEO_PROJECTION
    } else {
        IMAGE_PROJECTION
    }

    private fun sortOrder(): String =
        "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_ADDED} DESC"

    private val Kind.mediaPermission: String
        get() = if (this == Kind.VIDEO) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_MEDIA_IMAGES
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun android.database.Cursor.string(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""
    }

    private fun android.database.Cursor.long(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else 0L
    }

    private fun android.database.Cursor.int(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    companion object {
        const val ALL_PHOTOS = "__all__"
        private const val MAX_ALBUM_SCAN = 50_000
        private const val MAX_PAGE_SIZE = 60

        private val IMAGE_PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )

        private val VIDEO_PROJECTION = IMAGE_PROJECTION + MediaStore.Video.Media.DURATION
    }
}
