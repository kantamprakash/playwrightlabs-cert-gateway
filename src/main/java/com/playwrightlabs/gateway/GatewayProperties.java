package com.playwrightlabs.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwksUri where the auth service publishes the public key that session tokens are signed with
 * @param trustedProxies how many proxies in front of the gateway append to X-Forwarded-For (Nginx: 1).
 * The client IP used for rate limits is read that many entries from the end, so a client can't
 * dodge its limit by sending its own X-Forwarded-For header. 0 means use the TCP peer address.
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(String jwksUri, int trustedProxies) {
}
