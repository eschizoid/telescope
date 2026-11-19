package io.github.eschizoid.telescope.benchmarks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One write of an order {@link MergeByIdBenchmark} measures, loaded by the class loader the
 * benchmark builds for it, so the {@code ContainerCopy} it reaches takes that loader's policy.
 * {@code generated} and {@code runtime} write the same {@code size} lines, sent back as the order
 * holds them, through {@link MbMappers#FORM} and {@link MbMappers#EDIT}; {@code hand} writes each
 * line's two properties into the line of its index in a loop, reaching no telescope code.
 */
public final class MbDrive implements Supplier<Object> {

  private final String mode;
  private final MbOrder order = new MbOrder();
  private final MbOrderForm form;
  private final MbOrderEdit edit;

  public MbDrive(final String mode, final int size) {
    this.mode = mode;
    final var forms = new ArrayList<MbLineForm>(size);
    final var edits = new ArrayList<MbLineEdit>(size);
    for (var i = 0; i < size; i++) {
      final var id = (long) i + 1;
      order.getLines().add(new MbLine(id, "sku-" + i, i));
      forms.add(new MbLineForm(id, "sku-" + i, i));
      edits.add(new MbLineEdit(id, "sku-" + i, i));
    }
    form = new MbOrderForm(List.copyOf(forms));
    edit = new MbOrderEdit(List.copyOf(edits));
  }

  @Override
  public Object get() {
    return switch (mode) {
      case "generated" -> MbMappers.FORM.into(order, form);
      case "runtime" -> MbMappers.EDIT.into(order, edit);
      case "hand" -> {
        final var held = order.getLines();
        final var sent = form.lines();
        for (var i = 0; i < sent.size(); i++) {
          final var line = held.get(i);
          line.setSku(sent.get(i).sku());
          line.setQuantity(sent.get(i).quantity());
        }
        yield order;
      }
      default -> throw new IllegalArgumentException(mode);
    };
  }

  /** The order written, for the benchmark's setup to check the write it measures. */
  public MbOrder order() {
    return order;
  }
}
