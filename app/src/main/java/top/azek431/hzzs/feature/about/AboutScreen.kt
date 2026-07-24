/**
 * 关于页。
 *
 * 职责：展示版本 / 免责声明 / 捐赠入口与项目链接。
 * 边界：不承载开发者选项；诊断、调试帧、Native 自检等仅在设置「开发者选项」分类中配置。
 */
package top.azek431.hzzs.feature.about

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.R
import top.azek431.hzzs.core.designsystem.HzzsCallout
import top.azek431.hzzs.core.designsystem.HzzsCalloutTone
import top.azek431.hzzs.core.designsystem.LocalHzzsDimensions
import top.azek431.hzzs.core.designsystem.SectionCard

enum class DonationKind { WECHAT, ALIPAY }

/**
 * 关于主界面：版本信息、项目说明、免责声明与赞赏入口。
 * 开发者选项请到设置 → 开发者选项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    onSaveQr: (DonationKind) -> Unit,
) {
    var donation by remember { mutableStateOf<DonationKind?>(null) }
    val dimensions = LocalHzzsDimensions.current
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.about_title)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(dimensions.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimensions.sectionGap),
        ) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(dimensions.cardPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.LocalFireDepartment,
                            contentDescription = null,
                            Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("HZZS", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            stringResource(R.string.about_tagline),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.about_version_chip, versionName)) },
                            leadingIcon = { Icon(Icons.Rounded.Tag, contentDescription = null) },
                        )
                    }
                }
            }
            item {
                SectionCard {
                    Text(
                        stringResource(R.string.about_project_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.about_project_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                HzzsCallout(
                    title = stringResource(R.string.about_disclaimer_title),
                    text = stringResource(R.string.about_disclaimer_body),
                    tone = HzzsCalloutTone.WARNING,
                )
            }
            item {
                Text(
                    stringResource(R.string.about_support_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { donation = DonationKind.WECHAT }) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.about_wechat))
                    }
                    FilledTonalButton(onClick = { donation = DonationKind.ALIPAY }) {
                        Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.about_alipay))
                    }
                }
            }
            item {
                AboutRow(
                    Icons.Rounded.Code,
                    stringResource(R.string.about_github),
                    stringResource(R.string.about_github_sub),
                    "https://github.com/Azek431/hzzs",
                )
            }
            item {
                AboutRow(
                    Icons.AutoMirrored.Rounded.Article,
                    stringResource(R.string.about_license),
                    stringResource(R.string.about_license_sub),
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    donation?.let { kind ->
        DonationDialog(kind, onDismiss = { donation = null }, onSave = { onSaveQr(kind) })
    }
}

@Composable
private fun DonationDialog(kind: DonationKind, onDismiss: () -> Unit, onSave: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (kind == DonationKind.WECHAT) {
                    stringResource(R.string.about_donation_wechat)
                } else {
                    stringResource(R.string.about_donation_alipay)
                },
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val id = if (kind == DonationKind.WECHAT) {
                    R.drawable.donation_wechat
                } else {
                    R.drawable.donation_alipay
                }
                Image(
                    bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(id),
                    contentDescription = stringResource(R.string.about_donation_qr_cd),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .sizeIn(maxWidth = 340.dp, maxHeight = 460.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onLongPress = { onSave() })
                        },
                )
                Text(
                    stringResource(R.string.about_donation_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text(stringResource(R.string.action_save_to_gallery))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}

@Composable
private fun AboutRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    url: String? = null,
) {
    val context = LocalContext.current
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            if (url != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        },
        modifier = Modifier.clickable(enabled = url != null) {
            url?.let { target ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target))) }
                    .onFailure {
                        Toast.makeText(
                            context,
                            context.getString(R.string.about_open_link_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
            }
        },
    )
}
