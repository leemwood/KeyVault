package cn.lemwood.keyvault.ui.screen.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperDialog

@Composable
fun InputDialog(
    title: String,
    hint: String,
    initialValue: String = "",
    confirmEnabled: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf(initialValue) }
    val focusRequester = FocusRequester()
    val canConfirm = confirmEnabled && text.isNotBlank()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    SuperDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = text,
                onValueChange = { text = it },
                label = hint,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canConfirm) {
                            onConfirm(text)
                        }
                    }
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(
                text = "确定",
                onClick = { onConfirm(text) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canConfirm,
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
