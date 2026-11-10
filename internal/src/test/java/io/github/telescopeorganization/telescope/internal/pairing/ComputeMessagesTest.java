package io.github.telescopeorganization.telescope.internal.pairing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The text of the refusals of a {@code compute} row's function. The runtime mapper, the bridge
 * processor and the compile-time verifier all build them and are compared by equality, so each
 * names the pair, the property, the function and the fix in exactly these words.
 */
class ComputeMessagesTest {

  @Test
  @DisplayName("a reference bound as a supplier names the form taking the source it did not reach")
  void supplierWithASourceForm() {
    assertEquals(
      "Deep map User → UserDto: compute(...) fills 'full' from Names::full, which Java binds as a supplier taking" +
        " no argument, though Names.full(User) takes the source User. Hold the one meant in a static final" +
        " Supplier or FromSource field, or give the form taking the source a name of its own.",
      PairingMessages.computeSupplierHasASourceForm("User", "UserDto", "full", "Names::full", "Names.full(User)")
    );
  }

  @Test
  @DisplayName("a function taking another class names the class it takes and asks for one taking the source")
  void functionOffSource() {
    assertEquals(
      "Deep map User → UserDto: compute(...) fills 'full' from a lambda, which takes Admin, and the source User" +
        " is not one. Name a method taking User, or a method of User.",
      PairingMessages.computeFunctionOffSource("User", "UserDto", "full", "a lambda", "Admin")
    );
  }
}
