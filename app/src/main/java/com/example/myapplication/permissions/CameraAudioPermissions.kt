package com.example.myapplication.permissions

import android.Manifest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RequestCameraAndAudioPermissions(
    onAllPermissionsGranted: () -> Unit,
    onPermissionsDenied: () -> Unit
) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            onAllPermissionsGranted()
        }
    }

    when {
        permissionsState.allPermissionsGranted -> {
            // 所有权限已授予，无需显示任何内容
        }
        permissionsState.shouldShowRationale -> {
            PermissionExplanationDialog(
                onConfirm = { permissionsState.launchMultiplePermissionRequest() },
                onDismiss = onPermissionsDenied,
                message = "为了进行AI视频通话，我们需要访问您的相机和麦克风。"
            )
        }
        else -> {
            LaunchedEffect(Unit) {
                permissionsState.launchMultiplePermissionRequest()
            }
        }
    }
}

@Composable
private fun PermissionExplanationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要权限") },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("授予权限")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
} 