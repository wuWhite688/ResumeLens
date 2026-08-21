package com.arthur.jdragresume.security;

public final class CookieSecurity {
    private CookieSecurity() {
    }

    /**
     * Local HTTP keeps working when the property is false. Direct HTTPS to the
     * API still gets the Secure flag even if the property was left at the
     * development default.
     */
    public static boolean secure(boolean configuredSecure, boolean requestSecure) {
        return configuredSecure || requestSecure;
    }
}
