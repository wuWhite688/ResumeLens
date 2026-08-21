package com.arthur.jdragresume.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CookieSecurityTests {
    @Test
    void localHttpKeepsInsecureCookieWhenNotConfigured() {
        assertFalse(CookieSecurity.secure(false, false));
    }

    @Test
    void httpsRequestSetsSecureEvenWhenPropertyIsFalse() {
        assertTrue(CookieSecurity.secure(false, true));
    }

    @Test
    void configuredProductionFlagForcesSecure() {
        assertTrue(CookieSecurity.secure(true, false));
    }
}
