package com.lemicare.shoppingcart.controller;

import com.lemicare.shoppingcart.dto.request.*;
import com.lemicare.shoppingcart.dto.response.ShippingEstimate;
import com.lemicare.shoppingcart.exception.CartNotFoundException;
import com.lemicare.shoppingcart.exception.InsufficientStockException;
import com.lemicare.shoppingcart.exception.ProductNotFoundException;
import com.lemicare.shoppingcart.exception.ServiceCommunicationException;
import com.lemicare.shoppingcart.service.CartService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/public/cart/{orgId}")
@Slf4j // For logging

public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    /**
     * Adds a product to the shopping cart for a given organization.
     * Can be used by logged-in users or guests.
     *
     * @param orgId The ID of the tenant/organization.
     * @param userId The ID of the logged-in user (from X-User-ID header).
     * @param guestId The ID of the guest user (from _guest_id cookie).
     * @param request The AddItemRequest containing productId and quantity.
     * @return The updated cart details.
     */
    @PostMapping("/items")
    public ResponseEntity<CartDto> addItemToCart(
            @PathVariable String orgId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @CookieValue(value = "_guest_id", required = false) String guestId,
            @Valid @RequestBody AddItemRequest request) {

        log.info("Received request to add item to cart for orgId: {}, userId: {}, guestId: {}. ProductId: {}, Quantity: {}",
                orgId, userId, guestId, request.getProductId(), request.getQuantity());

        // Ensure request DTO contains the user/guest context for service layer
        if (userId != null && !userId.isBlank()) {
            request.setUserId(userId);
        } else if (guestId != null && !guestId.isBlank()) {
            request.setGuestId(guestId);
        } else {
            // This case should ideally be caught by API Gateway or frontend before it reaches here
            // or handled by creating a new guest ID for the user
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User or Guest ID is required to add items to cart.");
        }

        try {
            CartDto updatedCart = cartService.addItemToCart(orgId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(updatedCart);
        } catch (ProductNotFoundException e) {
            log.warn("Product not found during add item to cart: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (InsufficientStockException e) {
            log.warn("Insufficient stock during add item to cart: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (ServiceCommunicationException e) {
            log.error("Service communication error during add item to cart: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dependent service unavailable.", e);
        } catch (IllegalArgumentException e) {
            log.error("Invalid arguments for add item to cart: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (ExecutionException | InterruptedException e) {
            log.error("Internal server error during add item to cart: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to add item to cart due to internal error.", e);
        }
    }

    /**
     * Retrieves the current shopping cart details for a given organization and user/guest.
     *
     * @param orgId The ID of the tenant/organization.
     * @param userId The ID of the logged-in user (from X-User-ID header).
     * @param guestId The ID of the guest user (from _guest_id cookie).
     * @return The current cart details.
     */
    @GetMapping
    public ResponseEntity<CartDto> getCartDetails(
            @PathVariable String orgId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @CookieValue(value = "_guest_id", required = false) String guestId) {

        log.info("Received request to get cart details for orgId: {}, userId: {}, guestId: {}", orgId, userId, guestId);

        if ((userId == null || userId.isBlank()) && (guestId == null || guestId.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User or Guest ID is required to retrieve cart.");
        }

        try {
            CartDto cart = cartService.getCartDetails(orgId, userId, guestId);
            return ResponseEntity.ok(cart);
        } catch (CartNotFoundException e) {
            log.info("Cart not found for orgId: {}, userId: {}, guestId: {}", orgId, userId, guestId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null); // Return 404 with empty body
        } catch (IllegalArgumentException e) {
            log.error("Invalid arguments for get cart details: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /**
     * Updates the quantity of a specific item in the cart.
     *
     * @param orgId The ID of the tenant/organization.
     * @param cartItemId The ID of the cart item to update.
     * @param request The UpdateItemQuantityRequest containing the new quantity.
     * @return The updated cart details.
     */
    @PutMapping("/items/{cartItemId}")
    public ResponseEntity<CartDto> updateItemQuantity(
            @PathVariable String orgId,
            @PathVariable String cartItemId,
            @Valid @RequestBody UpdateItemQuantityRequest request) {

        log.info("Received request to update quantity for cartItemId: {} in orgId: {}. New quantity: {}",
                cartItemId, orgId, request.getQuantity());

        try {
            CartDto updatedCart = cartService.updateItemQuantity(orgId, cartItemId, request);
            return ResponseEntity.ok(updatedCart);
        } catch (CartNotFoundException e) {
            log.warn("Cart item not found during update quantity: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (InsufficientStockException e) {
            log.warn("Insufficient stock during update quantity for cartItemId {}: {}", cartItemId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (ServiceCommunicationException e) {
            log.error("Service communication error during update item quantity: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dependent service unavailable.", e);
        } catch (ExecutionException | InterruptedException e) {
            log.error("Internal server error during update item quantity: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update item quantity due to internal error.", e);
        }
    }

    /**
     * Removes a specific item from the cart.
     *
     * @param orgId The ID of the tenant/organization.
     * @param cartItemId The ID of the cart item to remove.
     * @return No content if successful.
     */
    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<Void> removeItemFromCart(
            @PathVariable String orgId,
            @PathVariable String cartItemId) {

        log.info("Received request to remove cartItemId: {} from cart for orgId: {}", cartItemId, orgId);

        try {
            cartService.removeItemFromCart(orgId, cartItemId);
            return ResponseEntity.noContent().build();
        } catch (CartNotFoundException e) {
            log.warn("Cart item not found during remove item: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (ExecutionException | InterruptedException e) {
            log.error("Internal server error during remove item from cart: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to remove item from cart due to internal error.", e);
        }
    }

    /**
     * Clears the entire cart for a given user or guest.
     *
     * @param orgId The ID of the tenant/organization.
     * @param userId The ID of the logged-in user (from X-User-ID header).
     * @param guestId The ID of the guest user (from _guest_id cookie).
     * @return No content if successful.
     */
    @DeleteMapping
    public ResponseEntity<Void> clearCart(
            @PathVariable String orgId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @CookieValue(value = "_guest_id", required = false) String guestId) {

        log.info("Received request to clear cart for orgId: {}, userId: {}, guestId: {}", orgId, userId, guestId);

        if ((userId == null || userId.isBlank()) && (guestId == null || guestId.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User or Guest ID is required to clear cart.");
        }

        try {
            cartService.clearCart(orgId, userId, guestId);
            return ResponseEntity.noContent().build();
        } catch (CartNotFoundException e) {
            log.warn("Cart not found during clear cart: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.error("Invalid arguments for clear cart: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (ExecutionException | InterruptedException e) {
            log.error("Internal server error during clear cart: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to clear cart due to internal error.", e);
        }
    }

    /**
     * Merges a guest cart into a user's cart upon login.
     *
     * @param orgId The ID of the tenant/organization.
     * @param request The MergeCartRequest containing guestId and userId.
     * @return The merged user cart details.
     */
    @PostMapping("/merge")
    public ResponseEntity<CartDto> mergeGuestCart(
            @PathVariable String orgId,
            @Valid @RequestBody MergeCartRequest request) {

        log.info("Received request to merge guest cart {} into user cart {} for orgId: {}",
                request.getGuestId(), request.getUserId(), orgId);

        try {
            CartDto mergedCart = cartService.mergeGuestCart(orgId, request);
            return ResponseEntity.ok(mergedCart);
        } catch (CartNotFoundException e) {
            log.warn("Cart not found during merge guest cart: {}", e.getMessage());
            // This could happen if the target user cart doesn't exist, but it should be created by service.
            // Or if guest cart was already merged/cleared. Adjust as per desired behavior.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.error("Invalid arguments for merge guest cart: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (ExecutionException | InterruptedException e) {
            log.error("Internal server error during merge guest cart: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to merge carts due to internal error.", e);
        }
    }

    /**
     * Estimates shipping costs for the current cart to a given pincode.
     * This API should be called before proceeding to full checkout.
     *
     * @param orgId The ID of the tenant/organization.
     * @param userId The ID of the logged-in user (from X-User-ID header).
     * @param guestId The ID of the guest user (from _guest_id cookie).
     * @return A list of available delivery options with estimated costs.
     */

    @GetMapping("/estimateshippingcost")
    public ResponseEntity<ShippingEstimate> estimateShipping(
            @PathVariable String orgId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @CookieValue(value = "_guest_id", required = false) String guestId,
            @RequestParam("destinationPincode") @Valid int destinationPincode)   {

        log.info("Received request to estimate shipping for orgId: {}, userId: {}, guestId: {}, pincode: {}",
                orgId, userId, guestId, destinationPincode);

        // You'll need to pass userId/guestId to the service layer as well
        // You might consider adding userId/guestId fields directly to ShippingEstimateRequest
        // or passing them separately. For now, let's assume service handles it.

        try {

            ShippingEstimate estimate = cartService.estimateShipping(orgId, userId, guestId, destinationPincode);
            return ResponseEntity.ok(estimate);
        } catch (CartNotFoundException e) {
            log.warn("Cart not found when estimating shipping: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.error("Invalid arguments for shipping estimation: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (ServiceCommunicationException e) {
            log.error("Service communication error with delivery partner API during shipping estimation: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Could not get shipping estimates. Please try again later.", e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
