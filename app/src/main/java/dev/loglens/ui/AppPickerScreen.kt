package dev.loglens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import dev.loglens.ui.mx.CircularProgressIndicator
import dev.loglens.ui.mx.ExperimentalMaterial3Api
import dev.loglens.ui.mx.Icon
import dev.loglens.ui.mx.IconButton
import androidx.compose.material3.MaterialTheme
import dev.loglens.ui.mx.OutlinedTextField
import dev.loglens.ui.mx.Scaffold
import dev.loglens.ui.mx.Surface
import dev.loglens.ui.mx.Text
import dev.loglens.ui.mx.TopAppBar
import dev.loglens.ui.mx.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.loglens.data.AppRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    current: String,
    onBack: () -> Unit,
    onSelect: (AppRepository.AppItem) -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    var apps by remember { mutableStateOf<List<AppRepository.AppItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = AppRepository.loadInstalled(context)
        loading = false
    }

    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "选择应用",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = colors.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = {
                    Text("搜应用名或包名", style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "清除",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                textStyle = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = when {
                    loading -> "正在读安装列表…"
                    query.isBlank() -> "共 ${apps.size} 个应用"
                    else -> "匹配 ${filtered.size} 个，共 ${apps.size} 个"
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            Spacer(Modifier.height(4.dp))

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(26.dp)
                    )
                }

                filtered.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.SearchOff,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "没有匹配的应用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 2.dp,
                        bottom = 24.dp
                    )
                ) {
                    items(items = filtered, key = { it.packageName }) { app ->
                        AppRow(
                            app = app,
                            selected = app.packageName == current,
                            onClick = { onSelect(app) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppRepository.AppItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app.packageName, size = 42, corner = 12)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (app.isSystem) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = colors.surfaceVariant,
                        contentColor = colors.onSurfaceVariant
                    ) {
                        Text(
                            "系统",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${app.packageName} · uid ${app.uid}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Rounded.Check,
                contentDescription = "已选择",
                tint = colors.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

