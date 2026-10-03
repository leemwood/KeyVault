package cn.lemwood.keyvault.ui.screen.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.lemwood.keyvault.data.model.VaultProfile
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog

/**
 * 首页左上角的配置切换弹窗：列出全部配置，点一下就切过去。
 * 底部提供「管理配置」入口，指向导入/导出/重命名/删除的管理页。
 */
@Composable
fun ProfileSwitcherDialog(
    profiles: List<VaultProfile>,
    activeProfileId: String,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onManage: () -> Unit
) {
    val activeName = profiles.find { it.id == activeProfileId }?.name.orEmpty()

    SuperDialog(
        show = true,
        title = "切换配置",
        summary = "当前：$activeName",
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            profiles.forEach { profile ->
                val isActive = profile.id == activeProfileId
                TextButton(
                    text = buildString {
                        append(profile.name)
                        append(" · ")
                        append(profile.services.size)
                        append(" 个服务")
                        if (isActive) append("（当前）")
                    },
                    onClick = {
                        if (!isActive) onSwitch(profile.id)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isActive) {
                        ButtonDefaults.textButtonColorsPrimary()
                    } else {
                        ButtonDefaults.textButtonColors()
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                text = "管理配置",
                onClick = onManage,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
