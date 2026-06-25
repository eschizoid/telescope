package io.github.eschizoid.telescope.codegen;

import static io.github.eschizoid.telescope.codegen.ProcessorHarness.source;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FromMapCrossPkgAuditTest {

  @Test
  void crossPackageDirectNested() {
    final var c = ProcessorHarness.compile(
      new FromMapProcessor(),
      source(
        "demo.Profile",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.FromMap;
        import other.Address;
        @FromMap
        public record Profile(String handle, Address address) {}
        """
      ),
      source(
        "other.Address",
        """
        package other;
        import io.github.eschizoid.telescope.annotations.FromMap;
        @FromMap
        public record Address(String city) {}
        """
      )
    );
    final var gen = c.generated().get("demo.ProfileFromMap");
    System.out.println("=== DIRECT NESTED demo.ProfileFromMap ===\n" + gen);
    assertTrue(c.success(), () -> "DIRECT FAILED: " + c.errorMessages());
  }

  @Test
  void crossPackageListNested() {
    final var c = ProcessorHarness.compile(
      new FromMapProcessor(),
      source(
        "demo.Team",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.FromMap;
        import java.util.List;
        import other.Address;
        @FromMap
        public record Team(String n, List<Address> sites) {}
        """
      ),
      source(
        "other.Address",
        """
        package other;
        import io.github.eschizoid.telescope.annotations.FromMap;
        @FromMap
        public record Address(String city) {}
        """
      )
    );
    final var gen = c.generated().get("demo.TeamFromMap");
    System.out.println("=== LIST NESTED demo.TeamFromMap ===\n" + gen);
    assertTrue(c.success(), () -> "LIST FAILED: " + c.errorMessages());
  }

  @Test
  void crossPackageMapValueNested() {
    final var c = ProcessorHarness.compile(
      new FromMapProcessor(),
      source(
        "demo.Org",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.FromMap;
        import java.util.Map;
        import other.Address;
        @FromMap
        public record Org(Map<String, Address> byId) {}
        """
      ),
      source(
        "other.Address",
        """
        package other;
        import io.github.eschizoid.telescope.annotations.FromMap;
        @FromMap
        public record Address(String city) {}
        """
      )
    );
    final var gen = c.generated().get("demo.OrgFromMap");
    System.out.println("=== MAP NESTED demo.OrgFromMap ===\n" + gen);
    assertTrue(c.success(), () -> "MAP FAILED: " + c.errorMessages());
  }

  @Test
  void crossPackageOptionalNested() {
    final var c = ProcessorHarness.compile(
      new FromMapProcessor(),
      source(
        "demo.Acct",
        """
        package demo;
        import io.github.eschizoid.telescope.annotations.FromMap;
        import java.util.Optional;
        import other.Address;
        @FromMap
        public record Acct(Optional<Address> home) {}
        """
      ),
      source(
        "other.Address",
        """
        package other;
        import io.github.eschizoid.telescope.annotations.FromMap;
        @FromMap
        public record Address(String city) {}
        """
      )
    );
    final var gen = c.generated().get("demo.AcctFromMap");
    System.out.println("=== OPTIONAL NESTED demo.AcctFromMap ===\n" + gen);
    assertTrue(c.success(), () -> "OPTIONAL FAILED: " + c.errorMessages());
  }
}
