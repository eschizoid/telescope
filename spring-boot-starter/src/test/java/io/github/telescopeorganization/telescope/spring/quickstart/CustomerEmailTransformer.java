package io.github.telescopeorganization.telescope.spring.quickstart;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.TelescopeTransformer;
import io.github.telescopeorganization.telescope.inject.TelescopeTransformation;
import io.github.telescopeorganization.telescope.inject.Transformation;
import java.util.Locale;

@TelescopeTransformer
public interface CustomerEmailTransformer extends TelescopeTransformation<Customer, String> {
  @Override
  default Telescope<Customer, String> path() {
    return Telescope.of(Customer.class).field(Customer::contact).field(Contact::email);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>("unknown@example.com", email -> email.strip().toLowerCase(Locale.ROOT));
  }
}
