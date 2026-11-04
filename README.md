# Playwright Labs certification: API gateway

The single entry point for every `/api/**` request on certify.playwrightlabs.com. Nginx serves the React
app and passes `/api/**` here; the services behind it are not reachable from outside.

```
Browser ─► Nginx (TLS, React) ─► gateway ─┬─► auth service   /api/auth/**
                                          └─► exam service   everything else under /api/**
```

Until the auth service is split out, both routes point at `cert-api`.

## What it does

| | |
|---|---|
| Routing | `/api/auth/**` → `AUTH_SERVICE_URL`; other `/api/**` → `EXAM_SERVICE_URL`. Anything else is refused. |
| Sign-in check | Reads the `PWL_SESSION` cookie (an RS256 JWT) and checks it against the auth service's public key (`AUTH_JWKS_URI`, cached). Bad or expired cookies count as signed out. |
| Access rules | `/api/admin/**` needs the ADMIN role; `/api/me/**`, `/api/attempts/**`, `/api/auth/me` and anything not public need a signed-in user. Public: `/api/auth/**`, `/api/public/**`, `GET /api/exams[/{slug}]`. Services check again themselves. |
| Rate limits | Per client IP, in Redis: sign-in, register, password reset and verification emails 10 at once then 10 a minute; everything else 50/s (burst 100). Over the limit: `429`. If Redis is down, requests are let through. |
| Request ids | Every request gets `X-Request-Id` (kept if Nginx sent a sane one), passed to the services and returned to the browser; one access-log line per request. |
| Header hygiene | Client-sent `X-User-*` and `X-Forwarded-*` headers never reach the services. |

## Settings

| Variable | Default | |
|---|---|---|
| `AUTH_SERVICE_URL` | `http://localhost:8085` | |
| `EXAM_SERVICE_URL` | `http://localhost:8085` | |
| `AUTH_JWKS_URI` | `http://localhost:8085/api/auth/jwks.json` | |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | |
| `TRUSTED_PROXIES` | `1` | Proxies in front that append to `X-Forwarded-For` (Nginx). The rate limiter trusts only that many entries from the end, so clients can't fake their IP. `0` = use the TCP peer. |
| `PORT` | `8080` | |

## Run

Tests (needs Docker, for Redis): `./mvnw verify`

Locally against `cert-api` running in dev mode on 8085:

```bash
docker run -d --name cert-redis -p 6379:6379 redis:7-alpine
PORT=8086 ./mvnw spring-boot:run
curl -i http://localhost:8086/api/exams
```

The whole stack (Nginx, gateway, services, Postgres, Redis) runs from the `playwrightlabs-cert-deploy` repo.
