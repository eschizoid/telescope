package io.github.eschizoid.telescope.demo.spring.bughunt.maps;

import java.util.Map;

/**
 * Bean twin of {@link MetadataOrder}. No JPA annotations: this slice only exercises the deep-map
 * surface, not Hibernate. Same-name fields (id / metadata / tags) flow through telescope's auto
 * inference; {@code metadata}'s same-typed values short-circuit to {@code Iso.identity()} under
 * {@code liftMapValues}, while {@code tags} recurses into the {@code Tag ↔ TagDto} pair.
 */
public class MetadataOrderEntity {

  private String id;
  private Map<String, String> metadata;
  private Map<String, TagDto> tags;

  public MetadataOrderEntity() {}

  public String getId() {
    return id;
  }

  public void setId(final String id) {
    this.id = id;
  }

  public Map<String, String> getMetadata() {
    return metadata;
  }

  public void setMetadata(final Map<String, String> metadata) {
    this.metadata = metadata;
  }

  public Map<String, TagDto> getTags() {
    return tags;
  }

  public void setTags(final Map<String, TagDto> tags) {
    this.tags = tags;
  }
}
