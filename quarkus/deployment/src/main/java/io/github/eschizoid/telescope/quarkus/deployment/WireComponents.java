package io.github.eschizoid.telescope.quarkus.deployment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;

/**
 * Brings the component schemas the wire schemas name into a document, each under the name it takes
 * there. Two scans name a component by its class's simple name and number the classes sharing one
 * in the order each meets them, so a name the document holds may stand for another class.
 *
 * <p>A wire component takes the name of a document component that describes the same thing and is
 * named as SmallRye OpenAPI could have named that class: by the wire component's own name, or by
 * that name followed by the number its duplicate numbering appends. Describing the same thing is
 * equal content once every reference in either is set aside, with references that, position for
 * position, lead to components describing the same thing in turn. That is the largest such
 * correspondence, so two components referring to each other match two document components that do.
 * A matched component is not added again. Any other component keeps its name where the document has
 * none of it, and otherwise takes its name followed by the lowest number neither the document nor
 * another component takes, as SmallRye numbers a duplicate. A name is never cut short: digits a
 * class's own name ends in are part of it. Every reference in the wire schemas and in the
 * components added then points at the name its target takes.
 */
final class WireComponents {

  private static final String REFERENCE_PREFIX = "#/components/schemas/";
  private static final String SET_ASIDE = REFERENCE_PREFIX + " ";

  private WireComponents() {}

  /**
   * Adds the components {@code roots} name, at any depth, from {@code available} to {@code
   * document}, and points every reference among them at the name its target takes there.
   */
  static void copy(final OpenAPI document, final Map<String, Schema> available, final List<Schema> roots) {
    final var closure = closure(roots, available);
    if (closure.isEmpty()) return;
    final Map<String, Schema> existing =
      document.getComponents() == null || document.getComponents().getSchemas() == null
        ? Map.of()
        : document.getComponents().getSchemas();
    final var matches = matches(closure, available, existing);
    final var names = new LinkedHashMap<String, String>();
    final var taken = new HashSet<>(existing.keySet());
    for (final var name : closure) {
      final var match = matches.getOrDefault(name, Set.of());
      if (match.contains(name)) names.put(name, name);
      else if (!match.isEmpty()) names.put(name, match.iterator().next());
      else if (!existing.containsKey(name)) {
        names.put(name, name);
        taken.add(name);
      }
    }
    for (final var name : closure) {
      if (names.containsKey(name)) continue;
      var number = 1;
      while (taken.contains(name + number)) number++;
      names.put(name, name + number);
      taken.add(name + number);
    }
    final Set<Schema> rewritten = Collections.newSetFromMap(new IdentityHashMap<>());
    for (final var root : roots) rename(root, names, rewritten);
    final var added = new ArrayList<String>();
    for (final var name : closure) {
      if (existing.containsKey(names.get(name)) || available.get(name) == null) continue;
      rename(available.get(name), names, rewritten);
      added.add(name);
    }
    if (added.isEmpty()) return;
    if (document.getComponents() == null) document.setComponents(OASFactory.createComponents());
    for (final var name : added) document.getComponents().addSchema(names.get(name), available.get(name));
  }

  // For each wire component, the document components SmallRye could have given its name that
  // describe the same thing,
  // in name order: every pair whose content is equal with references set aside, less each pair
  // whose references lead to a pair not kept, until none is dropped.
  private static Map<String, Set<String>> matches(
    final Set<String> closure,
    final Map<String, Schema> available,
    final Map<String, Schema> existing
  ) {
    final var matches = new HashMap<String, Set<String>>();
    final var leads = new HashMap<List<String>, List<List<String>>>();
    for (final var name : closure) {
      final var schema = available.get(name);
      if (schema == null) continue;
      final var kept = new TreeSet<String>();
      for (final var candidate : existing.entrySet()) {
        if (!numbered(name, candidate.getKey()) || !equalAside(schema, candidate.getValue())) continue;
        final var pairs = new ArrayList<List<String>>();
        pairs(schema, candidate.getValue(), pairs);
        kept.add(candidate.getKey());
        leads.put(List.of(name, candidate.getKey()), pairs);
      }
      matches.put(name, kept);
    }
    var dropped = true;
    while (dropped) {
      dropped = false;
      for (final var entry : matches.entrySet())
        for (final var iterator = entry.getValue().iterator(); iterator.hasNext(); ) {
          final var candidate = iterator.next();
          for (final var lead : leads.get(List.of(entry.getKey(), candidate))) {
            final var held = matches.get(lead.get(0));
            if (held != null ? held.contains(lead.get(1)) : lead.get(0).equals(lead.get(1))) continue;
            iterator.remove();
            dropped = true;
            break;
          }
        }
    }
    return matches;
  }

