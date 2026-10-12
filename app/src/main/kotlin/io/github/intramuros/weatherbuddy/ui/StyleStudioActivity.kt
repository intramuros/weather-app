package io.github.intramuros.weatherbuddy.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.intramuros.weatherbuddy.R
import io.github.intramuros.weatherbuddy.core.CustomStyle
import io.github.intramuros.weatherbuddy.core.ImageQuality
import io.github.intramuros.weatherbuddy.core.OpenAiException
import io.github.intramuros.weatherbuddy.core.ScenePicture
import io.github.intramuros.weatherbuddy.core.ScenePrompts
import io.github.intramuros.weatherbuddy.core.Style
import io.github.intramuros.weatherbuddy.core.StyleDesigner
import io.github.intramuros.weatherbuddy.data.ApiKeyStore
import io.github.intramuros.weatherbuddy.data.CustomStyleStore
import io.github.intramuros.weatherbuddy.data.OpenAiClient
import io.github.intramuros.weatherbuddy.work.StyleWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Makes a new style: the user describes it to the designer ([StyleDesigner]) on their
 * own OpenAI key, has a preview drawn, and once they like it, [StyleWorker] draws the
 * rest in the background.
 */
class StyleStudioActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeatherBuddyTheme {
                StudioScreen(onBack = ::finish)
            }
        }
    }
}

class StyleStudioViewModel(private val app: Application) : AndroidViewModel(app) {
    /** What the conversation shows, in order. */
    sealed interface Item {
        data class Said(val fromUser: Boolean, val text: String) : Item

        data class Proposed(val proposal: StyleDesigner.Proposal) : Item

        /** A picture drawn from [proposal]; [webp] becomes the style's reference if it's made. */
        class Preview(val proposal: StyleDesigner.Proposal, val quality: ImageQuality, val webp: ByteArray, val bitmap: Bitmap) : Item
    }

    data class State(
        /** `null` until the saved key has been looked up. */
        val needsKey: Boolean? = null,
        val checkingKey: Boolean = false,
        val keyError: String? = null,
        val items: List<Item> = emptyList(),
        val thinking: Boolean = false,
        val drawing: Boolean = false,
        val quality: ImageQuality = ImageQuality.MEDIUM,
        /** Why the last request failed; it can be tried again. */
        val error: String? = null,
        /** The style is being drawn, so the screen is done. */
        val made: Boolean = false,
    )

    private val keys = ApiKeyStore(app)
    private var key: String? = null
    private val conversation = mutableListOf<StyleDesigner.Message>()

