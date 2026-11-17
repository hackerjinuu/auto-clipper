package com.jare.autoclipper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.jare.autoclipper.editor.EditorViewModel
import com.jare.autoclipper.model.ModelManager
import com.jare.autoclipper.ui.ClipperTheme
import com.jare.autoclipper.ui.EditorScreen
import com.jare.autoclipper.ui.LoginScreen
import com.jare.autoclipper.ui.ModelScreen

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClipperTheme {
                var exitInfo by androidx.compose.runtime.remember { mutableStateOf(App.lastExit) }
                exitInfo?.let { txt -> com.jare.autoclipper.ui.LastExitDialog(txt) { App.lastExit = null; exitInfo = null } }
                // login -> (first time) model download -> editor
                var screen by rememberSaveable {
                    mutableStateOf(
                        when {
                            App.prefs.token.isBlank() -> "login"
                            needSetup() -> "model"
                            else -> "editor"
                        }
                    )
                }
                when (screen) {
                    "login" -> LoginScreen(onDone = {
                        screen = if (needSetup()) "model" else "editor"
                    })
                    "model" -> {
                        val leave = { App.prefs.modelPrompted = true; App.prefs.packPrompted = true; vm.reloadLibrary(); screen = "editor" }
                        BackHandler { leave() }
                        ModelScreen(onContinue = { leave() })
                    }
                    else -> EditorScreen(vm,
                        onOpenModel = { screen = "model" },
                        onLogout = { App.prefs.token = ""; vm.preview.stop(); screen = "login" })
                }
            }
        }
    }

    private fun needSetup(): Boolean {
        val mm = ModelManager(this)
        return (!mm.isReady() && !App.prefs.modelPrompted) || (!mm.packReady() && !App.prefs.packPrompted)
    }

    override fun onStop() { super.onStop(); vm.preview.stop() }
}
