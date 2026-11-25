# ADR-0035: Password Hashing Is PBKDF2-HMAC-SHA256 at 600,000 Iterations, Self-Describing

## Status

accepted — 2026-10-03

Related: ADR-0001/ADR-0002 (no new dependency, JDK 17 baseline — the two facts that bound this choice), ADR-0033
(the module that owns it).

## Context

A framework that ships an authentication module will be asked how it stores passwords. Doing nothing (leave it to the
application) is defensible, but the framework's thesis is secure-by-default, and the most common insecure default is a
home-grown SHA-256-of-the-password. So the module ships one hasher, correct, with no third-party dependency.

## Problem

1. Which algorithm, given no external dependencies (no bcrypt library, no Argon2 native binding) and a JDK 17 floor?
2. How is a parameter that will need to change over time (the iteration count) recorded so old hashes still verify?
3. What may a hash and a password never appear in?

## Options Considered

**A — bcrypt via jBCrypt.** Rejected: a new third-party compile dependency, and the enforcer's dependency posture
(Spring banned, slf4j-only in core) makes adding one an ADR-sized decision — the honest answer here is that no
dependency is needed when the JDK ships a correct KDF.

**B — Argon2.** Rejected on the same ground, harder: no JDK implementation exists, so it would require a native or
third-party library. It is the strongest choice in the abstract and the wrong one for a zero-dependency framework
today; that tension is the reopening trigger below.

**C — PBKDF2-HMAC-SHA256 via `SecretKeyFactory`, chosen.** Available in every conforming JDK 17, no dependency, and
600,000 iterations is OWASP's 2023 recommendation for this exact construction. It is not the strongest KDF, and the
record says so rather than pretending otherwise; strength here is a parameter in the encoded form, so it can move.

## Decision

`PasswordHasher` hashes with PBKDF2-HMAC-SHA256, 16-byte salt from `SecureRandom`, 32-byte derived key (the
HMAC-SHA-256 block size), default 600,000 iterations. The encoded form is self-describing —
`PBKDF2WithHmacSHA256$<iterations>$<b64 salt>$<b64 key>` — so a hash carries the count it was made with and the count
can be raised without invalidating stored hashes; verification reads the parameters from the hash, not from the hasher.

A malformed, unknown-algorithm, or wrong-length hash verifies to `false` and never throws: a corrupt hash is not a
password, and a wrong password is not a server error. Comparison uses `MessageDigest.isEqual` (constant-time within
the JVM), so the verification path does not leak how many leading bytes matched.

`standard()` gives the secure default; `of(iterations, random)` exists so tests can run fast (1,000 iterations) and
deterministically, and refuses a non-positive count (mutation SM11 guards this). Amended on 2026-11-25 with a
2,000,000-iteration ceiling: construction refuses a larger count and verification rejects a larger stored count before
derivation, so a corrupted record cannot turn one login into unbounded CPU work. The class is `final` — it is a
concrete safe default, not an extension point; an application that wants Argon2 implements `Authentication` against
its own store rather than subclassing this one.

## Consequences

- Positive: a correct-by-default password store with zero dependencies and a forward path (the count is a field in
  the hash).
- Positive: the raw password and the hash never appear in `toString` or any log — the module cannot even name a
  logger (ADR-0033's ArchUnit rule), and `Credential.toString` prints the scheme only.
- Accepted cost: PBKDF2 is memory-cheap, so it is weaker against a GPU/ASIC attack than Argon2 at equal wall time.
  The countermeasure is the iteration count and its encoding, and the honest countermeasure is the trigger below.

## Security implications

Iteration count is the whole defence-in-depth of this choice; because it lives inside the stored hash, raising the
default later is a migration on next login, not a break. Empty passwords are hashed like any input — a minimum-length
policy is a registration concern, not a hashing concern.

## Testing

`PasswordHasherTest` (15 tests in this phase; 17 current): format and field lengths, salt uniqueness across two
hashes of the same password, accept-right/reject-wrong, malformed/unknown-algorithm/corrupt-iterations/corrupt-salt-or-
key all verify false without throwing, cross-hashers verification at different iteration counts, null and
non-positive-iteration refusals, `standard()` = 600,000, and `toString` disclosure. The current suite also rejects an
excessive stored count before derivation and refuses an excessive construction count. Mutations SM11 (iterations `<=0`
→ `<0`) and SM12 (no salt randomness) each killed by a single named test, restored byte-identical.

## Non-goals

Password strength policy, credential stuffing lockout, per-user parameter upgrades, and Argon2/scrypt/bcrypt
interoperability. Verification of externally produced hashes is limited to what `verify` can parse — a bcrypt hash is
a malformed hash here, correctly.

## Reopening trigger

If a threat model with GPU/ASIC offline attack on a stolen store becomes one the framework must address for its users,
an Argon2 record reopens this one — and must then decide the dependency question the enforcer makes explicit, since
the JDK will not supply it.

## Related Decisions

ADR-0001, ADR-0002, ADR-0033, ADR-0036.