    /** Repeats the request that last failed. */
    private var again: (() -> Unit)? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        val greeting = app.getString(R.string.studio_greeting)
        conversation += StyleDesigner.greeting(greeting)
        _state.update { it.copy(items = listOf(Item.Said(fromUser = false, greeting))) }
        viewModelScope.launch {
            key = withContext(Dispatchers.IO) { keys.get() }
            _state.update { it.copy(needsKey = key == null) }
        }
    }

    fun saveKey(text: String) {
        val candidate = text.trim()
        if (candidate.isEmpty() || state.value.checkingKey) return
        _state.update { it.copy(checkingKey = true, keyError = null) }
        viewModelScope.launch {
            val error = try {
                if (OpenAiClient.accepts(candidate)) {
                    withContext(Dispatchers.IO) { keys.set(candidate) }
                    key = candidate
                    null
                } else {
                    app.getString(R.string.key_rejected)
                }
            } catch (e: IOException) {
                describe(e)
            } catch (e: GeneralSecurityException) {
                e.message ?: e.javaClass.simpleName
            }
            _state.update { it.copy(checkingKey = false, keyError = error, needsKey = error != null) }
        }
    }

    /** Forgets the saved key, to enter another. */
    fun changeKey() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { keys.clear() }
            key = null
            _state.update { it.copy(needsKey = true, keyError = null) }
        }
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || state.value.thinking) return
        conversation += StyleDesigner.Message(StyleDesigner.Role.USER, message)
        _state.update { it.copy(items = it.items + Item.Said(fromUser = true, message)) }
        answer()
    }

    private fun answer() {
        val key = key ?: return
        _state.update { it.copy(thinking = true, error = null) }
        viewModelScope.launch {
            try {
                val turn = OpenAiClient.chat(key, conversation.toList())
                conversation += turn.message
                val said = listOfNotNull(Item.Said(fromUser = false, turn.reply), turn.proposal?.let { Item.Proposed(it) })
                _state.update { it.copy(items = it.items + said) }
            } catch (e: IOException) {
                fail(e) { answer() }
            } finally {
                _state.update { it.copy(thinking = false) }
            }
        }
    }

    fun setQuality(quality: ImageQuality) = _state.update { it.copy(quality = quality) }

    /** Draws [CustomStyle.REFERENCE] in the proposed style, the picture all the others will copy. */
    fun drawPreview(proposal: StyleDesigner.Proposal) {
        val key = key ?: return
        if (state.value.drawing) return
        val quality = state.value.quality
        _state.update { it.copy(drawing = true, error = null) }
        viewModelScope.launch {
            try {
                val picture = CustomStyle.REFERENCE
                val scene = withContext(Dispatchers.IO) {
                    app.assets.open(Style.PIXEL_ART.scene(picture)).use { it.readBytes() }
                }
                val webp = OpenAiClient.draw(key, ScenePrompts.forPicture(proposal.description, picture), listOf(scene), quality)
                // Half size is plenty on a phone, and keeps a few previews in memory cheap.
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(webp, 0, webp.size, options) }
                    ?: throw OpenAiException(OpenAiException.Problem.SERVER, "OpenAI sent a broken picture")
                _state.update { it.copy(items = it.items + Item.Preview(proposal, quality, webp, bitmap)) }
            } catch (e: IOException) {
                fail(e) { drawPreview(proposal) }
            } finally {
                _state.update { it.copy(drawing = false) }
            }
        }
    }

    /** Saves the style with [preview] as its first picture, and has the rest drawn. */
    fun make(preview: Item.Preview) {
        if (state.value.made) return
        val style = preview.proposal.toStyle(CustomStyle.newSlug(), preview.quality, System.currentTimeMillis())
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val store = CustomStyleStore(app)
                    store.save(style)
                    store.savePicture(style, CustomStyle.REFERENCE, preview.webp)
                }
            } catch (e: IOException) {
                fail(e) { make(preview) }
                return@launch
            }
            StyleWorker.start(app, style)
            _state.update { it.copy(made = true) }
        }
    }

    fun retry() {
        val request = again ?: return
        again = null
        request()
    }

    private fun fail(e: IOException, request: () -> Unit) {
        again = request
        _state.update { it.copy(error = describe(e)) }
        // Asks for a new key; the failed request can be tried again with it.
        if (e is OpenAiException && e.problem == OpenAiException.Problem.KEY) {
            _state.update { it.copy(needsKey = true, keyError = describe(e)) }
        }
    }

    private fun describe(e: IOException): String = when ((e as? OpenAiException)?.problem) {
        null -> app.getString(R.string.studio_error_network)
        OpenAiException.Problem.KEY -> app.getString(R.string.key_rejected)
        OpenAiException.Problem.QUOTA -> app.getString(R.string.studio_error_quota)
        OpenAiException.Problem.BUSY -> app.getString(R.string.studio_error_busy)
        OpenAiException.Problem.REJECTED -> app.getString(R.string.studio_error_rejected)
        OpenAiException.Problem.SERVER, OpenAiException.Problem.OTHER -> app.getString(R.string.studio_error_other, e.message)
    }
}

@Composable
private fun StudioScreen(onBack: () -> Unit, vm: StyleStudioViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.made) { if (state.made) onBack() }
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.gallery_back))
                }
                Text(stringResource(R.string.studio_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.needsKey == false) {
                    TextButton(onClick = vm::changeKey) { Text(stringResource(R.string.studio_change_key)) }
                }
            }
            when (state.needsKey) {
                null -> Unit
                true -> KeyForm(state, onSave = vm::saveKey)
                false -> Conversation(state, vm)
            }
        }
    }
}

