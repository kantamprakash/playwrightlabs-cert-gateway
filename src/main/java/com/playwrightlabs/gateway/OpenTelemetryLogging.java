package com.playwrightlabs.gateway;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** Connects the OTEL Logback appender (logback-spring.xml) to Spring Boot's OpenTelemetry SDK. */
@Component
class OpenTelemetryLogging implements InitializingBean {

	private final OpenTelemetry openTelemetry;

	OpenTelemetryLogging(OpenTelemetry openTelemetry) {
		this.openTelemetry = openTelemetry;
	}

	@Override
	public void afterPropertiesSet() {
		OpenTelemetryAppender.install(this.openTelemetry);
	}

}
