package com.ports.fnfcompiler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ports.fnfcompiler.core.BuildTarget
import com.ports.fnfcompiler.core.SettingsStore
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType

@Composable
fun App() {
    FnfTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val scope = rememberCoroutineScope()
            val uriHandler = LocalUriHandler.current
            val model = remember { CompilerModel(scope, SettingsStore()) { uriHandler.openUri(it) } }
            HomeScreen(model)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(model: CompilerModel) {
    val picker = rememberFilePickerLauncher(
        type = PickerType.File(extensions = listOf("zip")),
        mode = PickerMode.Single,
        title = "Select the mod archive"
    ) { file ->
        if (file != null) model.selectZip(file.name) { file.readBytes() }
    }
    val logScroll = rememberScrollState()
    LaunchedEffect(model.log.size) { logScroll.animateScrollTo(logScroll.maxValue) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), Color.Transparent)),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AppLogo(size = 72.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("FNF Compiler", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    Text("Paste a source code link. Get the executable.", color = muted)
                }
            }

            Panel {
                SectionTitle("Source", "A public GitHub repository or a ZIP of the mod")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = model.fromRepo, onClick = { model.fromRepo = true }, label = { Text("GitHub URL") })
                    FilterChip(selected = !model.fromRepo, onClick = { model.fromRepo = false }, label = { Text("ZIP file") })
                }
                if (model.fromRepo) {
                    OutlinedTextField(
                        value = model.repoUrl,
                        onValueChange = { model.repoUrl = it },
                        label = { Text("Repository URL") },
                        placeholder = { Text("https://github.com/owner/repository") },
                        singleLine = true,
                        enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { model.repoUrl = EXAMPLE_URL }, enabled = !model.busy) {
                        Text("Use the Psych Engine example")
                    }
                } else {
                    OutlinedButton(
                        onClick = { picker.launch() },
                        enabled = !model.busy,
                        modifier = Modifier.fillMaxWidth().height(64.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(model.zip?.fileName ?: "Choose the mod .zip", fontWeight = FontWeight.Bold)
                            Text("Sources above 200 MB should use the GitHub URL", fontSize = 12.sp, color = muted)
                        }
                    }
                }
            }

            Panel {
                SectionTitle("Platform")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2
                ) {
                    BuildTarget.entries.forEach { option ->
                        PlatformCard(option, option == model.target, !model.busy, Modifier.weight(1f)) { model.selectTarget(option) }
                    }
                }
            }

            Button(
                onClick = { model.compile() },
                enabled = !model.busy,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(58.dp)
            ) {
                Text(if (model.busy) "Working" else "Compile for ${model.target.label}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                if (model.automatic) "Fully automatic: the build runs on GitHub Actions and the executable downloads when it is ready."
                else "No token set: the app opens GitHub and guides you through one click. Add a token in Advanced settings to make it automatic.",
                fontSize = 13.sp,
                color = muted
            )
            TextButton(onClick = { model.check() }, enabled = !model.busy) { Text("Only check the source") }

            if (model.busy || model.stage > 0 || model.status.isNotEmpty()) {
                Panel {
                    StepIndicator(model.stage, model.failed)
                    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (model.status.isNotEmpty()) {
                        Text(model.status, color = if (model.failed) WarnColor else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            model.manual?.let { pending ->
                Panel {
                    SectionTitle("Run the build on GitHub", "Press Run workflow on the page that opened and enter these values")
                    SelectionContainer {
                        Column(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            pending.values.forEach { (name, value) ->
                                Text("$name: ${value.ifEmpty { "(leave empty)" }}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.openPage(pending.pageUrl) }) { Text("Open Actions page") }
                        OutlinedButton(onClick = { model.checkManual() }, enabled = !model.busy) { Text("Check result") }
                    }
                }
            }

            model.outcome?.let { done ->
                val url = done.downloadUrl
                if (url != null) {
                    Panel {
                        SectionTitle("Your executable is ready", "${done.fileName ?: model.target.output}, ${done.sizeBytes / 1_048_576} MB")
                        Button(onClick = { model.openPage(url) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text("Download", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                done.runUrl?.let { page ->
                    OutlinedButton(onClick = { model.openPage(page) }, modifier = Modifier.fillMaxWidth()) { Text("Open build page") }
                }
            }

            model.analysis?.let { result ->
                Panel {
                    SectionTitle("Analysis", "${result.scanned} Haxe files, Haxe ${result.profile.haxe}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FindingTile("Engine", result.engine.label, result.engine.ok, Modifier.weight(1f))
                        FindingTile("Libraries", result.libs.label, result.libs.ok, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FindingTile("Code", result.code.label, result.code.ok, Modifier.weight(1f))
                        FindingTile("Project.xml", result.project.label, result.project.ok, Modifier.weight(1f))
                    }
                    if (result.warnings.isEmpty()) {
                        Text("No problems found.", color = OkColor, fontWeight = FontWeight.Bold)
                    } else {
                        result.warnings.take(3).forEach { Text("• $it", fontSize = 14.sp) }
                        if (result.warnings.size > 3) {
                            Collapsible("All ${result.warnings.size} warnings") {
                                result.warnings.drop(3).forEach { Text("• $it", fontSize = 14.sp) }
                            }
                        }
                    }
                    Text("The analysis reduces surprises. Only the real build confirms them.", fontSize = 12.sp, color = muted)
                }
            }

            Panel {
                Collapsible("Advanced settings") {
                    Text(
                        "The token is optional. With it, the app starts the build and follows it for you. Create a fine grained token for the build repository with Contents and Actions set to read and write. It stays on this device.",
                        fontSize = 13.sp,
                        color = muted
                    )
                    OutlinedTextField(
                        value = model.token,
                        onValueChange = { model.token = it },
                        label = { Text("GitHub token (optional)") },
                        placeholder = { Text("github_pat_...") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = model.buildRepo,
                            onValueChange = { model.buildRepo = it },
                            label = { Text("Build repository") },
                            placeholder = { Text(DEFAULT_REPO) },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = model.branch,
                            onValueChange = { model.branch = it },
                            label = { Text("Branch") },
                            placeholder = { Text(DEFAULT_BRANCH) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (model.log.isNotEmpty()) {
                Panel {
                    Collapsible("Build log", initiallyOpen = true) {
                        SelectionContainer {
                            Box(
                                Modifier.fillMaxWidth().height(240.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                    .verticalScroll(logScroll)
                                    .padding(10.dp)
                            ) {
                                Text(
                                    model.log.joinToString("\n"),
                                    color = OkColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
