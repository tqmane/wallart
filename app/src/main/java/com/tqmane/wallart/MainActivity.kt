package com.tqmane.wallart

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tqmane.wallart.storage.BitmapDecoder
import com.tqmane.wallart.storage.CardStore
import com.tqmane.wallart.storage.ImageStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

// クレジットカード規格（ISO/IEC 7810 ID-1: 85.60mm x 53.98mm）および切り抜き解像度（700x440）に完全一致する比率
private const val CARD_ASPECT_RATIO = 700f / 440f
// 実寸比率（幅の約3.7%）とGoogle WalletのカードUIに準拠した自然な角丸
private val CARD_CORNER_RADIUS = 12.dp

private data class PendingCrop(val cardId: String, val bitmap: Bitmap)

class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var refreshCards: (() -> Unit)? = null
    private var pendingCardId: String? = null
    private var pendingCrop by mutableStateOf<PendingCrop?>(null)
    private var pendingGmsLink by mutableStateOf<CardStore.PendingGmsLink?>(null)
    private var showGmsLinkDialog by mutableStateOf(false)
    private var returnToGmsAfterLink = false

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
        CardStore.grantTargetUriAccess(this)
        handleGmsLinkIntent(intent)
        enableEdgeToEdge()
        setContent {
            WallArtTheme {
                WallArtScreen(
                    pendingCrop = pendingCrop,
                    pendingGmsLink = pendingGmsLink,
                    showGmsLinkDialog = showGmsLinkDialog,
                    onRegisterRefresh = { refreshCards = it },
                    onOpenGmsLinkDialog = { showGmsLinkDialog = true },
                    onDismissGmsLinkDialog = {
                        showGmsLinkDialog = false
                        returnToGmsAfterLink = false
                    },
                    onLinkGmsCard = { gmsId, cardId ->
                        val linked = CardStore.linkGmsCard(this, gmsId, cardId)
                        if (linked) {
                            pendingGmsLink = CardStore.pendingGmsLink(this)
                            showGmsLinkDialog = false
                            refreshCards?.invoke()
                            if (returnToGmsAfterLink) {
                                returnToGmsAfterLink = false
                                finish()
                            }
                        } else {
                            Toast.makeText(this, getString(R.string.gms_link_error), Toast.LENGTH_LONG).show()
                        }
                        linked
                    },
                    onSelectImage = { cardId ->
                        pendingCardId = cardId
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onAdjustCrop = { cardId, bitmap ->
                        // 既存のカスタム画像から再度クロップを開く
                        val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
                        if (copy != null) {
                            pendingCrop = PendingCrop(cardId, copy)
                        }
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

    private fun handleGmsLinkIntent(intent: Intent?) {
        pendingGmsLink = CardStore.pendingGmsLink(this)
        showGmsLinkDialog = intent?.getBooleanExtra(CardStore.EXTRA_OPEN_GMS_LINK, false) == true
                && pendingGmsLink != null
        returnToGmsAfterLink = showGmsLinkDialog
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGmsLinkIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        pendingGmsLink = CardStore.pendingGmsLink(this)
        refreshCards?.invoke()
    }

    override fun onDestroy() {
        pendingCrop?.bitmap?.let { if (!it.isRecycled) it.recycle() }
        worker.shutdownNow()
        super.onDestroy()
    }
}

private fun launchGoogleWallet(context: Context) {
    try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(CardStore.WALLET_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        } else {
            val playStoreIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=${CardStore.WALLET_PACKAGE}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(playStoreIntent)
        }
    } catch (_: Throwable) {
        Toast.makeText(context, context.getString(R.string.no_cards_message), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun WallArtTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
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
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallArtScreen(
    pendingCrop: PendingCrop?,
    pendingGmsLink: CardStore.PendingGmsLink?,
    showGmsLinkDialog: Boolean,
    onRegisterRefresh: (((() -> Unit)?) -> Unit),
    onOpenGmsLinkDialog: () -> Unit,
    onDismissGmsLinkDialog: () -> Unit,
    onLinkGmsCard: (String, String) -> Boolean,
    onSelectImage: (String) -> Unit,
    onAdjustCrop: (String, Bitmap) -> Unit,
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.screen_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                navigationIcon = {
                    Surface(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(40.dp),
                        shape = CircleShape,
                        color = Color(0xFF222224),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_app_icon),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { launchGoogleWallet(context) },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = stringResource(R.string.open_wallet),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { currentRefresh() }) {
                        Icon(
                            Icons.Outlined.RestartAlt,
                            contentDescription = stringResource(R.string.refresh_cards),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val expanded = maxWidth >= 600.dp
            val customCount = remember(cards) { cards.count { it.hasCustomArt() } }

            if (cards.isEmpty()) {
                EmptyState(
                    onOpenWallet = { launchGoogleWallet(context) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val headerContent: @Composable () -> Unit = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.cards_supporting),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Surface(
                                shape = RoundedCornerShape(100.dp),
                                color = if (customCount > 0) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = if (customCount > 0) {
                                        stringResource(R.string.custom_count_badge, customCount, cards.size)
                                    } else {
                                        pluralStringResource(R.plurals.cards_count, cards.size, cards.size)
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (customCount > 0) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }

                        pendingGmsLink?.let { link ->
                            val linkedCard = cards.firstOrNull { it.id == link.linkedCardId }
                            GmsLinkBanner(
                                link = link,
                                linkedCard = linkedCard,
                                onOpenDialog = onOpenGmsLinkDialog,
                            )
                        }
                    }
                }

                if (expanded) {
                    LazyVerticalGrid(
                        modifier = Modifier.fillMaxSize(),
                        columns = GridCells.Adaptive(380.dp),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            headerContent()
                        }
                        items(cards, key = { it.id }) { card ->
                            CardArtCard(
                                card = card,
                                reloadToken = reloadToken,
                                onSelectImage = onSelectImage,
                                onAdjustCrop = onAdjustCrop,
                                onReset = onReset,
                                onFitChanged = onFitChanged,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            headerContent()
                        }
                        items(cards, key = { it.id }) { card ->
                            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                                CardArtCard(
                                    card = card,
                                    reloadToken = reloadToken,
                                    onSelectImage = onSelectImage,
                                    onAdjustCrop = onAdjustCrop,
                                    onReset = onReset,
                                    onFitChanged = onFitChanged,
                                )
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

    if (showGmsLinkDialog && pendingGmsLink != null) {
        GmsCardLinkDialog(
            link = pendingGmsLink,
            cards = cards,
            onDismiss = onDismissGmsLinkDialog,
            onLink = onLinkGmsCard,
        )
    }
}

@Composable
private fun GmsLinkBanner(
    link: CardStore.PendingGmsLink,
    linkedCard: CardStore.Card?,
    onOpenDialog: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.CreditCard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.gms_link_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = if (linkedCard == null) {
                        stringResource(R.string.gms_link_banner_supporting, link.label)
                    } else {
                        stringResource(R.string.gms_link_banner_linked, link.label, linkedCard.label)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                )
            }
            FilledTonalButton(
                onClick = onOpenDialog,
                shape = RoundedCornerShape(100.dp),
            ) {
                Text(stringResource(R.string.gms_link_open))
            }
        }
    }
}

@Composable
private fun CardArtCard(
    card: CardStore.Card,
    reloadToken: Int,
    onSelectImage: (String) -> Unit,
    onAdjustCrop: (String, Bitmap) -> Unit,
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
            .animateContentSize(animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ヘッダー情報（カードタイトル・ブランド名・ステータスバッジ）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = network + (card.lastFour?.let { "  •••• $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = if (card.hasCustomArt()) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (card.hasCustomArt()) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Text(
                            text = stringResource(
                                if (card.hasCustomArt()) R.string.custom_preview else R.string.original_preview
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (card.hasCustomArt()) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // カードプレビューエリア（元の券面 vs カスタム券面）
            // アスペクト比 CARD_ASPECT_RATIO (700:440) と角丸 CARD_CORNER_RADIUS (12dp) により
            // 上下の余白と過剰な丸みを完全に解消
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OriginalCardPane(
                    bitmap = originalBitmap,
                    modifier = Modifier.weight(1f),
                )
                CustomCardPane(
                    bitmap = bitmap,
                    fit = card.fit,
                    hasCustomArt = card.hasCustomArt(),
                    onSelectImage = { onSelectImage(card.id) },
                    onEditCrop = if (bitmap != null) {
                        { onAdjustCrop(card.id, bitmap!!) }
                    } else null,
                    modifier = Modifier.weight(1f),
                )
            }

            // 画像の配置モード（M3 Expressive SingleChoiceSegmentedButtonRow）
            FitModeSegmentedControl(
                selectedFit = card.fit,
                onFitChanged = { onFitChanged(card.id, it) },
            )

            // アクションボタン列
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onSelectImage(card.id) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp),
                    shape = RoundedCornerShape(100.dp),
                ) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(if (card.hasCustomArt()) R.string.change_image else R.string.select_image),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                if (card.hasCustomArt()) {
                    OutlinedButton(
                        onClick = { onReset(card.id) },
                        modifier = Modifier.heightIn(min = 44.dp),
                        shape = RoundedCornerShape(100.dp),
                    ) {
                        Icon(
                            Icons.Outlined.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.reset),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OriginalCardPane(
    bitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .aspectRatio(CARD_ASPECT_RATIO)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(CARD_CORNER_RADIUS),
            ),
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.original_preview),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp),
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            ) {
                Text(
                    text = stringResource(R.string.original_preview),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CustomCardPane(
    bitmap: Bitmap?,
    fit: String,
    hasCustomArt: Boolean,
    onSelectImage: () -> Unit,
    onEditCrop: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .aspectRatio(CARD_ASPECT_RATIO)
            .border(
                width = 1.dp,
                color = if (hasCustomArt) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(CARD_CORNER_RADIUS),
            ),
        shape = RoundedCornerShape(CARD_CORNER_RADIUS),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = if (hasCustomArt) 1.dp else 0.dp,
    ) {
        if (hasCustomArt && bitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (onEditCrop != null) {
                            Modifier.clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.adjust_crop),
                                onClick = onEditCrop,
                            )
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.custom_preview),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = when (fit) {
                        FitMode.CENTER_CROP -> ContentScale.Crop
                        FitMode.CENTER_INSIDE -> ContentScale.Inside
                        else -> ContentScale.Fit
                    },
                )

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                ) {
                    Text(
                        text = stringResource(R.string.custom_preview),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                if (onEditCrop != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(24.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.Crop,
                                contentDescription = stringResource(R.string.adjust_crop),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        } else {
            // カスタム未設定時のプレースホルダー（タップで画像選択）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.add_custom_art),
                        onClick = onSelectImage,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.add_custom_art),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FitModeSegmentedControl(
    selectedFit: String,
    onFitChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val modes = listOf(
        FitMode.FIT_CENTER to Pair(stringResource(R.string.fit_label_fit), Icons.Outlined.FitScreen),
        FitMode.CENTER_CROP to Pair(stringResource(R.string.fit_label_crop), Icons.Outlined.CropFree),
        FitMode.CENTER_INSIDE to Pair(stringResource(R.string.fit_label_inside), Icons.Outlined.CenterFocusStrong),
    )
    val selectedIndex = modes.indexOfFirst { it.first == selectedFit }.let { if (it == -1) 0 else it }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.fit_mode),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, labelAndIcon) ->
                SegmentedButton(
                    selected = index == selectedIndex,
                    onClick = { onFitChanged(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    icon = {
                        SegmentedButtonDefaults.Icon(active = index == selectedIndex) {
                            Icon(
                                labelAndIcon.second,
                                contentDescription = null,
                                modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                            )
                        }
                    },
                ) {
                    Text(
                        labelAndIcon.first,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    onOpenWallet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().animateContentSize(spring()),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.no_cards_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.no_cards_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onOpenWallet,
                    shape = RoundedCornerShape(100.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.empty_open_wallet))
                }
            }
        }
    }
}

@Composable
private fun GmsCardLinkDialog(
    link: CardStore.PendingGmsLink,
    cards: List<CardStore.Card>,
    onDismiss: () -> Unit,
    onLink: (String, String) -> Boolean,
) {
    val linkableCards = remember(cards) { cards.filter { it.hasCustomArt() } }
    var selectedCardId by remember(link.id, link.linkedCardId) { mutableStateOf(link.linkedCardId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.CreditCard, contentDescription = null) },
        title = { Text(stringResource(R.string.gms_link_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.gms_link_dialog_supporting, link.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (linkableCards.isEmpty()) {
                    Text(
                        text = stringResource(R.string.gms_link_no_custom_cards),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(linkableCards, key = { it.id }) { card ->
                            GmsLinkCardOption(card, selectedCardId == card.id) {
                                selectedCardId = card.id
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedCardId?.let { onLink(link.id, it) } },
                enabled = selectedCardId != null && linkableCards.any { it.id == selectedCardId },
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(100.dp),
            ) {
                Text(stringResource(R.string.gms_link_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.gms_link_cancel))
            }
        },
    )
}

@Composable
private fun GmsLinkCardOption(card: CardStore.Card, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, card.id, card.extension) {
        value = withContext(Dispatchers.IO) {
            BitmapDecoder.decodeFile(File(CardStore.artDir(context), "${card.id}.${card.extension}"))
        }
    }
    val label = when {
        card.label == "Google Wallet card" -> stringResource(R.string.generic_wallet_card)
        card.label.startsWith("Wallet card · ") -> stringResource(
            R.string.wallet_card_alias,
            card.label.substringAfter('·').trim(),
        )
        else -> card.label
    }
    val network = if (card.network == "Card") stringResource(R.string.network_card) else card.network
    val shape = MaterialTheme.shapes.large

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .border(
                1.5.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape,
            ),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.gms_link_preview_description, label),
                    modifier = Modifier
                        .size(width = 72.dp, height = 45.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Surface(
                    modifier = Modifier.size(width = 72.dp, height = 45.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.CreditCard, contentDescription = null)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = network + (card.lastFour?.let { "  •••• $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun CropEditorDialog(bitmap: Bitmap, onCancel: () -> Unit, onSave: (Bitmap) -> Unit) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var translation by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember(bitmap) { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val cropShape = RoundedCornerShape(CARD_CORNER_RADIUS)
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
                shape = if (expanded) MaterialTheme.shapes.extraLarge else RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxSize()) {
                    // ダイアログヘッダー
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.crop_cancel))
                        }
                        Text(
                            text = stringResource(R.string.crop_title),
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Button(
                            onClick = {
                                if (viewport.width > 0 && viewport.height > 0) {
                                    onSave(createCroppedBitmap(bitmap, viewport, zoom, translation))
                                }
                            },
                            shape = RoundedCornerShape(100.dp),
                            modifier = Modifier.heightIn(min = 40.dp),
                        ) {
                            Text(stringResource(R.string.crop_save))
                        }
                    }

                    Text(
                        text = stringResource(R.string.crop_instructions),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // クロッププレビュー（CARD_CORNER_RADIUS=12dp の角丸で描画）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .aspectRatio(CARD_ASPECT_RATIO)
                                .clip(cropShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    shape = cropShape,
                                )
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

                    // ズームスライダー
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = cropZoomLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
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
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = cropZoomLabel },
                        )
                        Text(
                            text = stringResource(R.string.crop_zoom_percent, (zoom * 100).roundToInt()),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
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
