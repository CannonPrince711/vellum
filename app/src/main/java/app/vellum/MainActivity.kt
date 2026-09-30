package app.vellum

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import app.vellum.ui.home.HomeScreen
import app.vellum.ui.theme.Display
import app.vellum.ui.theme.LocalVellum
import app.vellum.ui.theme.Marginalia
import app.vellum.ui.theme.VellumTheme
import app.vellum.ui.viewer.PasswordDialog
import app.vellum.ui.viewer.VellumDialog
import app.vellum.ui.viewer.ViewerScreen
import app.vellum.ui.viewer.ViewerViewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { VellumTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        uri?.let { vm.requestOpen(it) }
    }
}

@Composable
private fun App(vm: ViewerViewModel) {
    val v = LocalVellum.current
    val view = LocalView.current
    DisposableEffect(vm.keepScreenOn) {
        view.keepScreenOn = vm.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // A single "save to…" picker shared by Save a copy, Extract pages and Lock a copy.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
        vm.onExportResult(it)
    }
    val req = vm.exportRequest
    LaunchedEffect(req) {
        if (req != null && !req.launched) { vm.markExportLaunched(); exportLauncher.launch(req.name) }
    }

    // Toast-like "slip of paper" notes instead of a stock snackbar.
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.messages.collect { note = it }
    }
    LaunchedEffect(note) { if (note != null) { delay(3200); note = null } }

    Box(Modifier.fillMaxSize().background(v.desk)) {
        // Crossfade on "is a document open", not on the engine instance, so edits don't reset the viewer.
        Crossfade(targetState = vm.engine != null, label = "screen") { reading ->
            val e = vm.engine
            if (reading && e != null) ViewerScreen(vm, e) else HomeScreen(vm)
        }

        AnimatedVisibility(
            visible = note != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp, start = 24.dp, end = 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp),
                color = Color(0xFF1C2533), shadowElevation = 12.dp,
                modifier = Modifier.clickable { note = null }
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Color(0xFFD9480F), RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(12.dp))
                    Text(note ?: "", color = Color(0xFFF3EEE3), fontSize = 14.sp)
                }
            }
        }

        vm.busy?.let { label ->
            Box(
                Modifier.fillMaxSize().background(Color(0x661C2533))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                contentAlignment = Alignment.Center
            ) {
                Surface(shape = RoundedCornerShape(26.dp), color = v.sheet, shadowElevation = 18.dp) {
                    Column(Modifier.padding(horizontal = 30.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = v.accent, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.size(14.dp))
                        Text("$label…", fontFamily = Display, fontSize = 18.sp, color = v.ink)
                        Text("one moment", style = Marginalia, color = v.inkSoft)
                    }
                }
            }
        }
    }

    vm.passwordRequest?.let { r ->
        PasswordDialog(r.name, r.wrong, onSubmit = vm::submitPassword, onDismiss = vm::cancelPassword)
    }
    if (vm.pendingOpen != null) {
        VellumDialog(
            "Open another document?", "Your unsaved changes to ${vm.docName} will be lost.",
            "Discard & open", true, { vm.resolvePendingOpen(discard = true) }, { vm.resolvePendingOpen(discard = false) },
            dismiss = "Stay here"
        ) { }
    }
}
