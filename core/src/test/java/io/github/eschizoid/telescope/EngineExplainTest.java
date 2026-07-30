package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.Edit.over;
import static io.github.eschizoid.telescope.mapping.MapExtractStep.extract;
import static io.github.eschizoid.telescope.mapping.MergeStep.auto;
import static io.github.eschizoid.telescope.mapping.MergeStep.from;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.eschizoid.telescope.introspection.OpticNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins that every engine — not just the deep mapper — surfaces its build-time decisions through
 * {@code explain()}. Before this, fromMap, merge, and every multi-edit normalizer explained as
 * empty while the deep mapper promised "the report cannot drift from what the mapper does".
 */
class EngineExplainTest {

  record Payment(String id, String currency, int retries) {}

  record Customer(String id, String email) {}

  record Audit(String createdBy, String createdAt) {}

  record Profile(String id, String email, String createdBy, String note) {}

  @Nested
  @DisplayName("fromMap explains its slot decisions")
  class FromMapExplain {

    @Test
    @DisplayName("one Transformed row per extract, one MISSING_SOURCE skip per defaulted slot")
    void slotDecisionsSurface() {
      final var mapper = Telescope.fromMap(
        Payment.class,
        extract("payment_id", Payment::id, Object::toString),
        extract("ccy", Payment::currency, Object::toString)
      );

      final var report = mapper.explain();
      assertFalse(report.isEmpty(), "fromMap must explain itself");
      assertEquals(
        List.of(new OpticNode.Transformed("payment_id", "id", "map value", "converted")),
        report
          .transformations()
          .stream()
          .filter(t -> t.to().equals("id"))
          .toList()
      );
      assertTrue(
        report.skipped().contains(new OpticNode.Skipped("retries", OpticNode.Reason.MISSING_SOURCE)),
        "the defaulted slot is reported"
      );
    }
  }

  @Nested
  @DisplayName("merge explains its plan")
  class MergeExplain {

    @Test
    @DisplayName("one Mapped row per bound component (SourceClass.field → target), one skip per unbound")
    void planSurfaces() {
      final var mapper = Telescope.merge(
        Profile.class,
        from(Customer::id, Profile::id),
        auto(Customer.class),
        from(Audit::createdBy, Profile::createdBy)
      );

      final var report = mapper.explain();
      assertFalse(report.isEmpty(), "merge must explain itself");
      assertTrue(report.mapped().contains(new OpticNode.Mapped("Customer.id", "id")), report.mapped().toString());
      assertTrue(report.mapped().contains(new OpticNode.Mapped("Customer.email", "email")));
      assertTrue(report.mapped().contains(new OpticNode.Mapped("Audit.createdBy", "createdBy")));
      assertTrue(report.skipped().contains(new OpticNode.Skipped("note", OpticNode.Reason.MISSING_SOURCE)));
    }
  }

  @Nested
  @DisplayName("multi-edit normalizers explain their edits")
  class AllExplain {

    record User(String name, String email) {}

    record Team(String label, List<User> users) {}

    @Test
    @DisplayName("the product's trail is the concatenation of the edits' trails, in edit order")
    void editsTrailsConcatenate() {
      final var names = Telescope.of(Team.class).each(Team::users).field(User::name);
      final var label = Telescope.of(Team.class).field(Team::label);
      final var product = Telescope.all(over(names, String::toUpperCase), over(label, String::trim));

      final var expected = new ArrayList<OpticNode>();
      expected.addAll(names.explain().nodes());
      expected.addAll(label.explain().nodes());
      assertEquals(expected, product.explain().nodes());
    }
  }
}
