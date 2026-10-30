package dev.halftoast.stmobile.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.halftoast.stmobile.settings.AppSettings
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.StBox
import dev.halftoast.stmobile.ui.StButton
import dev.halftoast.stmobile.ui.StFieldShape
import dev.halftoast.stmobile.ui.stTextFieldColors

/** Why [url] can't be used as the SillyTavern address, or null if it can. */
fun connectError(url: String): String? =
    if (AppSettings.isValidServerUrl(url)) null else "Enter an address starting with http:// or https://"

/**
 * Shown in Chat's place while no SillyTavern address is saved (first launch): asks for it.
 * [onConnect] gets the trimmed address once it is valid.
 */
@Composable
fun ConnectScreen(initialUrl: String, onConnect: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalStPalette.current
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    // Shown only after a Connect tap, so an empty field doesn't open with an error.
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val connect = {
        error = connectError(url)
        if (error == null) {
            keyboard?.hide() // the chat comes up in this screen's place
            onConnect(url.trim())
        }
    }
    Surface(modifier.fillMaxSize(), color = Color(p.background)) {
        // Centred while it fits; scrolls when the keyboard leaves too little room.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                StBox(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Connect to SillyTavern",
                            Modifier.fillMaxWidth(),
                            color = Color(p.text),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it; error = null },
                            label = { Text("SillyTavern address") },
                            placeholder = { Text("http://192.168.1.20:8000") },
                            singleLine = true,
                            isError = error != null,
                            supportingText = error?.let { { Text(it) } },
                            keyboardOptions = KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(onGo = { connect() }),
                            shape = StFieldShape,
                            colors = stTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        StButton("Connect", onClick = connect, modifier = Modifier.fillMaxWidth())
                        Text(
                            "SillyTavern must accept remote connections: set listen: true in its config.yaml, " +
                                "then whitelist this phone or turn on a login.",
                            color = Color(p.muted),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
