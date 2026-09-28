package com.souptik.inventory.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Core catalog entity. Kept intentionally simple - the interview focus is the
 * caching architecture around it, not the domain model itself.
 *
 * Implements Serializable defensively (some 3rd-party cache tooling still
 * expects it), but note the actual wire format used by RedisCacheManager is
 * JSON via GenericJackson2JsonRedisSerializer - see CacheConfig.
 */
@Entity
@Table(name = "products")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Product implements Serializable {

    @Id
    private String id;

    private String sku;
    private String name;
    private String description;
    private String category;
    private BigDecimal price;
    private Integer stockLevel;
    private Instant lastUpdated;

    @Version
    private Long version; // optimistic locking for concurrent stock updates
}
