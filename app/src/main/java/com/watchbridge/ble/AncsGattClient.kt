package com.watchbridge.ble

/**
 * Marker file — ANCS GATT client logic is now integrated directly into
 * [BleConnectionManager] because Nordic BLE library's enableNotifications,
 * setNotificationCallback, and writeCharacteristic are protected methods
 * that can only be called from within the BleManager subclass.
 *
 * This file is kept for reference. All ANCS GATT operations are in
 * BleConnectionManager.kt.
 */
