package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.RequestrDatabase
import com.example.data.models.CapturedTrafficItem
import com.example.ui.components.formatByteCount
import com.example.interception.http.HttpBodyDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * C1, extended for the §2.14 binary-body-persistence follow-up: the outcome of trying to render
 * one [CapturedTrafficItem]'s response as an image.
 *
 * Real captured binary image bytes are now recoverable via [com.example.data.local.CapturedTrafficEntity.responseBodyBytes]
 * whenever [com.example.data.local.CapturedTrafficEntity.isResponseBodyBinary] is true and the
 * body was within [HttpBodyDecoder.MAX_STORED_BODY_BYTES] -- that's [Decoded]. When the real
 * bytes exceeded the cap ([com.example.data.local.CapturedTrafficEntity.responseBodyBytesTruncated]),
 * nothing was stored and [ExceededCap] carries the true observed size so the UI can say exactly
 * why, instead of a generic "not stored" placeholder. [Unavailable] covers every other
 * non-renderable case: no binary bytes AND no usable base64-text body (the older, still-supported
 * `data:image/...;base64,...` text case -- see [decodeCapturedImage]), or bytes that exist but
 * fail to decode as an image (corrupt/truncated payload).
 */
private sealed interface GalleryImageState {
    data object Loading : GalleryImageState
    data class Decoded(val bitmap: Bitmap) : GalleryImageState
    data class ExceededCap(val trueSizeBytes: Long) : GalleryImageState
    data object Unavailable : GalleryImageState
}

/**
 * Attempts to decode [item]'s response body into a [Bitmap] from real base64 *text* (either a
 * bare base64 string, or a `data:image/...;base64,` URI) -- the case a captured "image" response
 * has genuine renderable bytes as a plain [String]. Returns null (never throws) for anything
 * else, including a genuinely blank/placeholder body. Kept as the fallback path for rows whose
 * real binary bytes weren't persisted (see [GalleryImageState]'s doc) or that predate binary-body
 * persistence.
 */
private fun decodeCapturedImage(item: CapturedTrafficItem): Bitmap? {
    val raw = stripDataUriPrefix(item.responseBody).trim()
    if (raw.isEmpty()) return null
    return runCatching {
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        decodeBitmapSafely(bytes)
    }.getOrNull()
}

/**
 * Decodes [bytes] as a bitmap, guarding against malformed/corrupt image data. `BitmapFactory`
 * already returns null (rather than throwing) for most malformed input, but a handful of
 * genuinely broken payloads throw instead -- `runCatching` turns those into the same "couldn't
 * render" outcome as a clean null, so a corrupt captured body can never crash the gallery.
 */
private fun decodeBitmapSafely(bytes: ByteArray): Bitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

/**
 * Resolves [item]'s [GalleryImageState] using the real, byte-exact stored body when available
 * (falling back to the base64-text path). Suspends only for the `CapturedTrafficDao.getById`
 * query and the bitmap decode itself -- callers must invoke this off the main thread (see
 * [rememberGalleryImageState]), since both a Room read and `BitmapFactory.decodeByteArray` are
 * blocking calls that must never run on the UI thread.
 */
private suspend fun loadGalleryImageState(context: Context, item: CapturedTrafficItem): GalleryImageState {
    val entity = item.id.toLongOrNull()?.let {
        runCatching { RequestrDatabase.get(context).capturedTrafficDao().getById(it) }.getOrNull()
    }
    if (entity != null && entity.isResponseBodyBinary) {
        val bytes = entity.responseBodyBytes
        if (entity.responseBodyBytesTruncated || bytes == null) {
            return GalleryImageState.ExceededCap(entity.responseSizeBytes)
        }
        val bitmap = decodeBitmapSafely(bytes)
        return if (bitmap != null) GalleryImageState.Decoded(bitmap) else GalleryImageState.Unavailable
    }
    val bitmap = decodeCapturedImage(item)
    return if (bitmap != null) GalleryImageState.Decoded(bitmap) else GalleryImageState.Unavailable
}

/** Loads [item]'s [GalleryImageState] off the main thread, re-running whenever [item] changes. */
@Composable
private fun rememberGalleryImageState(item: CapturedTrafficItem): GalleryImageState {
    val context = LocalContext.current
    return produceState<GalleryImageState>(initialValue = GalleryImageState.Loading, item.id) {
        value = withContext(Dispatchers.IO) { loadGalleryImageState(context, item) }
    }.value
}

/**
 * C1: grid of every captured response whose content-type is `image/...` ([extractImageItems]),
 * decoding real bytes off the main thread wherever they're actually available
 * ([loadGalleryImageState]) and showing an honest, size-accurate "exceeded the capture cap"
 * placeholder only in the genuinely-not-stored case. Tapping a tile opens a full-size preview;
 * from there "Open request" routes back to [onOpenTransaction] for that capture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGalleryBottomSheet(
    items: List<CapturedTrafficItem>,
    onDismiss: () -> Unit,
    onOpenTransaction: (CapturedTrafficItem) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val imageItems = remember(items) { extractImageItems(items) }
    var previewItem by remember { mutableStateOf<CapturedTrafficItem?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Image gallery",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${imageItems.size} image response(s)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (imageItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No image responses captured yet",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(imageItems, key = { it.id }) { item ->
                        ImageGalleryTile(item = item, onClick = { previewItem = item })
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    previewItem?.let { item ->
        ImagePreviewDialog(
            item = item,
            onDismiss = { previewItem = null },
            onOpenTransaction = {
                previewItem = null
                onOpenTransaction(item)
            }
        )
    }
}

@Composable
private fun ImageGalleryTile(item: CapturedTrafficItem, onClick: () -> Unit) {
    val state = rememberGalleryImageState(item)
    Column(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            when (state) {
                is GalleryImageState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                }
                is GalleryImageState.Decoded -> {
                    Image(
                        painter = BitmapPainter(state.bitmap.asImageBitmap()),
                        contentDescription = item.url,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is GalleryImageState.ExceededCap, is GalleryImageState.Unavailable -> {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = "Image bytes not available",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.width(28.dp)
                    )
                }
            }
        }
        Text(
            text = item.host,
            fontSize = 9.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ImagePreviewDialog(
    item: CapturedTrafficItem,
    onDismiss: () -> Unit,
    onOpenTransaction: () -> Unit
) {
    val state = rememberGalleryImageState(item)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.host,
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(item.url, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, maxLines = 2)
            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    is GalleryImageState.Loading -> {
                        CircularProgressIndicator()
                    }
                    is GalleryImageState.Decoded -> {
                        Image(
                            painter = BitmapPainter(state.bitmap.asImageBitmap()),
                            contentDescription = item.url,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is GalleryImageState.ExceededCap -> {
                        BrokenImageExplanation(
                            // §2.14 follow-up: name the real cap and the real observed size,
                            // never a generic "not stored" -- see exceededCapMessage's KDoc.
                            message = exceededCapMessage(
                                trueSizeBytes = state.trueSizeBytes,
                                capBytes = HttpBodyDecoder.MAX_STORED_BODY_BYTES.toLong(),
                            )
                        )
                    }
                    is GalleryImageState.Unavailable -> {
                        BrokenImageExplanation(
                            message = "This response's image bytes couldn't be decoded, or weren't " +
                                "captured as real bytes/base64 text " +
                                "(${formatByteCount(item.responseSize)} reported)."
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onOpenTransaction, modifier = Modifier.fillMaxWidth()) {
                Text("Open request")
            }
        }
    }
}

@Composable
private fun BrokenImageExplanation(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = message,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
