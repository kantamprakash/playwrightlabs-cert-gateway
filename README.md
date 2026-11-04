# Playwright Labs certification: API gateway

The single entry point for every `/api/**` request on certify.playwrightlabs.com. Nginx serves the React
app and passes `/api/**` here; the services behind it are not reachable from outside.

```
Browser ─► Nginx (TLS, React) ─► gateway ─┬─► auth service   /api/auth/**, /api/admin/users/**
                                          └─► exam service   everything else under /api/**
```

## What it does

| | |
|---|---|
| Routing | `/api/auth/**` and `/api/admin/users/**` → `AUTH_SERVICE_URL`; other `/api/**` → `EXAM_SERVICE_URL`. Anything else (including the services' `/internal/**`) is refused. |
| Sign-in check | Reads the `PWL_SESSION` cookie (an RS256 JWT) and checks it against the auth service's public key (`AUTH_JWKS_URI`, cached). Bad or expired cookies count as signed out. |
| Access rules | `/api/admin/**` needs the ADMIN role; `/api/me/**`, `/api/attempts/**`, `/api/auth/me` and anything not public need a signed-in user. Public: `/api/auth/**`, `/api/public/**`, `GET /api/exams[/{slug}]`. Services check again themselves. |
| Rate limits | Per client IP, in Redis: sign-in, register, password reset and verification emails 10 at once then 10 a minute; everything else 50/s (burst 100). Over the limit: `429`. If Redis is down, requests are let through. |
| Tracing | Every request gets a W3C trace id (or continues the caller's `traceparent`), passed to the services in `traceparent`, returned to the browser in `X-Trace-Id`, and shown in every log line as `[traceId-spanId]`. One access-log line per request. With `OTEL_ENABLED=true`, logs, traces and metrics go to `OTEL_EXPORTER_OTLP_ENDPOINT`. |
| Header hygiene | Client-sent `X-User-*` and `X-Forwarded-*` headers never reach the services. |

## Settings

| Variable | Default | |
|---|---|---|
| `AUTH_SERVICE_URL` | `http://localhost:8087` | playwrightlabs-cert-auth |
| `EXAM_SERVICE_URL` | `http://localhost:8085` | playwrightlabs-cert-api |
| `AUTH_JWKS_URI` | `http://localhost:8087/api/auth/jwks.json` | |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| `TRUSTED_PROXIES` | `1` | Proxies in front that append to `X-Forwarded-For` (Nginx). The rate limiter trusts only that many entries from the end, so clients can't fake their IP. `0` = use the TCP peer. |
| `OTEL_ENABLED` | `false` | Export logs, traces and metrics over OTLP |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | OTLP/HTTP collector |
| `PORT` | `8080` | |

## Run

Tests (needs Docker, for Redis): `./mvnw verify`

Locally against the auth service (dev mode, 8087) and `cert-api` (dev mode, 8085):

```bash
docker run -d --name cert-redis -p 6379:6379 redis:7-alpine
PORT=8086 ./mvnw spring-boot:run
curl -i http://localhost:8086/api/exams
```

The whole stack (Nginx, gateway, services, Postgres, Redis) runs from the `playwrightlabs-cert-deploy` repo.
