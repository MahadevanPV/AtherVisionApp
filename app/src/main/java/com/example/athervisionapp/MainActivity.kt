package com.example.athervisionapp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.athervisionapp.ui.theme.AtherVisionAppTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableIntStateOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.util.Log

class MainActivity : ComponentActivity() {
    // Array of permissions needed for the app
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )

    // Permission launcher for multiple permissions
    private val multiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some permissions were denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check for required permissions
        requestPermissionsIfNeeded()

        setContent {
            AtherVisionAppTheme {
                AppNavigation()
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            multiplePermissionsLauncher.launch(permissionsToRequest)
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    var isRailExpanded by remember { mutableStateOf(true) }
    val capturedImageUri = remember { mutableStateOf<Uri?>(null) }

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            MainScreen(
                isRailExpanded = isRailExpanded,
                onToggleRail = { isRailExpanded = !isRailExpanded },
                onNavigateToCamera = { navController.navigate("camera") },
                onNavigateToObjectDetection = { navController.navigate("object_detection") },
                capturedImageUri = capturedImageUri.value
            )
        }

        composable("camera") {
            CameraScreen(
                onNavigateBack = { navController.popBackStack() },
                onImageCaptured = { uri ->
                    capturedImageUri.value = uri
                    navController.popBackStack()
                }
            )
        }

        // Add the object detection route
        composable("object_detection") {
            CameraScreen(  // We're reusing the same CameraScreen for now
                onNavigateBack = { navController.popBackStack() },
                onImageCaptured = { uri ->
                    capturedImageUri.value = uri
                    navController.popBackStack()
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isRailExpanded: Boolean,
    onToggleRail: () -> Unit,
    onNavigateToCamera: () -> Unit,
    onNavigateToObjectDetection: () -> Unit,
    capturedImageUri: Uri?
) {
    var selectedItem by remember { mutableIntStateOf(0) }
    val items = listOf("Home", "Settings")
    val icons = listOf(Icons.Filled.Home, Icons.Filled.Settings)

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with menu button
        TopAppBar(
            title = { Text("Ather Vision App") },
            navigationIcon = {
                IconButton(onClick = onToggleRail) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Toggle menu"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )

        // Main content area for tablets
        Row(modifier = Modifier.fillMaxSize()) {
            // Navigation rail - only shown when expanded
            AnimatedVisibility(
                visible = isRailExpanded,
                enter = slideInHorizontally() + expandHorizontally(),
                exit = slideOutHorizontally() + shrinkHorizontally()
            ) {
                NavigationRail {
                    items.forEachIndexed { index, item ->
                        NavigationRailItem(
                            icon = { Icon(icons[index], contentDescription = item) },
                            label = { Text(item) },
                            selected = selectedItem == index,
                            onClick = { selectedItem = index }
                        )
                    }
                }
            }

            // Main content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                when (selectedItem) {
                    0 -> HomeContent(
                        onOpenCamera = onNavigateToCamera,
                        onOpenObjectDetection = onNavigateToObjectDetection,
                        capturedImageUri = capturedImageUri
                    )
                    1 -> SettingsScreen(onNavigateBack = { selectedItem = 0 })
                }
            }
        }
    }
}


@Composable
fun HomeContent(
    onOpenCamera: () -> Unit,
    capturedImageUri: Uri?,
    onOpenObjectDetection: () -> Unit // Add this parameter
) {
    // Get the current context
    val context = LocalContext.current

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Welcome to Ather Vision",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = "Your computer vision assistant for tablets",
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Camera status - show if an image was captured
            if (capturedImageUri != null) {
                Text(
                    text = "Image captured successfully!",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "Image saved at: ${capturedImageUri.path}",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 32.dp)
                )
            }

            Button(
                onClick = onOpenCamera,
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(0.7f)
            ) {
                Text("Open Camera")
            }

            // Test Detection Button
            Button(
                onClick = {
                    try {
                        // Create a simple test bitmap
                        val bitmap = Bitmap.createBitmap(ModelInfo.MODEL_WIDTH, ModelInfo.MODEL_HEIGHT, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(android.graphics.Color.GRAY)

                        // Draw a rectangle that should be detected as something
                        val paint = Paint()
                        paint.color = android.graphics.Color.RED
                        canvas.drawRect(100f, 100f, 300f, 400f, paint)

                        // Try to run detection
                        val detector = ObjectDetector(context)
                        val results = detector.detect(bitmap)

                        // Log results
                        android.util.Log.d("TestDetection", "Detection results: ${results.size} objects found")
                        results.forEach {
                            android.util.Log.d("TestDetection", "Detected: ${it.label} (${it.confidence})")
                        }

                        // Show a toast with the results
                        android.widget.Toast.makeText(
                            context,
                            "Detection results: ${results.size} objects found",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        android.util.Log.e("TestDetection", "Error running detection: ${e.message}", e)
                        android.widget.Toast.makeText(
                            context,
                            "Error: ${e.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(0.7f)
            ) {
                Text("Test Object Detection")
            }

            Button(
                onClick = { /* TODO: Open settings */ },
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(0.7f)
            ) {
                Text("Settings")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Text("Settings functionality coming soon")
        }
    }
}