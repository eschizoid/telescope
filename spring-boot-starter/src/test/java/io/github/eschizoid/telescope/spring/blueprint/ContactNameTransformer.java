package io.github.eschizoid.telescope.spring.blueprint;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.spring.TelescopeTransform;
import io.github.eschizoid.telescope.spring.TelescopeTransformation;
import io.github.eschizoid.telescope.spring.Transformation;
import java.util.Locale;

@TelescopeTransform
public interface ContactNameTransformer extends TelescopeTransformation<ContactBean, String> {
  default Telescope<ContactBean, String> path() {
    return Telescope.ofBean(ContactBean.class).field(ContactBean::getName);
  }

  @Override
  default Transformation<String> transform() {
    return new Transformation<>("(unnamed)", value -> value.toLowerCase(Locale.ROOT));
  }
}
