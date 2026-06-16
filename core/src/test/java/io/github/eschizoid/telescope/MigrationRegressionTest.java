package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.mapping.Mapping;
import io.github.eschizoid.telescope.mapping.WriteHint;
import java.io.Serial;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;
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
          Mapping.to(
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
        Mapping.to(
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
        Mapping.to(
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
      assertThrows(IllegalStateException.class, () ->
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

    // Broader primitive coverage (long, double, char, short, byte, float) so a future refactor
    // of the wrap()/primitiveDefault tables doesn't silently drop a primitive variant.

    public static class WideSrc {

      private long l;
      private Double d;
      private char c;
      private Short sh;
      private byte by;
      private Float f;

      public long getL() {
        return l;
      }

      public void setL(final long l) {
        this.l = l;
      }

      public Double getD() {
        return d;
      }

      public void setD(final Double d) {
        this.d = d;
      }

      public char getC() {
        return c;
      }

      public void setC(final char c) {
        this.c = c;
      }

      public Short getSh() {
        return sh;
      }

      public void setSh(final Short sh) {
        this.sh = sh;
      }

      public byte getBy() {
        return by;
      }

      public void setBy(final byte by) {
        this.by = by;
      }

      public Float getF() {
        return f;
      }

      public void setF(final Float f) {
        this.f = f;
      }
    }

    public static class WideTgt {

      private Long l; // primitive → boxed
      private double d; // boxed → primitive
      private Character c; // primitive → boxed
      private short sh; // boxed → primitive
      private Byte by; // primitive → boxed
      private float f; // boxed → primitive

      public Long getL() {
        return l;
      }

      public void setL(final Long l) {
        this.l = l;
      }

      public double getD() {
        return d;
      }

      public void setD(final double d) {
        this.d = d;
      }

      public Character getC() {
        return c;
      }

      public void setC(final Character c) {
        this.c = c;
      }

      public short getSh() {
        return sh;
      }

      public void setSh(final short sh) {
        this.sh = sh;
      }

      public Byte getBy() {
        return by;
      }

      public void setBy(final Byte by) {
        this.by = by;
      }

      public float getF() {
        return f;
      }

      public void setF(final float f) {
        this.f = f;
      }
    }

    @Test
    @DisplayName("all 6 remaining primitive ↔ wrapper pairs (long, double, char, short, byte, float) autobox")
    void allPrimitiveVariantsAutoBox() {
      final var mapper = Telescope.mapper(WideSrc.class, WideTgt.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new WideSrc();
      src.setL(99L);
      src.setD(2.5);
      src.setC('z');
      src.setSh((short) 7);
      src.setBy((byte) 3);
      src.setF(1.5f);
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(Long.valueOf(99L), tgt.getL());
      assertEquals(2.5, tgt.getD());
      assertEquals(Character.valueOf('z'), tgt.getC());
      assertEquals((short) 7, tgt.getSh());
      assertEquals(Byte.valueOf((byte) 3), tgt.getBy());
      assertEquals(1.5f, tgt.getF());
    }

    @Test
    @DisplayName("null boxed source values to primitive targets use JLS defaults for every primitive variant")
    void nullBoxedToPrimitiveDefaultsForEveryPrimitive() {
      final var mapper = Telescope.mapper(WideSrc.class, WideTgt.class, writeBeans(WriteHint.WriteStrategy.SETTERS));
      final var src = new WideSrc();
      // src.d, src.sh, src.f left null
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(0.0, tgt.getD());
      assertEquals((short) 0, tgt.getSh());
      assertEquals(0.0f, tgt.getF());
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
    public static class ImageUrls extends ArrayList<ImageUrl> {

      @Serial
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
      // Pass-through by reference: same ImageUrls instance, same element. assertSame pins
      // identity — assertEquals would pass on any List with the same contents.
      assertSame(urls, tgt.getImageUrls());
      assertEquals("https://example.com/a.png", tgt.getImageUrls().get(0).getUrl());
    }

    // Adversarial reproduction — the same-generic-type identity shortcut at the top of
    // DeepMap.computeAutoIso hides this bug when the field is identical on both sides. Force
    // different concrete types so computeAutoIso falls through to the same-kind Collection
    // branch where collectionCopyIso decides whether to copy elements via addAll.
    public static class ImageUrlsAlt extends ArrayList<ImageUrl> {

      @Serial
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

    // Fixture pair for the positive same-kind-List acceptance test: both StringList and
    // StringLinkedList implement List, so sameKindCollection accepts the pair and the element-
    // copy Iso runs via the target's no-arg ctor + addAll.
    public static class StringList extends ArrayList<String> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class StringLinkedList extends LinkedList<String> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class HolderList {

      private StringList items;

      public StringList getItems() {
        return items;
      }

      public void setItems(final StringList items) {
        this.items = items;
      }
    }

    public static class HolderLinkedList {

      private StringLinkedList items;

      public StringLinkedList getItems() {
        return items;
      }

      public void setItems(final StringLinkedList items) {
        this.items = items;
      }
    }

    @Test
    @DisplayName("List subtype ↔ List subtype is copied via addAll (positive case for same-kind gate)")
    void listSubtypeVsListSubtypeAccepted() {
      // Both StringList and StringLinkedList implement List — the same-kind gate accepts them.
      // Element order survives via List's contract.
      final var mapper = Telescope.mapper(
        HolderList.class,
        HolderLinkedList.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new HolderList();
      src.setItems(new StringList());
      src.getItems().add("a");
      src.getItems().add("b");
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(StringLinkedList.class, tgt.getItems().getClass());
      assertEquals(List.of("a", "b"), tgt.getItems());
    }

    // Fixture pair for the SortedMap positive case — both sides extend TreeMap so the new
    // sameKindMap discriminator's Sorted-vs-Sorted axis fires the copy Iso.
    public static class StringTreeMap extends TreeMap<String, String> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class StringTreeMapAlt extends TreeMap<String, String> {

      @Serial
      private static final long serialVersionUID = 1L;
    }

    public static class HolderTreeMap {

      private StringTreeMap items;

      public StringTreeMap getItems() {
        return items;
      }

      public void setItems(final StringTreeMap items) {
        this.items = items;
      }
    }

    public static class HolderTreeMapAlt {

      private StringTreeMapAlt items;

      public StringTreeMapAlt getItems() {
        return items;
      }

      public void setItems(final StringTreeMapAlt items) {
        this.items = items;
      }
    }

    @Test
    @DisplayName("SortedMap subtype ↔ SortedMap subtype is copied via putAll (positive case for sameKindMap gate)")
    void sortedMapSubtypeVsSortedMapSubtypeAccepted() {
      // Both extend TreeMap → both are SortedMap → the gate accepts. Forward copies entries
      // verbatim into a fresh instance of the target's concrete class.
      final var mapper = Telescope.mapper(
        HolderTreeMap.class,
        HolderTreeMapAlt.class,
        writeBeans(WriteHint.WriteStrategy.SETTERS)
      );
      final var src = new HolderTreeMap();
      src.setItems(new StringTreeMap());
      src.getItems().put("a", "alpha");
      src.getItems().put("b", "beta");
      final var tgt = assertDoesNotThrow(() -> mapper.forward(src));
      assertEquals(StringTreeMapAlt.class, tgt.getItems().getClass());
      assertEquals("alpha", tgt.getItems().get("a"));
      assertEquals("beta", tgt.getItems().get("b"));
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
