package io.github.halz.macremote.ui.session

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.halz.macremote.rfb.keysym.Keysyms

/**
 * Invisible text field that turns IME output into key events.
 *
 * The field is seeded with one space so that backspace on an "empty" field
 * still produces a text change. While the IME is composing (Japanese input),
 * changes are held back; only committed text is diffed and sent — kanji and
 * kana go over the wire as Unicode keysyms (best effort, see README).
 */
@Composable
fun KeyInputBridge(
    active: Boolean,
    onText: (String) -> Unit,
    onKeysym: (Int) -> Unit,
) {
    val seed = TextFieldValue(SEED_TEXT, TextRange(SEED_TEXT.length))
    var field by remember { mutableStateOf(seed) }
    var committed by remember { mutableStateOf(SEED_TEXT) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(active) {
        if (active) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            keyboard?.hide()
        }
    }
    if (!active) return

    BasicTextField(
        value = field,
        onValueChange = { new ->
            if (new.composition != null) {
                field = new
                return@BasicTextField
            }
            val newText = new.text
            var prefix = 0
            while (prefix < committed.length && prefix < newText.length && committed[prefix] == newText[prefix]) prefix++
            repeat(committed.length - prefix) { onKeysym(Keysyms.BACKSPACE) }
            if (newText.length > prefix) onText(newText.substring(prefix))
            field = seed
            committed = SEED_TEXT
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val keysym = when (event.key) {
                    Key.DirectionLeft -> Keysyms.LEFT
                    Key.DirectionRight -> Keysyms.RIGHT
                    Key.DirectionUp -> Keysyms.UP
                    Key.DirectionDown -> Keysyms.DOWN
                    Key.Escape -> Keysyms.ESCAPE
                    Key.Tab -> Keysyms.TAB
                    else -> return@onPreviewKeyEvent false
                }
                onKeysym(keysym)
                true
            },
    )
}

private const val SEED_TEXT = " "
