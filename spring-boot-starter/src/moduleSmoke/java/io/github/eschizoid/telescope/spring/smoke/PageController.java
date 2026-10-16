package io.github.eschizoid.telescope.spring.smoke;

import io.github.eschizoid.telescope.spring.smoke.ModuleSmoke.Account;
import io.github.eschizoid.telescope.spring.smoke.ModuleSmoke.AccountView;
import io.github.eschizoid.telescope.spring.web.TelescopeGetMapping;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.RestController;

/** Loaded only in the full run, where Spring Data is on the module path. */
@RestController
public class PageController {

  @TelescopeGetMapping(value = "/accounts", to = AccountView.class)
  public Page<Account> accounts() {
    final var accounts = List.of(new Account("1", "Ada", "h1"), new Account("2", "Bea", "h2"));
    return new PageImpl<>(accounts, PageRequest.of(0, 2), accounts.size());
  }
}
