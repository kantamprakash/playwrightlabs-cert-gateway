package com.playwrightlabs.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import reactor.core.publisher.Mono;

/**
 * Checks the session cookie at the edge, before anything reaches a service.
 *
 * <p>The token is an RS256 JWT issued by the auth service and checked with its published public key
 * (JWKS, fetched once and cached). The services behind the gateway check it again themselves, so this
 * is the first of two layers, not the only one.
 *
 * <p>The access rules mirror the exam service's. A missing, expired or tampered cookie makes the
 * request anonymous rather than failing it, so public endpoints such as login keep working for someone
 * with a stale cookie.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

	public static final String SESSION_COOKIE = "PWL_SESSION";

	public static final String ISSUER = "playwrightlabs-cert-auth";

	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder) {
		return http
			// The session cookie is SameSite=Strict and the API only accepts JSON.
			.csrf(ServerHttpSecurity.CsrfSpec::disable)
			.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
			.formLogin(ServerHttpSecurity.FormLoginSpec::disable)
			.logout(ServerHttpSecurity.LogoutSpec::disable)
			.requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
			.securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
			// Services set their own caching headers (e.g. the public key may be cached for a few minutes).
			.headers(h -> h.cache(ServerHttpSecurity.HeaderSpec.CacheSpec::disable))
			.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
			.addFilterAt(cookieAuthentication(decoder), SecurityWebFiltersOrder.AUTHENTICATION)
			.authorizeExchange(a -> a
				.pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
				.pathMatchers("/api/auth/me").authenticated()
				.pathMatchers("/api/auth/**", "/api/public/**").permitAll()
				.pathMatchers(HttpMethod.GET, "/api/exams", "/api/exams/*").permitAll()
				.pathMatchers("/api/admin/**").hasRole("ADMIN")
				.anyExchange().authenticated())
			.build();
	}

	@Bean
	ReactiveJwtDecoder jwtDecoder(GatewayProperties props) {
		NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(props.jwksUri())
			.jwsAlgorithm(SignatureAlgorithm.RS256)
			.build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		return decoder;
	}

	/** Nobody signs in at the gateway; this stops Boot creating a default user with a generated password. */
	@Bean
	ReactiveUserDetailsService noUsers() {
		return username -> Mono.empty();
	}

	private static AuthenticationWebFilter cookieAuthentication(ReactiveJwtDecoder decoder) {
		// The token's "role" claim (CANDIDATE or ADMIN) becomes ROLE_CANDIDATE / ROLE_ADMIN.
		JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("role");
		authorities.setAuthorityPrefix("ROLE_");
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);

		JwtReactiveAuthenticationManager manager = new JwtReactiveAuthenticationManager(decoder);
		manager.setJwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter));

		AuthenticationWebFilter filter = new AuthenticationWebFilter(manager);
		filter.setServerAuthenticationConverter(exchange -> Mono
			.justOrEmpty(exchange.getRequest().getCookies().getFirst(SESSION_COOKIE))
			.map(HttpCookie::getValue)
			.filter(token -> !token.isBlank())
			.map(BearerTokenAuthenticationToken::new));
		// A bad token: carry on as anonymous; the access rules above decide what that may reach.
		filter.setAuthenticationFailureHandler((webFilterExchange, ex) -> webFilterExchange.getChain()
			.filter(webFilterExchange.getExchange()));
		return filter;
	}

}
