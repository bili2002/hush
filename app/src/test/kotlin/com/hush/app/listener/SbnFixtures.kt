package com.hush.app.listener

import android.app.Notification
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import org.robolectric.RuntimeEnvironment

/** Robolectric application context. */
fun appContext(): Context = RuntimeEnvironment.getApplication()

/** Synthetic notification: fixed channel, overridable extras and flags. Never real content. */
fun notification(
    title: CharSequence? = "Synthetic title",
    text: CharSequence? = "Synthetic text",
    bigText: CharSequence? = null,
    subText: CharSequence? = null,
    textLines: Array<CharSequence>? = null,
    flags: Int = 0,
    category: String? = null,
    build: (NotificationCompat.Builder) -> Unit = {},
): Notification {
    val b = NotificationCompat.Builder(appContext(), "synthetic-channel")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(text)
        .setSubText(subText)
    if (category != null) b.setCategory(category)
    if (bigText != null) b.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
    if (textLines != null) b.setStyle(NotificationCompat.InboxStyle().also { s -> textLines.forEach { s.addLine(it) } })
    build(b)
    return b.build().also { it.flags = it.flags or flags }
}

fun messagingNotification(
    messages: List<Pair<String?, String>>,
    historic: List<Pair<String?, String>> = emptyList(),
): Notification = notification(title = null, text = null) { b ->
    val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
    messages.forEach { (sender, text) -> style.addMessage(text, 1_000L, sender?.let { Person.Builder().setName(it).build() }) }
    historic.forEach { (sender, text) ->
        style.addHistoricMessage(NotificationCompat.MessagingStyle.Message(text, 500L, sender?.let { Person.Builder().setName(it).build() }))
    }
    b.setStyle(style)
}

/** Wraps [n] the way the system would. Key becomes `0|pkg|id|tag|uid`. */
@Suppress("DEPRECATION")
fun sbn(
    n: Notification = notification(),
    packageName: String = "com.example.alpha",
    id: Int = 1,
    tag: String? = null,
    postTime: Long = 1_700_000_000_000L,
    uid: Int = 10_000,
): StatusBarNotification =
    StatusBarNotification(packageName, packageName, id, tag, uid, 0, 0, n, Process.myUserHandle(), postTime)
