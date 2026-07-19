package top.azek431.hzzs.feature.about

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.dp
import top.azek431.hzzs.core.designsystem.HzzsSection

private const val AFDIAN_URL = "https://www.ifdian.net/a/Azek431"
private const val GITHUB_URL = "https://github.com/Azek431/hzzs"

@Composable
fun AboutScreen(onSaveQr: (DonationKind) -> Unit) {
    var donation by remember { mutableStateOf<DonationKind?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().then(if (donation != null) Modifier.blur(14.dp) else Modifier),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(92.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.LocalFireDepartment, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("火崽崽助手", style = MaterialTheme.typography.headlineSmall)
                    Text("0.1.0 · 本地视觉辅助", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                HzzsSection("项目") {
                    AboutRow(Icons.Rounded.Code, "GitHub 项目", "源码、问题反馈与发行版", GITHUB_URL)
                    AboutRow(Icons.Rounded.Update, "检查更新", "Gitee 优先，GitHub 交叉校验", null)
                    AboutRow(Icons.Rounded.PrivacyTip, "隐私与权限", "截图仅在本机处理")
                    AboutRow(Icons.Rounded.Article, "开源许可", "许可证与第三方声明")
                }
            }
            item {
                HzzsSection("赞赏") {
                    Text("赞赏完全自愿，不会解锁额外功能。谢谢你为项目添一小簇火光。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = { donation = DonationKind.WECHAT }, modifier = Modifier.weight(1f)) { Text("微信") }
                        FilledTonalButton(onClick = { donation = DonationKind.ALIPAY }, modifier = Modifier.weight(1f)) { Text("支付宝") }
                    }
                    val context = LocalContext.current
                    OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AFDIAN_URL))) }, modifier = Modifier.fillMaxWidth()) { Text("爱发电") }
                }
            }
            item {
                HzzsSection("安全说明") {
                    Text("自动操作默认关闭，并且需要本次会话明确启用。识别失败、切换主题、离开目标页面或更新配置时会自动解除。")
                }
            }
        }
        donation?.let { kind -> DonationDialog(kind = kind, onDismiss = { donation = null }, onLongPress = { onSaveQr(kind) }) }
    }
}

enum class DonationKind { WECHAT, ALIPAY }

@Composable private fun DonationDialog(kind: DonationKind, onDismiss: () -> Unit, onLongPress: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 8.dp, modifier = Modifier.padding(28.dp).clickable(enabled = false) {}) {
            Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (kind == DonationKind.WECHAT) "微信赞赏" else "支付宝赞赏", style = MaterialTheme.typography.titleLarge)
                val id = if (kind == DonationKind.WECHAT) top.azek431.hzzs.feature.about.R.drawable.donation_wechat else top.azek431.hzzs.feature.about.R.drawable.donation_alipay
                Image(bitmap = androidx.compose.ui.graphics.ImageBitmap.imageResource(id), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.sizeIn(maxWidth = 340.dp, maxHeight = 460.dp).pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) })
                Text("长按保存图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

@Composable private fun AboutRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, url: String? = null) {
    val context = LocalContext.current
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
        modifier = Modifier.clickable(enabled = url != null) { url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } },
    )
}
