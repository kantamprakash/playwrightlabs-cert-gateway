package com.playwrightlabs.gateway;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Rate limits are counted per client IP in Redis (shared by every gateway instance). The limits
 * themselves are set per route in application.yml. If Redis is down, requests are let through.
 */
@Configuration
public class RateLimitConfig {

	@Bean
	KeyResolver clientIpKeyResolver(GatewayProperties props) {
		RemoteAddressResolver resolver = props.trustedProxies() > 0
				? XForwardedRemoteAddressResolver.maxTrustedIndex(props.trustedProxies())
				: new RemoteAddressResolver() {
				};
		return exchange -> {
			InetSocketAddress address = resolver.resolve(exchange);
			return Mono.just(address == null ? "unknown" : address.getHostString());
		};
	}

}
