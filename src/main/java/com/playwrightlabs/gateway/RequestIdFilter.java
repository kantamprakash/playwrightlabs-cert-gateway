package com.playwrightlabs.gateway;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gives every request an id, passed to the services in X-Request-Id and returned to the browser, so one
 * request can be followed through the logs of every service. Runs before security, so 401/403/429
 * responses carry the id too, and writes one access-log line per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

	public static final String HEADER = "X-Request-Id";

	private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

	private static final Logger access = LoggerFactory.getLogger("access");

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
		String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
		ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> h.set(HEADER, id)).build();
		exchange.getResponse().getHeaders().set(HEADER, id);
		long started = System.nanoTime();
		return chain.filter(exchange.mutate().request(request).build()).doFinally(signal -> {
			var status = exchange.getResponse().getStatusCode();
			access.info("{} {} {} {}ms rid={}", request.getMethod(), request.getPath().value(),
					status == null ? "-" : status.value(), (System.nanoTime() - started) / 1_000_000, id);
		});
	}

}
