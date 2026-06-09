package io.github.eschizoid.telescope.demo.spring.bughunt.jpacycle;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

/**
 * Hibernate-managed twin of {@link Employee} with a bidirectional self-reference: {@code manager}
 * is a LAZY {@code @ManyToOne}, {@code reports} is the inverse {@code @OneToMany}. Together they
 * form a literal value-level cycle once Hibernate populates both sides ({@code bob.manager == alice
 * && alice.reports.contains(bob)}).
 *
 * <p>Intentionally <b>NOT</b> annotated with {@code @BeanFocus} — same reason as {@link Employee}.
 * The runtime mapper path is the unit under test.
 */
@Entity
@Table(name = "employee")
public class EmployeeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String name;

  @ManyToOne(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
  @JoinColumn(name = "manager_id")
  private EmployeeEntity manager;

  @OneToMany(mappedBy = "manager", fetch = FetchType.LAZY)
  private List<EmployeeEntity> reports = new ArrayList<>();

  public EmployeeEntity() {}

  public EmployeeEntity(final String name) {
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public EmployeeEntity getManager() {
    return manager;
  }

  public void setManager(final EmployeeEntity manager) {
    this.manager = manager;
  }

  public List<EmployeeEntity> getReports() {
    return reports;
  }

  public void setReports(final List<EmployeeEntity> reports) {
    this.reports = reports;
  }
}
