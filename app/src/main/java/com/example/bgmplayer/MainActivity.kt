package com.example.bgmplayer

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlin.math.floor

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
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        context = this,
                        getController = { mediaController }
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

    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // Helper to load songs from a URI and save it
    fun loadAndSaveFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val (name, songs) = loadSongsFromFolder(context, treeUri)
        folderName = name
        playlist = songs
        if (songs.isNotEmpty() && currentSong == null) {
            currentSong = songs.first()
        }
        // Save to SharedPreferences
        prefs.edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { loadAndSaveFolder(it) }
    }

    // Restore last folder on launch
    LaunchedEffect(Unit) {
        if (!folderLoaded) {
            folderLoaded = true
            val savedUri = prefs.getString(KEY_FOLDER_URI, null)
            if (savedUri != null) {
                try {
                    val uri = Uri.parse(savedUri)
                    // Check if we still have permission
                    val hasPermission = context.contentResolver.persistedUriPermissions.any {
                        it.uri == uri && it.isReadPermission
                    }
                    if (hasPermission) {
                        val (name, songs) = loadSongsFromFolder(context, uri)
                        folderName = name
                        playlist = songs
                        if (songs.isNotEmpty()) {
                            currentSong = songs.first()
                        }
                    }
                } catch (_: Exception) {
                    // Folder no longer accessible, clear saved preference
                    prefs.edit().remove(KEY_FOLDER_URI).apply()
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.MusicNote, contentDescription = "Player") },
                    label = { Text("Player") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = "Library") },
                    label = { Text("Library") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            // Poll playback position while playing
            LaunchedEffect(isPlaying) {
                while (isPlaying) {
                    val controller = getController()
                    if (controller != null) {
                        currentPosition = controller.currentPosition
                        duration = controller.duration.coerceAtLeast(0)
                    }
                    delay(500)
                }
            }

            when (selectedTab) {
                0 -> PlayerTab(
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    onPlayPause = {
                        val controller = getController()
                        if (controller != null) {
                            if (controller.isPlaying) {
                                controller.pause()
                                isPlaying = false
                            } else {
                                if (controller.mediaItemCount == 0 && currentSong != null) {
                                    controller.setMediaItem(MediaItem.fromUri(currentSong!!.uri))
                                    controller.prepare()
                                }
                                controller.play()
                                isPlaying = true
                            }
                        }
                    },
                    onSeek = { seekMs ->
                        getController()?.seekTo(seekMs)
                        currentPosition = seekMs
                    }
                )
                1 -> LibraryTab(
                    folderName = folderName,
                    playlist = playlist,
                    onSelectFolder = {
                        folderPickerLauncher.launch(null)
                    },
                    onSelectSong = { song ->
                        currentSong = song
                        val controller = getController()
                        controller?.setMediaItem(MediaItem.fromUri(song.uri))
                        controller?.prepare()
                        controller?.play()
                        isPlaying = true
                    }
                )
                2 -> SettingsTab(
                    folderName = folderName,
                    onChangeFolder = {
                        folderPickerLauncher.launch(null)
                    },
                    onClearFolder = {
                        prefs.edit().remove(KEY_FOLDER_URI).apply()
                        playlist = listOf()
                        currentSong = null
                        folderName = "No folder selected"
                    }
                )
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = floor(ms / 1000.0).toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun PlayerTab(
    currentSong: Song?,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(120.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = currentSong?.title ?: "No song selected",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(32.dp))

        // Seek slider
        if (duration > 0) {
            val sliderValue = if (isSeeking) seekPosition else currentPosition.toFloat().coerceIn(0f, duration.toFloat())
            Slider(
                value = sliderValue,
                onValueChange = { value ->
                    isSeeking = true
                    seekPosition = value
                },
                onValueChangeFinished = {
                    isSeeking = false
                    onSeek(seekPosition.toLong())
                },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTime(if (isSeeking) seekPosition.toLong() else currentPosition),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = formatTime(duration),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
}

@Composable
fun LibraryTab(
    folderName: String,
    playlist: List<Song>,
    onSelectFolder: () -> Unit,
    onSelectSong: (Song) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = onSelectFolder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select Music Folder")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Folder: $folderName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            items(playlist) { song ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = { onSelectSong(song) }
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(text = song.title, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(
    folderName: String,
    onChangeFolder: () -> Unit,
    onClearFolder: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Current folder section
        Text(
            text = "Music Folder",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(onClick = onChangeFolder) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Change Folder")
                    }
                    OutlinedButton(onClick = onClearFolder) {
                        Text("Clear")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // About section
        Text(
            text = "About",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "BGM Player", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Version 1.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
