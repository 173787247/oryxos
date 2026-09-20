# Dependency security triage — 2026-09-18

## Outcome and residual risk

Upgraded the vulnerable Office/archive dependencies and frontend packages; removed the unused Spring AI MCP bridge. npm audit reports **0 vulnerabilities including development dependencies**. The scoped Office/MCP Java suite passes **23 tests**; frontend tests pass **9 test files**, and production frontend build succeeds.

**The Spring platform is not fully patched.** Framework 6.2.19, Security crypto 6.5.11, Data JPA 3.5.13 and the required Spring AI 1.1.8 modules remain. Their reviewed CVEs are excluded only for the current application architecture and use of the affected APIs; these are deployment-specific risk assessments, not assertions of zero risk or CPE false positives. Official same-generation fixes require enterprise access; community fixes require a separately tested platform migration. No severity threshold was lowered. New exclusions and the corrected XSLT exclusion expire **2027-01-01 UTC**, requiring maintainer reassessment.

The final installed-artifact Dependency-Check scan completed successfully: 171 objects, 0 unsuppressed findings and 121 reasoned exclusion records. See the [acceptance record](acceptance.md) and final scan note below. This does not remove the unpatched Spring residual risks described here.

## Input and actual dependency selection

Initial `target/dependency-check-report.json`: engine 12.2.2; NVD last checked `2026-09-18T02:55:56Z`. Maven dependency trees were captured before/after, using the current reactor POMs rather than assuming every jar found in old targets is deployed.

The initial report includes old installed `oryxos-web-0.1.5-RELEASE.jar` copies with `index-D82r5gPX.js` and `index-fzd7Zz9c.js`, as well as the then-current source frontend `index-CLEcP8J2.js`. These old bundles are **not false positives**: they contain old code. They must disappear from a clean replacement artifact, not be suppressed. The running Docker application's mounted old boot jar was not cleaned, replaced, or packaged by this work. Until the controller deploys the new artifact, dependency fixes do not retroactively repair that running jar.

Before mediation, POI 5.2.5 pulls Commons Compress 1.25.0 and IO 2.15.0, while embedded-postgres pulls IO 2.11.0. In the boot graph the nearer dependency can select IO 2.11.0, even though other module graphs show 2.15.0. This is why the parent now explicitly manages IO/Compress, rather than only updating POI.

## Fixed dependency findings

