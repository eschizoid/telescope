package io.github.eschizoid.telescope.demo.spring.bughunt.lazyfetch;

import io.github.eschizoid.telescope.conversion.Mapper;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document CRUD exercising the {@code @ManyToOne(fetch=LAZY)} relation on {@link
 * LazyDocumentEntity#getAuthor()}. Both endpoints are {@code @Transactional} so the proxy has a
 * session to dereference against when telescope's bean reflection hits {@code getAuthor()}.
 */
@RestController
@RequestMapping("/bughunt/docs")
public class LazyDocumentController {

  private final Mapper<Document, LazyDocumentEntity> documentMapper;
  private final LazyDocumentRepository repository;

  public LazyDocumentController(
    final Mapper<Document, LazyDocumentEntity> documentMapper,
    final LazyDocumentRepository repository
  ) {
    this.documentMapper = documentMapper;
    this.repository = repository;
  }

  @PostMapping
  @Transactional
  public ResponseEntity<Document> create(@RequestBody final Document request) {
    final var entity = documentMapper.forward(request);
    final var saved = repository.save(entity);
    return ResponseEntity.ok(documentMapper.backward(saved));
  }

  @GetMapping("/{id}")
  @Transactional(readOnly = true)
  public ResponseEntity<Document> get(@PathVariable final Long id) {
    return repository
      .findById(id)
      .map(documentMapper::backward)
      .map(ResponseEntity::ok)
      .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
