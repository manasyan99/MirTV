package ru.mytv.live

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) MirTV/1.0")

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        player = exo

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App(exo) { setFullscreenMode(it) }
            }
        }
    }

    private fun setFullscreenMode(on: Boolean) {
        requestedOrientation = if (on) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}

class Browse {
    var source by mutableStateOf<Source?>(null)
    var items by mutableStateOf<List<Item>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    suspend fun load(block: suspend () -> List<Item>) {
        loading = true
        error = null
        items = emptyList()
        try {
            val r = block()
            items = r
            error = if (r.isEmpty()) "Список пуст. Попробуйте другую страну или категорию." else null
            loading = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Не удалось загрузить список. Проверьте интернет и попробуйте ещё раз."
            loading = false
        }
    }
}

@Composable
fun App(player: ExoPlayer, setFullscreenMode: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<Item?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }
    var favs by remember { mutableStateOf(Store.loadFavs(ctx)) }
    var tvMode by remember { mutableIntStateOf(0) }
    var customUrl by remember { mutableStateOf(Store.getCustomUrl(ctx)) }

    val tv = remember { Browse() }
    val radio = remember { Browse() }
    val custom = remember { Browse() }

    val defaultCountry = remember {
        val cc = Locale.getDefault().country
        Repo.countries.firstOrNull { it.code == cc }
            ?: Repo.countries.firstOrNull { it.code == "US" }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playError = "Поток недоступен. Попробуйте другой канал."
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) playError = null
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    LaunchedEffect(Unit) {
        if (tv.source == null) tv.source = defaultCountry
        if (radio.source == null) radio.source = defaultCountry
        if (customUrl.isNotBlank()) custom.load { Repo.tv(customUrl.trim()) }
    }
    LaunchedEffect(tv.source) {
        tv.source?.let { s -> tv.load { Repo.tv(s.url) } }
    }
    LaunchedEffect(radio.source) {
        radio.source?.let { s -> radio.load { Repo.radio(s.code) } }
    }

    fun play(item: Item) {
        current = item
        playError = null
        player.setMediaItem(MediaItem.fromUri(item.url))
        player.prepare()
        player.playWhenReady = true
    }

    fun toggleFav(item: Item) {
        favs = if (favs.any { it.url == item.url }) {
            favs.filter { it.url != item.url }
        } else {
            favs + item
        }
        Store.saveFavs(ctx, favs)
    }

    fun setFs(on: Boolean) {
        fullscreen = on
        setFullscreenMode(on)
    }

    fun random() {
        val list = when (tab) {
            0 -> tv.items
            1 -> radio.items
            2 -> custom.items
            else -> favs
        }
        if (list.isNotEmpty()) play(list.random())
    }

    if (fullscreen) {
        BackHandler { setFs(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoView(player, Modifier.fillMaxSize())
            TextButton(
                onClick = { setFs(false) },
                modifier = Modifier.align(Alignment.TopEnd)
            ) { Text("Свернуть") }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Мир ТВ",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { random() }) { Text("🎲 Случайный") }
            }

            current?.let { cur ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                ) {
                    VideoView(player, Modifier.fillMaxSize())
                    playError?.let {
                        Text(
                            it,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp)
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(cur.name, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(
                        if (favs.any { it.url == cur.url }) "★" else "☆",
                        fontSize = 26.sp,
                        modifier = Modifier
                            .clickable { toggleFav(cur) }
                            .padding(8.dp)
                    )
                    TextButton(onClick = { setFs(true) }) { Text("На весь экран") }
                }
            }

            TabRow(selectedTabIndex = tab) {
                listOf("ТВ", "Радио", "Свой список", "★ Избранное").forEachIndexed { i, t ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(t, maxLines = 1, fontSize = 13.sp) }
                    )
                }
            }

            when (tab) {
                0 -> Column(Modifier.weight(1f)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ModeButton("Страны", tvMode == 0) {
                            tvMode = 0
                            tv.source = defaultCountry
                        }
                        ModeButton("Категории", tvMode == 1) {
                            tvMode = 1
                            tv.source = Repo.categories.first()
                        }
                        SourcePicker(
                            sources = if (tvMode == 0) Repo.countries else Repo.categories,
                            selected = tv.source,
                            onSelect = { tv.source = it }
                        )
                    }
                    ListBody(tv, current, favs, { play(it) }, { toggleFav(it) })
                }

                1 -> Column(Modifier.weight(1f)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Страна: ")
                        SourcePicker(Repo.countries, radio.source) { radio.source = it }
                    }
                    ListBody(radio, current, favs, { play(it) }, { toggleFav(it) })
                }

                2 -> Column(Modifier.weight(1f)) {
                    Text(
                        "Вставьте ссылку на M3U-плейлист (например, подборку вебкамер или своих каналов).",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("Ссылка на .m3u") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            Store.setCustomUrl(ctx, customUrl.trim())
                            scope.launch { custom.load { Repo.tv(customUrl.trim()) } }
                        }) { Text("Загрузить") }
                    }
                    ListBody(custom, current, favs, { play(it) }, { toggleFav(it) })
                }

                else -> Column(Modifier.weight(1f)) {
                    if (favs.isEmpty()) {
                        Text(
                            "Пока пусто. Нажмите ☆ рядом с каналом или станцией, чтобы добавить сюда.",
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        ItemList(favs, current, favs, { play(it) }, { toggleFav(it) })
                    }
                }
            }
        }
    }
}

@Composable
fun VideoView(exo: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            PlayerView(c).apply {
                player = exo
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
        },
        onRelease = { it.player = null }
    )
}

@Composable
fun ModeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(text, maxLines = 1, fontSize = 13.sp) }
    } else {
        OutlinedButton(onClick = onClick) { Text(text, maxLines = 1, fontSize = 13.sp) }
    }
}

@Composable
fun SourcePicker(sources: List<Source>, selected: Source?, onSelect: (Source) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(selected?.title ?: "Выберите…", maxLines = 1, fontSize = 13.sp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            sources.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.title) },
                    onClick = {
                        open = false
                        onSelect(s)
                    }
                )
            }
        }
    }
}

@Composable
fun ColumnScope.ListBody(
    b: Browse,
    current: Item?,
    favs: List<Item>,
    onPlay: (Item) -> Unit,
    onFav: (Item) -> Unit
) {
    OutlinedTextField(
        value = b.query,
        onValueChange = { b.query = it },
        singleLine = true,
        label = { Text("Поиск") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
    val err = b.error
    if (b.loading) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
    } else if (err != null) {
        Text(err, modifier = Modifier.padding(16.dp))
    } else {
        val filtered = remember(b.items, b.query) {
            if (b.query.isBlank()) b.items
            else b.items.filter { it.name.contains(b.query, ignoreCase = true) }
        }
        ItemList(filtered, current, favs, onPlay, onFav)
    }
}

@Composable
fun ColumnScope.ItemList(
    list: List<Item>,
    current: Item?,
    favs: List<Item>,
    onPlay: (Item) -> Unit,
    onFav: (Item) -> Unit
) {
    LazyColumn(Modifier.weight(1f)) {
        items(list) { item ->
            val isCurrent = item.url == current?.url
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .clickable { onPlay(item) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.logo.isNotBlank()) {
                    AsyncImage(
                        model = item.logo,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                } else {
                    Spacer(Modifier.size(40.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, maxLines = 1)
                    if (item.group.isNotBlank()) {
                        Text(
                            item.group,
                            fontSize = 12.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    if (favs.any { it.url == item.url }) "★" else "☆",
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clickable { onFav(item) }
                        .padding(8.dp)
                )
            }
        }
    }
}
