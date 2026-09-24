package com.arthur.jdragresume.security;

import com.arthur.jdragresume.exception.BusinessException;

/**
 * CSRF defence for the endpoints that read or write the refresh cookie.
 *
 * <p>Everything else authenticates with a Bearer header, which a browser never
 * attaches on its own. register / login / refresh / logout are different: the
 * refresh cookie rides along automatically. {@code SameSite=Lax} keeps cross-site
 * POSTs out, but it is decided per <em>site</em> (registrable domain), so a page on
 * a sibling subdomain is same-site and its POST still carries the cookie.
 *
 * <p>{@code Sec-Fetch-Site} is set by the browser and cannot be forged by page
 * script. Only {@code same-origin} (our own frontend) and {@code none} (typed or
 * bookmarked navigation) are allowed. A missing header means a non-browser client,
 * which is not a CSRF vector, so it is allowed. The BFF enforces the same rule with
 * an Origin fallback; this is the second line for a backend reached without it.
 *
 * <p>Only {@code Sec-Fetch-Site} is trusted, never {@code Sec-Fetch-Mode}: Node's
 * fetch in the BFF adds {@code sec-fetch-mode: cors} on its own when forwarding.
 */
public final class FetchMetadataGuard {

    public static final String HEADER = "Sec-Fetch-Site";

    private FetchMetadataGuard() {
    }

    public static void requireSameOrigin(String secFetchSite) {
        if (secFetchSite == null || secFetchSite.isBlank()) {
            return;
        }
        String site = secFetchSite.trim();
        if ("same-origin".equalsIgnoreCase(site) || "none".equalsIgnoreCase(site)) {
            return;
        }
        throw new BusinessException(
                "CROSS_SITE_REQUEST_BLOCKED",
                "cross-site request rejected, please use this site's own pages"
        );
    }
}
