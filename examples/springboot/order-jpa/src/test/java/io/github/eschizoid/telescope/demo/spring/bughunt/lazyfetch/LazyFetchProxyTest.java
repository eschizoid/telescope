package io.github.eschizoid.telescope.demo.spring.bughunt.lazyfetch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eschizoid.telescope.conversion.Mapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Drives the LAZY-fetch bughunt slice. Three assertions, each pinning a different facet:
 *
 * <ol>
 *   <li>HTTP round-trip preserves shape end-to-end. Baseline — without this we can't trust the
 *       deeper assertions.
 *   <li>Inside a transaction, loading the entity returns a {@link HibernateProxy} for the lazy
 *       author. Telescope's bean reflection will key on this proxy class, NOT on {@link
 *       AuthorEntity}. Assert the runtime class identity so a future fix routing through {@code
 *       HibernateProxyHelper.getClassWithoutInitializingProxy} is visible as a behaviour change.
 *   <li>{@link Mapper#backward(Object)} on the proxy-bearing entity initializes the proxy exactly
 *       once. Hibernate {@link Statistics#getEntityFetchCount()} is the witness — if telescope
 *       triggers more than one fetch the proxy is being walked through the structural-iso loop more
 *       than once (caching regression).
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LazyFetchProxyTest {

  @LocalServerPort
  private int port;

  @Autowired
  private Mapper<Document, LazyDocumentEntity> documentMapper;

  @Autowired
  private LazyDocumentRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @PersistenceContext
  private EntityManager entityManager;

  private RestClient client;

  @BeforeEach
  void setUp() {
    this.client = RestClient.create("http://localhost:" + port);
  }

  @Test
  void postRoundTripPreservesShape() {
    final var sent = new Document(null, "RFC-0001", new Author(null, "Ada Lovelace"));

    final var got = client
      .post()
      .uri("/bughunt/docs")
      .contentType(MediaType.APPLICATION_JSON)
      .body(sent)
      .retrieve()
      .body(Document.class);

    assertThat(got).isNotNull();
    assertThat(got.id()).isNotNull();
    assertThat(got.title()).isEqualTo("RFC-0001");
    assertThat(got.author()).isNotNull();
    assertThat(got.author().id()).isNotNull();
    assertThat(got.author().name()).isEqualTo("Ada Lovelace");
  }

  @Test
  @Transactional
  void lazyAuthorIsHibernateProxyBeforeTouch() {
    final var saved = repository.save(seed("RFC-0002", "Grace Hopper"));
    entityManager.flush();
    entityManager.clear();

    final var reloaded = repository.findById(saved.getId()).orElseThrow();
    final var lazyAuthor = reloaded.getAuthor();

    // Pin the proxy: runtime class is a HibernateProxy subclass, NOT AuthorEntity itself.
    // Telescope's Beans.readProperty / MetadataHolderProbe.probeFor(...) cache keys this class.
    assertThat(lazyAuthor).isInstanceOf(HibernateProxy.class);
    assertThat(lazyAuthor.getClass()).isNotEqualTo(AuthorEntity.class);
    assertThat(Hibernate.isInitialized(lazyAuthor)).isFalse();

    // The would-be metadata holder for the proxy class doesn't exist on the classpath — codegen
    // only emits <X>Telescope for the @BeanFocus-annotated class, not for Hibernate-generated
    // bytecode subclasses. So a hypothetical dispatch site keying the probe by `proxy.getClass()`
    // would miss the holder and fall back to LMF-shaped reflection on the proxy class. This
    // does not currently bite because the deep-map source class is the configured AuthorEntity,
    // not the runtime proxy class — but any future code path that probes by runtime class would
    // silently lose the codegen fast path on every proxy encounter.
    final var holderClassName = lazyAuthor.getClass().getName() + "Telescope";
    final var holderForProxy = classOrNull(holderClassName);
    final var holderForEntity = classOrNull(AuthorEntity.class.getName() + "Telescope");
    assertThat(holderForProxy).as("no holder is emitted for the runtime proxy class").isNull();
    assertThat(holderForEntity).as("holder IS present for the configured entity class").isNotNull();
  }

  private static Class<?> classOrNull(final String fqn) {
    try {
      return Class.forName(fqn);
    } catch (final ClassNotFoundException e) {
      return null;
    }
  }

  @Test
  void backwardOnProxyInitializesAuthorExactlyOnce() {
    // Seed in its own transaction, then count fetches over a fresh load + telescope backward call.
    final var savedId = transactionTemplate.execute(status -> {
      final var s = repository.save(seed("RFC-0003", "Margaret Hamilton"));
      entityManager.flush();
      return s.getId();
    });

    final var stats = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    final var wasEnabled = stats.isStatisticsEnabled();
    stats.setStatisticsEnabled(true);
    stats.clear();

    final long fetchesBefore = stats.getEntityFetchCount();
    final var result = transactionTemplate.execute(status -> {
      final var entity = repository.findById(savedId).orElseThrow();
      // Telescope walks the proxy: backward() reads getAuthor() → forces initialization.
      // Assert the proxy is NOT initialized before the backward() call — pins the
      // "telescope
      // itself triggered the fetch" relationship.
      assertThat(Hibernate.isInitialized(entity.getAuthor())).isFalse();
      return documentMapper.backward(entity);
    });
    final long fetchesAfter = stats.getEntityFetchCount();
    stats.setStatisticsEnabled(wasEnabled);

    assertThat(result).isNotNull();
    assertThat(result.author().name()).isEqualTo("Margaret Hamilton");
    // One initialization fetch — if telescope walks the proxy more than once (e.g., caches keyed
    // on proxy class re-resolves on every read), this jumps to N.
    assertThat(fetchesAfter - fetchesBefore).as("proxy initialization fetch count during backward()").isEqualTo(1L);
  }

  private static LazyDocumentEntity seed(final String title, final String authorName) {
    final var author = new AuthorEntity();
    author.setName(authorName);
    final var doc = new LazyDocumentEntity();
    doc.setTitle(title);
    doc.setAuthor(author);
    return doc;
  }
}
