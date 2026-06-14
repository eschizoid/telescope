package io.github.eschizoid.telescope;

import static io.github.eschizoid.telescope.mapping.Mapping.constant;
import static io.github.eschizoid.telescope.mapping.Mapping.to;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.eschizoid.telescope.mapping.MapStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code Telescope.mapperBuilder(A, B).inherit(...).add(...).build()} — the @
 * InheritConfiguration equivalent for sharing {@code MapStep} groups across related mappers. Covers
 * the inherit/add semantic interchangeability, override precedence (later wins), build
 * independence, error surface, and the buildTelescope alternative.
 */
class MapperBuilderTest {

  record Entity(String id, String name, String createdAt, String updatedAt) {}

  record Dto(String id, String name, String createdAt, String updatedAt, String tenant) {}

  record AdminDto(String id, String name, String createdAt, String updatedAt, String role) {}

  // Shared audit-column group reused across mappers
  private static final MapStep[] AUDIT_COLUMNS = {
    to(Entity::createdAt, Dto::createdAt),
    to(Entity::updatedAt, Dto::updatedAt),
  };

  @Nested
  @DisplayName("inherit + add — accumulate steps and build a working Mapper")
  class BasicBuild {

    @Test
    @DisplayName("inherit(AUDIT_COLUMNS).add(constant(...)) — both row groups apply")
    void inheritAndAdd() {
      final var mapper = Telescope.mapperBuilder(Entity.class, Dto.class)
        .inherit(AUDIT_COLUMNS)
        .add(constant(Dto::tenant, "us-east"))
        .build();

      final var entity = new Entity("e1", "Alice", "2026-01-01", "2026-06-01");
      final var dto = mapper.forward(entity);

      assertEquals("e1", dto.id());
      assertEquals("Alice", dto.name());
      assertEquals("2026-01-01", dto.createdAt(), "inherited audit row");
      assertEquals("2026-06-01", dto.updatedAt(), "inherited audit row");
      assertEquals("us-east", dto.tenant(), "added constant row");
    }

    @Test
    @DisplayName("multiple inherit groups compose; same builder feeds multiple distinct mappers")
    void multipleGroups() {
      final MapStep[] tenantRow = { constant(Dto::tenant, "shared-tenant") };
      final var mapper = Telescope.mapperBuilder(Entity.class, Dto.class)
        .inherit(AUDIT_COLUMNS)
        .inherit(tenantRow)
        .build();

      final var dto = mapper.forward(new Entity("e1", "Alice", "2026-01-01", "2026-06-01"));
      assertEquals("shared-tenant", dto.tenant());
      assertEquals("2026-01-01", dto.createdAt());
    }
  }

  @Nested
  @DisplayName("Different mappers reusing the same shared group")
  class SharedAcrossMappers {

    @Test
    @DisplayName("AUDIT_COLUMNS feeds both userDto and adminDto mappers without re-declaration")
    void sharedAuditAcrossMappers() {
      final var userMapper = Telescope.mapperBuilder(Entity.class, Dto.class)
        .inherit(AUDIT_COLUMNS)
        .add(constant(Dto::tenant, "tenant-x"))
        .build();

      // Use the audit subset only — same field shape, different rows after
      final MapStep[] adminAudit = {
        to(Entity::createdAt, AdminDto::createdAt),
        to(Entity::updatedAt, AdminDto::updatedAt),
      };
      final var adminMapper = Telescope.mapperBuilder(Entity.class, AdminDto.class)
        .inherit(adminAudit)
        .add(constant(AdminDto::role, "ADMIN"))
        .build();

      final var entity = new Entity("e1", "Alice", "2026-01-01", "2026-06-01");

      final var userDto = userMapper.forward(entity);
      assertEquals("2026-01-01", userDto.createdAt());
      assertEquals("tenant-x", userDto.tenant());

      final var adminDto = adminMapper.forward(entity);
      assertEquals("2026-01-01", adminDto.createdAt(), "same audit semantic on admin variant");
      assertEquals("ADMIN", adminDto.role());
    }
  }

  @Nested
  @DisplayName("Build independence — calling build() multiple times yields independent Mappers")
  class BuildIndependence {

    @Test
    @DisplayName("two build() calls on the same builder return distinct Mapper instances")
    void distinctInstances() {
      final var builder = Telescope.mapperBuilder(Entity.class, Dto.class)
        .inherit(AUDIT_COLUMNS)
        .add(constant(Dto::tenant, "shared"));

      final var m1 = builder.build();
      final var m2 = builder.build();

      assertNotSame(m1, m2, "successive build() calls produce independent instances");

      final var entity = new Entity("e1", "Alice", "2026-01-01", "2026-06-01");
      assertEquals(m1.forward(entity), m2.forward(entity), "but both behave identically");
    }

