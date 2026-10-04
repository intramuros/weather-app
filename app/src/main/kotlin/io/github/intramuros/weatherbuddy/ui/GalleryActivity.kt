package io.github.intramuros.weatherbuddy.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.SceneKind
import io.github.intramuros.weatherbuddy.core.ScenePicture
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.core.TimeOfDay
import io.github.intramuros.weatherbuddy.data.SettingsRepository
import io.github.intramuros.weatherbuddy.labelRes
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Every picture of every style, as a grid; tap one to see it whole and swipe through the rest. */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initial = Style.fromSlug(intent.getStringExtra(EXTRA_STYLE))
        setContent {
            WeatherBuddyTheme {
                GalleryScreen(initial, onBack = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_STYLE = "style"

        /** Opens the gallery on [style]'s pictures. */
        fun intent(context: Context, style: Style): Intent =
            Intent(context, GalleryActivity::class.java).putExtra(EXTRA_STYLE, style.slug)
    }
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val assets = app.assets

    val styles: List<Style> = SettingsRepository(app).availableStyles.ifEmpty { listOf(Style.PIXEL_ART) }

    private val thumbnails = object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun thumbnail(style: Style, picture: ScenePicture): Bitmap? {
        val path = style.scene(picture)
        return thumbnails.get(path) ?: decode(path, THUMB_SAMPLE)?.also { thumbnails.put(path, it) }
    }

    suspend fun picture(style: Style, picture: ScenePicture): Bitmap? = decode(style.scene(picture), 1)

    private suspend fun decode(path: String, sampleSize: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        /** The pictures are 1200 px square; a quarter of that is plenty for a grid cell. */
        const val THUMB_SAMPLE = 4

        /** Room for two styles' thumbnails, so switching back and forth doesn't decode again. */
        const val THUMB_CACHE_BYTES = 24 * 1024 * 1024
    }
}

@Composable
private fun GalleryScreen(initial: Style?, onBack: () -> Unit, vm: GalleryViewModel = viewModel()) {
    val styles = vm.styles
    var styleSlug by rememberSaveable { mutableStateOf((initial?.takeIf { it in styles } ?: styles.first()).slug) }
    val style = Style.fromSlug(styleSlug)?.takeIf { it in styles } ?: styles.first()
    // The picture shown full screen, by its index in ScenePicture.entries, and the one last shown.
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    var lastOpen by rememberSaveable { mutableStateOf<Int?>(null) }
    val grid = rememberLazyGridState()

    open?.let { index ->
        BackHandler { open = null }
        Viewer(
            style = style,
            initialPage = index,
            vm = vm,
            onPage = { open = it; lastOpen = it },
            onClose = { open = null },
        )
        return
    }

    // Coming back from the viewer after swiping away, keep the last picture in sight.
    LaunchedEffect(lastOpen) {
        val index = lastOpen ?: return@LaunchedEffect
        val visible = snapshotFlow { grid.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
        if (visible.none { it.index == index }) grid.scrollToItem(index)
    }

    Scaffold { padding ->
        Column(Modifier.padding(padding)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.gallery_back))
                }
                Text(stringResource(R.string.gallery_title), style = MaterialTheme.typography.titleLarge)
            }
            if (styles.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    styles.forEach {
                        FilterChip(
                            selected = it == style,
                            onClick = { styleSlug = it.slug },
                            label = { Text(it.displayName) },
                        )
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                state = grid,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(ScenePicture.entries, key = { _, picture -> "${style.slug}/${picture.slug}" }) { index, picture ->
                    Thumbnail(style, picture, vm, onClick = { open = index; lastOpen = index })
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(style: Style, picture: ScenePicture, vm: GalleryViewModel, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, style, picture) { value = vm.thumbnail(style, picture) }
    val (name, detail) = pictureLabel(picture)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(role = Role.Image, onClick = onClick)
            .clearAndSetSemantics { contentDescription = "$name, $detail" },
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = if (style.pixelated) FilterQuality.None else FilterQuality.Low,
                )
            }
        }
        Text(name, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** One picture whole, on black; swipe for the style's other pictures. */
@Composable
private fun Viewer(style: Style, initialPage: Int, vm: GalleryViewModel, onPage: (Int) -> Unit, onClose: () -> Unit) {
    val pictures = ScenePicture.entries
    val pager = rememberPagerState(initialPage = initialPage) { pictures.size }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect(onPage) }

    // Light status and navigation bar icons over the black, whatever the theme.
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        onDispose { activity?.enableEdgeToEdge() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(state = pager, key = { pictures[it].slug }, modifier = Modifier.fillMaxSize()) { page ->
            val picture = pictures[page]
            val bitmap by produceState<Bitmap?>(null, style, picture) { value = vm.picture(style, picture) }
            val (name, detail) = pictureLabel(picture)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = stringResource(R.string.gallery_picture_description, name, detail),
                        contentScale = ContentScale.Fit,
                        filterQuality = if (style.pixelated) FilterQuality.None else FilterQuality.Low,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.gallery_back), tint = Color.White)
        }

        val (name, detail) = pictureLabel(pictures[pager.currentPage])
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(24.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(
                stringResource(R.string.gallery_position, detail, pager.currentPage + 1, pictures.size),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * What a picture shows, e.g. "Rain" and "Cool, night". A clear night already says
 * "Clear night", so only other night pictures add it.
 */
@Composable
private fun pictureLabel(picture: ScenePicture): Pair<String, String> {
    val name = stringResource(picture.kind.labelRes(picture.time))
    val warmth = stringResource(picture.warmth.labelRes())
    val night = picture.time == TimeOfDay.NIGHT && picture.kind != SceneKind.CLEAR
    return name to if (night) stringResource(R.string.gallery_night, warmth) else warmth
}
