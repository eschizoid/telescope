package io.github.eschizoid.telescope.examples;

import static io.github.eschizoid.telescope.Edit.over;

import io.github.eschizoid.telescope.Telescope;
import java.util.List;

/**
 * Exercises the multi-edit shape: {@code Telescope.all(over(PATH, fn), ...)} — the recommended way
 * to apply two-or-more independent edits to one root. Each {@code over(...)} call site reads as one
 * bullet, the count is visible at a glance, and the resulting {@code Telescope<S, S>} is reusable
 * across sources.
 */
final class MultiEditDemo {

  private MultiEditDemo() {}

  record User(String name, String email) {}

  record Team(String name, List<User> users) {}

  record Department(String name, List<Team> teams) {}

  record Company(String name, List<Department> departments) {}

  private static final Telescope<Company, String> EMAILS = Telescope.of(Company.class)
    .each(Company::departments)
    .each(Department::teams)
    .each(Team::users)
    .field(User::email);

  private static final Telescope<Company, String> DEPT_NAMES = Telescope.of(Company.class)
    .each(Company::departments)
    .field(Department::name);

  private static final Telescope<Company, String> TEAM_NAMES = Telescope.of(Company.class)
    .each(Company::departments)
    .each(Department::teams)
    .field(Team::name);

  private static Company sample() {
    return new Company(
      "Acme",
      List.of(
        new Department(
          " Engineering ",
          List.of(
            new Team(" Platform ", List.of(new User("alice", "ALICE@ACME.COM"), new User("bob", "BOB@ACME.COM"))),
            new Team(" Data ", List.of(new User("eve", "EVE@ACME.COM")))
          )
        ),
        new Department(" Sales ", List.of(new Team(" APAC ", List.of(new User("carol", "CAROL@ACME.COM")))))
      )
    );
  }

  static void run() {
    final var company = sample();

    // Three heterogeneous edits, declared as one block — every leaf changes, in argument order.
    final Telescope<Company, Company> normalize = Telescope.all(
      over(EMAILS, String::toLowerCase),
      over(DEPT_NAMES, String::trim),
      over(TEAM_NAMES, String::trim)
    );

    final var out = normalize.apply(company);

    System.out.println("[all/over] dept names trimmed: " + out.departments().stream().map(Department::name).toList());
    System.out.println(
      "[all/over] team names trimmed: " + out.departments().getFirst().teams().stream().map(Team::name).toList()
    );
    System.out.println(
      "[all/over] first user email  : " + out.departments().getFirst().teams().getFirst().users().getFirst().email()
    );

    // The same normalizer is reusable on a different source.
    final var another = new Company(
      "Bingo",
      List.of(new Department(" Ops ", List.of(new Team(" SRE ", List.of(new User("zane", "Z@BINGO.COM"))))))
    );
    final var another2 = normalize.apply(another);
    System.out.println(
      "[all/over] reused on another : " +
        another2.departments().getFirst().teams().getFirst().users().getFirst().email()
    );
  }
}
