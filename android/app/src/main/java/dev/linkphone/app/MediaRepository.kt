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

/** Read-only access to the phone's MediaStore photo collection. */
class MediaRepository(private val context: Context) {

    data class Album(
        val id: String,
        val name: String,
        val count: Int,
        val coverPhotoId: String
    )

    data class Photo(
        val id: String,
        val albumId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val dateTaken: Long,
        val width: Int,
        val height: Int
    )

    data class Page(val photos: List<Photo>, val hasMore: Boolean)

    data class EncodedImage(val mime: String, val bytes: ByteArray)

    private val resolver = context.contentResolver
    private val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun hasPermission(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun albums(): List<Album> {
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
            collection,
            arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            ),
            null,
            null,
            SORT_ORDER
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
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
        return listOf(Album(ALL_PHOTOS, "All photos", allCount, allCover)) +
            found.values.map { Album(it.id, it.name, it.count, it.coverPhotoId) }
    }

    fun photos(albumId: String, offset: Int, limit: Int): Page {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val selection = if (albumId == ALL_PHOTOS) null else "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val args = if (selection == null) null else arrayOf(albumId)
        val out = ArrayList<Photo>(safeLimit + 1)

        resolver.query(collection, PHOTO_PROJECTION, selection, args, SORT_ORDER)?.use { cursor ->
            if (safeOffset > 0 && !cursor.moveToPosition(safeOffset - 1)) return@use

            while (cursor.moveToNext() && out.size <= safeLimit) {
                val id = cursor.string(MediaStore.Images.Media._ID)
                if (id.isEmpty()) continue
                val bucketId = cursor.string(MediaStore.Images.Media.BUCKET_ID).ifBlank { "other" }
                val taken = cursor.long(MediaStore.Images.Media.DATE_TAKEN)
                    .takeIf { it > 0 }
                    ?: (cursor.long(MediaStore.Images.Media.DATE_ADDED) * 1000L)
                out.add(
                    Photo(
                        id = id,
                        albumId = bucketId,
                        name = cursor.string(MediaStore.Images.Media.DISPLAY_NAME).ifBlank {
                            "Photo-$id"
                        },
                        mime = cursor.string(MediaStore.Images.Media.MIME_TYPE).ifBlank {
                            "image/jpeg"
                        },
                        size = cursor.long(MediaStore.Images.Media.SIZE).coerceAtLeast(0),
                        dateTaken = taken.coerceAtLeast(0),
                        width = cursor.int(MediaStore.Images.Media.WIDTH).coerceAtLeast(0),
                        height = cursor.int(MediaStore.Images.Media.HEIGHT).coerceAtLeast(0)
                    )
                )
            }
        }

        val hasMore = out.size > safeLimit
        if (hasMore) out.removeAt(out.lastIndex)
        return Page(out, hasMore)
    }

    fun thumbnail(photoId: String): EncodedImage? = encode(photoId, 360, 78)

    fun preview(photoId: String): EncodedImage? = encode(photoId, 1600, 88)

    fun uri(photoId: String): Uri? = photoId.toLongOrNull()?.let {
        ContentUris.withAppendedId(collection, it)
    }

    private fun encode(photoId: String, maxSize: Int, quality: Int): EncodedImage? {
        val uri = uri(photoId) ?: return null
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

        private const val SORT_ORDER =
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"

        private val PHOTO_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
    }
}
