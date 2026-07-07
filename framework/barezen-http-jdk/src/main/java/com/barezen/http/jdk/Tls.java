package com.barezen.http.jdk;

import com.barezen.core.config.Configuration;
import com.barezen.core.config.ConfigurationKey;
import com.barezen.core.error.ConfigurationException;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Objects;
import java.util.Optional;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The TLS half of the JDK adapter, resolved once at construction from the configuration snapshot and never read again.
 *
 * <p>Two keys decide everything: a keystore path and its password. Absent path means the adapter serves plaintext, which
 * is the behaviour every deployment had before this class existed and every deployment keeps unless it opts in — a
 * capability turned on by presence, not a flag that can be set to {@code maybe}. Present path with no password is a
 * misassembly and fails construction, naming both keys; that pair is refused rather than guessed because a keystore
 * opened with a wrong password fails in the same opaque way as one opened with no password, and the difference between
 * "you did not write the key" and "you wrote the wrong one" is the only thing an operator can act on.
 *
 * <p><b>Why this is not a new constructor.</b> The adapter already takes a {@link Configuration} (ADR-0031 added that
 * path for the body limit), so TLS arrives through the same snapshot and the public surface grows by nothing. An
 * {@code enableTls(...)} method or a {@code Builder} would be a second way to say what two configuration keys already
 * say, and ADR-0018 §12's discipline about refusing equivalent entry points applies here as it did to
 * {@code Router.Builder.get}/{@code .post}.
 *
 * <p><b>What is read, and what is never written down.</b> The password is used to open the keystore and to build the key
 * manager, and it appears in no message this class can produce: every refusal names the key, the path, and the
 * exception the JDK gave. {@code ConfigurationException} carries the key name and the value's source, which for a file
 * read from a snapshot on disk is a path — an operator's own file, not a secret to keep from them — so the message
 * repeats the key and the path but never the password.
 *
 * <p><b>The protocol decision, stated because it is a policy.</b> The context is {@code TLSv1.2} as the ceiling with
 * {@code TLSv1.3} preferred, which is the JDK's own default ordering; no protocol list is configured, no cipher suite is
 * chosen, and no client certificate is requested. mTLS, SNI beyond the default, session-ticket tuning and certificate
 * pinning are each a key with a consumer, and none has one yet — the reopen trigger is named in ADR-0050.
 */
final class Tls {

  /**
   * {@code barezen.http.jdk.tls.keystorePath} — the file holding this server's certificate and private key. No default:
   * absent means plaintext, which is what every unconfigured deployment gets.
   */
  static final ConfigurationKey<String> KEYSTORE_PATH =
      ConfigurationKey.of("barezen.http.jdk.tls.keystorePath", String.class, null);

  /**
   * {@code barezen.http.jdk.tls.keystorePassword} — the password opening that keystore. No default, and required as
   * soon as a path is present.
   */
  static final ConfigurationKey<String> KEYSTORE_PASSWORD =
      ConfigurationKey.of("barezen.http.jdk.tls.keystorePassword", String.class, null);

  private final SSLContext context;
  private final Path keystore;

  private Tls(SSLContext context, Path keystore) {
    this.context = context;
    this.keystore = keystore;
  }

  /**
   * Resolves the snapshot into either a TLS endpoint or no TLS at all.
   *
   * @param configuration the resolved snapshot, never null
   * @return the configured endpoint, or null when no keystore is named — plaintext serving, unchanged from before this
   *     class existed
   * @throws ConfigurationException if a keystore is named without a password, or cannot be opened with the one supplied
   */
  static Tls from(Configuration configuration) {
    Objects.requireNonNull(configuration, "the configuration to read the TLS keys from; with none there is nothing"
        + " to resolve");
    Optional<String> path = configuration.get(KEYSTORE_PATH);
    if (path.isEmpty() || path.get().isBlank()) {
      return null;
    }
    Optional<String> password = configuration.get(KEYSTORE_PASSWORD);
    if (password.isEmpty() || password.get().isEmpty()) {
      throw new ConfigurationException("\"" + KEYSTORE_PATH.name() + "\" names a keystore but \""
          + KEYSTORE_PASSWORD.name() + "\" supplies no password. A keystore opened without one fails the same way as"
          + " one opened with the wrong one, and the operator cannot tell the two apart from the JDK's message");
    }
    Path keystore = Path.of(path.get());
    try {
      // The JDK's default type is what its own keytool writes, so a deployment's keystore and this reader agree
      // without a key nobody would think to set.
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      char[] secret = password.get().toCharArray();
      try (InputStream input = Files.newInputStream(keystore)) {
        store.load(input, secret);
      }
      KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keys.init(store, secret);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(keys.getKeyManagers(), null, null);
      return new Tls(context, keystore);
    } catch (IOException | GeneralSecurityException unusable) {
      throw new ConfigurationException("\"" + KEYSTORE_PATH.name() + "\" points at " + keystore
          + ", which this server cannot open as a keystore: " + unusable.getClass().getName() + ": "
          + unusable.getMessage() + ". The password is not echoed here because it is not the part an operator can"
          + " check by reading a log", unusable);
    }
  }

  /**
   * Opens the server this endpoint needs: an {@link HttpsServer} when TLS is configured, the plain {@code HttpServer}
   * when it is not.
   *
   * <p>Called by {@link JdkHttpServerAdapter#start()} on the plaintext path with {@code null}, which is why this is a
   * static factory rather than a method on an instance — the decision "which JDK server class" cannot be made by an
   * object that only exists after the choice.
   */
  static com.sun.net.httpserver.HttpServer openServer(java.net.InetSocketAddress bind, int backlog, Tls tls)
      throws IOException {
    return tls == null
        ? com.sun.net.httpserver.HttpServer.create(bind, backlog)
        : HttpsServer.create(bind, backlog);
  }

  /** @return the keystore this endpoint was built from, named in the startup log in place of any credential */
  Path keystore() {
    return keystore;
  }

  /**
   * Applies this endpoint to a freshly created server.
   *
   * <p>The parameters are left at the JDK's defaults — no client authentication requested, no protocol override — and
   * the context is the one built from the keystore, so a handshake that fails fails in the JDK's own terms with the
   * certificate this server was given.
   */
  void apply(HttpsServer server) {
    // The base configurator, unsubclassed: it supplies the context and sets nothing else, which is the JDK's own
    // default parameters. An anonymous subclass would also be a non-final type in this package, and every
    // package-private class here is final because none of them is an extension point (ADR-0022 §9, ADR-0050).
    server.setHttpsConfigurator(new HttpsConfigurator(context));
  }
}
