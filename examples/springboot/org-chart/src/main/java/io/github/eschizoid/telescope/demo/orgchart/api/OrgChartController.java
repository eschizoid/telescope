package io.github.eschizoid.telescope.demo.orgchart.api;

import io.github.eschizoid.telescope.Mapper;
import io.github.eschizoid.telescope.demo.orgchart.domain.Employee;
import io.github.eschizoid.telescope.demo.orgchart.persistence.EmployeeEntity;
import io.github.eschizoid.telescope.demo.orgchart.persistence.EmployeeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two endpoints driving the self-referencing {@code Mapper<Employee, EmployeeEntity>}:
 *
 * <ul>
 *   <li>{@code POST /org-chart} — accept a {@link Employee} record, forward-map to {@link
 *       EmployeeEntity}, save via JPA, and return the canonical record back. Cascade-persist walks
 *       the manager chain end-to-end.
 *   <li>{@code GET /org-chart/{id}} — load by id, force the bidirectional graph to materialise
 *       inside the transactional boundary (so reports + manager are both populated), then
 *       backward-map to {@link Employee}. This is the value-cycle severance path — DeepMap's
 *       per-traversal IdentityHashMap seen-set keeps the cycle from blowing the stack.
 * </ul>
 */
@RestController
@RequestMapping("/org-chart")
public class OrgChartController {

  private final Mapper<Employee, EmployeeEntity> employeeMapper;
  private final EmployeeRepository repository;

  @PersistenceContext
  private EntityManager em;

  public OrgChartController(
    final Mapper<Employee, EmployeeEntity> employeeMapper,
    final EmployeeRepository repository
  ) {
    this.employeeMapper = employeeMapper;
    this.repository = repository;
  }

  @PostMapping
  @Transactional
  public ResponseEntity<Employee> create(@RequestBody final Employee request) {
    final var entity = employeeMapper.forward(request);
    final var saved = repository.save(entity);
    em.flush();
    return ResponseEntity.ok(employeeMapper.backward(saved));
  }

  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  public ResponseEntity<Employee> get(@PathVariable final Long id) {
    return repository
      .findById(id)
      .map(entity -> {
        // Touch manager + reports inside the transaction so Hibernate stitches the
        // bidirectional
        // graph before backward-map runs. Without this the LAZY associations resolve to
        // proxies
        // and the value cycle wouldn't even be reachable — defeating the demo's purpose.
        if (entity.getManager() != null) entity.getManager().getName();
        entity.getReports().size();
        return employeeMapper.backward(entity);
      })
      .map(ResponseEntity::ok)
      .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
