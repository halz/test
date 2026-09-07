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
 * Invisible text field that mirrors IME output to the Mac as key events.
 *
 * The field is seeded with one space so that backspace on an "empty" field
 * still produces a text change. The field is synchronized by diffing against
 * [sent] — exactly what the Mac has already received — on every change,
 * including in-progress composition: predictive keyboards (which keep even
 * plain Latin typing in a composing region until a suggestion commits) type
 * immediately, and a Japanese conversion converges via backspaces. Because
 * the diff base is what was actually sent, a duplicate commit callback from
 * the IME produces an empty diff instead of double-typed text. The buffer is
 * only reset when it empties or grows large — never on every commit — since
 * resets restart the IME and were the source of duplicated input.
 */
@Composable
fun KeyInputBridge(
    active: Boolean,
    onText: (String) -> Unit,
    onKeysym: (Int) -> Unit,
) {
    var field by remember { mutableStateOf(seedValue()) }
    var sent by remember { mutableStateOf(SEED_TEXT) }
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
            field = new
            val newText = new.text
            var prefix = 0
            while (prefix < sent.length && prefix < newText.length && sent[prefix] == newText[prefix]) prefix++
            repeat(sent.length - prefix) { onKeysym(Keysyms.BACKSPACE) }
            if (newText.length > prefix) onText(newText.substring(prefix))
            sent = newText
            // Programmatic resets fire no onValueChange, so nothing is
            // re-sent; done only outside composition so the IME's composing
            // region is never yanked out from under it.
            if (new.composition == null && (newText.isEmpty() || newText.length > TRIM_AT)) {
                field = seedValue()
                sent = SEED_TEXT
            }
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

private fun seedValue() = TextFieldValue(SEED_TEXT, TextRange(SEED_TEXT.length))

private const val SEED_TEXT = " "
private const val TRIM_AT = 64
