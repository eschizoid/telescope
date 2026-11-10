package io.github.telescopeorganization.telescope.spring.blueprint;

import io.github.telescopeorganization.telescope.Telescope;
import io.github.telescopeorganization.telescope.annotations.TelescopeTransformer;
import io.github.telescopeorganization.telescope.inject.TelescopeTransformation;
import io.github.telescopeorganization.telescope.inject.Transformation;
import java.util.Locale;

@TelescopeTransformer
public interface ContactNameTransformer extends TelescopeTransformation<ContactBean, String> {
  default Telescope<ContactBean, String> path() {
    return Telescope.ofBean(ContactBean.class).field(ContactBean::getName);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>("(unnamed)", value -> value.toLowerCase(Locale.ROOT));
  }
}
