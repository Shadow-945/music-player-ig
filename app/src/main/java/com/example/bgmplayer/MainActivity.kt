package com.example.bgmplayer

import android.content.ComponentName
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

data class Song(val title: String, val uri: Uri)

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
            // Dark Mode Theme enabled by default
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

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { treeUri ->
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val docDir = DocumentFile.fromTreeUri(context, treeUri)
            folderName = docDir?.name ?: "Selected Folder"
            
            val loadedSongs = mutableListOf<Song>()
            docDir?.listFiles()?.forEach { file ->
                if (file.isFile && (file.name?.lowercase()?.endsWith(".mp3") == true)) {
                    file.name?.let { name ->
                        loadedSongs.add(Song(name, file.uri))
                    }
                }
            }
            playlist = loadedSongs
            if (playlist.isNotEmpty() && currentSong == null) {
                currentSong = playlist.first()
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
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (selectedTab == 0) {
                PlayerTab(
                    currentSong = currentSong,
                    isPlaying = isPlaying,
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
                    }
                )
            } else {
                LibraryTab(
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
            }
        }
    }
}

@Composable
fun PlayerTab(currentSong: Song?, isPlaying: Boolean, onPlayPause: () -> Unit) {
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
