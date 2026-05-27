package com.example.presentation.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.NetGuardApplication
import com.example.domain.model.TrafficSummary
import com.example.presentation.dashboard.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FloatingOverlayService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    
    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null

    private val trafficRepository by lazy {
        (application as NetGuardApplication).container.trafficRepository
    }

    companion object {
        var isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showOverlayWindow()
    }

    private fun showOverlayWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            
            // Set required helper registries to allow compose/MaterialTheme inside an SDK Service smoothly
            val lifecycleOwner = ServiceLifecycleOwner()
            setViewTreeLifecycleOwner(lifecycleOwner)
            val storeOwner = object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = ViewModelStore()
            }
            setViewTreeViewModelStoreOwner(storeOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                MaterialTheme {
                    var summary by remember { mutableStateOf(TrafficSummary.EMPTY) }

                    LaunchedEffect(Unit) {
                        trafficRepository.observeTrafficSummary().collectLatest {
                            summary = it
                        }
                    }

                    FloatingWidget(
                        summary = summary,
                        onDrag = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            windowManager.updateViewLayout(composeView, params)
                        },
                        onClose = {
                            stopSelf()
                        }
                    )
                }
            }
        }

        windowManager.addView(composeView, params)
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        composeView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Safe clear
            }
        }
        super.onDestroy()
    }
}

@Composable
fun FloatingWidget(
    summary: TrafficSummary,
    onDrag: (Float, Float) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        modifier = Modifier
            .wrapContentSize()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.width(140.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color.Green)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "NetGuard",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close overlay",
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onClose() },
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Real-time rates inside standard pill containers
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "↓ ${formatBytes(summary.rxPerSec)}/s",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                )
                Text(
                    text = "↑ ${formatBytes(summary.txPerSec)}/s",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 11.sp
                    )
                )
                Text(
                    text = "Blocked: ${summary.blockedRequestsToday}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.Red,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
}

// Custom simple dummy LifecycleOwner matching Jetpack Service tree guidelines
class ServiceLifecycleOwner : androidx.lifecycle.LifecycleOwner, androidx.savedstate.SavedStateRegistryOwner {
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this).apply {
        currentState = androidx.lifecycle.Lifecycle.State.STARTED
    }
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this).apply {
        performRestore(null)
    }

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry = savedStateRegistryController.savedStateRegistry
}
