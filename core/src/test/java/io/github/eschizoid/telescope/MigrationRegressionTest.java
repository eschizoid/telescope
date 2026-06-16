package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.eschizoid.telescope.mapping.WriteHint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Regressions for the migration-feedback bugs (see {@code docs/migration-feedback.md}). One nested
 * class per bug — keeps the test names traceable back to the feedback entries.
 */
class MigrationRegressionTest {

  @Nested
  @DisplayName("Bug 2 — boolean accessor NPE (P0 blocker)")
  class Bug2BooleanAccessorNpe {

    public static class Order {

      private boolean shipped;
      private String name;

      public boolean isShipped() {
        return shipped;
      }

      public void setShipped(final boolean shipped) {
        this.shipped = shipped;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    public static class OrderDto {

      private boolean shipped;
      private String name;

      public boolean isShipped() {
        return shipped;
      }

      public void setShipped(final boolean shipped) {
        this.shipped = shipped;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    @Test
    @DisplayName("Telescope.mapper(Order, OrderDto) constructs without NPE when boolean accessors are present")
    void mapperConstructionDoesNotNpeOnBooleanAccessors() {
      // The migration feedback reports any class with a boolean primitive field is unusable with
      // Telescope.mapper() / mapperForward() because Beans.propertyOf(null) NPEs in the bean
      // auto-discovery path. The unit-level fix lives in Beans; this test pins the end-to-end
      // contract from the public API surface.
      assertDoesNotThrow(() ->
        Telescope.mapper(Order.class, OrderDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS))
      );
    }

    @Test
    @DisplayName("forward(Order) on a populated source round-trips the boolean field correctly")
    void mapperRoundTripsBooleanField() {
      final var mapper = Telescope.mapper(Order.class, OrderDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new Order();
      src.setShipped(true);
      src.setName("alice");
      final var tgt = mapper.forward(src);
      assertEquals(true, tgt.isShipped());
      assertEquals("alice", tgt.getName());
    }

    public static class OrderWithCustomer {

      private Customer customer;

      public Customer getCustomer() {
        return customer;
      }

      public void setCustomer(final Customer customer) {
        this.customer = customer;
      }
    }

    public static class Customer {

      private String email;

      public String getEmail() {
        return email;
      }

      public void setEmail(final String email) {
        this.email = email;
      }
    }

    public static class FlatOrderDto {

      private String customerEmail;

      public String getCustomerEmail() {
        return customerEmail;
      }

      public void setCustomerEmail(final String customerEmail) {
        this.customerEmail = customerEmail;
      }
    }

    @Test
    @DisplayName("Mapping.to(srcTelescope, tgtAccessor) — nested source path — does not NPE at mapper construction")
    void nestedSourceTelescopeRowConstructsWithoutNpe() {
      // The actual reproduction of Bug 2's reported NPE: `Mapping.to(srcTelescope, tgtAccessor)`
      // builds a FromTelescopeTo row whose `sourceField()` is null by design (the source is a
      // nested telescope, not a flat accessor). DeepMap.populateIso normalizes the source field
      // unconditionally before the FromTelescopeTo `instanceof` peel, so `Beans.normalize(null)`
      // → `Beans.propertyOf(null)` would NPE without the defensive guard.
      assertDoesNotThrow(() ->
        Telescope.mapper(
          OrderWithCustomer.class,
          FlatOrderDto.class,
          io.github.eschizoid.telescope.mapping.Mapping.to(
            Telescope.ofBean(OrderWithCustomer.class).field(OrderWithCustomer::getCustomer).field(Customer::getEmail),
            FlatOrderDto::getCustomerEmail
          ),
          writeBeans(WriteHint.WriteStrategy.SETTERS)
        )
      );
    }
  }

  @Nested
  @DisplayName("Bug 4 — NPE on null intermediate objects in nested telescope paths")
  class Bug4NullIntermediateNpe {

    public static class Order {

      private Customer customer; // may be null

      public Customer getCustomer() {
        return customer;
      }

      public void setCustomer(final Customer customer) {
        this.customer = customer;
      }
    }

    public static class Customer {

      private String email;

      public String getEmail() {
        return email;
      }

      public void setEmail(final String email) {
        this.email = email;
      }
    }

    public static class OrderDto {

      private String customerEmail;

      public String getCustomerEmail() {
        return customerEmail;
      }

      public void setCustomerEmail(final String customerEmail) {
        this.customerEmail = customerEmail;
      }
    }

    @Test
    @DisplayName("forward(order) on a source whose nested intermediate is null short-circuits to null instead of NPE")
    void forwardWithNullIntermediateShortCircuitsToNull() {
      // The adopter's exact scenario: a 2-hop nested source path order → customer → email, with
      // order.customer == null at runtime. Before the fix, the second hop calls
      // Beans.readProperty(null, "email") which delegates to persistentClassOf(null) → null,
      // then ClassValue.get(null) NPEs. After the fix, readProperty short-circuits to null and
      // the optic pipeline propagates the null through the rest of the chain.
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        io.github.eschizoid.telescope.mapping.Mapping.to(
          Telescope.ofBean(Order.class).field(Order::getCustomer).field(Customer::getEmail),
          OrderDto::getCustomerEmail
        ),
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new Order(); // customer left null
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(null, tgt.getCustomerEmail());
    }

    @Test
    @DisplayName("forward(order) on a populated nested intermediate still reads through the chain")
    void forwardWithPopulatedIntermediateReadsThrough() {
      final var mapper = Telescope.mapper(
        Order.class,
        OrderDto.class,
        io.github.eschizoid.telescope.mapping.Mapping.to(
          Telescope.ofBean(Order.class).field(Order::getCustomer).field(Customer::getEmail),
          OrderDto::getCustomerEmail
        ),
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var customer = new Customer();
      customer.setEmail("alice@example.com");
      final var src = new Order();
      src.setCustomer(customer);
      final var tgt = mapper.forward(src);
      assertEquals("alice@example.com", tgt.getCustomerEmail());
    }
  }

  @Nested
  @DisplayName("Bug 6 — DeepMap strict bijection on nested auto-recursed types")
  class Bug6NestedStrictBijection {

    public static class ScoreResponse {

      private int firstNameScore;
      private int lastNameScore;

      public int getFirstNameScore() {
        return firstNameScore;
      }

      public void setFirstNameScore(final int v) {
        this.firstNameScore = v;
      }

      public int getLastNameScore() {
        return lastNameScore;
      }

      public void setLastNameScore(final int v) {
        this.lastNameScore = v;
      }
    }

    public static class ScoreResponseDto {

      private int firstNameScore;
      private int lastNameScore;
      private String matchingStatus; // extra field, not on source

      public int getFirstNameScore() {
        return firstNameScore;
      }

      public void setFirstNameScore(final int v) {
        this.firstNameScore = v;
      }

      public int getLastNameScore() {
        return lastNameScore;
      }

      public void setLastNameScore(final int v) {
        this.lastNameScore = v;
      }

      public String getMatchingStatus() {
        return matchingStatus;
      }

      public void setMatchingStatus(final String v) {
        this.matchingStatus = v;
      }
    }

    public static class Parent {

      private ScoreResponse scores;

      public ScoreResponse getScores() {
        return scores;
      }

      public void setScores(final ScoreResponse scores) {
        this.scores = scores;
      }
    }

    public static class ParentDto {

      private ScoreResponseDto scores;

      public ScoreResponseDto getScores() {
        return scores;
      }

      public void setScores(final ScoreResponseDto scores) {
        this.scores = scores;
      }
    }

    @Test
    @DisplayName("nested auto-recursed pair with asymmetric fields is lenient — top-level construction succeeds")
    void nestedAsymmetricPairConstructsLeniently() {
      // The adopter's exact scenario: top-level pair (Parent, ParentDto) auto-recurses into
      // (ScoreResponse, ScoreResponseDto). The nested target has `matchingStatus` with no
      // same-name source. Strict bijection on every recursed pair throws at construction time.
      // After fix, nested pairs are lenient — unmatched target fields stay at JLS defaults,
      // unmatched source fields are silently dropped. Only the TOP-LEVEL pair (here: Parent →
      // ParentDto) enforces strictness.
      final var mapper = Telescope.mapper(Parent.class, ParentDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var inner = new ScoreResponse();
      inner.setFirstNameScore(80);
      inner.setLastNameScore(90);
      final var src = new Parent();
      src.setScores(inner);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(80, tgt.getScores().getFirstNameScore());
      assertEquals(90, tgt.getScores().getLastNameScore());
      // Nested target's unmatched field stays at the JLS default.
      assertEquals(null, tgt.getScores().getMatchingStatus());
    }

    @Test
    @DisplayName("top-level pair with asymmetric fields still throws — strictness preserved at the top")
    void topLevelAsymmetricPairStillThrows() {
      // Strictness at the top-level call (the user's explicit request) is preserved — the user
      // asked for this pair and a missing field is a real configuration mistake. Only nested
      // recursion auto-relaxes.
      org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
        Telescope.mapper(ScoreResponse.class, ScoreResponseDto.class, writeBeans(WriteHint.WriteStrategy.SETTERS))
      );
    }
  }

  @Nested
  @DisplayName("Bug 3 — primitive ↔ wrapper autoboxing")
  class Bug3PrimitiveWrapperAutoboxing {

    public static class Source {

      private boolean active; // primitive
      private Integer count; // boxed
      private String name;

      public boolean isActive() {
        return active;
      }

      public void setActive(final boolean v) {
        this.active = v;
      }

      public Integer getCount() {
        return count;
      }

      public void setCount(final Integer v) {
        this.count = v;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    public static class Target {

      private Boolean active; // boxed (source is primitive)
      private int count; // primitive (source is boxed)
      private String name;

      public Boolean getActive() {
        return active;
      }

      public void setActive(final Boolean v) {
        this.active = v;
      }

      public int getCount() {
        return count;
      }

      public void setCount(final int v) {
        this.count = v;
      }

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    @Test
    @DisplayName("auto-mapping handles primitive↔wrapper pairs (boolean↔Boolean, Integer↔int) without throwing")
    void primitiveWrapperPairsAreAutoBoxed() {
      // The adopter scenario: source has boolean primitive `active` and boxed `Integer count`;
      // target inverts them. DeepMap used to reject the pair at populateIso with
      // "incompatible source/target shapes — boolean vs java.lang.Boolean". MapStruct silently
      // autoboxes / unboxes; telescope should match.
      final var mapper = Telescope.mapper(Source.class, Target.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new Source();
      src.setActive(true);
      src.setCount(42);
      src.setName("alice");
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(Boolean.TRUE, tgt.getActive());
      assertEquals(42, tgt.getCount());
      assertEquals("alice", tgt.getName());
    }

    @Test
    @DisplayName("null boxed source mapping to primitive target uses JLS default (no unboxing NPE)")
    void nullBoxedToPrimitiveUsesJlsDefault() {
      // Source.count is Integer null; target.count is int. The auto-iso must null-guard at the
      // boxing leaf, otherwise we get NullPointerException on intValue().
      final var mapper = Telescope.mapper(Source.class, Target.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new Source();
      src.setActive(false);
      // count left null
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(0, tgt.getCount()); // JLS default for int
    }
  }

  @Nested
  @DisplayName("Bug 7 — LMF fails on classes extending JDK collection types")
  class Bug7JdkCollectionSubtypes {

    public static class ImageUrl {

      private String url;

      public String getUrl() {
        return url;
      }

      public void setUrl(final String url) {
        this.url = url;
      }
    }

    // Custom collection wrapper — common in legacy bean codebases. Extends ArrayList so it
    // inherits a long tail of platform-module getters (isEmpty, getClass, etc.) that telescope
    // used to try to LMF-bind, hitting java.base private-lookup rejection.
    public static class ImageUrls extends java.util.ArrayList<ImageUrl> {

      @java.io.Serial
      private static final long serialVersionUID = 1L;
    }

    public static class DocumentData {

      private ImageUrls imageUrls;

      public ImageUrls getImageUrls() {
        return imageUrls;
      }

      public void setImageUrls(final ImageUrls imageUrls) {
        this.imageUrls = imageUrls;
      }
    }

    public static class DocumentDataDto {

      private ImageUrls imageUrls;

      public ImageUrls getImageUrls() {
        return imageUrls;
      }

      public void setImageUrls(final ImageUrls imageUrls) {
        this.imageUrls = imageUrls;
      }
    }

    @Test
    @DisplayName(
      "auto-recursion through a Collection subtype does NOT try to bean-decompose it (pass-through by reference)"
    )
    void collectionSubtypeIsPassThrough() {
      // Adopter scenario: a bean graph contains a custom collection wrapper class extending
      // ArrayList. Without the fix, DeepMap recurses into ImageUrls trying to bean-decompose
      // it, discovers inherited `isEmpty()` etc. as "getters", then LMF-binds them — which
      // fails with "Invalid caller: java.util.ArrayList" because java.base modules don't
      // grant private lookup to application code. Fix: treat Collection/Map subtypes as
      // scalars (pass-through by reference), and skip inherited platform-module methods in
      // scanGetters as defence-in-depth.
      final var mapper = Telescope.mapper(
        DocumentData.class,
        DocumentDataDto.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var urls = new ImageUrls();
      final var u = new ImageUrl();
      u.setUrl("https://example.com/a.png");
      urls.add(u);
      final var src = new DocumentData();
      src.setImageUrls(urls);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      // Pass-through by reference: same ImageUrls instance, same element.
      assertEquals(urls, tgt.getImageUrls());
      assertEquals("https://example.com/a.png", tgt.getImageUrls().get(0).getUrl());
    }

    // Adversarial reproduction — same-type shortcut at autoIso line 803 hides Bug 7 when the
    // field is identical on both sides. Force different types so DeepMap.computeAutoIso falls
    // through to the bean-recursion branch where `isReflectable` decides whether to descend.
    public static class ImageUrlsAlt extends java.util.ArrayList<ImageUrl> {

      @java.io.Serial
      private static final long serialVersionUID = 1L;
    }

    public static class DocumentDataAlt {

      private ImageUrls imageUrls; // source type

      public ImageUrls getImageUrls() {
        return imageUrls;
      }

      public void setImageUrls(final ImageUrls imageUrls) {
        this.imageUrls = imageUrls;
      }
    }

    public static class DocumentDataDtoAlt {

      private ImageUrlsAlt imageUrls; // target type — different subclass

      public ImageUrlsAlt getImageUrls() {
        return imageUrls;
      }

      public void setImageUrls(final ImageUrlsAlt imageUrls) {
        this.imageUrls = imageUrls;
      }
    }

    @Test
    @DisplayName("cross-Collection-subtype field copies elements via target's no-arg ctor + addAll")
    void differentCollectionSubtypesAreCopiedViaAddAll() {
      // ImageUrls vs ImageUrlsAlt — both extend ArrayList<ImageUrl>. The same-type shortcut at
      // autoIso doesn't fire (different concrete classes). The fix routes this through
      // `collectionCopyIso`: instantiate the target type via its no-arg ctor and addAll(source).
      // End-to-end the mapper produces an ImageUrlsAlt instance carrying the source's elements.
      final var mapper = Telescope.mapper(
        DocumentDataAlt.class,
        DocumentDataDtoAlt.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var urls = new ImageUrls();
      final var u = new ImageUrl();
      u.setUrl("https://example.com/a.png");
      urls.add(u);
      final var src = new DocumentDataAlt();
      src.setImageUrls(urls);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(ImageUrlsAlt.class, tgt.getImageUrls().getClass());
      assertEquals(1, tgt.getImageUrls().size());
      assertEquals("https://example.com/a.png", tgt.getImageUrls().get(0).getUrl());
    }
  }

  @Nested
  @DisplayName("Bug 8 — SettersWriter NPE on null primitive value")
  class Bug8PrimitiveSetterNullNpe {

    public static class Source {

      private String name;

      // No `count` property — after Bug 6 lenient nested mode, valueByName returns null for the
      // target's `count` field.

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }
    }

    public static class Target {

      private String name;
      private int count; // primitive, will receive null from valueByName

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public int getCount() {
        return count;
      }

      public void setCount(final int count) {
        this.count = count;
      }
    }

    public static class TopSource {

      private Source nested;

      public Source getNested() {
        return nested;
      }

      public void setNested(final Source nested) {
        this.nested = nested;
      }
    }

    public static class TopTarget {

      private Target nested;

      public Target getNested() {
        return nested;
      }

      public void setNested(final Target nested) {
        this.nested = nested;
      }
    }

    @Test
    @DisplayName("forward(top) where nested target has primitive setter for an unmatched field uses JLS default")
    void primitiveSetterReceivesJlsDefaultOnNullValue() {
      // After Bug 6 + Bug 5 fixes, a nested target property like `int count` whose source has
      // no matching `count` field receives `null` from valueByName. SettersWriter.construct used
      // to NPE when passing that null to `setCount(int)` (Integer-to-int unboxing on null).
      // Fix: null-guard primitive setters at construction time so primitives stay at their JLS
      // default (0 for int, false for boolean, etc.).
      final var mapper = Telescope.mapper(
        TopSource.class,
        TopTarget.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new TopSource();
      final var inner = new Source();
      inner.setName("alice");
      src.setNested(inner);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals("alice", tgt.getNested().getName());
      // Target's primitive int stays at JLS default 0 (source had no `count`).
      assertEquals(0, tgt.getNested().getCount());
    }

    public static class FlatSource {

      private String name;
      private Integer count; // boxed; will hold null at runtime
      private Boolean active; // boxed; will hold null at runtime

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public Integer getCount() {
        return count;
      }

      public void setCount(final Integer count) {
        this.count = count;
      }

      public Boolean getActive() {
        return active;
      }

      public void setActive(final Boolean active) {
        this.active = active;
      }
    }

    public static class FlatTarget {

      private String name;
      private Integer count; // matching: boxed → boxed (no autoboxing needed)
      private Boolean active; // matching: boxed → boxed

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public Integer getCount() {
        return count;
      }

      public void setCount(final Integer count) {
        this.count = count;
      }

      public Boolean getActive() {
        return active;
      }

      public void setActive(final Boolean active) {
        this.active = active;
      }
    }

    @Test
    @DisplayName("forward(src) with null boxed source values reaching matching boxed setters does not NPE")
    void nullBoxedSourceReachesBoxedSetterWithoutNpe() {
      // Even on matched-name boxed→boxed paths, the source value can legitimately be null and
      // the target setter must accept null gracefully. Pins the contract that the construct
      // loop doesn't choke on legitimately-null boxed values.
      final var mapper = Telescope.mapper(
        FlatSource.class,
        FlatTarget.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new FlatSource();
      src.setName("alice");
      // count and active left null
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals("alice", tgt.getName());
      assertEquals(null, tgt.getCount());
      assertEquals(null, tgt.getActive());
    }
  }

  @Nested
  @DisplayName("Bug 5 — SettersWriter throws on getter-only properties")
  class Bug5GetterOnlyProperties {

    // Both Source and OrderResponse have a `processed` property so DeepMap's same-name bijection
    // check (Bug 6) passes — we want to isolate Bug 5 (SettersWriter on the getter-only target).
    public static class Source {

      private String name;
      private boolean processed;

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      public boolean isProcessed() {
        return processed;
      }

      public void setProcessed(final boolean processed) {
        this.processed = processed;
      }
    }

    public static class OrderResponse {

      private String name;
      private boolean processed;

      public String getName() {
        return name;
      }

      public void setName(final String name) {
        this.name = name;
      }

      // Getter-only — no setProcessed() exists. Common shape for computed / derived properties
      // (the field is populated by an internal hook, not by external callers).
      public boolean isProcessed() {
        return processed;
      }
    }

    @Test
    @DisplayName("forward(source) silently skips getter-only target properties — no throw")
    void getterOnlyTargetPropertiesAreSilentlySkipped() {
      // MapStruct silently ignores target fields whose setter is missing. Telescope used to throw
      // IllegalArgumentException("no setter setX") at first forward() call. This pins the new
      // no-op contract: forward() succeeds; the getter-only target property stays at its JLS
      // default (false for boolean primitive).
      final var mapper = Telescope.mapper(
        Source.class,
        OrderResponse.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new Source();
      src.setName("alice");
      src.setProcessed(true);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals("alice", tgt.getName());
      // The `processed` source value was discarded — target's getter-only field stays at the
      // primitive default. MapStruct would do the same.
      assertEquals(false, tgt.isProcessed());
    }
  }
}
