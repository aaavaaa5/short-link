package com.app.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserAgentUtilTest {

    private final UserAgentUtil userAgentUtil = new UserAgentUtil();

    @Test
    void parsesDesktopAndMobileClients() {
        String windowsChrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        assertEquals("Chrome", userAgentUtil.parseBrowser(windowsChrome));
        assertEquals("Windows", userAgentUtil.parseOs(windowsChrome));
        assertEquals("PC", userAgentUtil.parseDevice(windowsChrome));

        String androidChrome = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
        assertEquals("Chrome", userAgentUtil.parseBrowser(androidChrome));
        assertEquals("Android", userAgentUtil.parseOs(androidChrome));
        assertEquals("Mobile", userAgentUtil.parseDevice(androidChrome));
    }

    @Test
    void parsesIpadSafariAsIosTablet() {
        String ipadSafari = "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 "
                + "Mobile/15E148 Safari/604.1";

        assertEquals("Safari", userAgentUtil.parseBrowser(ipadSafari));
        assertEquals("iOS", userAgentUtil.parseOs(ipadSafari));
        assertEquals("Tablet", userAgentUtil.parseDevice(ipadSafari));
    }
}