  // Whether two schemas are equal once every reference in both is set aside. Each reference is
  // put back as it was.
  private static boolean equalAside(final Schema wire, final Schema document) {
    final var references = new IdentityHashMap<Schema, String>();
    setAside(wire, references);
    setAside(document, references);
    try {
      return wire.equals(document);
    } finally {
      for (final var entry : references.entrySet()) entry.getKey().setRef(entry.getValue());
    }
  }

  private static void setAside(final Schema schema, final Map<Schema, String> references) {
    if (schema == null) return;
    if (schema.getRef() != null && !references.containsKey(schema)) {
      references.put(schema, schema.getRef());
      schema.setRef(SET_ASIDE);
    }
    for (final var nested : nested(schema)) setAside(nested, references);
  }

  // The component names two schemas of equal shape refer to at the same position, as pairs.
  private static void pairs(final Schema wire, final Schema document, final List<List<String>> pairs) {
    if (wire == null || document == null) return;
    final var wireName = component(wire.getRef());
    final var documentName = component(document.getRef());
    if (wireName != null && documentName != null) pairs.add(List.of(wireName, documentName));
    if (wire.getProperties() != null && document.getProperties() != null) for (final var property : wire
      .getProperties()
      .entrySet())
      pairs(property.getValue(), document.getProperties().get(property.getKey()), pairs);
    pairs(wire.getItems(), document.getItems(), pairs);
    pairs(wire.getNot(), document.getNot(), pairs);
    pairs(wire.getAdditionalPropertiesSchema(), document.getAdditionalPropertiesSchema(), pairs);
    pairsAt(wire.getAllOf(), document.getAllOf(), pairs);
    pairsAt(wire.getAnyOf(), document.getAnyOf(), pairs);
    pairsAt(wire.getOneOf(), document.getOneOf(), pairs);
  }

  private static void pairsAt(final List<Schema> wire, final List<Schema> document, final List<List<String>> pairs) {
    if (wire == null || document == null) return;
    for (var position = 0; position < Math.min(wire.size(), document.size()); position++) pairs(
      wire.get(position),
      document.get(position),
      pairs
    );
  }

  // The components the roots name, and every one those name in turn.
  private static Set<String> closure(final List<Schema> roots, final Map<String, Schema> available) {
    final var pending = new ArrayDeque<String>();
    for (final var root : roots) pending.addAll(referenced(root));
    final var closure = new LinkedHashSet<String>();
    while (!pending.isEmpty()) {
      final var name = pending.poll();
      if (!closure.add(name)) continue;
      final var schema = available.get(name);
      if (schema != null) pending.addAll(referenced(schema));
    }
    return closure;
  }

  // Points every reference in a schema, at any depth of its own structure, at the name its target
  // takes. Each schema is rewritten once, however many wire schemas share it.
  private static void rename(final Schema schema, final Map<String, String> names, final Set<Schema> rewritten) {
    if (schema == null || !rewritten.add(schema)) return;
    final var target = component(schema.getRef());
    if (target != null && names.containsKey(target)) schema.setRef(REFERENCE_PREFIX + names.get(target));
    for (final var nested : nested(schema)) rename(nested, names, rewritten);
  }

  // The components a schema names, at any depth of its own structure, without following one.
  private static Set<String> referenced(final Schema schema) {
    final var names = new LinkedHashSet<String>();
    referenced(schema, names);
    return names;
  }

  private static void referenced(final Schema schema, final Set<String> names) {
    if (schema == null) return;
    final var target = component(schema.getRef());
    if (target != null) names.add(target);
    for (final var nested : nested(schema)) referenced(nested, names);
  }

  // The schemas a schema holds directly: its properties, items, additional properties, negation and
  // compositions.
  private static List<Schema> nested(final Schema schema) {
    final var nested = new ArrayList<Schema>();
    if (schema.getProperties() != null) nested.addAll(schema.getProperties().values());
    if (schema.getItems() != null) nested.add(schema.getItems());
    if (schema.getNot() != null) nested.add(schema.getNot());
    if (schema.getAdditionalPropertiesSchema() != null) nested.add(schema.getAdditionalPropertiesSchema());
    if (schema.getAllOf() != null) nested.addAll(schema.getAllOf());
    if (schema.getAnyOf() != null) nested.addAll(schema.getAnyOf());
    if (schema.getOneOf() != null) nested.addAll(schema.getOneOf());
    return nested;
  }

  private static String component(final String ref) {
    return ref != null && ref.startsWith(REFERENCE_PREFIX) ? ref.substring(REFERENCE_PREFIX.length()) : null;
  }

  // Whether SmallRye could have given the class a wire component names the document component's
  // name: the same name, or the same name followed by its duplicate number.
  private static boolean numbered(final String wire, final String document) {
    if (document.equals(wire)) return true;
    if (!document.startsWith(wire) || document.length() == wire.length()) return false;
    for (var position = wire.length(); position < document.length(); position++) if (
      !Character.isDigit(document.charAt(position))
    ) return false;
    return true;
  }
}
