# ADR-0043: The OpenAPI Generator Is a Declared-Data Projection — JSON Only, No Reflection, No New Module

## Status

accepted
Date: 2026-10-04
Decides: where Phase 2.14's generator lives; that schemas are declared data rather than reflected classes; that the document is emitted as JSON only; which OpenAPI 3.0.3 concepts the subset contains; that route→operation mapping is one-way; where determinism is owned; how the six Stage 2.14-F refusal categories are closed
Related: ADR-0042 (the plugin fence this placement stays inside, and its collector non-goal this finishes), ADR-0041 (registration presumes nothing about documents), ADR-0019 (the router's pattern grammar the path-parameter cross-check reads), ADR-0029 (the framing rule that forbids a 204 body schema), ADR-0036/0037 (the bearer session and status codes the security subset describes), ADR-0016 (plugins register into nothing; the document handler is mounted by the application)

## Context

2.13 closed the collector with a deliberate non-goal: it records `OperationMetadata` and generates no document. 2.14 is the projection that decision was waiting for. The directive names the model (paths, operations, parameters, request body, responses, schemas, security), six validation failures a generated document must refuse, byte-equivalent output for the same input as a hard requirement, and a warning — «不要为了"完整 OpenAPI"无限扩张». Three decisions are forced before any code: where the generator lives (a new module was implied), how schemas come to exist (reflection over DTOs is the industry default), and what the output format is. Each interacts with a rule already in force: the plugins fence (ADR-0042 rule ①), determinism (which reflection threatens first), and the framework's own public-API discipline (§10's nine questions).

## Problem

Can the framework produce an OpenAPI 3.0.3 document that (a) stays legal inside the plugin boundary, (b) cannot emit a lying spec, (c) renders byte-identically for identical declarations, (d) survives cyclic type graphs, and (e) does so without a classpath scanner, a YAML engine, or a second lifecycle?

## Options Considered

- **A. A `barezen-openapi` Maven module below the plugins namespace.** Rejected by measurement, not taste: `OperationMetadata` and `OpenApiPlugin` live in `com.barezen.plugins.openapi` (2.13 shipped them there), so any module of framework code that names those types is a class outside `com.barezen.plugins..` depending in — the exact edge ADR-0042 rule ① made permanent, and the ArchUnit sweep would fail it on first run.
- **B. Reflection-based schema generation from DTO classes.** Rejected: all four directive conditions (deterministic, cycle-safe, no arbitrary scanning, stable property order) become properties to *engineer and re-verify* — field order needs a spec against the JVM's unordered `getDeclaredFields`, cycles need an identity map, scanning needs a package policy. Each is one regression away from breaking.
- **C. JSON and YAML output.** Rejected: two emitters for one model means two determinism proofs, and the only consumer in-repo (`handler()`) serves one content type anyway.
- **D. Full 3.0.3 grammar (oneOf, discriminators, cookies, oauth2, multipart, callbacks).** Rejected by the phase's own warning; every concept not filled by a framework truth is a field the generator would invent.
- **E. Declared-data model, JSON-only, inside the plugins namespace.** Chosen: schemas are values the application writes, `$ref` is a name the emitter prints without recursing, and the fence is untouched because no new dependency direction exists.

## Decision

