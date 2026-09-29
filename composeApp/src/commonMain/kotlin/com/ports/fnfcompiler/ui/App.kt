package com.ports.fnfcompiler.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ports.fnfcompiler.core.Analysis
import com.ports.fnfcompiler.core.Analyzer
import com.ports.fnfcompiler.core.BuildConfig
import com.ports.fnfcompiler.core.BuildOutcome
import com.ports.fnfcompiler.core.BuildTarget
import com.ports.fnfcompiler.core.GitHubBuilder
import com.ports.fnfcompiler.core.SettingsStore
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DEFAULT_REPO = "Marques16-bit/Fnf-compilador"
private const val DEFAULT_BRANCH = "main"

private class PickedZip(val name: String, val bytes: ByteArray)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun App() {
    FnfTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val scope = rememberCoroutineScope()
            val store = remember { SettingsStore() }
            val uriHandler = LocalUriHandler.current
            val saved = remember { store.load() }

            var target by remember { mutableStateOf(store.loadTarget()) }
            var zip by remember { mutableStateOf<PickedZip?>(null) }
            var analysis by remember { mutableStateOf<Analysis?>(null) }
            var status by remember { mutableStateOf("") }
            var token by remember { mutableStateOf(saved.token) }
            var repo by remember { mutableStateOf(saved.repo) }
            var branch by remember { mutableStateOf(saved.branch) }
            var busy by remember { mutableStateOf(false) }
            var outcome by remember { mutableStateOf<BuildOutcome?>(null) }
            val logLines = remember { mutableStateListOf<String>() }
            val logScroll = rememberScrollState()

            val picker = rememberFilePickerLauncher(
                type = PickerType.File(extensions = listOf("zip")),
                mode = PickerMode.Single,
                title = "Select the mod archive"
            ) { file ->
                if (file != null) {
                    scope.launch {
                        status = "Reading ${file.name}"
                        try {
                            zip = PickedZip(file.name, file.readBytes())
                        } catch (e: Exception) {
                            zip = null
                            analysis = null
                            status = "Could not read the file: ${e.message}"
                        }
                    }
                }
            }

            LaunchedEffect(zip, target) {
                val picked = zip ?: return@LaunchedEffect
                status = "Analyzing the project"
                analysis = null
                try {
                    analysis = withContext(Dispatchers.Default) { Analyzer.analyze(picked.bytes, target) }
                    status = if (analysis?.hasProject == true) "Analysis finished" else "Project.xml was not found in the archive"
                } catch (e: Exception) {
                    status = "Could not open the archive: ${e.message}"
                }
            }

            LaunchedEffect(logLines.size) {
                logScroll.animateScrollTo(logScroll.maxValue)
            }

            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("FNF COMPILER", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Pick the mod archive, choose a platform and the app builds it on GitHub Actions. The archive is removed from the release as soon as the build starts.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text("Platform", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BuildTarget.entries.forEach { option ->
                            FilterChip(
                                selected = option == target,
                                onClick = {
                                    target = option
                                    store.saveTarget(option)
                                },
                                label = { Text("${option.label} ${option.output}") }
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { picker.launch() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(zip?.name ?: "Choose the mod .zip", fontWeight = FontWeight.Bold)
                            Text("It must contain Project.xml", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    analysis?.let { result ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FindingCard("Engine", result.engine.label, result.engine.ok)
                            FindingCard("Libraries", result.libs.label, result.libs.ok)
                            FindingCard("Code", result.code.label, result.code.ok)
                            FindingCard("Project.xml", result.project.label, result.project.ok)
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (result.warnings.isEmpty()) {
                                    Text("No problems found in the analysis.", color = OkColor, fontWeight = FontWeight.Bold)
                                } else {
                                    result.warnings.forEach { Text("• $it") }
                                }
                                if (result.hasProject) {
                                    Text(
                                        "${result.scanned} .hx files scanned. The analysis reduces errors but only the real build confirms them.",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Build", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Create a fine grained GitHub token for the build repository only, with Contents and Actions set to read and write. It is stored only on this device.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it },
                                label = { Text("GitHub token") },
                                placeholder = { Text("github_pat_...") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = repo,
                                    onValueChange = { repo = it },
                                    label = { Text("Build repository") },
                                    placeholder = { Text(DEFAULT_REPO) },
                                    singleLine = true,
                                    modifier = Modifier.weight(2f)
                                )
                                OutlinedTextField(
                                    value = branch,
                                    onValueChange = { branch = it },
                                    label = { Text("Branch") },
                                    placeholder = { Text(DEFAULT_BRANCH) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    enabled = !busy,
                                    onClick = {
                                        val picked = zip
                                        logLines.clear()
                                        outcome = null
                                        if (token.isBlank()) {
                                            logLines.add("Fill in the GitHub token")
                                        } else if (picked == null) {
                                            logLines.add("Choose the mod .zip first")
                                        } else if (analysis?.hasProject != true) {
                                            logLines.add("The archive needs a Project.xml before it can be built")
                                        } else {
                                            val config = BuildConfig(
                                                token.trim(),
                                                repo.trim().ifEmpty { DEFAULT_REPO },
                                                branch.trim().ifEmpty { DEFAULT_BRANCH }
                                            )
                                            store.save(BuildConfig(config.token, repo.trim(), branch.trim()))
                                            busy = true
                                            scope.launch {
                                                val builder = GitHubBuilder(config) { logLines.add(it) }
                                                try {
                                                    outcome = builder.build(picked.bytes, picked.name, target)
                                                } catch (e: Exception) {
                                                    logLines.add(e.message ?: "The build could not be started. Check your connection and try again")
                                                } finally {
                                                    builder.close()
                                                    busy = false
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary)
                                ) {
                                    Text("Compile ${target.label}")
                                }
                                if (busy) CircularProgressIndicator(Modifier.height(28.dp), strokeWidth = 3.dp)
                            }
                            SelectionContainer {
                                Box(
                                    Modifier.fillMaxWidth().height(240.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                        .verticalScroll(logScroll)
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        logLines.joinToString("\n"),
                                        color = OkColor,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.5.sp
                                    )
                                }
                            }
                            outcome?.let { done ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    done.downloadUrl?.let { url ->
                                        Button(onClick = { uriHandler.openUri(url) }) {
                                            Text("Download ${done.fileName ?: target.output} (${done.sizeBytes / 1_048_576} MB)")
                                        }
                                    }
                                    done.runUrl?.let { url ->
                                        OutlinedButton(onClick = { uriHandler.openUri(url) }) { Text("Open build page") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindingCard(title: String, label: String, ok: Boolean) {
    val color = if (ok) OkColor else WarnColor
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, color),
        modifier = Modifier.widthIn(min = 150.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontWeight = FontWeight.Bold, color = color)
        }
    }
}
