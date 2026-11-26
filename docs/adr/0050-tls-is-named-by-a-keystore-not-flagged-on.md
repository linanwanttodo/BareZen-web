# ADR-0050: TLS Is Turned On By Naming a Keystore, Not By a Flag — and the Adapter's Public Surface Grows by Nothing

## Status

accepted
Date: 2026-10-06
Decides: how the JDK adapter gets HTTPS, which configuration keys exist and what their absence means, where a bad keystore fails, what the log may name, and which TLS decisions are deliberately not made
Related: ADR-0021 (the adapter owns the JDK server surface), ADR-0024 (one instance, one lifecycle, `port()`), ADR-0013 (configuration resolved once at assembly), ADR-0031 (the same module's second configuration key, and its "unconfigured is never unbounded" rule), ADR-0002 (a runtime capability, not a build variant), ADR-0009 (the exception taxonomy this keeps using), ADR-0049 (the servlet integration, where TLS is the container's job)

## Context

By the end of 3.3 the framework had two ways to run and exactly one of them could serve a browser over `https://`: a
servlet inside a container inherits that container's TLS, and the JDK adapter could not have TLS at all. That made the
framework's own default deployment unable to do the thing every production HTTP endpoint now has to do, and it left the
"compatible with various projects" claim with an obvious hole — a reader who wants to try BareZen on a real host has to
bring a container or ship a proxy.

The interesting constraint was not the JSSE, which is a dozen lines, but what adding it would do to the public surface.
`JdkHttpServerAdapter` has three constructors, each already justified under the nine-question gate ADR-0031 used
(address, router / +threads / +configuration). A fourth taking `SSLContext` or a boolean would be: a second way to say
what a configuration snapshot already says, and an `enableTls(boolean)` key would be worse — a flag that can be `true`
with no keystore, which is a deployment that believes it encrypted something.

## Options Considered

**A — a `boolean barezen.http.jdk.tls.enabled` plus a keystore path.** Refused: two keys that must agree, one legal
state where they do not (`enabled=true`, no keystore), and an operator reading a config file cannot tell which
combination is the mistake. A capability that is present or absent is not a boolean.

**B — a fourth constructor taking an `SSLContext`.** Refused on the surface question rather than the correctness one:
an application that owns an `SSLContext` genuinely can use it, but it can equally hand it to the JDK server in one line
of its own, and the adapter would then have two ways to arrive at the same socket. ADR-0019 §12 refused
`Router.Builder.get()/post()` for the same reason — nine spellings of one thing, each with its own documentation to keep
in agreement.

**C — a separate `barezen-http-jdk-tls` module.** Refused: it would split one implementation of one contract across two
artifacts so that a deployment could depend on the TLS half and still be able to construct a plaintext server, and
`com.sun.net.httpserver` is already confined to one module by ADR-0021 for the opposite reason.

**D — chosen: two keys, and presence is the decision.** `barezen.http.jdk.tls.keystorePath` and
`barezen.http.jdk.tls.keystorePassword`, both with no default. No path → plaintext, which is every deployment's existing
state and stays the behaviour of a snapshot that says nothing. A path with no password → the deployment fails at
construction, naming both keys.

## Decision

