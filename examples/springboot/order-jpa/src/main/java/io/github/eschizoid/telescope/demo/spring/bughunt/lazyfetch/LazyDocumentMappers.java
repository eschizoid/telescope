package io.github.eschizoid.telescope.demo.spring.bughunt.lazyfetch;

import static io.github.eschizoid.telescope.mapping.Mapping.via;
import static io.github.eschizoid.telescope.mapping.WriteHint.WriteStrategy.SETTERS;
import static io.github.eschizoid.telescope.mapping.WriteHint.writeBeans;

import io.github.eschizoid.telescope.Telescope;
import io.github.eschizoid.telescope.conversion.Mapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@code Document ↔ LazyDocumentEntity} mapper. Same shape as {@code OrderMappers} but
 * with one nested record-pair ({@code Author ↔ AuthorEntity}) sitting behind a
 * {@code @ManyToOne(fetch=LAZY)} relation on the entity side.
 */
@Configuration
public class LazyDocumentMappers {

  @Bean
  public Mapper<Author, AuthorEntity> authorMapper() {
    return Telescope.mapper(Author.class, AuthorEntity.class, writeBeans(SETTERS));
  }

  @Bean
  public Mapper<Document, LazyDocumentEntity> documentMapper(final Mapper<Author, AuthorEntity> authorMapper) {
    return Telescope.mapper(
      Document.class,
      LazyDocumentEntity.class,
      via(Document::author, LazyDocumentEntity::getAuthor, authorMapper),
      writeBeans(SETTERS)
    );
  }
}
