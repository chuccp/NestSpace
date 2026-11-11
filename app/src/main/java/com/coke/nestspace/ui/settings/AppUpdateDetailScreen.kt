package com.coke.nestspace.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coke.nestspace.i18n.AppLanguage
import com.coke.nestspace.ui.agent.MarkdownContent
import com.coke.nestspace.update.AppUpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AppUpdateDetailScreen(
    state: AppUpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onOpenRelease: () -> Unit,
    onSkip: () -> Unit,
    onRestoreSkipped: () -> Unit
) {
    val release = state.release
    val checkedAt = remember(state.checkedAtMillis) {
        state.checkedAtMillis.takeIf { it > 0L }?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsSection(AppLanguage.tr("App 更新", "App update")) }
        item {
            SettingsCard(
                title = "NestSpace",
                status = AppLanguage.tr(
                    "当前 ${state.currentVersion}",
                    "Installed ${state.currentVersion}"
                )
            ) {
                when {
                    state.checking -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp))
                            Spacer(Modifier.weight(1f))
                        }
                        Text(
                            AppLanguage.tr(
                                "正在通过 GitHub 检查新版本…",
                                "Checking GitHub for a new version…"
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    release != null -> {
                        Text(
                            AppLanguage.tr(
                                "发现新版本 ${release.tagName}",
                                "Version ${release.tagName} is available"
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (state.skippedVersion == release.version) {
                            Text(
                                AppLanguage.tr(
                                    "你已跳过此版本。新版本发布后仍会再次提醒。",
                                    "You skipped this version. A newer release will be " +
                                        "announced again."
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(AppLanguage.tr("下载并更新", "Download update"))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = onOpenRelease,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(AppLanguage.tr("查看 GitHub Release", "View release"))
                            }
                            if (state.skippedVersion == release.version) {
                                TextButton(
                                    onClick = onRestoreSkipped,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(AppLanguage.tr("恢复提醒", "Restore alert"))
                                }
                            } else {
                                TextButton(
                                    onClick = onSkip,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(AppLanguage.tr("跳过此版本", "Skip this version"))
                                }
                            }
                        }
                    }

                    else -> {
                        Text(
                            AppLanguage.tr("已是最新版本", "You're up to date"),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                if (!state.checking) {
                    OutlinedButton(
                        onClick = onCheck,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(AppLanguage.tr("检查更新", "Check for updates"))
                    }
                }
                state.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                checkedAt?.let {
                    Text(
                        AppLanguage.tr("上次检查：$it", "Last checked: $it"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    AppLanguage.tr(
                        "每天最多自动检查一次，更新由 GitHub Releases 提供。",
                        "Checks automatically at most once per day. Updates are " +
                            "provided through GitHub Releases."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (release != null && release.releaseNotes.isNotBlank()) {
            item { SettingsSection(AppLanguage.tr("更新内容", "What's new")) }
            item {
                SettingsCard(
                    title = release.tagName,
                    status = AppLanguage.tr("版本说明", "Release notes")
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MarkdownContent(release.releaseNotes)
                    }
                }
            }
        }
    }
}
