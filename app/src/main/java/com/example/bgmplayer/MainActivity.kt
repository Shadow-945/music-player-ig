package com.example.bgmplayer

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlin.math.floor

// ── Mood Definitions ──
data class MoodColors(
    val accent: Color,
    val accentSoft: Color,
    val accentBorder: Color,
    val accentGlow: Color
)

object MoodPresets {
    val midnight = MoodColors(Color(0xFF7C3AED), Color(0x1E7C3AED), Color(0x597C3AED), Color(0x1A7C3AED))
    val sunset  = MoodColors(Color(0xFFF97316), Color(0x1EF97316), Color(0x59F97316), Color(0x1AF97316))
    val ocean   = MoodColors(Color(0xFF38BDF8), Color(0x1E38BDF8), Color(0x5938BDF8), Color(0x1A38BDF8))
    val forest  = MoodColors(Color(0xFF10B981), Color(0x1E10B981), Color(0x5910B981), Color(0x1A10B981))
    val neon    = MoodColors(Color(0xFFEC4899), Color(0x1EEC4899), Color(0x59EC4899), Color(0x1AEC4899))
    val rose    = MoodColors(Color(0xFFE879F9), Color(0x1EE879F9), Color(0x59E879F9), Color(0x1AE879F9))
    val blood   = MoodColors(Color(0xFFDC2626), Color(0x1EDC2626), Color(0x59DC2626), Color(0x1ADC2626))
    val light   = MoodColors(Color(0xFFE2E8F0), Color(0x1EE2E8F0), Color(0x59E2E8F0), Color(0x1AE2E8F0))
    val amber   = MoodColors(Color(0xFFF59E0B), Color(0x1EF59E0B), Color(0x59F59E0B), Color(0x1AF59E0B))

    fun fromName(name: String): MoodColors = when (name) {
        "midnight" -> midnight
        "sunset" -> sunset
        "ocean" -> ocean
        "forest" -> forest
        "neon" -> neon
        "rose" -> rose
        "blood" -> blood
        "light" -> light
        "amber" -> amber
        else -> midnight
    }
}

private val PureBlack = Color(0xFF000000)
private val CardBg = Color(0xFF080808)
private val Muted = Color(0xFF444444)
private val DimText = Color(0xFF888888)
private val DimBorder = Color(0x14FFFFFF)

// ── Data ──
data class Song(val title: String, val uri: Uri)

private const val PREFS_NAME = "bgm_player_prefs"
private const val KEY_FOLDER_URI = "last_folder_uri"
private const val KEY_MOOD = "selected_mood"

fun loadSongsFromFolder(context: Context, treeUri: Uri): Pair<String, List<Song>> {
    val docDir = DocumentFile.fromTreeUri(context, treeUri)
    val name = docDir?.name ?: "Selected Folder"
    val songs = mutableListOf<Song>()
    docDir?.listFiles()?.forEach { file ->
        if (file.isFile && (file.name?.lowercase()?.endsWith(".mp3") == true)) {
            file.name?.let { songs.add(Song(it, file.uri)) }
        }
    }
    return Pair(name, songs)
}

fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSeconds = floor(ms / 1000.0).toLong()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