1. **Placement.** The generator lives in `com.barezen.plugins.openapi`, same module as its input. The 2.13 javadoc's phrase "2.14's module" is read as "2.14's module *of code*", satisfied by this package; a future separation must first re-open ADR-0042 rule ①. This is the phase's only deviation from the directive's wording, and it is forced by code, not preference.
2. **The subset is exactly the named concepts.** OpenAPI `3.0.3`; `info` (title, version required); `servers` in declaration order; `paths` with method keys lowered; operations carrying derived `operationId`, collected `summary`/`tags`, `parameters` (path/query/header — cookie is a documented non-goal because the framework answers identity through a bearer header, ADR-0036), `requestBody` and responses with the single media type `application/json`, `security` requirements pairing against declared `securitySchemes`, and `components` with named schemas of `type`/`format`/`properties`/`items`/`required`/`nullable`/`$ref`. A response's `description` is required at construction (the spec's Response Object says so; a model holding null would force the emitter to invent).
3. **No reflection; cycle-safety and ordering are structural.** A `Schema.REFERENCE` node carries a name and nothing else, so a `User` whose `friends` is `array($ref User)` is one string deep in emission — no walk of the document can loop — and property order is the order the caller wrote, which is the strongest possible stability claim. Determinism therefore does not depend on any serializer library's map policy: `JsonWriter` (package-private) writes members in exactly the order added, and `OpenApiJson` walks the document's order and adds nothing.
4. **The projection is one-way.** `Operation.builder(OperationMetadata)` consumes what the collector holds; nothing in 2.14 can write back. The collector's `operations()` snapshot was already frozen (2.13), and the serving path (`ApiDocument.handler()`) renders once at assembly, so no request ever re-enters the plugin.
5. **`operationId` is derived, never declared** — method plus path words, camel-cased (`GET /users/{id}` → `getUsersId`). A declared id is one more thing that can collide silently; the derivation makes collisions a property of the path templates, which the document already checks.
6. **Validation is build-time and total.** The six Stage 2.14-F categories are each closed at a named site: missing required field (`Builder.build`); duplicate path (pass 1 of `validate`, a `Set` of `METHOD path`); duplicate operationId (pass 2, distinct templates that derive the same id); invalid response (`Operation.build` requires ≥1 response; `ApiResponse` refuses the code grammar, a null description, and a body schema on 204/304 per ADR-0029); invalid schema reference (pass 3 resolves *every* `$ref` — operation payloads and component bodies alike, recursively); invalid security requirement (the named scheme must be declared). Wrong path parameters are refused earlier, at `Operation.build`, in both directions.
7. **Determinism is owned once, in `ApiDocument`.** Paths by template text, operations within a path by method text, component schemas and security schemes by name, tags distinct-sorted; servers keep declaration order (the spec's own rule). Responses and parameters keep *their* declaration order because `Operation` promises the caller's source text is the order — both orderings are decisions of this type, never of the emitter. Amended on 2026-11-26: a parameter's identity is `(location, name)`, so `Operation.Builder.build()` refuses a duplicate pair while still allowing the same name in two locations.

## Consequences

The fence stays provable without a new rule: the 7 ADR-0042 ArchUnit rules already cover every class under `com.barezen..`, and the generator passed them unchanged — rule ① stays green because nothing outside plugins imports these types, rule ⑥ stays green because the model is immutable records and final holders. Serving is free: `handler()` is a `com.barezen.web.Handler`, so the OpenAPI document reaches clients through the framework's one request path, including through `SecurityMiddleware` if the application protects it. The cost of declared data is a second list (the schema beside the Java type) kept aligned by the application's own code — the same trade ADR-0019 made for routes, with the same mitigation: everything one operation needs is written at one location. JSON-only means a YAML fan will require re-opening this ADR, not just adding a file.

## Security implications

The document is a statement about the API; every refusal in §6 exists so the statement cannot silently lie (a missing security requirement teaches a client generator to call an endpoint unauthenticated). No secret can leak through it structurally: nothing is scanned, so only what an application explicitly declared can appear — the same information-disclosure posture ADR-0042 set for `toString`. `handler()` renders once and serves identical bytes, so a request cannot perturb document state or make the generation path a per-request CPU cost.

## Testing

`OpenApiDocumentTest` (19): the six categories as six named tests, golden-byte rendering of the minimum and the full document (every subset feature visible), declared details (parameter description, body description, `nullable`, `boolean`) pinned to their own golden, determinism under re-ordered assembly, `$ref`-cycle termination proven by the test completing, path-parameter refusals in both directions, the one-way projection against a real collector, and the served bytes through a real `Router`. `OpenApiModelTest` (24) and `JsonWriterTest` (7) close construction-time and emitter-level misuse, and `ReadmeDocumentExampleTest` (1) executes the README snippet verbatim. Stage 2.14-G ran 8 mutations — drop endpoint, drop response, drop parameter, change method, wrong path parameter, wrong schema type, non-deterministic ordering, invalid security requirement — all 8 killed, every file restored byte-identical. Two generator bugs were found by these tests first and fixed from measurement: the duplicate-path guard used `List.add` (always `true`) instead of `Set.add`, and reference resolution did not walk into `array`/`properties` nesting.

## Non-goals

YAML; HTML/ReDoc/Swagger-UI serving; a scanner of any kind; `cookie` parameters; `oauth2`/`apiKey`/`mutualTLS` schemes; a second media type; `oneOf`/`anyOf`/`allOf`, discriminators, `readOnly`, vendor extensions, request/callback bodies, examples; route enumeration from `Router`; a spec-validation oracle against the full 3.0.3 grammar.

## Reopening trigger

A named consumer for any non-goal (a client generator needing YAML, an OAuth provider needing its scheme); a real reflection request that arrives *with* all four directive conditions as acceptance tests, not as intentions; the first feature that would make `JsonWriter`'s escaping promise insufficient (then the question is a library, per option C's reasoning, re-argued); or any proposal to move the generator out of the plugins namespace, which re-opens ADR-0042 rule ① with it.

## Related Decisions

ADR-0042 (placement and collector split), ADR-0041 (registration neutrality), ADR-0019 (pattern grammar), ADR-0029 (no-body statuses), ADR-0036/0037 (bearer security documented as-is), ADR-0016 (no second lifecycle; applications mount the handler).
