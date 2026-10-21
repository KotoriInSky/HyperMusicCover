package com.os4.musiccover.island

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPictureThemeTest {
    @Test fun lightMaterialUsesLightResourceBeforeDarkAndAodFallbacks() {
        val keys = listOf("tickerPicDark", "tickerPic", "aodPic")
        assertEquals(listOf("tickerPic", "tickerPicDark", "aodPic"), NotificationPictureTheme.keys(keys, false))
        assertEquals(keys, NotificationPictureTheme.keys(keys, true))
    }
    @Test fun unthemedArtworkIsPreserved() {
        val keys = listOf("picCover", "srcDark")
        assertEquals(keys, NotificationPictureTheme.keys(keys, false))
    }
}
