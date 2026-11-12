package io.github.eschizoid.telescope.demo.spring.substrate;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.ArrayList;
import java.util.List;

/**
 * An employee and their manager, both ways: a self-referencing entity Hibernate links in a cycle.
 */
@Entity
public class Employee {

  @Id
  @GeneratedValue
  private Long id;

  private String name;

  @ManyToOne(fetch = FetchType.LAZY)
  private Employee manager;

  @OneToMany(mappedBy = "manager")
  private List<Employee> reports = new ArrayList<>();

  public Employee() {}

  public Employee(final String name) {
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

  public Employee getManager() {
    return manager;
  }

  public void setManager(final Employee manager) {
    this.manager = manager;
  }

  public List<Employee> getReports() {
    return reports;
  }

  public void setReports(final List<Employee> reports) {
    this.reports = reports;
  }
}
