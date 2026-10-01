package io.github.eschizoid.telescope.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eschizoid.telescope.spring.quickstart.Contact;
import io.github.eschizoid.telescope.spring.quickstart.Customer;
import io.github.eschizoid.telescope.spring.quickstart.CustomerDto;
import io.github.eschizoid.telescope.spring.quickstart.CustomerEmailTransformer;
import io.github.eschizoid.telescope.spring.quickstart.CustomerMapper;
import io.github.eschizoid.telescope.spring.quickstart.CustomerProjection;
import io.github.eschizoid.telescope.spring.quickstart.CustomerSummaryProjection;
import io.github.eschizoid.telescope.spring.quickstart.NormalizedCustomerProjection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/** Runs the examples in the starter README's quickstart, so the documented behaviour stays true. */
class QuickstartExamplesTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Scan.class);

  @Test
  void bridgeBackedMapperIsInjectable() {
    runner.run(context -> {
      final var mapper = context.getBean(CustomerMapper.class);
      assertThat(mapper.map(new Customer("Ada", new Contact("ada@example.com")))).isEqualTo(
        new CustomerDto("Ada", new Contact("ada@example.com"))
      );
    });
  }

  @Test
  void projectionMapsBothWaysAndRefusesPatchOverTheBridge() {
    runner.run(context -> {
      final var projection = context.getBean(CustomerProjection.class);
      final var customer = new Customer("Ada", new Contact("ada@example.com"));
      assertThat(projection.backward(projection.map(customer))).isEqualTo(customer);
      assertThatThrownBy(() -> projection.patch(customer, new CustomerDto("Bea", null))).isInstanceOf(
        UnsupportedOperationException.class
      );
    });
  }

  @Test
  void transformerNormalizesBeforeMapping() {
    runner.run(context -> {
      final var email = context.getBean(CustomerEmailTransformer.class);
      final var projection = context.getBean(NormalizedCustomerProjection.class);
      final var customer = new Customer("Ada", new Contact(" ADA@EXAMPLE.COM "));

      assertThat(email.path().read(customer)).isEqualTo(" ADA@EXAMPLE.COM ");
      assertThat(email.apply(customer).contact().email()).isEqualTo("ada@example.com");
      assertThat(projection.map(customer)).isEqualTo(new CustomerDto("Ada", new Contact("ada@example.com")));
      assertThat(projection.map(new Customer("Ada", new Contact(null))).contact().email()).isEqualTo(
        "unknown@example.com"
      );
      assertThat(projection.map(new Customer("Ada", null)).contact()).isNull();
      assertThat(customer.contact().email()).isEqualTo(" ADA@EXAMPLE.COM ");
    });
  }

  @Test
  void translateRenamesAndStampsWithTypedRows() {
    runner.run(context -> {
      final var summary = context
        .getBean(CustomerSummaryProjection.class)
        .map(new Customer("Ada", new Contact("ada@example.com")));
      assertThat(summary.displayName()).isEqualTo("Ada");
      assertThat(summary.source()).isEqualTo("crm");
      assertThat(summary.generatedAt()).isNotNull();
    });
  }

  @Configuration
  @ComponentScan(basePackageClasses = Customer.class)
  static class Scan {}
}