| Finding | Applied change | Primary evidence |
| --- | --- | --- |
| POI CVE-2025-31672, duplicate OOXML ZIP entries | `poi-ooxml` 5.2.5 → **5.5.1**, same POI release across its transitives | [Apache POI advisory/release news](https://poi.apache.org/), fixed since 5.4.0 |
| Commons Compress CVE-2024-25710 and CVE-2024-26308 | Parent manages **1.28.0**, including embedded-postgres path | [Apache Commons Compress security](https://commons.apache.org/proper/commons-compress/security.html), fixed since 1.26.0 |
| Commons IO CVE-2024-47554 | Parent manages **2.22.0** across every module | [Apache Commons IO security](https://commons.apache.org/proper/commons-io/security.html), fixed since 2.14.0 |
| DOMPurify CVE-2026-75838 | Manifest floor `^3.4.15`, lock resolves **3.4.15**; rebuilt frontend | [Maintainer advisory](https://github.com/cure53/DOMPurify/security/advisories/GHSA-55q2-fjhq-7xh7), fixed since 3.4.13. Existing use is string sanitization, no IN_PLACE hooks, but patch applied anyway |
| PostCSS CVE-2026-69153 (npm audit, dev chain) | Compatible lock update to **8.5.28** | [Maintainer advisory](https://github.com/postcss/postcss/security/advisories/GHSA-fxqj-rqcc-2cmp), fixed since 8.5.23 |
| nanoid CVE-2026-67213 (npm audit, dev chain) | Compatible lock update to **3.3.19** | [Maintainer changelog](https://github.com/ai/nanoid/blob/main/CHANGELOG.md), 3.3.18 includes the async-loop fix and 3.3.19 further bounds huge IDs |

MCP code imports `io.modelcontextprotocol.*` only. `spring-ai-mcp` was therefore replaced by direct `io.modelcontextprotocol.sdk:mcp:0.18.3`, preserving the previously resolved SDK version. `spring-ai-model` remains necessary for tool schema generation, and provider modules remain necessary for LLM protocol adaptation. Removing the unused bridge reduces dependencies; it does not repair every issue affecting other Spring AI modules.

## Genuine component mismatches: OpenTelemetry

The initial Java findings covered enumerated API/SDK/exporter/context modules at 1.44.1, API incubator 1.44.1-alpha, and proto 1.5.0-alpha. After integration of main@9cc4290, the BOM selects 1.66.0; CI found CVE-2026-54285 on opentelemetry-api@1.66.0 and, in the next scan, opentelemetry-sdk@1.66.0. The obsolete 1.44.1 rule was replaced with an explicit list of Java modules at exactly 1.66.0 extracted from the verified boot JAR after rechecking the JS advisory; the proto rule remains scoped to 1.5.0-alpha. The following advisories explicitly concern different language implementations. Java protobuf message classes contain none of the named exporter/propagator implementations. Exclusions are exact purl/version plus CVE; no blanket OpenTelemetry product suppression was added.

| CVE | Actual affected component and official source |
| --- | --- |
| CVE-2026-54285 | npm `@opentelemetry/core`, [JS baggage advisory](https://github.com/open-telemetry/opentelemetry-js/security/advisories/GHSA-8988-4f7v-96qf) |
| CVE-2026-41078 | NuGet Jaeger exporter, [dotnet advisory](https://github.com/open-telemetry/opentelemetry-dotnet/security/advisories/GHSA-38h3-2333-qx47) |
| CVE-2026-40894 | NuGet API/Extensions.Propagators, [dotnet propagation advisory](https://github.com/open-telemetry/opentelemetry-dotnet/security/advisories/GHSA-g94r-2vxg-569j) |
| CVE-2026-39882 | Go OTLP HTTP exporters, [Go response-body advisory](https://github.com/open-telemetry/opentelemetry-go/security/advisories/GHSA-w8rr-5gcm-pp58) |
| CVE-2026-41178 | Go baggage parser, [Go baggage advisory](https://github.com/open-telemetry/opentelemetry-go/security/advisories/GHSA-5wrp-cwcj-q835) |
| CVE-2026-44967 | C++ OTLP HTTP exporters, [maintainer issue](https://github.com/open-telemetry/opentelemetry-cpp/issues/3958) |

These 2026 CVEs and the Spring advisories are real published advisories as of this review, not dismissed merely because their numbers look future-dated.

## Spring deployment-specific assessments (unpatched libraries remain)

Current server uses Spring MVC/Tomcat with JSON controllers and static Vue rendering. Remote LLM clients can use WebFlux client classes; this does **not** mean WebFlux is absent. It means the affected reactive server APIs are not configured or implemented here. `PasswordEncoderFactory` provides the standard DelegatingPasswordEncoder/bcrypt. `LocalMasterKeyCipher` uses JCE AES/GCM with a fresh SecureRandom IV. Existing SQL sort selection is a fixed `lastActiveAt` literal in `JpaSessionManager`; endpoint pagination does not accept caller-supplied sort properties.

All rows below require code/dependency/configuration reassessment when their trigger is introduced. Current UI governance and MCP management additions were included in source inspection. Their configuration controls backend resources and policy records; they do not create arbitrary Spring beans, expression evaluators, views, or model-cache implementations.

| CVE and official advisory | Trigger and current reason for exclusion | Change that invalidates conclusion |
| --- | --- | --- |
| [47851](https://spring.io/security/cve-2026-47851/) | Spring AI PDF outline reader; reader module absent, project uses its own PDFBox extraction | Add Spring AI PDF readers |
| [47852](https://spring.io/security/cve-2026-47852/), [59294](https://spring.io/security/cve-2026-59294/) | ONNX transformer deterministic cache / ResourceCacheService URI-fragment write; transformer module absent, embeddings use remote providers | Add local TransformersEmbeddingModel/cache, including plugin-provided implementations |
| [47883](https://spring.io/security/cve-2026-47883/) | Broad UrlHandlerFilter redirect mappings; no such filter registered | Register UrlHandlerFilter |
| [47884](https://spring.io/security/cve-2026-47884/) | XsltView with implicit view name and `/**` rendering; no XSLT view resolver | Add XSLT view resolution. **Corrects preexisting suppression that incorrectly cited sibling WebFlux CVEs** |
| [47885](https://spring.io/security/cve-2026-47885/) | WebFlux PartEvent reader with maxInMemorySize=-1; servlet MultipartFile upload is used | Add reactive multipart streaming reader |
| [47886](https://spring.io/security/cve-2026-47886/) | Untrusted SpEL power expressions; no application user-expression evaluation path | Add untrusted expressions, including indirect rule-engine integration |
| [47887](https://spring.io/security/cve-2026-47887/) | UrlFileNameViewController end-path mapping; absent | Register that controller/view mapping |
| [47888](https://spring.io/security/cve-2026-47888/) | RSocket SETUP frame handler; no RSocket server/dependency | Add RSocket listener |
| [47889](https://spring.io/security/cve-2026-47889/) | Jetty 12 reactive response adapter drops sameSite; deployed servlet Tomcat | Switch to affected reactive Jetty server |
| [47893](https://spring.io/security/cve-2026-47893/) | WebFlux server WebSocket handshake error exposes headers; no such server route | Add reactive WebSocket routes |
| [59280](https://spring.io/security/cve-2026-59280/) | FreeMarker template name from untrusted input; no FreeMarker templates/resolver | Add view rendering using dynamic names |
| [59281](https://spring.io/security/cve-2026-59281/) | EscapedErrors field errors rendered as HTML; JSON responses/static Vue instead | Add Spring escaped-error HTML rendering |
| [59282](https://spring.io/security/cve-2026-59282/) | Property binder traverses a self-populating List of nested beans; request DTOs use Jackson and normal collections | Introduce self-populating collection binding; adding a binder alone requires review |
| [47834](https://spring.io/security/cve-2026-47834/) | Untrusted Sort/Pageable sort sent to native SQL repository; current fixed order/derived methods exclude untrusted property strings | Add caller-selectable sort expressions/native SQL sorting |
| [47841](https://spring.io/security/cve-2026-47841/) | WebAuthn with distributed session serialization; only crypto jar, no WebAuthn module | Add WebAuthn/security filter chain |
| [47842](https://spring.io/security/cve-2026-47842/) | AesBytesEncryptor CBC with null IV; secret encryption uses independent JCE GCM | Adopt affected Spring encryptor/CBC constructor |
| [59276](https://spring.io/security/cve-2026-59276/) | Digest filter, KeyBasedPersistenceTokenService, Password4j encoders, in-memory authorization server; none used by current bcrypt/custom authentication | Add any named component/encoder or authorization server |

The exclusion patterns enumerate only observed artifacts and versions, and CVEs. Dependency-Check merges related Spring AI jars into a representative record; the new AI pattern enumerates required commons/model/openai/retry/template-st modules, not arbitrary future PDF/transformer modules. Framework exclusion for new findings enumerates only the observed Framework modules at exactly 6.2.19. The initial report grouped them under spring-core; the isolated rescan grouped them under spring-tx. The selector includes the union of these observed representative and related module names, not arbitrary future modules or versions.

### Patch path and configuration limits

Official fixes: Framework **6.2.20** (enterprise) / **7.0.9** (community); Spring AI **1.1.9** (enterprise) / **2.0.1** (community); Security **6.5.12** (enterprise) / **7.0.7** or **7.1.1** (community); Data JPA **3.5.14** (enterprise) / **4.0.7** or **4.1.1** (community). Each linked advisory lists these availability constraints. Obtaining enterprise patches or migrating the supported Boot platform is the durable way to remove these residual dependency risks; simply suppressing them does not do so.

No supported OryxOS setting turns on the excluded reader/transformer/view/authentication components. However Spring environment overrides, external bean configuration, third-party Java plugins or a future dependency can change that. In particular, setting a reactive application type, adding view resolvers, or adding expressions/binders invalidates the corresponding deployment review even if this repository remains unchanged. Administrators must not carry these exclusions unchanged into customized distributions. Static checks cannot inspect externally supplied code or prove transitive implementation reachability.

## Change detector and verification

`scripts/check-security-assumptions.py` scans root/module POMs, production Java/resources, deployment configuration and example YAML files. It can also inspect a freshly resolved `dependency:tree`. It flags reviewed risky API/module/configuration families and unreviewed Sort uses. It deliberately permits WebFlux client exceptions used by provider error classification. It ignores comments and tests. It is a conservative **change detector**, not a complete Java call-graph analyzer, taint tracker, or runtime attestation; reflection assembled at runtime, indirect libraries and external configuration remain review obligations.

Commands/results:

- `mvn -q -pl oryxos-tool -am test -Dtest=FormatToolsTest,McpClientServiceTest,McpToolAdapterTest,McpServerAdminServiceTest,ToolRegistryTest -Dsurefire.failIfNoSpecifiedTests=false -Dspotless.skip=true -DargLine=-javaagent:${HOME}/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar`: exit 0, 23 tests; `/tmp/oryxos-security-targeted.log`.
- `mvn -q -pl oryxos-boot -am dependency:tree -DoutputFile=/tmp/oryxos-security-tree-after.txt -DappendOutput=true`: exit 0; confirms POI5.5.1/IO2.22.0/Compress1.28.0/direct MCP0.18.3, no spring-ai-mcp.
- Frontend `npm install 'dompurify@^3.4.13' --ignore-scripts`, `npm update postcss nanoid --ignore-scripts`, `npm audit --json`: exit 0 final audit, 0 findings; `/tmp/oryxos-security-npm-after.json`.
- Frontend `npm test`, `npm run build`: exit 0; 9 test files passed; `/tmp/oryxos-security-npm-test.log`, `/tmp/oryxos-security-npm-build.log`.
- `python3 scripts/test-security-assumptions.py`: 6 tests pass, including root POM and example configuration coverage.
- `python3 scripts/check-security-assumptions.py --dependency-tree /tmp/oryxos-security-tree-after.txt`: exit 0 on merged sources and current dependency graph.
- Suppression XML parses successfully. The final installed-artifact Dependency-Check scan also validated the suppression schema and matching; its result is recorded below.

No production database, external service, boot jar, or running container was modified by these checks. There is no claim of completed deployment or clean remote vulnerability scan in this scoped report.

## Isolated rescan selector correction

The controller's first isolated scan (`/tmp/oryxos-042-delivery/target/dependency-check-report.json`) retained 13 active findings: 10 Framework findings now represented by `spring-tx@6.2.19`, the JS OpenTelemetry CVE now represented by `opentelemetry-sdk@1.44.1`, and two old installed frontend bundles. Dependency-Check selected different representatives after grouping related jars. The exact selectors were corrected from this report's `packages` and `relatedDependencies.packageIds`:

- Framework, all exactly 6.2.19: spring-aop, spring-aspects, spring-beans, spring-context, spring-context-support, spring-core, spring-expression, spring-jcl, spring-jdbc, spring-messaging, spring-orm, spring-tx, spring-web, spring-webflux, spring-webmvc.
- OpenTelemetry Java, all exactly 1.44.1: opentelemetry-api, opentelemetry-context, opentelemetry-exporter-common, opentelemetry-exporter-otlp, opentelemetry-exporter-otlp-common, opentelemetry-exporter-sender-okhttp, opentelemetry-sdk, opentelemetry-sdk-common, opentelemetry-sdk-extension-autoconfigure-spi, opentelemetry-sdk-logs, opentelemetry-sdk-metrics, opentelemetry-sdk-trace; the preexisting exact 1.44.1-alpha API-incubator entry remains. Only CVE-2026-54285 (the JS implementation finding) is covered by this Java API/SDK selector.

This changes artifact matching only, not the assessed CVE lists or risk acceptance conditions. XML parsing and regex matching against the isolated report confirmed that the two new representative purls match. No JS filename/hash suppression was added. The controller installed the newly built internal modules and rescanned; the final result below confirms that stale Maven-local frontend jars were replaced.

Central installed-artifact scan on 2026-09-18: 171 objects, 0 unsuppressed findings, 121 suppressed advisory records; `/tmp/oryxos-042-owasp-final.log` exits 0. Old frontend bundles are absent. This is a processed-finding result, not a claim that the unpatched Spring platform has no residual risk.

## PR integration scan (2026-09-20 UTC)

[CI run 35484593607](https://github.com/oryx-labs/oryxos/actions/runs/35484593607) used Dependency-Check 13.0.0 and scanned 169 objects after the main merge. It passed the existing severity threshold but still reported one medium finding: CVE-2026-54285 incorrectly matched the Java API 1.66.0 to npm @opentelemetry/core. The exact component/version exclusion has been corrected; the subsequent scan must confirm zero unsuppressed findings. The original 171/121 counts above are historical, not this merged dependency graph.

Run 35484840168 additionally identified the same npm advisory against the Java SDK 1.66.0 (170 objects, 1 unsuppressed, 120 suppressed); the rule now enumerates only the API and SDK artifacts at 1.66.0.

Run 35485045622 retained the same npm finding on opentelemetry-common@1.66.0. To cover all affected Java component mismatches, the rule now enumerates the 12 OpenTelemetry Java modules actually packaged in the verified boot JAR at exactly 1.66.0; it does not match future versions, other CVEs, or npm packages.