// ── Activity ──
class MainActivity : ComponentActivity() {
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ mediaController = controllerFuture.get() }, MoreExecutors.directExecutor())

        setContent {
            val prefs = remember { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
            var moodName by remember { mutableStateOf(prefs.getString(KEY_MOOD, "midnight") ?: "midnight") }
            val mood = remember(moodName) { MoodPresets.fromName(moodName) }

            val darkColors = darkColorScheme(
                primary = mood.accent,
                secondary = Color(0xFF06B6D4),
                tertiary = Color(0xFFEC4899),
                background = PureBlack,
                surface = PureBlack,
                surfaceVariant = CardBg,
                onPrimary = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White,
                onSurfaceVariant = DimText
            )

            MaterialTheme(colorScheme = darkColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = PureBlack) {
                    MainScreen(
                        context = this@MainActivity,
                        getController = { mediaController },
                        mood = mood,
                        moodName = moodName,
                        onMoodChange = { newMood ->
                            moodName = newMood
                            prefs.edit().putString(KEY_MOOD, newMood).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaController.releaseFuture(controllerFuture)
    }
}

// ── Main Screen ──
@Composable
fun MainScreen(
    context: android.content.Context,
    getController: () -> MediaController?,
    mood: MoodColors,
    moodName: String,
    onMoodChange: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var playlist by remember { mutableStateOf(listOf<Song>()) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("No folder selected") }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var folderLoaded by remember { mutableStateOf(false) }
    var isShuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(0) }

    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun loadAndSaveFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val (name, songs) = loadSongsFromFolder(context, treeUri)
        folderName = name
        playlist = if (isShuffle) songs.shuffled() else songs
        if (songs.isNotEmpty() && currentSong == null) currentSong = playlist.first()
        prefs.edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply()
    }

    fun playSong(song: Song) {
        currentSong = song
        val controller = getController()
        controller?.setMediaItem(MediaItem.fromUri(song.uri))
        controller?.prepare()
        controller?.play()
        isPlaying = true
    }

    fun playNext() {
        if (playlist.isEmpty()) return
        val idx = playlist.indexOfFirst { it.uri == currentSong?.uri }
        val nextIdx = when {
            repeatMode == 2 -> idx
            isShuffle -> (0 until playlist.size).random()
            idx < playlist.size - 1 -> idx + 1
            repeatMode == 1 -> 0
            else -> return
        }
        if (nextIdx in playlist.indices) playSong(playlist[nextIdx])
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        val idx = playlist.indexOfFirst { it.uri == currentSong?.uri }
        val prevIdx = when {
            isShuffle -> (0 until playlist.size).random()
            idx > 0 -> idx - 1
            repeatMode == 1 -> playlist.size - 1
            else -> return
        }
        if (prevIdx in playlist.indices) playSong(playlist[prevIdx])
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { loadAndSaveFolder(it) } }

    LaunchedEffect(Unit) {
        if (!folderLoaded) {
            folderLoaded = true
            val savedUri = prefs.getString(KEY_FOLDER_URI, null)
            if (savedUri != null) {
                try {
                    val uri = Uri.parse(savedUri)
                    if (context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                        val (name, songs) = loadSongsFromFolder(context, uri)
                        folderName = name; playlist = songs
                        if (songs.isNotEmpty()) currentSong = songs.first()
                    }
                } catch (_: Exception) { prefs.edit().remove(KEY_FOLDER_URI).apply() }
            }
        }
    }

    LaunchedEffect(isShuffle) { getController()?.shuffleModeEnabled = isShuffle }
    LaunchedEffect(repeatMode) {
        getController()?.repeatMode = when (repeatMode) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = PureBlack, contentColor = Color.White) {
                val tabs = listOf(Triple(Icons.Default.MusicNote, "Player", 0), Triple(Icons.Default.FolderOpen, "Library", 1), Triple(Icons.Default.Settings, "Settings", 2))
                tabs.forEach { (icon, label, index) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = mood.accent,
                            selectedTextColor = mood.accent,
                            unselectedIconColor = Color(0xFF1A1A1A),
                            unselectedTextColor = Color(0xFF1A1A1A),
                            indicatorColor = mood.accent.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            LaunchedEffect(isPlaying) {
                while (isPlaying) {
                    getController()?.let { currentPosition = it.currentPosition; duration = it.duration.coerceAtLeast(0) }
                    kotlinx.coroutines.delay(500)
                }
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally(tween(250)) { it / 4 } + fadeIn(tween(250)) togetherWith
                        slideOutHorizontally(tween(250)) { -it / 4 } + fadeOut(tween(200))
                    } else {
                        slideInHorizontally(tween(250)) { -it / 4 } + fadeIn(tween(250)) togetherWith
                        slideOutHorizontally(tween(250)) { it / 4 } + fadeOut(tween(200))
                    }
                },
                label = "tabTransition"
            ) { tab ->
                when (tab) {
                    0 -> PlayerTab(
                        currentSong = currentSong, isPlaying = isPlaying,
                        currentPosition = currentPosition, duration = duration,
                        isShuffle = isShuffle, repeatMode = repeatMode, mood = mood,
                        onPlayPause = {
                            getController()?.let { c ->
                                if (c.isPlaying) { c.pause(); isPlaying = false }
                                else {
                                    if (c.mediaItemCount == 0 && currentSong != null) { c.setMediaItem(MediaItem.fromUri(currentSong!!.uri)); c.prepare() }
                                    c.play(); isPlaying = true
                                }
                            }
                        },
                        onSeek = { getController()?.seekTo(it); currentPosition = it },
                        onSkipNext = ::playNext, onSkipPrevious = ::playPrevious,
                        onToggleShuffle = { isShuffle = !isShuffle },
                        onToggleRepeat = { repeatMode = (repeatMode + 1) % 3 }
                    )
                    1 -> LibraryTab(
                        folderName = folderName, playlist = playlist, currentSong = currentSong, mood = mood,
                        onSelectFolder = { folderPickerLauncher.launch(null) },
                        onSelectSong = ::playSong
                    )
                    2 -> SettingsTab(
                        folderName = folderName, moodName = moodName, mood = mood,
                        onChangeFolder = { folderPickerLauncher.launch(null) },
                        onMoodChange = onMoodChange,
                        onClearFolder = {
                            prefs.edit().remove(KEY_FOLDER_URI).apply()
                            playlist = listOf(); currentSong = null; folderName = "No folder selected"
                        }
                    )
                }
            }
        }
    }
}

