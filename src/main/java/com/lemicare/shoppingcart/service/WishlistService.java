package com.lemicare.shoppingcart.service;

import com.cosmicdoc.common.model.StorefrontProduct;
import com.cosmicdoc.common.model.Wishlist;
import com.cosmicdoc.common.model.WishlistItem;
import com.cosmicdoc.common.repository.StorefrontProductRepository;
import com.cosmicdoc.common.repository.WishlistRepository;
import com.google.cloud.Timestamp;
import com.lemicare.shoppingcart.client.StorefrontServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final StorefrontServiceClient storefrontServiceClient;
    // To fetch product details


    /**
     * Retrieves a user's wishlist for a given organization.
     * If no wishlist exists, an empty one is returned.
     */
    public Wishlist getWishlist(String organizationId, String customerId) throws ExecutionException, InterruptedException {
        return wishlistRepository.findByOrganizationIdAndCustomerId(organizationId, customerId)
                // Pass an empty ArrayList instead of null
                .orElseGet(() -> new Wishlist(customerId, organizationId, new ArrayList<>()));
    }

    /**
     * Retrieves a user's wishlist along with the full product details for each item.
     * This is what you'll typically use to display the wishlist in the UI.
     */
    public Wishlist addProductToWishlist(String organizationId, String customerId, String productId) throws ExecutionException, InterruptedException {
        Wishlist wishlist = wishlistRepository.findByOrganizationIdAndCustomerId(organizationId, customerId)
                // Pass an empty ArrayList instead of null
                .orElseGet(() -> new Wishlist(customerId, organizationId, new ArrayList<>()));

        if (!wishlist.containsProduct(productId)) {
            WishlistItem newItem = new WishlistItem(productId, Timestamp.now());
            wishlist.getItems().add(newItem);
            return wishlistRepository.save(wishlist);
        }
        return wishlist; // Item already in wishlist, no change
    }

    /**
     * Removes a product from the user's wishlist.
     *
     * @return The updated wishlist.
     */
    public Wishlist removeProductFromWishlist(String organizationId, String customerId, String productId) throws ExecutionException, InterruptedException {
        Optional<Wishlist> optionalWishlist = wishlistRepository.findByOrganizationIdAndCustomerId(organizationId, customerId);

        if (optionalWishlist.isPresent()) {
            Wishlist wishlist = optionalWishlist.get();
            boolean removed = wishlist.getItems().removeIf(item -> item.getProductId().equals(productId));
            if (removed) {
                return wishlistRepository.save(wishlist);
            }
            // If the item wasn't found, just return the existing wishlist (no change)
            return wishlist;
        }
        // If wishlist doesn't exist, nothing to remove, return a new empty wishlist
        return new Wishlist(customerId, organizationId, new ArrayList<>());
    }

    /**
     * Clears (deletes) the entire wishlist for a user within an organization.
     */
    public void clearWishlist(String organizationId, String customerId) throws ExecutionException, InterruptedException {
        wishlistRepository.delete(organizationId, customerId);
    }

    /**
     * Retrieves a user's wishlist along with the full product details for each item.
     * This is what you'll typically use to display the wishlist in the UI.
     */
    public List<StorefrontProduct> getWishlistProducts(String organizationId, String customerId) throws ExecutionException, InterruptedException {
        Wishlist wishlist = getWishlist(organizationId, customerId);
        List<String> productIds = wishlist.getItems().stream()
                .map(WishlistItem::getProductId)
                .collect(Collectors.toList());

        if (productIds.isEmpty()) {
            return Collections.emptyList();
        }

        // Fetch products using your StorefrontProductRepository
        // Note: For more than 10 productIds, you'd typically need multiple separate queries or a Firestore Collection Group Query
        // if your products were in a root-level collection.
        // Since your products are under organizations/{organizationId}/storefront_products,
        // you'll need to fetch them one by one or use a batched approach if the repository supports it.
        // For simplicity, here's a direct loop. Optimize with batch reads if productIds can be large.

        // Example: Fetching products individually (can be optimized with batch reads)
        return storefrontServiceClient.getProductsByIds(organizationId,productIds);

    }

}