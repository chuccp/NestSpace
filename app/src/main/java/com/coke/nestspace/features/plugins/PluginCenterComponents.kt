package com.coke.nestspace.features.plugins

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coke.nestspace.R
import com.coke.nestspace.core.i18n.AppLanguage
import com.coke.nestspace.plugin.PluginCapability
import com.coke.nestspace.plugin.PluginPermission

@Composable
internal fun PluginInstalledHeader(
    installedCount: Int,
    installing: Boolean,
    installStatus: String,
    onInstall: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_plugin_center),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        AppLanguage.tr(
                            "$installedCount 个已安装",
                            "$installedCount installed"
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        AppLanguage.tr(
                            "插件参数、工具、界面与运行时代理",
                            "Plugin settings, tools, UI and runtime proxies"
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                AppLanguage.tr(
                    "管理已安装插件的参数和能力。",
                    "Manage settings and capabilities of installed plugins."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onInstall, enabled = !installing) {
                    Icon(
                        Icons.Rounded.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (installing) {
                            AppLanguage.tr("正在安装…", "Installing…")
                        } else {
                            AppLanguage.tr("从文件安装", "Install from file")
                        }
                    )
                }
                if (installStatus.isNotBlank()) {
                    Text(
                        installStatus,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
internal fun PluginSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp, start = 2.dp)
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PluginTileGrid(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        content()
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun PluginTile(
    title: String,
    subtitle: String,
    iconFile: java.io.File?,
    fallbackIcon: ImageVector,
    badge: String? = null,
    enabled: Boolean = true,
    onOpen: () -> Unit,
    menuActions: List<PluginMenuAction> = emptyList()
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val bitmap = remember(iconFile?.path) {
        iconFile?.takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.path) }
            ?.asImageBitmap()
    }
    Box {
        Column(
            modifier = Modifier
                .width(92.dp)
                .clip(RoundedCornerShape(18.dp))
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = {
                        if (menuActions.isNotEmpty()) {
                            menuExpanded = true
                        }
                    }
                )
                .padding(horizontal = 3.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .alpha(if (enabled) 1f else 0.5f),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        fallbackIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(30.dp)
                    )
                }
                if (!enabled) {
                    PluginBadge(
                        text = AppLanguage.tr("停用", "OFF"),
                        alignment = Alignment.TopEnd,
                        background = MaterialTheme.colorScheme.error,
                        foreground = MaterialTheme.colorScheme.onError,
                        bold = true
                    )
                }
                if (!badge.isNullOrBlank()) {
                    PluginBadge(
                        text = badge,
                        alignment = Alignment.BottomStart,
                        background = MaterialTheme.colorScheme.primary,
                        foreground = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            menuActions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            color = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            action.icon,
                            contentDescription = null,
                            tint = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        action.onClick()
                    }
                )
            }
        }
    }
}

@Composable
private fun BoxScope.PluginBadge(
    text: String,
    alignment: Alignment,
    background: androidx.compose.ui.graphics.Color,
    foreground: androidx.compose.ui.graphics.Color,
    bold: Boolean = false
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(3.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            fontWeight = if (bold) FontWeight.Bold else null,
            maxLines = 1
        )
    }
}

@Composable
internal fun PluginCategoryRow(
    selected: PluginCategory,
    onSelect: (PluginCategory) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(PluginCategory.entries, key = { it.name }) { item ->
            FilterChip(
                selected = selected == item,
                onClick = { onSelect(item) },
                label = { Text(categoryLabel(item)) }
            )
        }
    }
}

@Composable
internal fun EmptyPluginResults() {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                AppLanguage.tr(
                    "没有找到匹配的插件",
                    "No matching plugins found"
                ),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                AppLanguage.tr(
                    "尝试清空搜索或切换分类。",
                    "Try clearing search or changing category."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PluginCapabilitySummary(capabilities: Set<PluginCapability>) {
    if (capabilities.isEmpty()) return
    val permissions = PluginPermission.groupsFor(capabilities).toList()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        permissions.take(4).forEach { permission ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        permissionLabel(permission),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
        if (permissions.size > 4) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        "+${permissions.size - 4}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
    }
}
