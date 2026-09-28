package com.souptik.inventory.controller;

import com.souptik.inventory.model.Product;
import com.souptik.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    // ---- Composite L1+L2 path (stampede-protected, bloom-filter-guarded) ----
    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable String id) {
        inventoryService.recordProductView(id); // feeds the trending ZSET
        return inventoryService.getProduct(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Product> createOrUpdate(@RequestBody Product product) {
        return ResponseEntity.ok(inventoryService.createOrUpdateProduct(product));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        inventoryService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    // ---- Declarative @Cacheable/@CachePut/@CacheEvict path ----
    @GetMapping("/annotated/{id}")
    public ResponseEntity<Product> getProductAnnotated(@PathVariable String id) {
        Product product = inventoryService.getProductAnnotated(id);
        return product == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(product);
    }

    // ---- Redis Hash: partial stock updates ----
    @PatchMapping("/{id}/stock")
    public ResponseEntity<Void> updateStock(@PathVariable String id, @RequestParam int newStockLevel) {
        inventoryService.updateStockLevelPartial(id, newStockLevel);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/stock/decrement")
    public ResponseEntity<Map<String, Long>> decrementStock(@PathVariable String id,
                                                              @RequestParam int quantity) {
        long remaining = inventoryService.decrementStockAtomic(id, quantity);
        return ResponseEntity.ok(Map.of("remaining", remaining));
    }

    @PostMapping("/{id}/purchase")
    public ResponseEntity<Void> recordPurchase(@PathVariable String id, @RequestParam int quantity) {
        inventoryService.recordProductPurchase(id, quantity);
        return ResponseEntity.noContent().build();
    }

    // ---- ZSET: trending products leaderboard ----
    @GetMapping("/trending")
    public ResponseEntity<List<Map<String, Object>>> getTrending(@RequestParam(defaultValue = "10") int topN) {
        Set<ZSetOperations.TypedTuple<Object>> tuples = inventoryService.getTrendingProducts(topN);
        List<Map<String, Object>> response = tuples.stream()
                .map(t -> Map.<String, Object>of("productId", t.getValue(), "score", t.getScore()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }
}
