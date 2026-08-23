package com.arthur.jdragresume.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTests {

    @Test
    void usesBffAddressFromLoopbackOrPrivateProxy() {
        assertEquals("203.0.113.20", ClientIpResolver.resolve(request("127.0.0.1", "203.0.113.20")));
        assertEquals("198.51.100.21", ClientIpResolver.resolve(request("172.18.0.4", "198.51.100.21")));
    }

    @Test
    void ignoresSpoofedOrMalformedBffAddressOutsideTrustedNetwork() {
        assertEquals("198.51.100.30", ClientIpResolver.resolve(request("198.51.100.30", "203.0.113.99")));
        assertEquals("127.0.0.1", ClientIpResolver.resolve(request("127.0.0.1", "not-an-ip")));
    }

    private static MockHttpServletRequest request(String remoteAddress, String forwardedAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader(ClientIpResolver.BFF_CLIENT_IP_HEADER, forwardedAddress);
        return request;
    }
}
