package io.github.eschizoid.telescope.quarkus.deployment;

import io.smallrye.openapi.api.OpenApiConfig;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;
import org.jboss.jandex.DotName;

/**
 * Which classes the application's own OpenAPI scan reads, by its {@code mp.openapi.scan.classes},
 * {@code scan.packages}, {@code scan.exclude.classes} and {@code scan.exclude.packages}, decided by
 * SmallRye OpenAPI's rule. An entry starting with {@code ^} or ending with {@code $} is a pattern
 * found anywhere in the name; any other is a class name's suffix holding its whole simple name, or
 * a package name's prefix. A class named exactly is decided by that, an exclusion first; otherwise
 * the longest class match wins, an exclusion on a tie, then the longest package match the same way,
 * and a class nothing matches is read only when no inclusion is configured.
 */
final class ScanFilter {

  private final Entries classes;
  private final Entries packages;
  private final Entries excludedClasses;
  private final Entries excludedPackages;
  private final boolean anyInclusion;

  ScanFilter(final Config config) {
    final var settings = OpenApiConfig.fromConfig(config);
    classes = new Entries(settings.scanClasses());
    packages = new Entries(settings.scanPackages());
    excludedClasses = new Entries(settings.scanExcludeClasses());
    excludedPackages = new Entries(settings.scanExcludePackages());
    anyInclusion = !classes.isEmpty() || !packages.isEmpty();
  }

  /** Whether the application's scan reads the class {@code name}. */
  boolean reads(final DotName name) {
    final var binary = name.toString();
    final var simple = name.withoutPackagePrefix();
    final var dot = binary.lastIndexOf('.');
    final var packageName = dot > -1 ? binary.substring(0, dot) : "";
    if (excludedClasses.names().contains(binary)) return false;
    if (classes.names().contains(binary)) return true;
    final var excluded = excludedClasses.classMatch(binary, simple);
    final var included = classes.classMatch(binary, simple);
    if (!excluded.isEmpty() && excluded.length() >= included.length()) return false;
    if (!included.isEmpty()) return true;
    final var packageIncluded = packages.packageMatch(packageName);
    final var packageExcluded = excludedPackages.packageMatch(packageName);
    if (!packageExcluded.isEmpty() && packageExcluded.length() >= packageIncluded.length()) return false;
    if (!packageIncluded.isEmpty()) return true;
    return !anyInclusion;
  }

  // One of the four settings: its plain entries and its patterns.
  private record Entries(Set<String> names, Set<Pattern> patterns) {
    Entries(final Set<String> given) {
      this(new HashSet<>(), new HashSet<>());
      for (final var entry : given)
        if (entry.startsWith("^") || entry.endsWith("$")) patterns.add(Pattern.compile(entry));
        else names.add(entry);
    }

    boolean isEmpty() {
      return names.isEmpty() && patterns.isEmpty();
    }

    // The longest suffix of the class's name that holds its whole simple name, or pattern match.
    String classMatch(final String binary, final String simple) {
      var suffix = "";
      for (final var each : names) if (binary.endsWith(each) && each.length() > suffix.length()) suffix = each;
      if (!suffix.endsWith(simple)) suffix = "";
      return longer(suffix, patternMatch(binary));
    }

    // The longest prefix of the package's name, or pattern match.
    String packageMatch(final String packageName) {
      var prefix = "";
      for (final var each : names) if (packageName.startsWith(each) && each.length() > prefix.length()) prefix = each;
      return longer(prefix, patternMatch(packageName));
    }

    private String patternMatch(final String name) {
      var longest = "";
      for (final var pattern : patterns) {
        final var matcher = pattern.matcher(name);
        if (matcher.find() && matcher.group().length() > longest.length()) longest = matcher.group();
      }
      return longest;
    }

    private static String longer(final String first, final String second) {
      return second.length() > first.length() ? second : first;
    }
  }
}