1. **TLS is resolved once, at construction**, from the snapshot the adapter is already given, and `start()` then opens
   an `HttpsServer` instead of an `HttpServer`. No public constructor, method or type is added: `Tls` is
   package-private, so the adapter's shipped surface is unchanged and the phase's public-API question ("who consumes
   it?") is answered by the two keys and one internal class.
2. **The keystore type is the JDK's default** — the type the same JDK's `keytool` writes, so a generated keystore and
   this reader agree without a key nobody would think to set. No keystore-type key exists.
3. **A bad keystore fails at construction**, as a `ConfigurationException` naming the key, the path and the JDK's own
   reason. This is ADR-0031's placement rule for the body limit (a bad value is a startup error, not a runtime surprise),
   and it costs no port because construction precedes binding.
4. **The password never appears in a message this code writes.** The failure names the key and the path; the startup log
   names the keystore file and says `HTTPS`. Both are asserted, because the natural convenience — `"...: " + password`
   in a cause chain — is exactly what a log file keeps forever.
5. **Protocol and cipher policy is not configured.** The context is `TLS` with the JDK's own default parameters, no
   client authentication (no mTLS), no protocol allow-list, no cipher-suite choice, no SNI handling beyond the JDK's.
   Each of those is a key with a consumer, and the consumer is a deployment that needs it; ADR-0002's rule that a
   capability is a runtime fact rather than a build variant applies to the shape of the answer.
6. **The lifecycle contract is untouched.** `isRunning()`, `port()` and `stop()` behave identically (ADR-0024), because
   TLS is a property of the socket, not of ownership. TestServer and the examples keep running plaintext; nothing they
   assert changed.

## Consequences

- The framework's own server can now be the production server for a small deployment, which is what makes 3.6's site
  self-hosting rather than requiring a container in the picture.
- `barezen.http.jdk.*` is now three keys with one owner. The pair "path without password" is the only combination that
  can be wrong, and it is refused; there is no combination that is silently half-on.
- A TLS server with no certificate is not possible here, because the certificate comes from the file the key names. There
  is no ACME/automatic-certificate story, and adding one would mean a dependency this build has refused to take quietly.
- **One architecture rule was widened, and it is named here because a widened rule is what an audit looks for.**
  `the_adapter_depends_only_on_the_contract_core_slf4j_and_the_jdk` allowed eleven package prefixes and now allows
  fourteen: `java.nio.file..`, `java.security..` and `javax.net.ssl..`. Every one is a JDK type — a keystore is a file
  and a context is JSSE — and the rule's actual subject, a third-party dependency arriving inside an adapter, is still
  forbidden by that allow-list and named again, separately, by `no_server_framework_or_container_appears_here`. No rule
  was deleted, no threshold lowered and no violation suppressed: the first run after this change failed on both
  `the_adapter_depends_only_...` (18 violations) and `the_bridge_and_the_dispatcher_are_not_extension_points` (1 — the
  anonymous `HttpsConfigurator` subclass was a non-final package-private type), and both were fixed rather than
  muted. The configurator is now the unsubclassed base type, which is also the honest implementation: it supplies the
  context and sets no parameters, exactly as decision 5 says.
- Nothing in `barezen-web` changed: the contract cannot see TLS, so a handler cannot branch on it. An application that
  wants to redirect `http` to `https` does it at the proxy or with two adapters, and `Request` carries no scheme — a
  real limitation named in ADR-0050's reopen list rather than papered over by adding `isSecure()` to a contract that
  ADR-0018 §12 keeps server-free.

## Security implications

- Refusing a keystore without a password removes the "did you mean the empty password?" ambiguity; the JDK's own failure
  for a wrong password does not distinguish absent from wrong, and this module states which of the two it was given.
- No credential in any log line or exception message, asserted by test rather than by review.
- The handshake is entirely the JDK's; this module chooses no protocol, no cipher and no verification mode, so there is
  no place here where a weaker default could be introduced by accident. Test clients trust *this* certificate and
  nothing else; the negative test asserts a system-trust client is refused, which is the assertion that would fail if
  anyone ever set a trust-all manager.
- The plaintext path is unchanged, so existing deployments are not silently upgraded or silently broken.

## Testing

- `JdkTlsSemanticsTest` — 13 tests. Configured (6): the same route answers over `https` with the client trusting exactly
  this keystore; `isRunning()`/`port()` behave as they do in plaintext; a client trusting the system CAs is refused; a
  plaintext request to the TLS port gets no readable answer; the startup log says `HTTPS`, names the keystore and never
  the password; a 404 over TLS is the contract's own bytes, naming no path. Unconfigured (2): the adapter still serves
  plaintext on the same constructor; an explicitly blank path fails construction. Refusals (5): a keystore without a
  password names both keys; a wrong password names the file and not the secret; a missing file fails construction; an
  invalid path syntax is translated to `ConfigurationException` without echoing the raw value; the failure lands at
  construction so no port was spent. Amended on 2026-11-25: blank is no longer treated as absent,
  because an explicit empty value must not silently downgrade TLS. Amended on 2026-11-26: `InvalidPathException`
  is translated at the same configuration boundary.
- Keystores are generated per test by the running JDK's own `keytool` (`-storetype PKCS12`, `SAN=dns:localhost,ip:127.0.0.1`,
  one day of validity) in a `@TempDir`, so no keystore and no password is ever committed.
- **Mutations**, each killed against this test class, each file md5-restored: T1 ignore the keystore key (6 failures + 2
  errors), T2 accept a keystore with no password (1), T3 echo the password in the refusal (1), T4 accept a blank path as
  plaintext (1).
- Existing adapter suites unchanged and green: 200 http-jdk tests plus this class's 12.
- JDK: this build runs on Maven's JDK 24 with `release 17` (ADR-0049's correction); the emitted major is asserted inside
  the build.

## Non-goals

mTLS, cipher-suite or protocol configuration, certificate renewal, SNI beyond the JDK's default, `http → https` redirect
policy, a scheme or `isSecure()` value on `Request`, TLS in the servlet integration (the container's job, ADR-0049), and
any new public API on the adapter.

## Reopening trigger

- **`Request` carrying no scheme** opens when an application needs to branch on the request's security — the honest
  answer is a contract decision about what a `Request` knows (ADR-0018 §2), and the trigger is a consumer that cannot
  live without it (a redirect, an HSTS header, a cookie `Secure` flag set by a handler).
- **A protocol or cipher key** opens with a named compliance requirement, not a preference.
- **mTLS** opens when a deployment authenticates clients by certificate; it needs its own decision about what a refused
  handshake answers, which no handler in this framework is ever consulted for.

## Related Decisions

ADR-0021 (the module that may name the JDK server), ADR-0024 (the lifecycle TLS does not touch), ADR-0031 (the key
pattern: resolve at construction, fail loudly, never fall back quietly), ADR-0013 (assembly-time resolution), ADR-0002
(capability, not variant), ADR-0049 (the other server, where TLS belongs to the container).
