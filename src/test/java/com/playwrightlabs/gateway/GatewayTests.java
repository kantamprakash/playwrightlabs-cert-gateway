package com.playwrightlabs.gateway;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		// Small limits so the test can hit them.
		"gateway.rate-limit.auth.replenish-rate=1", "gateway.rate-limit.auth.burst-capacity=3",
		"gateway.rate-limit.auth.requested-tokens=1" })
class GatewayTests {

	@Container
	@ServiceConnection(name = "redis")
	static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	/** What the fake service behind the gateway received. */
	record Received(String service, String method, String path, Headers headers) {
	}

	static final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();

	static final RSAKey signingKey = newKey();

	static final RSAKey strangerKey = newKey();

	static final HttpServer authService = startBackend("auth");

	static final HttpServer examService = startBackend("exam");

	@DynamicPropertySource
	static void routes(DynamicPropertyRegistry registry) {
		String auth = "http://localhost:" + authService.getAddress().getPort();
		registry.add("AUTH_SERVICE_URL", () -> auth);
		registry.add("EXAM_SERVICE_URL", () -> "http://localhost:" + examService.getAddress().getPort());
		registry.add("gateway.jwks-uri", () -> auth + "/api/auth/jwks.json");
	}

	@AfterAll
	static void stopBackends() {
		authService.stop(0);
		examService.stop(0);
	}

	@Autowired
	Environment env;

	WebTestClient client;

	@BeforeEach
	void setUp() {
		client = WebTestClient.bindToServer().baseUrl("http://localhost:" + env.getProperty("local.server.port")).build();
		received.clear();
	}

	@Test
	void everyRequestGetsATraceIdThatIsPassedOnAndLogged(CapturedOutput output) {
		var res = client.get().uri("/api/exams").exchange().expectStatus().isOk().returnResult(String.class);

		String traceId = res.getResponseHeaders().getFirst("X-Trace-Id");
		assertThat(traceId).matches("[0-9a-f]{32}");
		// The service behind receives the same trace in the W3C traceparent header.
		Received r = only();
		assertThat(r.path()).isEqualTo("/api/exams");
		assertThat(r.headers().getFirst("traceparent")).matches("00-" + traceId + "-[0-9a-f]{16}-0[13]");
		// And the gateway's access-log line carries it.
		Awaitility.await().untilAsserted(() -> assertThat(output.getOut())
			.containsPattern("\\[" + traceId + "-[0-9a-f]{16}\\] access .*GET /api/exams 200"));

		String other = client.get().uri("/api/exams").exchange().returnResult(String.class).getResponseHeaders()
			.getFirst("X-Trace-Id");
		assertThat(other).isNotEqualTo(traceId);
	}

	@Test
	void continuesATraceStartedUpstream() {
		String upstream = "4bf92f3577b34da6a3ce929d0e0e4736";
		client.get().uri("/api/exams").header("traceparent", "00-" + upstream + "-00f067aa0ba902b7-01").exchange()
			.expectHeader().valueEquals("X-Trace-Id", upstream);
		assertThat(only().headers().getFirst("traceparent")).startsWith("00-" + upstream + "-");
	}

	@Test
	void forwardedHeadersAreNotPassedOnSoServicesCannotBeFooledByThem() {
		client.get().uri("/api/exams").header("X-Forwarded-For", "203.0.113.50").header("X-Forwarded-Host", "evil.example")
			.exchange()
			.expectStatus().isOk();
		Headers h = only().headers();
		assertThat(h.containsKey("X-Forwarded-For")).isFalse();
		assertThat(h.containsKey("X-Forwarded-Host")).isFalse();
	}

	@Test
	void eachPathReachesTheServiceThatOwnsIt() {
		String admin = token(signingKey, "ADMIN", 600, SecurityConfig.ISSUER);
		var routes = java.util.Map.of("/api/auth/me", "auth", "/api/admin/users", "auth", "/api/admin/users/5/verify", "auth",
				"/api/admin/exams", "exam", "/api/admin/usersx", "exam", "/api/exams", "exam", "/api/me/dashboard", "exam");
		routes.forEach((path, service) -> {
			received.clear();
			client.get().uri(path).cookie("PWL_SESSION", admin).exchange().expectStatus().isOk();
			assertThat(only().service()).as(path).isEqualTo(service);
		});
	}

