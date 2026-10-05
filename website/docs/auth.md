# Authentication

OryxOS ships with an **opt-in** HTTP Basic Auth for the management console (`/admin/**`). It is disabled by default — the core phase assumes a trusted internal network. When enabled, access to the admin console requires an account; the REST API (`/api/v1/**`) is **not** affected.

> Basic Auth is suited to internal networks fronted by HTTPS. For internet-facing deployments, terminate TLS at a reverse proxy and restrict network exposure; this minimal auth is a first line, not a perimeter.

## How it works

- **Scope**: only `/admin/**` is protected. Machine-to-machine auth for `/api/v1/**` is handled by the separate [REST API Key](#rest-api-key-authentication) switch — the two toggles are independent.
- **Accounts**: stored in the `web_users` SQLite table. Passwords are BCrypt-hashed (with a `{bcrypt}` prefix via Spring's `DelegatingPasswordEncoder`) — **never stored in plaintext**, never written to config, logs, or git history.
- **Realtime**: account changes take effect immediately. Each request re-reads the database — no in-process cache, no server restart needed for a new account to work.
- **Startup guard**: if you enable auth but no enabled account exists, startup is blocked with a clear error pointing at `oryxos user add`.

## Configuration

Auth is controlled under `oryxos.web.auth` in `application.yml` (the in-jar defaults, overridable via `config/application.yml`):

```yaml
oryxos:
  web:
    auth:
      enabled: false        # default off — trusted internal network
      realm: "OryxOS"        # Basic Auth realm string in the WWW-Authenticate header
```

| Property | Default | Description |
| --- | --- | --- |
| `oryxos.web.auth.enabled` | `false` | Master switch. `false` = no auth (current behavior). `true` = `/admin/**` requires Basic Auth. |
| `oryxos.web.auth.realm` | `OryxOS` | Realm value sent back in the `WWW-Authenticate: Basic realm="..."` challenge. |

There is no `exclude-paths` setting — the filter is scoped to `/admin/**` only, so `/api/v1/**` (and `/api/v1/health`) is naturally exempt.

## Quick start

1. **Enable auth** in `config/application.yml`:

   ```yaml
   oryxos:
     web:
       auth:
         enabled: true
   ```

2. **Create the first admin account** (startup is blocked until at least one enabled account exists):

   ```bash
   oryxos user add admin
   # Password (>= 8 chars): ********
   # Confirm: ********
   # Created user 'admin'
   ```

3. **Start the server**:

   ```bash
   oryxos serve
   ```

4. **Open the console** at `http://localhost:8080/admin/` — the browser prompts for credentials. Enter the account you just created.

## Verifying it works

```bash
# No credentials → 401 + WWW-Authenticate challenge
curl -i http://localhost:8080/admin/

# Correct credentials → 200
curl -u admin:<password> http://localhost:8080/admin/

# REST API stays open (not protected by Basic Auth)
curl http://localhost:8080/api/v1/health
```

## Account management

See the [`oryxos user` CLI reference](./cli.md#user-management) for `add`, `list`, `role`, `passwd`, `disable`, and `delete`.

- `list` **never prints passwords or hashes**.
- `disable` keeps the row but blocks login (returns 401). `delete` removes it permanently.
- Passwords must be ≥ 8 characters; usernames must be ≤ 64 characters with no whitespace.

## REST API Key authentication

Machine-to-machine auth for `/api/v1/**` (018-rest-api-key), toggled independently from console auth above:

```yaml
oryxos:
  web:
    apikey:
      enabled: true   # default false — current behavior unchanged
```

- **Before enabling**, create a key with `oryxos apikey add <name>` — the `oryx_...` plaintext is **shown exactly once**; only its SHA-256 hash is stored.
- **Callers** pick either header: `Authorization: Bearer <key>` or `X-API-Key: <key>` — both are equivalent.
- **Exemptions**: `/api/v1/health` (probes), `/api/v1/auth/*` (console login subtree), and OPTIONS preflight; `/admin/**` is entirely unaffected.
- **Console interop**: requests carrying a valid console session pass as authenticated — with both switches on, admin data pages keep working. Enabling only apikey logs a startup warning (the browser has neither session nor key).
- **Lifecycle**: `oryxos apikey list` for inventory (no plaintext); `oryxos apikey revoke <name>` takes effect on the next request and leaves other keys untouched. Keys never expire automatically; a lost key can only be revoked and reissued.

```bash
# No key → 401 (uniform response, no failure-reason leak)
curl -i http://localhost:8080/api/v1/profiles
# With key → 200
curl -H "Authorization: Bearer oryx_..." http://localhost:8080/api/v1/profiles
# Probes stay open
curl http://localhost:8080/api/v1/health
```

## Authorization (RBAC)

Authentication answers "who are you"; authorization answers "what may you do". They are independent switches — **authorization only has a subject once authentication is on**: with both `web.auth` and `web.apikey` off, no gate produces a principal, every request stays anonymous, and authorization will not lock you out of a zero-config single-machine deployment (deliberate).

```yaml
oryxos:
  web:
    rbac:
      enabled: true          # default false — no authorization layer, behavior unchanged
      deny-anonymous: true   # default true; reject unauthenticated subjects once RBAC is on
      roles:
        default-user-roles: []      # default empty — no role means denied
        default-api-key-roles: []   # default empty — no default rights for machine credentials
```

| Property | Default | Meaning |
| --- | --- | --- |
| `oryxos.web.rbac.enabled` | `false` | Master switch. `true` = decide every `/api/v1/**` and `/admin/**` subject by role. |
| `oryxos.web.rbac.deny-anonymous` | `true` | Whether to reject unauthenticated subjects outright. |
| `oryxos.web.rbac.roles.default-user-roles` | empty | Fallback for accounts that carry no role of their own; **empty means denied**. |
| `oryxos.web.rbac.roles.default-api-key-roles` | empty | Fallback for API keys; by default a machine credential gets no rights at all. |

Once on, each request's principal goes to a **single decision point**, `AuthorizationService`, which maps the path to an action and decides; **an unmapped protected path is denied (fail-closed)**. A denial returns **403** (distinct from the 401 of failed authentication), leaves a structured log line, and is written to the `authz_events` table for filtering. Channel inbound webhooks, approval callbacks, health probes and `/api/v1/auth/**` are authenticated only, never adjudicated.

### What the three roles may do

| Role | What it may do |
| --- | --- |
| `VIEWER` | Read-only: see the workspace and the audit trail. |
| `EDITOR` | Above VIEWER, may do work: run Agents, manage Agents / knowledge / Skills / its own sessions. |
| `ADMIN` | Above EDITOR, manages the boundaries themselves: members, channels, policies, workspace settings. |

They nest (VIEWER ⊆ EDITOR ⊆ ADMIN). **API keys have a hard ceiling of their own**: even with `ADMIN` granted, a key may not change members or policies — a key is a long-lived machine credential that can be copied into any environment, so it does not share the human account's permission ceiling. A principal's own roles win and are **never unioned** with the fallback (otherwise a VIEWER account could escalate through the key role carried by the same request).

```bash
oryxos user role admin ADMIN   # set the role (replaces, does not append)
oryxos user list               # the ROLE column shows the current tier
```

New accounts default to `VIEWER`; doing real work requires an explicit promotion. Roles are re-resolved from the database on every request with no caching, so **a change or revocation takes effect on the very next request**.

### Startup checks (refuses to boot on misconfiguration)

With `rbac.enabled=true`, startup runs two checks and **refuses to start** when either fails, naming the fix:

- **API key auth is off** — no gate would produce a principal, so authorization would silently fail open;
- **no ADMIN account exists** — governance would be locked out; the error points at `oryxos user role <name> ADMIN`.

`web.auth.enabled=false` only warns (console data pages would have no session credential to use).

## Design notes

- **No Spring Security full stack**: only `spring-security-crypto` (the password-hashing jar) is used — no filter chain, no autoconfig. The `BasicAuthFilter` is a plain `OncePerRequestFilter` registered via a `FilterRegistrationBean` scoped to `/admin/**`; authorization (RBAC) likewise does not go through Spring Security but through 039's own `RbacEnforcer` + `AuthorizationService`.
- **Scope**: this page covers authentication only. SSO/OIDC (040) and authorization RBAC (039, see above) are built in; the console also has session-based login with logout (012, `POST /api/v1/auth/logout`). Password hashing with a delegating encoder leaves an upgrade path to Argon2 without migration.
- **HTTP Basic itself has no logout** — clearing Basic credentials is browser-controlled. The console additionally has a session login page (`/admin/login`) backed by the same `web_users` table, where `POST /api/v1/auth/logout` clears the session and its cookie (idempotent).
