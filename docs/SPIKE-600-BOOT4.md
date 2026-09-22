# Spike #600 — Spring Boot 4.0.8 + Spring AI 2.0.1

Branch: `feat/600-boot4-spike` (fork `173787247/oryxos` only).

## Versions

| Component | Version | Notes |
|-----------|---------|-------|
| Spring Boot | **4.0.8** | Parent bump from 3.x |
| Spring AI | **2.0.1** | OpenAI Java SDK client (no `OpenAiApi`) |
| Jackson | Boot default **3.x** (`tools.jackson`) + transitional **`spring-boot-jackson2`** | Do **not** set `jackson-bom.version=2.21.5` under Boot 4. Use `jackson-2-bom.version` for Jackson 2 pins. |

## What was fixed (Boot 4 modularization)

- `@EntityScan` → `org.springframework.boot.persistence.autoconfigure.EntityScan`
- Actuator Health → `org.springframework.boot.health.contributor.*`
- Metrics / `@AutoConfigureMetrics` package + `spring-boot-starter-micrometer-metrics-test`
- JDBC / DataSource auto-config packages
- `@DataJpaTest` / `@AutoConfigureTestDatabase` + `spring-boot-starter-data-jpa-test`
- Flyway requires `spring-boot-starter-flyway`; JPA tests need `@AutoConfigureDataSourceInitialization`
- `TestRestTemplate` → `spring-boot-resttestclient` + `@AutoConfigureTestRestTemplate`
- `UriComponentsBuilder.fromHttpUrl` → `fromUriString`
- Transitional `spring-boot-jackson2` for `com.fasterxml.jackson.databind.ObjectMapper` injection
- `spring-boot-starter-opentelemetry` on CLI/boot entrypoints

## Spring AI 2.x provider rewrite

- `ProviderChatModelFactory` / `ProviderEmbeddingModelFactory`: build `OpenAiChatModel` / `OpenAiEmbeddingModel` via OpenAI Java SDK (`ClientOptions` + OkHttp).
- baseUrl: **keep** trailing `/vN` (or append `/v1`) — opposite of AI 1.x `stripTrailingV1` (OpenAiApi used to insert `/v1`).
- Timeouts / `maxRetries(0)` / HTTP/1.1 / `followRedirects(false)` on OkHttp.
- `internalToolExecutionEnabled` removed (AI 2 ChatModel no longer auto-executes tools).
- `FallbackClassifier` / media retry: prefer `OpenAIServiceException` / `OpenAIIoException`.

## Build status (this spike)

- Target: `mvn -B clean verify` GREEN including previously `@Disabled` OpenAI-path E2Es in `ProviderFallbackE2ETest`.

## Out of scope / not done

- PR to `oryx-labs/oryxos` (fork spike only)
- Full Jackson 3 (`tools.jackson`) code migration
- Slimming away `spring-boot-jackson2` / classic starters
