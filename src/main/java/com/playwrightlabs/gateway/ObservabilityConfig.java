package com.playwrightlabs.gateway;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * Keeps traces to real requests: health checks (/actuator, polled every few seconds by Docker and
 * Kubernetes) aren't traced.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

	@Bean
	ObservationPredicate noHealthCheckTraces() {
		return (name, context) -> !(context instanceof ServerRequestObservationContext request
				&& request.getCarrier() != null && TraceIdFilter.isHealthCheck(request.getCarrier().getPath().value()));
	}

}