	@Test
	void privateEndpointsNeedASignedInUserAndNeverReachTheServiceWithout() {
		client.get().uri("/api/me/dashboard").exchange().expectStatus().isUnauthorized()
			.expectHeader().exists("X-Trace-Id");
		client.post().uri("/api/attempts").exchange().expectStatus().isUnauthorized();
		assertThat(received).isEmpty();

		client.get().uri("/api/me/dashboard").cookie("PWL_SESSION", token(signingKey, "CANDIDATE", 600, SecurityConfig.ISSUER))
			.exchange()
			.expectStatus().isOk();
		// The cookie itself is passed on: the service checks it again.
		assertThat(only().headers().getFirst("Cookie")).contains("PWL_SESSION=");
	}

	@Test
	void adminEndpointsNeedTheAdminRole() {
		client.get().uri("/api/admin/users").exchange().expectStatus().isUnauthorized();
		client.get().uri("/api/admin/users").cookie("PWL_SESSION", token(signingKey, "CANDIDATE", 600, SecurityConfig.ISSUER))
			.exchange()
			.expectStatus().isForbidden();
		assertThat(received).isEmpty();

		client.get().uri("/api/admin/users").cookie("PWL_SESSION", token(signingKey, "ADMIN", 600, SecurityConfig.ISSUER))
			.exchange()
			.expectStatus().isOk();
		assertThat(only().path()).isEqualTo("/api/admin/users");
	}

	@Test
	void forgedExpiredOrForeignTokensAreTreatedAsSignedOut() {
		for (String bad : List.of(token(strangerKey, "ADMIN", 600, SecurityConfig.ISSUER),
				token(signingKey, "ADMIN", -60, SecurityConfig.ISSUER), token(signingKey, "ADMIN", 600, "someone-else"),
				"not-a-jwt")) {
			client.get().uri("/api/admin/users").cookie("PWL_SESSION", bad).exchange().expectStatus().isUnauthorized();
		}
		assertThat(received).isEmpty();
	}

	@Test
	void aStaleCookieDoesNotBlockSigningIn() {
		client.post().uri("/api/auth/login").header("X-Forwarded-For", "198.51.100.1")
			.cookie("PWL_SESSION", token(signingKey, "CANDIDATE", -60, SecurityConfig.ISSUER))
			.exchange()
			.expectStatus().isOk();
		assertThat(only().path()).isEqualTo("/api/auth/login");
	}

	@Test
	void identityHeadersFromTheClientAreDropped() {
		client.get().uri("/api/exams").header("X-User-Id", "1").header("X-User-Role", "ADMIN").exchange()
			.expectStatus().isOk();
		Headers h = only().headers();
		assertThat(h.containsKey("X-User-Id")).isFalse();
		assertThat(h.containsKey("X-User-Role")).isFalse();
	}

	@Test
	void signInIsRateLimitedPerClientIp() {
		// Burst of 3 in this test: the 4th try from the same IP within a second is refused.
		for (int i = 0; i < 3; i++) {
			client.post().uri("/api/auth/login").header("X-Forwarded-For", "203.0.113.7").exchange().expectStatus().isOk();
		}
		client.post().uri("/api/auth/login").header("X-Forwarded-For", "203.0.113.7").exchange()
			.expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

		// Another client is unaffected, and so is a client that only spoofs an extra X-Forwarded-For entry:
		// the gateway trusts just the last entry, the one Nginx adds.
		client.post().uri("/api/auth/login").header("X-Forwarded-For", "203.0.113.8").exchange().expectStatus().isOk();
		client.post().uri("/api/auth/login").header("X-Forwarded-For", "1.2.3.4, 203.0.113.7").exchange()
			.expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	void onlyApiPathsAreRouted() {
		client.get().uri("/internal/secret").exchange().expectStatus().isUnauthorized();
		client.get().uri("/actuator/health").exchange().expectStatus().isOk();
		assertThat(received).isEmpty();
	}

	private static Received only() {
		assertThat(received).hasSize(1);
		return received.peek();
	}

	private static String token(RSAKey key, String role, long secondsValid, String issuer) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(issuer)
			.subject("7")
			.issuedAt(now.minusSeconds(Math.max(0, -secondsValid) + 60))
			.expiresAt(now.plusSeconds(secondsValid))
			.claim("email", "user@example.com")
			.claim("name", "Test User")
			.claim("role", role)
			.build();
		// The stranger's key claims our key id, as a forger would.
		var header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key))).encode(JwtEncoderParameters.from(header, claims))
			.getTokenValue();
	}

	private static RSAKey newKey() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			var pair = generator.generateKeyPair();
			return new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey(pair.getPrivate()).keyID("test-key").build();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static HttpServer startBackend(String service) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", exchange -> {
				byte[] body;
				if (exchange.getRequestURI().getPath().equals("/api/auth/jwks.json")) {
					body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
				}
				else {
					received.add(new Received(service, exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
							exchange.getRequestHeaders()));
					body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
				}
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			server.start();
			return server;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
