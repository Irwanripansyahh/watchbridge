package com.watchbridge.ancs

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages ANCS session state: tracks active notification UIDs,
 * handles session lifecycle, and coordinates event processing.
 */
class AncsSessionManager {

    enum class SessionState {
        DISCONNECTED,
        DISCOVERING,
        SUBSCRIBING,
        ACTIVE,
        RECONNECTING
    }

    private val _sessionState = MutableStateFlow(SessionState.DISCONNECTED)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _events = MutableSharedFlow<AncsNotificationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AncsNotificationEvent> = _events.asSharedFlow()

    private val _eventLog = MutableStateFlow<List<String>>(emptyList())
    val eventLog: StateFlow<List<String>> = _eventLog.asStateFlow()

    /** Set of currently known notification UIDs. */
    private val activeUids = mutableSetOf<UInt>()

    /** Parser for Data Source fragments. */
    val attributeParser = AncsAttributeParser()

    fun updateState(state: SessionState) {
        _sessionState.value = state
    }

    /**
     * Process an incoming 8-byte Notification Source event.
     */
    fun processNotificationEvent(data: ByteArray) {
        val event = AncsNotificationEvent.parse(data) ?: return

        when {
            event.isAdded || event.isModified -> {
                activeUids.add(event.notificationUid)
            }
            event.isRemoved -> {
                activeUids.remove(event.notificationUid)
            }
        }

        _events.tryEmit(event)
        appendLog(event.toString())
    }

    /**
     * Process a Data Source fragment. Returns parsed result if complete.
     */
    fun processDataSourceFragment(data: ByteArray): Any? {
        return attributeParser.feedFragment(data)
    }

    /**
     * Clear session state on disconnect. UIDs become invalid after disconnect.
     */
    fun onDisconnected() {
        activeUids.clear()
        attributeParser.reset()
        _sessionState.value = SessionState.DISCONNECTED
    }

    /**
     * Reset for a fresh session (e.g., after long disconnect).
     */
    fun resetSession() {
        activeUids.clear()
        attributeParser.reset()
        _eventLog.value = emptyList()
    }

    fun isUidActive(uid: UInt): Boolean = uid in activeUids

    fun getActiveUidCount(): Int = activeUids.size

    private fun appendLog(message: String) {
        val current = _eventLog.value
        // Keep last 50 entries
        _eventLog.value = (current + message).takeLast(50)
    }
}