// ── Player Tab ──
@Composable
fun PlayerTab(
    currentSong: Song?, isPlaying: Boolean, currentPosition: Long, duration: Long,
    isShuffle: Boolean, repeatMode: Int, mood: MoodColors,
    onPlayPause: () -> Unit, onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit, onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit, onToggleRepeat: () -> Unit
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableStateOf(0f) }
    val playScale by animateFloatAsState(if (isPlaying) 1.0f else 0.85f, tween(200), label = "play")

    Column(
        modifier = Modifier.fillMaxSize().background(PureBlack).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp))
                .background(CardBg).border(1.dp, DimBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MusicNote, null, Modifier.size(60.dp), tint = mood.accent)
        }
        Spacer(Modifier.height(32.dp))
        Text(currentSong?.title ?: "No song selected", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(4.dp))
        Text("Now Playing", style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.height(32.dp))

        if (duration > 0) {
            val sv = if (isSeeking) seekPosition else currentPosition.toFloat().coerceIn(0f, duration.toFloat())
            Slider(value = sv, onValueChange = { isSeeking = true; seekPosition = it },
                onValueChangeFinished = { isSeeking = false; onSeek(seekPosition.toLong()) },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(thumbColor = mood.accent, activeTrackColor = mood.accent, inactiveTrackColor = DimBorder),
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(if (isSeeking) seekPosition.toLong() else currentPosition), style = MaterialTheme.typography.bodySmall, color = Muted)
                Text(formatTime(duration), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Spacer(Modifier.height(16.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleShuffle, Modifier.size(48.dp)) {
                Icon(Icons.Default.Shuffle, "Shuffle", Modifier.size(22.dp), tint = if (isShuffle) mood.accent else Color(0xFF222222))
            }
            IconButton(onClick = onSkipPrevious, Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(36.dp), tint = Color(0xFF222222))
            }
            IconButton(onClick = onPlayPause, Modifier.size(64.dp).scale(playScale).clip(CircleShape).border(1.5.dp, mood.accent, CircleShape)) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", Modifier.size(32.dp), tint = mood.accent)
            }
            IconButton(onClick = onSkipNext, Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, "Next", Modifier.size(36.dp), tint = Color(0xFF222222))
            }
            IconButton(onClick = onToggleRepeat, Modifier.size(48.dp)) {
                Icon(if (repeatMode == 2) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat", Modifier.size(22.dp),
                    tint = if (repeatMode > 0) mood.accent else Color(0xFF222222))
            }
        }
    }
}

