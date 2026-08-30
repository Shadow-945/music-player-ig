package com.example.bgmplayer

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
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

// ── Color Palette ──
private val Purple80 = Color(0xFFBB86FC)
private val Purple40 = Color(0xFF6200EE)
private val Teal200 = Color(0xFF03DAC5)
private val DarkSurface = Color(0xFF1A1A2E)
private val DarkBackground = Color(0xFF0F0F23)
private val CardDark = Color(0xFF16213E)
private val AccentPink = Color(0xFFFF6B9D)
private val AccentBlue = Color(0xFF4ECDC4)

private val MusicColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = Teal200,
    tertiary = AccentPink,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = CardDark,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB0B0B0)
)

// ── Data ──
data class Song(val title: String, val uri: Uri)

private const val PREFS_NAME = "bgm_player_prefs"
private const val KEY_FOLDER_URI = "last_folder_uri"

fun loadSongsFromFolder(context: Context, treeUri: Uri): Pair<String, List<Song>> {
    val docDir = DocumentFile.fromTreeUri(context, treeUri)
    val name = docDir?.name ?: "Selected Folder"
    val songs = mutableListOf<Song>()
    docDir?.listFiles()?.forEach { file ->
        if (file.isFile && (file.name?.lowercase()?.endsWith(".mp3") == true)) {
            file.name?.let { songName ->
                songs.add(Song(songName, file.uri))
            }
        }
    }
    return Pair(name, songs)
}

fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSeconds = floor(ms / 1000.0).toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

// ── Activity ──
class MainActivity : ComponentActivity() {
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
        }, MoreExecutors.directExecutor())

        setContent {
            MaterialTheme(colorScheme = MusicColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(context = this, getController = { mediaController })
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(context: android.content.Context, getController: () -> MediaController?) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var playlist by remember { mutableStateOf(listOf<Song>()) }
    var currentSong by remember { mutableStateOf<Song?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("No folder selected") }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var folderLoaded by remember { mutableStateOf(false) }
    var isShuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(0) } // 0=off, 1=all, 2=one

    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun loadAndSaveFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
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
            repeatMode == 2 -> idx // repeat one
            isShuffle -> (0 until playlist.size).random()
            idx < playlist.size - 1 -> idx + 1
            repeatMode == 1 -> 0 // repeat all
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

    // Restore last folder on launch
    LaunchedEffect(Unit) {
        if (!folderLoaded) {
            folderLoaded = true
            val savedUri = prefs.getString(KEY_FOLDER_URI, null)
            if (savedUri != null) {
                try {
                    val uri = Uri.parse(savedUri)
                    val hasPermission = context.contentResolver.persistedUriPermissions.any {
                        it.uri == uri && it.isReadPermission
                    }
                    if (hasPermission) {
                        val (name, songs) = loadSongsFromFolder(context, uri)
                        folderName = name
                        playlist = songs
                        if (songs.isNotEmpty()) currentSong = songs.first()
                    }
                } catch (_: Exception) {
                    prefs.edit().remove(KEY_FOLDER_URI).apply()
                }
            }
        }
    }

    // Apply shuffle/repeat to controller
    LaunchedEffect(isShuffle) {
        getController()?.shuffleModeEnabled = isShuffle
    }
    LaunchedEffect(repeatMode) {
        getController()?.repeatMode = when (repeatMode) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                contentColor = Color.White
            ) {
                val tabs = listOf(
                    Triple(Icons.Default.MusicNote, "Player", 0),
                    Triple(Icons.Default.FolderOpen, "Library", 1),
                    Triple(Icons.Default.Settings, "Settings", 2)
                )
                tabs.forEach { (icon, label, index) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Purple80,
                            selectedTextColor = Purple80,
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Purple80.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            // Poll playback position
            LaunchedEffect(isPlaying) {
                while (isPlaying) {
                    val controller = getController()
                    if (controller != null) {
                        currentPosition = controller.currentPosition
                        duration = controller.duration.coerceAtLeast(0)
                    }
                    kotlinx.coroutines.delay(500)
                }
            }

            when (selectedTab) {
                0 -> PlayerTab(
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    onPlayPause = {
                        val controller = getController() ?: return@PlayerTab
                        if (controller.isPlaying) {
                            controller.pause(); isPlaying = false
                        } else {
                            if (controller.mediaItemCount == 0 && currentSong != null) {
                                controller.setMediaItem(MediaItem.fromUri(currentSong!!.uri))
                                controller.prepare()
                            }
                            controller.play(); isPlaying = true
                        }
                    },
                    onSeek = { seekMs -> getController()?.seekTo(seekMs); currentPosition = seekMs },
                    onSkipNext = ::playNext,
                    onSkipPrevious = ::playPrevious,
                    onToggleShuffle = { isShuffle = !isShuffle },
                    onToggleRepeat = { repeatMode = (repeatMode + 1) % 3 }
                )
                1 -> LibraryTab(
                    folderName = folderName,
                    playlist = playlist,
                    currentSong = currentSong,
                    onSelectFolder = { folderPickerLauncher.launch(null) },
                    onSelectSong = ::playSong
                )
                2 -> SettingsTab(
                    folderName = folderName,
                    onChangeFolder = { folderPickerLauncher.launch(null) },
                    onClearFolder = {
                        prefs.edit().remove(KEY_FOLDER_URI).apply()
                        playlist = listOf(); currentSong = null
                        folderName = "No folder selected"
                    }
                )
            }
        }
    }
}

