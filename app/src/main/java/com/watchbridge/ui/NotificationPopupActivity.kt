package com.watchbridge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.SwipeToDismissValue
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchbridge.R
import com.watchbridge.notification.NotificationIcons
import com.watchbridge.ui.theme.WatchBridgeTheme
import kotlinx.coroutines.delay

class NotificationPopupActivity : ComponentActivity() {

    companion object {
        const val BROADCAST_POPUP_DISMISSED = "com.watchbridge.POPUP_DISMISSED"
        internal const val AUTO_DISMISS_MS = 7000L
    }

    private var appName: String = ""
    private var sender: String = ""
    private var body: String = ""
    private var categoryId: Byte = 0
    private var notificationUid: Int = -1

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val uid = intent.getIntExtra("notification_uid", -1)
            if (uid == -1 || uid == notificationUid) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)
        renderContent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readExtras(intent)
        renderContent()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            dismissReceiver,
            IntentFilter(BROADCAST_POPUP_DISMISSED),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dismissReceiver)
    }

    private fun readExtras(intent: Intent) {
        appName = intent.getStringExtra("app_name") ?: ""
        sender = intent.getStringExtra("sender") ?: ""
        body = intent.getStringExtra("body") ?: ""
        categoryId = intent.getByteExtra("category_id", 0)
        notificationUid = intent.getIntExtra("notification_uid", -1)
    }

    private fun renderContent() {
        setContent {
            WatchBridgeTheme {
                NotificationPopupScreen(
                    appName = appName,
                    sender = sender,
                    body = body,
                    categoryId = categoryId,
                    autoDismissKey = notificationUid,
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
private fun NotificationPopupScreen(
    appName: String,
    sender: String,
    body: String,
    categoryId: Byte,
    autoDismissKey: Int,
    onDismiss: () -> Unit
) {
    LaunchedEffect(autoDismissKey) {
        delay(NotificationPopupActivity.AUTO_DISMISS_MS)
        onDismiss()
    }

    val swipeState = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipeState.currentValue) {
        if (swipeState.currentValue == SwipeToDismissValue.Dismissed) {
            onDismiss()
        }
    }

    SwipeToDismissBox(
        state = swipeState,
        modifier = Modifier.fillMaxSize()
    ) { isBackground ->
        if (isBackground) {
            Box(modifier = Modifier.fillMaxSize())
        } else {
            PopupContent(
                appName = appName,
                sender = sender,
                body = body,
                categoryId = categoryId,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun PopupContent(
    appName: String,
    sender: String,
    body: String,
    categoryId: Byte,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Top: category icon + app name caption
            if (appName.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        painter = painterResource(NotificationIcons.iconForCategory(categoryId)),
                        contentDescription = null,
                        tint = MaterialTheme.colors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = appName,
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Sender name with subtle ring
            if (sender.isNotEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colors.primary.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = sender,
                        style = MaterialTheme.typography.title2,
                        color = MaterialTheme.colors.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Message body — scrollable for long messages
            if (body.isNotEmpty()) {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 70.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            // Dismiss button
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Dismiss",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
