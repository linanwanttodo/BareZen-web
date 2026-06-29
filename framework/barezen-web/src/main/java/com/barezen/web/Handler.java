package com.barezen.web;

import java.util.Map;

/**
 * What a web component is: something that turns a {@link Request}, plus what the route table captured from it, into a
 * {@link Response}.
 *
 * <p>One method, synchronous, returning a value. This is the entire web layer's extension surface, and every later piece
 * of it — routing, middleware, an adapter, a server — is expressed as a component that holds a {@code Handler} and
 * produces a {@code Handler}, which is why this signature is the one thing in the layer worth getting right
 * (ADR-0018 §6).
 *
 * <p>The second argument is why this signature changed. Phase 2.8 built a {@link Router} whose answer
 * ({@link RouteMatch#parameters()}) had nowhere to go: a handler could not receive it, so an application re-parsed
 * {@link Request#path()} and the router's work was thrown away. Two arguments is the smallest correction that ends that,
 * and it is an intentional amendment of Phase 2.7's contract, recorded with its rejected alternatives in ADR-0020.
 *
 * <p>Why a {@code Map} and not a {@link RouteMatch}: a match is the <em>adapter's</em> answer — the handler to call and
 * the values to call it with — while a handler needs only the second half. Handing a handler the match would hand it a
 * {@link Handler}, its own identity, and the contract forbids a contract type from dispatching one: the handler that
 * called {@code match.handler()} would be a router wearing a handler's clothes (ADR-0020 §3). It would also make an
 * unrouted handler impossible to call, because a match cannot be built without a handler to name — the probe that
 * established this is in ADR-0020 §3. And why not the request: route parameters are not something the client sent, so a
 * request that carried them would stop being a record of the wire and become a place to put things, which is how an
 * attribute bag arrives (ADR-0018 §9, ADR-0020 §4).
 *
 * <p>Why no overload and no default method. The single-argument {@code handle(Request)} is gone rather than kept as a
 * convenience that hands itself {@link Map#of()}: two live signatures would mean a handler that quietly ignores the
 * parameters its route declares, and every wrapper, adapter and test would have to guess which one an application meant
 * to implement. There is no {@code LegacyHandler} and no {@code RouteHandler} for the same reason — one extension
 * surface is the whole point of having one (ADR-0020 §6).
 *
 * <p>Why it returns rather than writes. A handler that returns a response decides nothing about the connection: it has
 * no way to send bytes, so it cannot half-answer, cannot respond twice, and cannot be forced to commit to a status
 * before a later component has had its turn. That last property is the reason middleware can be added in a later phase
 * without touching this type — a component that wraps a {@code Handler}, inspects the {@code Response}, and returns a
 * changed one is the whole mechanism, and under this signature a wrapper passes the map through unchanged. A handler that
 * wrote to a stream would have to be handed that stream, and once bytes are on the wire an "after" component is too late
 * (ADR-0018 §3, ADR-0020 §8).
 *
 * <p>Why it is synchronous. The alternatives were {@code CompletableFuture<Response>} and a checked exception. A checked
 * exception would force every caller — including a wrapper, which is what middleware is — to catch or declare it, and
 * there is nothing a wrapper can do with {@code IOException} from a handler three layers down except pass it along.
 * {@code CompletableFuture} would put threading, and therefore an executor and a lifecycle, into the contract: the
 * question "does BareZen's web layer own threads?" would have been answered by a type that cannot be implemented without
 * answering it (ADR-0018 §8, §9, and the same rule ADR-0017 applied to the event bus).
 *
 * <p>Failure semantics, stated so a handler author does not have to guess (ADR-0018 §8):
 *
 * <ul>
 *   <li>an exception may propagate out of a handler, and nothing in this contract catches it — a handler that translates
 *       its own failure into a {@code 500} is fine, and a handler that lets a database exception escape is too. The
 *       exception is part of the contract of a call, not an escape from it, exactly as {@code EventBus.publish} lets a
 *       handler's exception reach the publisher;
 *   <li>an exception does not entitle a handler to leave anything half-done. The {@code Response} is the only channel of
 *       communication, so anything not returned was not sent;
 *   <li>who turns an escaping exception into a response to the client is not this type's decision. It is an adapter's, or
 *       a future error-handling component's, and the reason it is left unmade here is that deciding it now would choose an
 *       error format, a logging policy and a status code for every application.
 * </ul>
 *
 * <p>What this type must never grow (ADR-0018 §9): a {@code Context}, an {@code attribute(name)} bag, a
 * {@code service(Class)} accessor, a setter for the request, a reference to an event bus, a configuration handle or a
 * router. A handler is handed everything it needs by whoever constructed it, which is the rule ADR-0016 applies to
 * plugins and ADR-0006 applies to contexts; {@code Request} carries data that came from the client, {@code parameters}
 * carries what the route matched, and nothing else arrives. A handler that needs collaborators takes them in a
 * constructor.
 *
 * <p>Implementations are not required to be thread-safe towards each other, but a handler is invoked by an adapter
 * concurrently and must therefore not hold mutable per-request state in fields. What belongs in a field is what was
 * injected at construction. The map it is handed is already per-request, immutable and built by the route table, so two
 * concurrent requests on one route cannot see each other's values (ADR-0020 §8).
 */
@FunctionalInterface
public interface Handler {

  /**
   * Answer one request.
   *
   * @param request the request to answer, never null. Nothing in it is mutable, which is deliberate: a handler that could
   *     set the path or add a header would be changing what the client asked, and whichever component ran after it would be
   *     answering a different request from the one that arrived
   * @param parameters the path parameters the matched pattern captured, keyed by the names the pattern declared, in pattern
   *     order, unmodifiable, raw — never percent-decoded, exactly as {@link Request#path()} is raw
   *     ({@code /users/a%2Fb} yields one value holding {@code a%2Fb}, not two segments). Keys are only the names the
   *     pattern declared, so {@code get} of an undeclared name is a typo rather than a route that changed shape
   *     (ADR-0019 §5, §7; ADR-0020 §7). Empty for a route with no parameters, and it is never null: a caller that has no
   *     route table passes {@link Map#of()}, which is the same answer the table gives for a static route. The interface
   *     cannot enforce that obligation on a caller — a signature states it and every implementation would have to repeat
   *     the check — so it is stated here, and the producing side is under this module's control: {@link Router} builds the
   *     map and {@link RouteMatch} refuses a null one (ADR-0020 §7).
   * @return the response to send, never null. Returning null would be a response with no status, which an adapter cannot
   *     write; a handler that has nothing to say answers {@code 404} or {@code 204}, both of which are honest
   * @throws RuntimeException whatever the handler's own failure is; declared nowhere, caught nowhere, and reported by the
   *     component that decides how errors reach clients
   */
  Response handle(Request request, Map<String, String> parameters);
}