// ── Library Tab ──
@Composable
fun LibraryTab(
    folderName: String, playlist: List<Song>, currentSong: Song?, mood: MoodColors,
    onSelectFolder: () -> Unit, onSelectSong: (Song) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableIntStateOf(0) }
    val filtered = remember(playlist, searchQuery, sortBy) {
        val f = if (searchQuery.isBlank()) playlist else playlist.filter { it.title.contains(searchQuery, true) }
        when (sortBy) { 0 -> f.sortedBy { it.title.lowercase() }; 1 -> f.sortedByDescending { it.title.lowercase() }; else -> f }
    }

    Column(modifier = Modifier.fillMaxSize().background(PureBlack).padding(16.dp)) {
        // Folder card
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardBg)
            .border(1.dp, DimBorder, RoundedCornerShape(14.dp)).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Music Folder", style = MaterialTheme.typography.labelMedium, color = Muted)
                    Text(folderName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = onSelectFolder, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    shape = RoundedCornerShape(10.dp), border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(mood.accent.copy(alpha = 0.35f)))) {
                    Text("Change", color = mood.accent, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${filtered.size} songs", style = MaterialTheme.typography.bodySmall, color = Muted)
            TextButton(onClick = { sortBy = (sortBy + 1) % 2 }) {
                Text(if (sortBy == 0) "A→Z" else "Z→A", style = MaterialTheme.typography.labelMedium, color = mood.accent)
            }
        }

        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text("Search songs...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, "Clear") } },
            singleLine = true, shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = mood.accent.copy(alpha = 0.35f), unfocusedBorderColor = DimBorder,
                cursorColor = mood.accent, focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedContainerColor = CardBg, unfocusedContainerColor = CardBg
            ), modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(filtered) { index, song ->
                val isCurrent = song.uri == currentSong?.uri
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), onClick = { onSelectSong(song) },
                    colors = CardDefaults.cardColors(containerColor = if (isCurrent) mood.accentSoft else Color.Transparent),
                    shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (isCurrent) Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp), tint = mood.accent)
                        else Text("${index + 1}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1A1A1A), Modifier.width(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(song.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (isCurrent) mood.accent else DimText,
                            modifier = Modifier.weight(1f))
                        if (isCurrent) Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp), tint = mood.accent)
                    }
                }
            }
        }
    }
}

// ── Settings Tab ──
@Composable
fun SettingsTab(
    folderName: String, moodName: String, mood: MoodColors,
    onChangeFolder: () -> Unit, onMoodChange: (String) -> Unit, onClearFolder: () -> Unit
) {
    val moods = listOf(
        Triple("midnight", "🌙", "Midnight"), Triple("sunset", "🌅", "Sunset"),        Triple("ocean", "❄️", "Ice"),
        Triple("forest", "💚", "Emerald"), Triple("neon", "💎", "Neon"),        Triple("rose", "🌸", "Rose")
    )
    val moodsRow2 = listOf(
        Triple("blood", "🩸", "Blood"),
        Triple("light", "☀️", "Light"),
        Triple("amber", "🍯", "Amber")
    )

    Column(modifier = Modifier.fillMaxSize().background(PureBlack).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(24.dp))

        Text("MOOD", style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // First row: first 3 moods
        }
        // Mood grid - row 1
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            moods.take(3).forEach { (key, emoji, label) ->
                MoodCard(key = key, emoji = emoji, label = label, isActive = moodName == key, mood = mood,
                    modifier = Modifier.weight(1f), onClick = { onMoodChange(key) })
            }
        }
        Spacer(Modifier.height(8.dp))
        // Mood grid - row 2
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            moods.drop(3).forEach { (key, emoji, label) ->
                MoodCard(key = key, emoji = emoji, label = label, isActive = moodName == key, mood = mood,
                    modifier = Modifier.weight(1f), onClick = { onMoodChange(key) })
            }
            moodsRow2.forEach { (key, emoji, label) ->
                MoodCard(key = key, emoji = emoji, label = label, isActive = moodName == key, mood = mood,
                    modifier = Modifier.weight(1f), onClick = { onMoodChange(key) })
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("MUSIC FOLDER", style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(14.dp), border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DimBorder))) {
            Column(Modifier.padding(16.dp)) {
                Text(folderName, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onChangeFolder, colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(10.dp), border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(mood.accent.copy(alpha = 0.35f)))) {
                        Text("Change Folder", color = mood.accent)
                    }
                    OutlinedButton(onClick = onClearFolder, shape = RoundedCornerShape(10.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(DimBorder))) {
                        Text("Clear", color = Muted)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("ABOUT", style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(14.dp), border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(DimBorder))) {
            Column(Modifier.padding(16.dp)) {
                Text("BGM Player", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                Text("Version 1.0", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
fun MoodCard(key: String, emoji: String, label: String, isActive: Boolean, mood: MoodColors, modifier: Modifier, onClick: () -> Unit) {
    val borderColor by animateColorAsState(if (isActive) mood.accent else DimBorder, tween(300), label = "border")
    val bgColor by animateColorAsState(if (isActive) mood.accentSoft else Color.Transparent, tween(300), label = "bg")

    Card(modifier = modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, borderColor, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = bgColor), onClick = onClick) {
        Column(modifier = Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                if (isActive) Box(Modifier.padding(end = 8.dp, top = 4.dp).size(6.dp).clip(CircleShape).background(mood.accent))
            }
            Text(emoji, fontSize = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold),
                color = if (isActive) mood.accent else Muted)
        }
    }
}
