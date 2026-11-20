package cn.lemwood.keyvault.ui.screen.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.lemwood.keyvault.data.backup.MAX_BACKUP_PASSWORD_CHARS
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun BackupPasswordDialog(
    exporting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    error: String? = null,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val autofill = LocalAutofillManager.current
    val dismiss = {
        autofill?.cancel()
        onDismiss()
    }
    val passwordsMatch = !exporting || password == confirmation
    val helper = when {
        error != null -> error
        exporting && confirmation.isNotEmpty() && !passwordsMatch -> "两次输入的口令不同"
        exporting && password.isNotEmpty() && (password.length < 12 || password.toSet().size < 6) ->
            "口令较弱，建议使用至少 12 个字符或多个随机词"
        else -> null
    }
    SuperDialog(
        show = true,
        title = if (exporting) "加密导出" else "解锁备份",
        summary = if (exporting) "请妥善保存口令，导入时需要用它解密备份。" else null,
        onDismissRequest = dismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BackupPasswordField("备份口令", password) { password = it }
            if (exporting) {
                BackupPasswordField("确认口令", confirmation) { confirmation = it }
            }
            if (helper != null) {
                Text(helper, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote2)
            }
            TextButton(
                text = if (exporting) "继续导出" else "解锁",
                onClick = {
                    autofill?.cancel()
                    onConfirm(password)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = password.isNotBlank() && passwordsMatch,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            TextButton(text = "取消", onClick = dismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BackupPasswordField(label: String, value: String, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = { if (it.length <= MAX_BACKUP_PASSWORD_CHARS) onChange(it) },
        label = label,
        modifier = Modifier.fillMaxWidth().semantics { contentDataType = ContentDataType.None },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
internal fun BackupProgressDialog(stage: String, cancelling: Boolean, onCancel: () -> Unit) {
    SuperDialog(show = true, title = "备份", onDismissRequest = { if (!cancelling) onCancel() }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(stage)
            }
            TextButton("取消", onClick = onCancel, modifier = Modifier.fillMaxWidth(), enabled = !cancelling)
        }
    }
}
