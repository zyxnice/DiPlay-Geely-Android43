package com.shilapi.xcertplay.compat

/** Linux usbfs operations on the fd Android has already authorized and opened. */
object LegacyUsbNative {
    init { System.loadLibrary("legacy_usb_compat") }
    external fun configure(fd: Int, configurationId: Int): Int
    /** H53/8600 opt-in only: verified 05ac:12a8, config 2 -> 6, snd-usb-audio only. */
    external fun configureGeely(fd: Int, configurationId: Int): Int
    external fun selectAlternate(fd: Int, interfaceId: Int, alternate: Int): Int
    external fun claim(fd: Int, interfaceId: Int): Int
}
