package com.tqmane.wallart

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.roundToInt
import com.tqmane.wallart.storage.BitmapDecoder
import com.tqmane.wallart.storage.CardStore
import com.tqmane.wallart.storage.ImageStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

private data class PendingCrop(val cardId: String, val bitmap: Bitmap)

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var refreshCards: (() -> Unit)? = null
    private var pendingCardId: String? = null
    private var pendingCrop by mutableStateOf<PendingCrop?>(null)

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val cardId = pendingCardId ?: return@registerForActivityResult
        pendingCardId = null
        if (uri == null) return@registerForActivityResult
        val mime = contentResolver.getType(uri)?.lowercase()
        if (mime !in setOf("image/png", "image/jpeg", "image/webp")) {
            Toast.makeText(this, getString(R.string.image_format_error), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val sourceSize = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Throwable) {
            -1L
        }
        if (sourceSize > 32L * 1024L * 1024L) {
            Toast.makeText(this, getString(R.string.image_size_error), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        worker.execute {
            val bitmap = BitmapDecoder.decode(contentResolver, uri)
            runOnUiThread {
                if (bitmap == null) {
                    Toast.makeText(this, getString(R.string.image_decode_error), Toast.LENGTH_LONG).show()
                } else {
                    pendingCrop = PendingCrop(cardId, bitmap)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        for (targetPackage in arrayOf(CardStore.WALLET_PACKAGE, CardStore.GOOGLE_PAY_PACKAGE)) {
            try {
                grantUriPermission(
                    targetPackage,
                    Uri.parse("content://${CardStore.AUTHORITY}/"),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            } catch (error: SecurityException) {
                Log.w("WallArt", "Could not grant target app access to the local provider", error)
            }
        }
        enableEdgeToEdge()
        setContent {
            WallArtTheme {
                WallArtScreen(
                    pendingCrop = pendingCrop,
                    onRegisterRefresh = { refreshCards = it },
                    onSelectImage = { cardId ->
                        pendingCardId = cardId
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onCancelCrop = {
                        pendingCrop?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                        pendingCrop = null
                    },
                    onSaveCrop = { source, cropped ->
                        worker.execute {
                            var saved = false
                            var errorMessage: String? = null
                            try {
                                val extension = ImageStorage.saveCropped(this, cropped, source.cardId)
                                CardStore.setCustom(this, source.cardId, extension)
                                saved = true
                            } catch (error: Throwable) {
                                errorMessage = error.message
                            } finally {
                                if (!cropped.isRecycled) cropped.recycle()
                            }
                            runOnUiThread {
                                if (saved && pendingCrop?.bitmap === source.bitmap) {
                                    if (!source.bitmap.isRecycled) source.bitmap.recycle()
                                    pendingCrop = null
                                    refreshCards?.invoke()
                                } else if (errorMessage != null) {
                                    Toast.makeText(this, getString(R.string.crop_save_error), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    onReset = { cardId ->
                        CardStore.reset(this, cardId)
                        refreshCards?.invoke()
                    },
                    onFitChanged = { cardId, fit ->
                        CardStore.setFit(this, cardId, fit)
                        refreshCards?.invoke()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCards?.invoke()
    }

    override fun onDestroy() {
        pendingCrop?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        worker.shutdownNow()
        super.onDestroy()
    }

}

@Composable
private fun WallArtTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallArtScreen(
    pendingCrop: PendingCrop?,
    onRegisterRefresh: (((() -> Unit)?) -> Unit),
    onSelectImage: (String) -> Unit,
    onCancelCrop: () -> Unit,
    onSaveCrop: (PendingCrop, Bitmap) -> Unit,
    onReset: (String) -> Unit,
    onFitChanged: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var cards by remember { mutableStateOf(CardStore.list(context)) }
    var reloadToken by remember { mutableIntStateOf(0) }
    val currentRefresh by rememberUpdatedState {
        cards = CardStore.list(context)
        reloadToken++
    }
    DisposableEffect(Unit) {
        onRegisterRefresh { currentRefresh() }
        onDispose { onRegisterRefresh(null) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Surface(
                        modifier = Modifier.padding(start = 12.dp).size(40.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.CreditCard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                },
                title = {
                    Text(
                        stringResource(R.string.screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    IconButton(onClick = { currentRefresh() }) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = stringResource(R.string.refresh_cards))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val expanded = maxWidth >= 600.dp
            if (cards.isEmpty()) {
                EmptyState(Modifier.fillMaxSize())
            } else {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.cards_heading),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(R.string.cards_supporting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                pluralStringResource(R.plurals.cards_count, cards.size, cards.size),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    if (expanded) {
                        LazyVerticalGrid(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            columns = GridCells.Adaptive(360.dp),
                            contentPadding = PaddingValues(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            items(cards, key = { it.id }) { card ->
                                CardArtCard(card, reloadToken, onSelectImage, onReset, onFitChanged)
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(cards, key = { it.id }) { card ->
                                CardArtCard(card, reloadToken, onSelectImage, onReset, onFitChanged)
                            }
                        }
                    }
                }
            }
        }
    }
    pendingCrop?.let { source ->
        CropEditorDialog(
            bitmap = source.bitmap,
            onCancel = onCancelCrop,
            onSave = { cropped -> onSaveCrop(source, cropped) },
        )
    }
}

@Composable
private fun CropEditorDialog(bitmap: Bitmap, onCancel: () -> Unit, onSave: (Bitmap) -> Unit) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var translation by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val cardShape = MaterialTheme.shapes.extraLarge
    val cropPreviewDescription = stringResource(R.string.crop_preview_description)
    val cropZoomLabel = stringResource(R.string.crop_zoom)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val expanded = maxWidth >= 600.dp
            Surface(
                modifier = if (expanded) Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.9f)
                else Modifier.fillMaxSize(),
                shape = if (expanded) cardShape else RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.crop_cancel))
                        }
                        Text(
                            stringResource(R.string.crop_title),
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        stringResource(R.string.crop_instructions),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .aspectRatio(700f / 440f)
                                .clip(cardShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .onSizeChanged { viewport = it }
                                .semantics { contentDescription = cropPreviewDescription }
                                .pointerInput(bitmap, viewport) {
                                    detectTransformGestures { centroid, panDelta, zoomDelta, _ ->
                                        if (viewport.width <= 0 || viewport.height <= 0) return@detectTransformGestures
                                        val nextZoom = (zoom * zoomDelta).coerceIn(1f, 4f)
                                        val ratio = nextZoom / zoom
                                        val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                        val focus = centroid - center
                                        val next = Offset(
                                            focus.x + (translation.x - focus.x) * ratio + panDelta.x,
                                            focus.y + (translation.y - focus.y) * ratio + panDelta.y,
                                        )
                                        val clamped = CropGeometry.clampOffset(
                                            bitmap.width, bitmap.height, viewport.width, viewport.height,
                                            nextZoom, next.x, next.y,
                                        )
                                        zoom = nextZoom
                                        translation = Offset(clamped[0], clamped[1])
                                    }
                                }
                        ) {
                            if (viewport.width > 0 && viewport.height > 0) {
                                val clamped = CropGeometry.clampOffset(
                                    bitmap.width, bitmap.height, viewport.width, viewport.height,
                                    zoom, translation.x, translation.y,
                                )
                                val scale = max(size.width / bitmap.width, size.height / bitmap.height) * zoom
                                val drawWidth = (bitmap.width * scale).roundToInt()
                                val drawHeight = (bitmap.height * scale).roundToInt()
                                drawImage(
                                    image = image,
                                    dstOffset = IntOffset(
                                        ((size.width - drawWidth) / 2f + clamped[0]).roundToInt(),
                                        ((size.height - drawHeight) / 2f + clamped[1]).roundToInt(),
                                    ),
                                    dstSize = IntSize(drawWidth, drawHeight),
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(cropZoomLabel, style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = zoom,
                            onValueChange = { value ->
                                zoom = value
                                if (viewport.width > 0 && viewport.height > 0) {
                                    val clamped = CropGeometry.clampOffset(
                                        bitmap.width, bitmap.height, viewport.width, viewport.height,
                                        zoom, translation.x, translation.y,
                                    )
                                    translation = Offset(clamped[0], clamped[1])
                                }
                            },
                            valueRange = 1f..4f,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                contentDescription = cropZoomLabel
                            },
                        )
                        Text(
                            stringResource(R.string.crop_zoom_percent, (zoom * 100).roundToInt()),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                if (viewport.width > 0 && viewport.height > 0) {
                                    onSave(createCroppedBitmap(bitmap, viewport, zoom, translation))
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.crop_save))
                        }
                    }
                }
            }
        }
    }
}

private fun createCroppedBitmap(source: Bitmap, viewport: IntSize, zoom: Float, translation: Offset): Bitmap {
    val width = 700
    val height = 440
    val clamped = CropGeometry.clampOffset(
        source.width, source.height, viewport.width, viewport.height, zoom, translation.x, translation.y,
    )
    val scale = max(width / source.width.toFloat(), height / source.height.toFloat()) * zoom
    val offsetX = clamped[0] / viewport.width * width
    val offsetY = clamped[1] / viewport.height * height
    val drawWidth = source.width * scale
    val drawHeight = source.height * scale
    val destination = RectF(
        (width - drawWidth) / 2f + offsetX,
        (height - drawHeight) / 2f + offsetY,
        (width + drawWidth) / 2f + offsetX,
        (height + drawHeight) / 2f + offsetY,
    )
    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    android.graphics.Canvas(result).drawBitmap(source, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    return result
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().animateContentSize(spring()),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.CreditCard,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(stringResource(R.string.no_cards_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.no_cards_message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun CardArtCard(
    card: CardStore.Card,
    reloadToken: Int,
    onSelectImage: (String) -> Unit,
    onReset: (String) -> Unit,
    onFitChanged: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val displayLabel = when {
        card.label == "Google Wallet card" -> stringResource(R.string.generic_wallet_card)
        card.label.startsWith("Wallet card · ") -> stringResource(
            R.string.wallet_card_alias,
            card.label.substringAfter('·').trim(),
        )
        else -> card.label
    }
    val network = if (card.network == "Card") stringResource(R.string.network_card) else card.network
    val originalBitmap by produceState<Bitmap?>(null, card.id, card.originalArtUri, reloadToken) {
        value = card.originalArtUri?.let { uri ->
            withContext(Dispatchers.IO) { BitmapDecoder.decode(context.contentResolver, uri) }
        }
    }
    val bitmap by produceState<Bitmap?>(null, card.id, card.extension, reloadToken) {
        value = if (card.hasCustomArt()) {
            withContext(Dispatchers.IO) {
                BitmapDecoder.decodeFile(File(CardStore.artDir(context), "${card.id}.${card.extension}"))
            }
        } else {
            null
        }
    }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displayLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        network + (card.lastFour?.let { "  •••• $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (card.hasCustomArt()) Icons.Outlined.CheckCircle else Icons.Outlined.CreditCard,
                    contentDescription = null,
                    tint = if (card.hasCustomArt()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PreviewPane(stringResource(R.string.original_preview), originalBitmap, Modifier.weight(1f))
                PreviewPane(stringResource(R.string.custom_preview), bitmap, Modifier.weight(1f), card.fit)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSelectImage(card.id) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.select_image))
                }
                OutlinedButton(
                    onClick = { onReset(card.id) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.reset))
                }
            }

            FitModeMenu(card.fit) { onFitChanged(card.id, it) }

            Text(
                stringResource(if (card.hasCustomArt()) R.string.custom_art_active else R.string.wallet_art_active),
                style = MaterialTheme.typography.labelLarge,
                color = if (card.hasCustomArt()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreviewPane(
    title: String,
    bitmap: Bitmap?,
    modifier: Modifier,
    fit: String = FitMode.FIT_CENTER,
) {
    Surface(
        modifier = modifier.height(132.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.artwork_preview_description, title),
                    modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraLarge),
                    contentScale = when (fit) {
                        FitMode.CENTER_CROP -> ContentScale.Crop
                        FitMode.CENTER_INSIDE -> ContentScale.Inside
                        else -> ContentScale.Fit
                    },
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            ) {
                Text(
                    title,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FitModeMenu(value: String, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val modes = listOf(FitMode.FIT_CENTER, FitMode.CENTER_CROP, FitMode.CENTER_INSIDE)
    val currentLabel = fitModeLabel(value)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.fit_mode)) },
            leadingIcon = { Icon(Icons.Outlined.Crop, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(fitModeLabel(mode)) },
                    onClick = {
                        expanded = false
                        onValueChange(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun fitModeLabel(mode: String): String = when (mode) {
    FitMode.CENTER_CROP -> stringResource(R.string.center_crop)
    FitMode.CENTER_INSIDE -> stringResource(R.string.center_inside)
    else -> stringResource(R.string.fit_center)
}