@Composable
private fun KeyForm(state: StyleStudioViewModel.State, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val getKey = stringResource(R.string.key_get)
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.key_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.key_text), style = MaterialTheme.typography.bodyMedium)
        Text(
            buildAnnotatedString { withLink(LinkAnnotation.Url("https://platform.openai.com/api-keys")) { append(getKey) } },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.key_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            isError = state.keyError != null,
            supportingText = state.keyError?.let { error -> { Text(error) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSave(text) },
            enabled = !state.checkingKey && text.isNotBlank(),
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(if (state.checkingKey) R.string.key_checking else R.string.key_save)) }
    }
}

@Composable
private fun Conversation(state: StyleStudioViewModel.State, vm: StyleStudioViewModel) {
    val list = rememberLazyListState()
    // The preview the user wants all pictures drawn from, until they confirm.
    var confirming by remember { mutableStateOf<StyleStudioViewModel.Item.Preview?>(null) }
    // Follows the conversation as it grows.
    val count = state.items.size + listOf(state.thinking, state.drawing, state.error != null).count { it }
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count - 1) }

    Column {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(state.items) { item ->
                when (item) {
                    is StyleStudioViewModel.Item.Said -> Bubble(item)
                    is StyleStudioViewModel.Item.Proposed -> ProposalCard(
                        proposal = item.proposal,
                        quality = state.quality,
                        enabled = !state.drawing,
                        onQuality = vm::setQuality,
                        onDraw = { vm.drawPreview(item.proposal) },
                    )
                    is StyleStudioViewModel.Item.Preview -> PreviewCard(
                        preview = item,
                        enabled = !state.drawing,
                        onRedraw = { vm.drawPreview(item.proposal) },
                        onMake = { confirming = item },
                    )
                }
            }
            if (state.thinking) item { Working(stringResource(R.string.studio_thinking)) }
            if (state.drawing) item { Working(stringResource(R.string.studio_drawing_preview)) }
            state.error?.let { error ->
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = vm::retry, modifier = Modifier.align(Alignment.End)) {
                                Text(stringResource(R.string.studio_retry))
                            }
                        }
                    }
                }
            }
        }
        MessageInput(enabled = !state.thinking, onSend = vm::send)
    }

    confirming?.let { preview ->
        val name = preview.proposal.displayName
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.studio_make_title, name)) },
            text = {
                val rest = ScenePicture.entries.size - 1
                Text(pluralStringResource(R.plurals.studio_make_text, rest, rest, name))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.make(preview)
                    confirming = null
                }) { Text(stringResource(R.string.studio_make_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Bubble(said: StyleStudioViewModel.Item.Said) {
    Row(
        horizontalArrangement = if (said.fromUser) Arrangement.End else Arrangement.Start,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (said.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(said.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun ProposalCard(
    proposal: StyleDesigner.Proposal,
    quality: ImageQuality,
    enabled: Boolean,
    onQuality: (ImageQuality) -> Unit,
    onDraw: () -> Unit,
) {
    var details by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(proposal.displayName, style = MaterialTheme.typography.titleMedium)
            Text(proposal.summary, style = MaterialTheme.typography.bodyMedium)
            // What the image model is told, for the curious.
            TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) {
                Text(stringResource(R.string.studio_details))
            }
            if (details) {
                Text(proposal.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageQuality.entries.forEach {
                    FilterChip(selected = it == quality, onClick = { onQuality(it) }, label = { Text(stringResource(it.labelRes())) })
                }
            }
            Text(stringResource(R.string.studio_quality_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onDraw, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.studio_draw_preview))
            }
        }
    }
}

@Composable
private fun PreviewCard(preview: StyleStudioViewModel.Item.Preview, enabled: Boolean, onRedraw: () -> Unit, onMake: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(
            bitmap = preview.bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.studio_preview_description, preview.proposal.displayName),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            OutlinedButton(onClick = onRedraw, enabled = enabled) { Text(stringResource(R.string.studio_redraw)) }
            Button(onClick = onMake, enabled = enabled) { Text(stringResource(R.string.studio_make, ScenePicture.entries.size)) }
        }
    }
}

@Composable
private fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessageInput(enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (enabled && text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.studio_message_hint)) },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = send, enabled = enabled && text.isNotBlank()) { Text(stringResource(R.string.studio_send)) }
    }
}

@StringRes
private fun ImageQuality.labelRes(): Int = when (this) {
    ImageQuality.MEDIUM -> R.string.studio_quality_medium
    ImageQuality.HIGH -> R.string.studio_quality_high
}
