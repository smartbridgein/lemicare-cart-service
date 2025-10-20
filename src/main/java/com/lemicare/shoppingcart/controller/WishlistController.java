package com.lemicare.shoppingcart.controller;

import com.cosmicdoc.common.model.StorefrontProduct;
import com.cosmicdoc.common.model.Wishlist;
import com.lemicare.shoppingcart.context.TenantContext;
import com.lemicare.shoppingcart.dto.request.AddItemToWishlistRequest;
import com.lemicare.shoppingcart.service.WishlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/wishlists")
@PreAuthorize("hasAnyAuthority('SCOPE_customer.read', 'SCOPE_customer.write')")
public class WishlistController {

    private final WishlistService wishlistService;

    // --- GET Wishlist (Product IDs only) ---
    @GetMapping
    public ResponseEntity<Wishlist> getWishlist() {
        String organizationId = TenantContext.getOrganizationId();
        String customerId = TenantContext.getUserId();
        try {
            Wishlist wishlist = wishlistService.getWishlist(organizationId, customerId);
            return ResponseEntity.ok(wishlist);
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error retrieving wishlist", e);
        }
    }

    // --- GET Wishlist with Product Details ---
    @GetMapping("/products")
    public ResponseEntity<List<StorefrontProduct>> getWishlistProducts() {
        String organizationId = TenantContext.getOrganizationId();
        String customerId = TenantContext.getUserId();

        try {
            List<StorefrontProduct> products = wishlistService.getWishlistProducts(organizationId, customerId);
            return ResponseEntity.ok(products);
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error retrieving wishlist products", e);
        }
    }

    // --- Add Item to Wishlist ---
    @PostMapping("/items")
    public ResponseEntity<Wishlist> addItemToWishlist(@RequestBody AddItemToWishlistRequest request) {
        String organizationId = TenantContext.getOrganizationId();
        String customerId = TenantContext.getUserId();

        try {
            Wishlist updatedWishlist = wishlistService.addProductToWishlist(organizationId, customerId, request.getProductId());
            return ResponseEntity.status(HttpStatus.CREATED).body(updatedWishlist);
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error adding item to wishlist", e);
        }
    }

    // --- Remove Item from Wishlist ---
    @DeleteMapping("/items/{productId}")
    public ResponseEntity<Wishlist> removeItemFromWishlist(@PathVariable String productId) {
        String organizationId = TenantContext.getOrganizationId();
        String customerId = TenantContext.getUserId();

        try {
            Wishlist updatedWishlist = wishlistService.removeProductFromWishlist(organizationId, customerId, productId);
            return ResponseEntity.ok(updatedWishlist);
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error removing item from wishlist", e);
        }
    }

    // --- Clear (Delete) Wishlist ---
    @DeleteMapping
    public ResponseEntity<Void> clearWishlist() {
        String organizationId = TenantContext.getOrganizationId();
        String customerId = TenantContext.getUserId();

        try {
            wishlistService.clearWishlist(organizationId, customerId);
            return ResponseEntity.noContent().build();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error clearing wishlist", e);
        }
    }
}