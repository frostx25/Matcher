package com.matcher.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteDiscoveryLayoutTest {
    @Test
    fun columnCountAdaptsFromPortraitPhoneToWideScreens() {
        assertEquals(3, discoveryColumnCountForWidth(360))
        assertEquals(3, discoveryColumnCountForWidth(479))
        assertEquals(4, discoveryColumnCountForWidth(480))
        assertEquals(5, discoveryColumnCountForWidth(600))
        assertEquals(6, discoveryColumnCountForWidth(720))
        assertEquals(6, discoveryColumnCountForWidth(840))
    }
}
