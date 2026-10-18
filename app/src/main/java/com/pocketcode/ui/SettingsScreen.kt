package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketcode.Provider
import com.pocketcode.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(initial: Settings, onSave: (Settings) -> Unit, onBack: () -> Unit) {
    var provider by remember { mutableStateOf(initial.provider) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var githubToken by remember { mutableStateOf(initial.githubToken) }
    var gitName by remember { mutableStateOf(initial.gitName) }
    var gitEmail by remember { mutableStateOf(initial.gitEmail) }
    var autoApprove by remember { mutableStateOf(initial.autoApprove) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Модель", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Provider.values().forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = {
                            provider = p
                            baseUrl = p.defaultBaseUrl
                            model = p.defaultModel
                        },
                        label = { Text(p.label) },
                    )
                }
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Базовый URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Модель") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API-ключ") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Для OpenRouter укажи https://openrouter.ai/api/v1 и имя модели вроде anthropic/claude-sonnet-4.5.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Git", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = githubToken,
                onValueChange = { githubToken = it },
                label = { Text("GitHub-токен (для clone/push приватных репозиториев)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = gitName,
                onValueChange = { gitName = it },
                label = { Text("Имя автора коммитов") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = gitEmail,
                onValueChange = { gitEmail = it },
                label = { Text("E-mail автора коммитов") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Безопасность", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Выполнять без подтверждения")
                    Text(
                        "Агент сам запускает команды и правит файлы. Включай, только если доверяешь задаче.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = autoApprove, onCheckedChange = { autoApprove = it })
            }

            Button(
                onClick = {
                    onSave(
                        Settings(
                            provider = provider,
                            baseUrl = baseUrl,
                            model = model,
                            apiKey = apiKey,
                            githubToken = githubToken,
                            gitName = gitName,
                            gitEmail = gitEmail,
                            autoApprove = autoApprove,
                        ),
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить") }
        }
    }
}
