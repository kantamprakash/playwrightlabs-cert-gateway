package com.playwrightlabs.gateway;

import java.util.Optional;

import io.micrometer.tracing.handler.TracingObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Returns each request's trace id to the browser in X-Trace-Id, so a user can quote it and it can be
 * looked up in every service's logs and traces, and writes one access-log line per request.
 *
 * <p>The trace itself is started by Spring's HTTP server observation before any filter runs, and Spring
 * Cloud Gateway passes it to the services in the W3C traceparent header. Runs before security, so 401,
 * 403 and 429 responses carry the id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements WebFilter {

	public static final String HEADER = "X-Trace-Id";

	private static final Logger access = LoggerFactory.getLogger("access");

	/** Docker and Kubernetes poll these every few seconds: no trace, no access-log line. */
	static boolean isHealthCheck(String path) {
		return path.startsWith("/actuator");
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		if (isHealthCheck(exchange.getRequest().getPath().value())) {
			return chain.filter(exchange);
		}
		traceId(exchange).ifPresent(id -> exchange.getResponse().getHeaders().set(HEADER, id));
		long started = System.nanoTime();
		var request = exchange.getRequest();
		return chain.filter(exchange).doFinally(signal -> {
			var status = exchange.getResponse().getStatusCode();
			access.info("{} {} {} {}ms", request.getMethod(), request.getPath().value(),
					status == null ? "-" : status.value(), (System.nanoTime() - started) / 1_000_000);
		});
	}

	static Optional<String> traceId(ServerWebExchange exchange) {
		return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
			.map(context -> {
				TracingObservationHandler.TracingContext tracing = context.get(TracingObservationHandler.TracingContext.class);
				return tracing == null || tracing.getSpan() == null ? null : tracing.getSpan().context().traceId();
			});
	}

}
