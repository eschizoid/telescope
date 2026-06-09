package io.github.eschizoid.telescope.demo.orgchart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.eschizoid.telescope.Mapper;
import io.github.eschizoid.telescope.demo.orgchart.domain.Employee;
import io.github.eschizoid.telescope.demo.orgchart.persistence.EmployeeEntity;
import io.github.eschizoid.telescope.demo.orgchart.persistence.EmployeeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pin telescope's deep-mapper behaviour when a JPA-managed self-referencing entity graph (alice CEO
 * → bob → carol IC) is read through {@code Mapper<Employee, EmployeeEntity>}.
 *
 * <p>Two distinct concerns under examination:
 *
 * <ol>
 *   <li><b>Type-level cycle resolution at {@code Telescope.mapper(...)} construction time.</b>
 *       {@link io.github.eschizoid.telescope.DeepMap} reserves the TypePair slot before recursing
 *       into auto-derived component Isos, so {@code Employee} containing {@code Optional<Employee>
 *       manager} + {@code List<Employee> reports} resolves without stack-overflow.
 *   <li><b>Value-level traversal at {@code mapper.backward(entityFromDb)} time.</b> Hibernate
 *       populates bidirectional associations on hydration: once bob is initialized, {@code
 *       bob.getManager() == alice} and {@code alice.getReports().contains(bob)}, a literal
 *       value-cycle. A per-traversal {@code IdentityHashMap} seen-set in {@code DeepMap.cycleSafe}
 *       severs the cycle on re-entry, yielding {@code null} (lifted to {@code Optional.empty()} for
 *       {@code Optional<Employee>} parents). The first level of every association resolves; deeper
 *       hops collapse to empty.
 * </ol>
 */
@SpringBootTest
@Transactional
class JpaCycleMappingTest {

  @Autowired
  private Mapper<Employee, EmployeeEntity> employeeMapper;

  @Autowired
  private EmployeeRepository employeeRepository;

  @PersistenceContext
  private EntityManager entityManager;

  /**
   * Construction sanity check: building the mapper for a self-referencing record type must not
   * stack-overflow during {@code DeepMap.populateIso}'s recursive walk over components. This is the
   * load-bearing TypePair cache assertion.
   */
  @Test
  void mapperConstructionForSelfReferencingTypeDoesNotStackOverflow() {
    assertThat(employeeMapper).isNotNull();
  }

  /**
   * Forward direction with a one-way record chain (carol → bob → alice → empty, no reports
   * populated). No value-cycle in the record graph; should succeed.
   */
  @Test
  void forwardMappingOneWayRecordChainSucceeds() {
    final var alice = new Employee(null, "Alice", Optional.empty(), List.of());
    final var bob = new Employee(null, "Bob", Optional.of(alice), List.of());
    final var carol = new Employee(null, "Carol", Optional.of(bob), List.of());

    assertThatCode(() -> {
      final var entity = employeeMapper.forward(carol);
      assertThat(entity).isNotNull();
      assertThat(entity.getName()).isEqualTo("Carol");
      assertThat(entity.getManager()).isNotNull();
      assertThat(entity.getManager().getName()).isEqualTo("Bob");
      assertThat(entity.getManager().getManager()).isNotNull();
      assertThat(entity.getManager().getManager().getName()).isEqualTo("Alice");
    }).doesNotThrowAnyException();
  }

  /**
   * Backward direction with a freshly hydrated Hibernate entity tree whose bidirectional links have
   * been touched (forming a literal value cycle: bob.manager == alice && alice.reports contains
   * bob).
   *
   * <p>The per-traversal {@code IdentityHashMap} seen-set severs re-entry on the same instance, so
   * the top-level record materializes with its first-level associations intact; deeper
   * back-pointers collapse to {@code Optional.empty()} / empty {@code List}.
   */
  @Test
  void backwardMappingHydratedEntityCycleSeversCleanly() {
    final var alice = employeeRepository.saveAndFlush(new EmployeeEntity("Alice"));
    final var bob = new EmployeeEntity("Bob");
    bob.setManager(alice);
    employeeRepository.saveAndFlush(bob);
    final var carol = new EmployeeEntity("Carol");
    carol.setManager(bob);
    final var carolId = employeeRepository.saveAndFlush(carol).getId();

    entityManager.flush();
    entityManager.clear();
    final var carolFromDb = employeeRepository.findById(carolId).orElseThrow();
    // Touch up + down so Hibernate populates the bidirectional reports lists — value cycle gets
    // stitched here: bob.reports[0] = carol, carol.manager = bob, etc. Touching it makes the
    // cycle deterministic across LAZY/EAGER fetch modes.
    carolFromDb.getManager().getManager().getReports().size();
    carolFromDb.getManager().getReports().size();

    final var carolRecord = employeeMapper.backward(carolFromDb);

    assertThat(carolRecord).isNotNull();
    assertThat(carolRecord.name()).isEqualTo("Carol");
    assertThat(carolRecord.manager()).isPresent();
    assertThat(carolRecord.manager().get().name()).isEqualTo("Bob");
  }
}