// ── Player Tab ──
@Composable
fun PlayerTab(
    currentSong: Song?,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    isShuffle: Boolean,
    repeatMode: Int,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleRepeat: () -> Unit
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableStateOf(0f) }
    val playButtonScale by animateFloatAsState(if (isPlaying) 1.0f else 0.85f, tween(200), label = "playScale")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DarkBackground, DarkSurface, Color(0xFF1A0A2E))
                )
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Album art placeholder
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Purple40, AccentPink, AccentBlue)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = Color.White.copy(alpha = 0.9f)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Song title
        Text(
            text = currentSong?.title ?: "No song selected",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Now Playing",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Seek bar
        if (duration > 0) {
            val sliderValue = if (isSeeking) seekPosition else currentPosition.toFloat().coerceIn(0f, duration.toFloat())
            Slider(
                value = sliderValue,
                onValueChange = { isSeeking = true; seekPosition = it },
                onValueChangeFinished = { isSeeking = false; onSeek(seekPosition.toLong()) },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Purple80,
                    activeTrackColor = Purple80,
                    inactiveTrackColor = Color(0xFF333355)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(if (isSeeking) seekPosition.toLong() else currentPosition),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatTime(duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Shuffle, Previous, Play/Pause, Next, Repeat
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmallControlButton(
                icon = Icons.Default.Shuffle,
                isActive = isShuffle,
                onClick = onToggleShuffle,
                contentDescription = "Shuffle"
            )
            ControlButton(
                icon = Icons.Default.SkipPrevious,
                onClick = onSkipPrevious,
                size = 40.dp,
                contentDescription = "Previous"
            )
            // Main play/pause button
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(72.dp)
                    .scale(playButtonScale)
                    .clip(CircleShape)
                    .border(2.dp, Purple80, CircleShape)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                    tint = Purple80
                )
            }
            ControlButton(
                icon = Icons.Default.SkipNext,
                onClick = onSkipNext,
                size = 40.dp,
                contentDescription = "Next"
            )
            SmallControlButton(
                icon = when (repeatMode) {
                    2 -> Icons.Default.RepeatOne
                    else -> Icons.Default.Repeat
                },
                isActive = repeatMode > 0,
                onClick = onToggleRepeat,
                contentDescription = "Repeat"
            )
        }
    }
}

@Composable
fun ControlButton(
    icon: ImageVector,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    contentDescription: String
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size + 16.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(size),
            tint = Color.White
        )
    }
}

@Composable
fun SmallControlButton(
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    contentDescription: String
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp),
            tint = if (isActive) Purple80 else Color.Gray
        )
    }
}

// ── Library Tab ──
@Composable
fun LibraryTab(
    folderName: String,
    playlist: List<Song>,
    currentSong: Song?,
    onSelectFolder: () -> Unit,
    onSelectSong: (Song) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableIntStateOf(0) } // 0=name, 1=name desc

    val filteredPlaylist = remember(playlist, searchQuery, sortBy) {
        val filtered = if (searchQuery.isBlank()) playlist
        else playlist.filter { it.title.contains(searchQuery, ignoreCase = true) }
        when (sortBy) {
            0 -> filtered.sortedBy { it.title.lowercase() }
            1 -> filtered.sortedByDescending { it.title.lowercase() }
            else -> filtered
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        // Folder selector card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Music Folder", style = MaterialTheme.typography.labelMedium, color = AccentBlue)
                    Text(folderName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = onSelectFolder,
                    colors = ButtonDefaults.buttonColors(containerColor = Purple80),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Change", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Song count + sort
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${filteredPlaylist.size} songs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { sortBy = (sortBy + 1) % 2 }) {
                Text(
                    text = if (sortBy == 0) "A→Z" else "Z→A",
                    style = MaterialTheme.typography.labelMedium,
                    color = Purple80
                )
            }
        }

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search songs...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple80,
                unfocusedBorderColor = Color(0xFF333355),
                cursorColor = Purple80,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = CardDark,
                unfocusedContainerColor = CardDark
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Song list
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(filteredPlaylist) { index, song ->
                val isCurrentSong = song.uri == currentSong?.uri
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = { onSelectSong(song) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrentSong) Purple80.copy(alpha = 0.15f) else CardDark
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Song number or playing indicator
                        if (isCurrentSong) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Purple80,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (isCurrentSong) FontWeight.Bold else FontWeight.Normal
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isCurrentSong) Purple80 else Color.White
                            )
                        }
                        if (isCurrentSong) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Purple80,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Settings Tab ──
@Composable
fun SettingsTab(
    folderName: String,
    onChangeFolder: () -> Unit,
    onClearFolder: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Music Folder
        Text("Music Folder", style = MaterialTheme.typography.titleMedium, color = AccentBlue)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(folderName, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onChangeFolder,
                        colors = ButtonDefaults.buttonColors(containerColor = Purple80),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Change Folder")
                    }
                    OutlinedButton(
                        onClick = onClearFolder,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentPink)
                    ) {
                        Text("Clear")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // About
        Text("About", style = MaterialTheme.typography.titleMedium, color = AccentBlue)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("BGM Player", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(4.dp))
                Text("Version 1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text("A simple music player with background playback and folder browsing.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