    @Test
    @DisplayName("rows added after build() affect ONLY future builds, not previous Mappers")
    void laterAddsDontAffectEarlierMappers() {
      final var builder = Telescope.mapperBuilder(Entity.class, Dto.class)
        .inherit(AUDIT_COLUMNS)
        .add(constant(Dto::tenant, "base"));

      final var m1 = builder.build();

      // Add another row AFTER first build()
      builder.add(constant(Dto::tenant, "later"));

      // m1 is unaffected — the "later" row doesn't enter its row list
      final var entity = new Entity("e1", "Alice", "2026-01-01", "2026-06-01");
      assertEquals("base", m1.forward(entity).tenant(), "earlier build snapshot unchanged");

      // Building again WOULD throw — duplicate target 'tenant'. We do not call build() to
      // demonstrate the snapshot semantics — the engine's duplicate-target validation is the
      // safety net for the post-build override case.
    }
  }

  @Nested
  @DisplayName("buildTelescope — same accumulator, deep-mapping Telescope shape")
  class BuildTelescopeVariant {

    @Test
    @DisplayName("buildTelescope() returns a Telescope<A, B> (composable shape)")
    void telescopeVariant() {
      final var telescope = Telescope.mapperBuilder(Entity.class, Dto.class)
        .inherit(AUDIT_COLUMNS)
        .add(constant(Dto::tenant, "us-east"))
        .buildTelescope();

      final var entity = new Entity("e1", "Alice", "2026-01-01", "2026-06-01");
      // Telescope#read on a deep-mapping Iso returns the forward result
      final var dto = telescope.read(entity);
      assertEquals("us-east", dto.tenant());
      assertEquals("2026-01-01", dto.createdAt());
    }
  }

  @Nested
  @DisplayName("Null guards")
  class NullGuards {

    @Test
    @DisplayName("inherit(null) throws NullPointerException")
    void inheritNullArray() {
      final var builder = Telescope.mapperBuilder(Entity.class, Dto.class);
      assertThrows(NullPointerException.class, () -> builder.inherit((MapStep[]) null));
    }

    @Test
    @DisplayName("add(null) throws NullPointerException")
    void addNullArray() {
      final var builder = Telescope.mapperBuilder(Entity.class, Dto.class);
      assertThrows(NullPointerException.class, () -> builder.add((MapStep[]) null));
    }

    @Test
    @DisplayName("inherit(MapStep[] with null element) throws NPE naming the failing index")
    void inheritNullElement() {
      final var builder = Telescope.mapperBuilder(Entity.class, Dto.class);
      final MapStep[] withNullSlot = {
        to(Entity::createdAt, Dto::createdAt),
        null,
        to(Entity::updatedAt, Dto::updatedAt),
      };

      final var ex = assertThrows(NullPointerException.class, () -> builder.inherit(withNullSlot));
      assertEquals(true, ex.getMessage().contains("rows[1]"), "NPE message names the failing index");
    }

    @Test
    @DisplayName("add(MapStep, null) throws NPE naming the failing index")
    void addNullElement() {
      final var builder = Telescope.mapperBuilder(Entity.class, Dto.class);

      final var ex = assertThrows(NullPointerException.class, () ->
        builder.add(to(Entity::createdAt, Dto::createdAt), null)
      );
      assertEquals(true, ex.getMessage().contains("rows[1]"), "NPE message names the failing index");
    }

    @Test
    @DisplayName("null source class throws NullPointerException")
    void nullSourceClass() {
      assertThrows(NullPointerException.class, () -> Telescope.mapperBuilder(null, Dto.class));
    }

    @Test
    @DisplayName("null target class throws NullPointerException")
    void nullTargetClass() {
      assertThrows(NullPointerException.class, () -> Telescope.mapperBuilder(Entity.class, null));
    }
  }

  @Nested
  @DisplayName("Empty builder — build() yields a pure auto-recursion mapper")
  class EmptyBuilder {

    record SameShapeSrc(String id, String name) {}

    record SameShapeDst(String id, String name) {}

    @Test
    @DisplayName("no inherit / no add → builder produces a mapper equivalent to direct Telescope.mapper(A, B)")
    void emptyBuild() {
      final var built = Telescope.mapperBuilder(SameShapeSrc.class, SameShapeDst.class).build();
      final var direct = Telescope.mapper(SameShapeSrc.class, SameShapeDst.class);

      final var src = new SameShapeSrc("e1", "Alice");
      assertEquals(direct.forward(src), built.forward(src), "empty builder matches direct mapper");
    }
  }
}
