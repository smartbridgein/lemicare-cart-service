package com.lemicare.shoppingcart.service;


import com.cosmicdoc.common.model.Cart;
import com.cosmicdoc.common.model.CartItem;
import com.cosmicdoc.common.repository.CartItemRepository;
import com.cosmicdoc.common.repository.CartRepository;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import com.lemicare.shoppingcart.client.InventoryServiceClient;
import com.lemicare.shoppingcart.client.StorefrontServiceClient;
import com.lemicare.shoppingcart.dto.request.AddItemRequest;
import com.lemicare.shoppingcart.dto.request.CartDto;
import com.lemicare.shoppingcart.dto.request.MergeCartRequest;
import com.lemicare.shoppingcart.dto.request.UpdateItemQuantityRequest;
import com.lemicare.shoppingcart.exception.CartNotFoundException;
import com.lemicare.shoppingcart.exception.InsufficientStockException;
import com.lemicare.shoppingcart.exception.ProductNotFoundException;
import com.lemicare.shoppingcart.exception.ServiceCommunicationException;
import com.lemicare.shoppingcart.mapper.CartMapper;
import feign.FeignException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;



import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
@Slf4j // For logging
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final StorefrontServiceClient storefrontServiceClient;
    private final InventoryServiceClient inventoryServiceClient;
    private final Firestore firestore;
    private final CartMapper cartMapper;


  //  @Transactional
    public CartDto addItemToCart(String orgId, AddItemRequest request)
            throws ExecutionException, InterruptedException {

        // --- External Service Calls (outside Firestore transaction for performance) ---
        StorefrontServiceClient.ProductDetailsResponse productDetails;
        try {
            productDetails = storefrontServiceClient.getProductDetails(orgId, request.getProductId());
            if (productDetails == null) {
                log.warn("Product details not found for productId: {} in orgId: {}", request.getProductId(), orgId);
                throw new ProductNotFoundException("Product not found: " + request.getProductId());
            }
        } catch (FeignException e) {
            log.error("Failed to get product details from Storefront Service for productId: {} in orgId: {}. Error: {}",
                    request.getProductId(), orgId, e.getMessage());
            throw new ServiceCommunicationException("Failed to retrieve product details.", e);
        }

        Integer availableStock;
        try {
            availableStock = inventoryServiceClient.getProductStock(orgId, request.getProductId());
            if (availableStock == null) {
                log.warn("Stock information not found for productId: {} in orgId: {}", request.getProductId(), orgId);
                // Treat as no stock if info is missing
                throw new InsufficientStockException("Stock information unavailable for product: " + request.getProductId());
            }
            if (availableStock < request.getQuantity()) {
                log.warn("Insufficient stock for productId: {} in orgId: {}. Requested: {}, Available: {}",
                        request.getProductId(), orgId, request.getQuantity(), availableStock);
                throw new InsufficientStockException("Insufficient stock for product: " + productDetails.name());
            }
        } catch (FeignException e) {
            log.error("Failed to get product stock from Inventory Service for productId: {} in orgId: {}. Error: {}",
                    request.getProductId(), orgId, e.getMessage());
            throw new ServiceCommunicationException("Failed to retrieve product stock.", e);
        }
        // --- End External Service Calls ---

        // Use Firestore Transaction for atomicity of Cart and CartItem updates
        return firestore.runTransaction((Transaction.Function<CartDto>) transaction -> {
            Cart cart;
            Optional<Cart> existingCartOptional;

            if (request.getUserId() != null && !request.getUserId().isBlank()) {
                existingCartOptional = cartRepository.findByOrgIdAndUserId(orgId, request.getUserId());
            } else if (request.getGuestId() != null && !request.getGuestId().isBlank()) {
                existingCartOptional = cartRepository.findByOrgIdAndGuestId(orgId, request.getGuestId());
            } else {
                log.error("Attempted to add item without userId or guestId for orgId: {}", orgId);
                throw new IllegalArgumentException("Either userId or guestId must be provided.");
            }

            if (existingCartOptional.isPresent()) {
                // If cart exists, re-read it within the transaction to ensure we're working with the latest state
                cart = transaction.get(firestore.collection("carts").document(existingCartOptional.get().getCartId())).get().toObject(Cart.class);
                if (cart == null) { // Should not happen if present, but good defensive check
                    log.error("Cart found by query but not in transaction read. CartId: {}", existingCartOptional.get().getCartId());
                    throw new CartNotFoundException("Internal error: Cart not found during transaction.");
                }
            } else {
                // Create a new cart
                cart = Cart.builder()
                        .cartId(UUID.randomUUID().toString())
                        .orgId(orgId)
                        .userId(request.getUserId())
                        .guestId(request.getGuestId())
                        .status("ACTIVE")
                        .createdAt(LocalDateTime.now())
                        .totalItems(0)
                        .subtotalAmount(0.0)
                        .build();
                log.info("Created new cart with cartId: {} for orgId: {} (userId: {}, guestId: {})",
                        cart.getCartId(), orgId, request.getUserId(), request.getGuestId());
            }

            // Find existing cart item or create new one
            Optional<CartItem> existingCartItemOptional = cartItemRepository.findByCartIdAndProductId(cart.getCartId(), request.getProductId());
            CartItem cartItem;

            if (existingCartItemOptional.isPresent()) {
                // Re-read existing cart item within the transaction
                cartItem = transaction.get(firestore.collection("cartItems").document(existingCartItemOptional.get().getCartItemId())).get().toObject(CartItem.class);
                if (cartItem == null) { // Defensive check
                    log.error("Cart item found by query but not in transaction read. CartItemId: {}", existingCartItemOptional.get().getCartItemId());
                    throw new CartNotFoundException("Internal error: Cart item not found during transaction.");
                }
                log.info("Updating existing cart item {} in cart {}. Old quantity: {}, new quantity: {}",
                        cartItem.getCartItemId(), cart.getCartId(), cartItem.getQuantity(), cartItem.getQuantity() + request.getQuantity());

                // Update quantity and recalculate
                cartItem.setQuantity(cartItem.getQuantity() + request.getQuantity());
                cartItem.setItemTotalPrice(cartItem.getPriceAtAddToCart() * cartItem.getQuantity());
                cartItem.setLastModifiedAt(LocalDateTime.now());
            } else {
                // Create a new cart item
                cartItem = CartItem.builder()
                        .cartItemId(UUID.randomUUID().toString())
                        .orgId(orgId)
                        .cartId(cart.getCartId())
                        .productId(productDetails.productId())
                        .productName(productDetails.name())
                        .productImageUrl(productDetails.imageUrl())
                        .priceAtAddToCart(productDetails.price()) // Store price at the time of adding
                        .quantity(request.getQuantity())
                        .itemTotalPrice(productDetails.price() * request.getQuantity())
                        .addedAt(LocalDateTime.now())
                        .lastModifiedAt(LocalDateTime.now())
                        .sku("N/A") // Placeholder, should come from productDetails if available
                        .build();
                log.info("Added new cart item {} for product {} to cart {}",
                        cartItem.getCartItemId(), cartItem.getProductId(), cart.getCartId());
            }

            // Update denormalized fields in Cart
            // Fetch all items (including the one just updated/added) to recalculate totals
            List<CartItem> itemsInCart = cartItemRepository.findByCartId(cart.getCartId()); // Fetch all items
            // Replace the updated item or add the new item for correct calculation
            List<CartItem> itemsForRecalculation = itemsInCart.stream()
                    .filter(item -> !item.getProductId().equals(cartItem.getProductId()))
                    .collect(Collectors.toCollection(ArrayList::new));
            itemsForRecalculation.add(cartItem); // Add the newly created/updated item

            int newTotalItems = itemsForRecalculation.stream().mapToInt(CartItem::getQuantity).sum();
            double newSubtotalAmount = itemsForRecalculation.stream().mapToDouble(CartItem::getItemTotalPrice).sum();

            cart.setTotalItems(newTotalItems);
            cart.setSubtotalAmount(newSubtotalAmount);
            cart.setLastModifiedAt(LocalDateTime.now());

            // Persist changes within the transaction
            transaction.set(firestore.collection("carts").document(cart.getCartId()), cart);
            transaction.set(firestore.collection("cartItems").document(cartItem.getCartItemId()), cartItem);
            log.debug("Cart {} and CartItem {} updated/created within transaction.", cart.getCartId(), cartItem.getCartItemId());

            // Re-fetch all cart items to return a complete DTO (optional, but ensures consistency after transaction)
            List<CartItem> finalCartItems = cartItemRepository.findByCartId(cart.getCartId());
            return cartMapper.toDto(cart, finalCartItems);
        }).get(); // Execute and wait for transaction to complete
    }

    public CartDto getCartDetails(String orgId, String userId, String guestId)
            throws ExecutionException, InterruptedException {
        Optional<Cart> cartOptional;
        if (userId != null && !userId.isBlank()) {
            cartOptional = cartRepository.findByOrgIdAndUserId(orgId, userId);
        } else if (guestId != null && !guestId.isBlank()) {
            cartOptional = cartRepository.findByOrgIdAndGuestId(orgId, guestId);
        } else {
            log.error("Attempted to get cart without userId or guestId for orgId: {}", orgId);
            throw new IllegalArgumentException("Either userId or guestId must be provided.");
        }

        Cart cart = cartOptional.orElseThrow(() -> new CartNotFoundException("Cart not found for the given user/guest ID."));
        if (!cart.getOrgId().equals(orgId)) { // Double-check multi-tenancy
            log.warn("Cart {} found but does not belong to orgId {}. Potential data access issue.", cart.getCartId(), orgId);
            throw new CartNotFoundException("Cart not found or does not belong to the organization.");
        }

        List<CartItem> items = cartItemRepository.findByCartId(cart.getCartId());
        log.debug("Fetched cart details for cartId: {} with {} items.", cart.getCartId(), items.size());
        return cartMapper.toDto(cart, items);
    }

  //  @Transactional
    public CartDto updateItemQuantity(String orgId, String cartItemId, @Valid UpdateItemQuantityRequest request)
            throws ExecutionException, InterruptedException {

        return firestore.runTransaction((Transaction.Function<CartDto>) transaction -> {
            // Read cart item within the transaction
            CartItem cartItem = transaction.get(firestore.collection("cartItems").document(cartItemId)).get().toObject(CartItem.class);
            if (cartItem == null || !cartItem.getOrgId().equals(orgId)) {
                log.warn("Cart item {} not found or does not belong to orgId {}.", cartItemId, orgId);
                throw new CartNotFoundException("Cart item not found or does not belong to the organization.");
            }

            // Read parent cart within the transaction
            Cart cart = transaction.get(firestore.collection("carts").document(cartItem.getCartId())).get().toObject(Cart.class);
            if (cart == null || !cart.getOrgId().equals(orgId)) {
                log.warn("Parent cart for item {} not found or does not belong to orgId {}.", cartItemId, orgId);
                throw new CartNotFoundException("Parent cart not found or does not belong to the organization.");
            }

            // 1. Validate stock
            Integer availableStock;
            try {
                availableStock = inventoryServiceClient.getProductStock(orgId, cartItem.getProductId());
                if (availableStock == null || availableStock < request.getQuantity()) {
                    log.warn("Insufficient stock for productId: {} (cartItemId: {}). Requested: {}, Available: {}",
                            cartItem.getProductId(), cartItemId, request.getQuantity(), availableStock);
                    throw new InsufficientStockException("Insufficient stock for product: " + cartItem.getProductName());
                }
            } catch (FeignException e) {
                log.error("Failed to get product stock during update for cartItemId: {}. Error: {}", cartItemId, e.getMessage());
                throw new ServiceCommunicationException("Failed to retrieve product stock for update.", e);
            }

            log.info("Updating quantity for cart item {} in cart {}. Old quantity: {}, new quantity: {}",
                    cartItemId, cart.getCartId(), cartItem.getQuantity(), request.getQuantity());

            cartItem.setQuantity(request.getQuantity());
            cartItem.setItemTotalPrice(cartItem.getPriceAtAddToCart() * cartItem.getQuantity());
            cartItem.setLastModifiedAt(LocalDateTime.now());

            // Update denormalized fields in Cart
            List<CartItem> currentCartItems = cartItemRepository.findByCartId(cart.getCartId());
            List<CartItem> itemsForRecalculation = currentCartItems.stream()
                    .filter(item -> !item.getCartItemId().equals(cartItem.getCartItemId()))
                    .collect(Collectors.toCollection(ArrayList::new));
            itemsForRecalculation.add(cartItem); // Add the updated item

            int newTotalItems = itemsForRecalculation.stream().mapToInt(CartItem::getQuantity).sum();
            double newSubtotalAmount = itemsForRecalculation.stream().mapToDouble(CartItem::getItemTotalPrice).sum();

            cart.setTotalItems(newTotalItems);
            cart.setSubtotalAmount(newSubtotalAmount);
            cart.setLastModifiedAt(LocalDateTime.now());

            transaction.set(firestore.collection("cartItems").document(cartItem.getCartItemId()), cartItem);
            transaction.set(firestore.collection("carts").document(cart.getCartId()), cart);
            log.debug("Cart {} and CartItem {} quantity updated within transaction.", cart.getCartId(), cartItem.getCartItemId());

            List<CartItem> updatedItems = cartItemRepository.findByCartId(cart.getCartId());
            return cartMapper.toDto(cart, updatedItems);
        }).get();
    }

   // @Transactional
    public void removeItemFromCart(String orgId, String cartItemId)
            throws ExecutionException, InterruptedException {

        firestore.runTransaction((Transaction.Function<Void>) transaction -> {
            CartItem cartItem = transaction.get(firestore.collection("cartItems").document(cartItemId)).get().toObject(CartItem.class);
            if (cartItem == null || !cartItem.getOrgId().equals(orgId)) {
                log.warn("Cart item {} not found or does not belong to orgId {}.", cartItemId, orgId);
                throw new CartNotFoundException("Cart item not found or does not belong to the organization.");
            }

            Cart cart = transaction.get(firestore.collection("carts").document(cartItem.getCartId())).get().toObject(Cart.class);
            if (cart == null || !cart.getOrgId().equals(orgId)) {
                log.warn("Parent cart for item {} not found or does not belong to orgId {}.", cartItemId, orgId);
                throw new CartNotFoundException("Parent cart not found or does not belong to the organization.");
            }

            log.info("Removing cart item {} from cart {}.", cartItemId, cart.getCartId());

            // Update denormalized fields in Cart
            cart.setTotalItems(cart.getTotalItems() - cartItem.getQuantity());
            cart.setSubtotalAmount(cart.getSubtotalAmount() - cartItem.getItemTotalPrice());
            cart.setLastModifiedAt(LocalDateTime.now());

            transaction.delete(firestore.collection("cartItems").document(cartItemId));
            transaction.set(firestore.collection("carts").document(cart.getCartId()), cart);

            // If cart becomes empty, consider marking it as ABANDONED or deleting it
            if (cart.getTotalItems() <= 0) { // Use <= 0 in case of negative quantities due to bugs
                cart.setStatus("ABANDONED");
                log.info("Cart {} is now empty, setting status to ABANDONED.", cart.getCartId());
                transaction.set(firestore.collection("carts").document(cart.getCartId()), cart);
            }
            return null;
        }).get();
    }

   // @Transactional
    public void clearCart(String orgId, String userId, String guestId)
            throws ExecutionException, InterruptedException {

        firestore.runTransaction((Transaction.Function<Void>) transaction -> {
            Optional<Cart> cartOptional;
            if (userId != null && !userId.isBlank()) {
                cartOptional = cartRepository.findByOrgIdAndUserId(orgId, userId);
            } else if (guestId != null && !guestId.isBlank()) {
                cartOptional = cartRepository.findByOrgIdAndGuestId(orgId, guestId);
            } else {
                log.error("Attempted to clear cart without userId or guestId for orgId: {}", orgId);
                throw new IllegalArgumentException("Either userId or guestId must be provided.");
            }

            Cart cart = cartOptional.orElseThrow(() -> new CartNotFoundException("Cart not found for the given user/guest ID."));
            if (!cart.getOrgId().equals(orgId)) {
                log.warn("Cart {} found but does not belong to orgId {}. Potential data access issue.", cart.getCartId(), orgId);
                throw new CartNotFoundException("Cart not found or does not belong to the organization.");
            }

            log.info("Clearing cart {} for orgId: {} (userId: {}, guestId: {})", cart.getCartId(), orgId, userId, guestId);

            // Fetch items to delete them
            List<CartItem> itemsToDelete = cartItemRepository.findByCartId(cart.getCartId());
            for (CartItem item : itemsToDelete) {
                transaction.delete(firestore.collection("cartItems").document(item.getCartItemId()));
            }

            // Mark cart as cleared
            cart.setStatus("CLEARED");
            cart.setTotalItems(0);
            cart.setSubtotalAmount(0.0);
            cart.setLastModifiedAt(LocalDateTime.now());
            transaction.set(firestore.collection("carts").document(cart.getCartId()), cart);
            log.debug("Cart {} items deleted and cart status set to CLEARED.", cart.getCartId());

            return null;
        }).get();
    }

  //  @Transactional
    public CartDto mergeGuestCart(String orgId, @Valid MergeCartRequest request)
            throws ExecutionException, InterruptedException {

        return firestore.runTransaction((Transaction.Function<CartDto>) transaction -> {
            // 1. Get the guest cart
            Optional<Cart> guestCartOptional = cartRepository.findByOrgIdAndGuestId(orgId, request.getGuestId());
            if (guestCartOptional.isEmpty() || !guestCartOptional.get().getOrgId().equals(orgId)) {
                log.info("No active guest cart found for guestId: {} in orgId: {}. No merge needed.", request.getGuestId(), orgId);
                // If no guest cart, just return the user's cart or create a new one
                return getOrCreateUserCart(orgId, request.getUserId(), transaction);
            }
            Cart guestCart = transaction.get(firestore.collection("carts").document(guestCartOptional.get().getCartId())).get().toObject(Cart.class);
            if (guestCart == null) { // Defensive check
                log.error("Guest cart found by query but not in transaction read. GuestCartId: {}", guestCartOptional.get().getCartId());
                throw new CartNotFoundException("Internal error: Guest cart not found during transaction.");
            }
            List<CartItem> guestCartItems = cartItemRepository.findByCartId(guestCart.getCartId());

            if (guestCartItems.isEmpty()) {
                // If guest cart is empty, simply delete it and return the user's cart (or a new one)
                log.info("Guest cart {} is empty, deleting it and returning user's cart.", guestCart.getCartId());
                transaction.delete(firestore.collection("carts").document(guestCart.getCartId()));
                return getOrCreateUserCart(orgId, request.getUserId(), transaction);
            }

            // 2. Get the user's existing cart or create a new one
            Optional<Cart> userCartOptional = cartRepository.findByOrgIdAndUserId(orgId, request.getUserId());
            Cart userCart;
            List<CartItem> userCartItems = new ArrayList<>();

            if (userCartOptional.isPresent()) {
                userCart = transaction.get(firestore.collection("carts").document(userCartOptional.get().getCartId())).get().toObject(Cart.class);
                if (userCart == null) { // Defensive check
                    log.error("User cart found by query but not in transaction read. UserCartId: {}", userCartOptional.get().getCartId());
                    throw new CartNotFoundException("Internal error: User cart not found during transaction.");
                }
                userCartItems.addAll(cartItemRepository.findByCartId(userCart.getCartId()));
                log.info("User {} has an existing cart {}. Merging guest cart {} into it.", request.getUserId(), userCart.getCartId(), guestCart.getCartId());
            } else {
                userCart = Cart.builder()
                        .cartId(UUID.randomUUID().toString())
                        .orgId(orgId)
                        .userId(request.getUserId())
                        .status("ACTIVE")
                        .createdAt(LocalDateTime.now())
                        .totalItems(0)
                        .subtotalAmount(0.0)
                        .build();
                log.info("Created new user cart {} for userId: {} in orgId: {}", userCart.getCartId(), request.getUserId(), orgId);
            }

            // 3. Merge items from guest cart into user cart
            for (CartItem guestItem : guestCartItems) {
                Optional<CartItem> existingUserItemOptional = userCartItems.stream()
                        .filter(item -> item.getProductId().equals(guestItem.getProductId()))
                        .findFirst();

                if (existingUserItemOptional.isPresent()) {
                    // Update quantity of existing user cart item
                    CartItem existingUserItem = transaction.get(firestore.collection("cartItems").document(existingUserItemOptional.get().getCartItemId())).get().toObject(CartItem.class);
                    existingUserItem.setQuantity(existingUserItem.getQuantity() + guestItem.getQuantity());
                    existingUserItem.setItemTotalPrice(existingUserItem.getPriceAtAddToCart() * existingUserItem.getQuantity());
                    existingUserItem.setLastModifiedAt(LocalDateTime.now());
                    transaction.set(firestore.collection("cartItems").document(existingUserItem.getCartItemId()), existingUserItem);
                    log.debug("Merged guest item {} (qty {}) into existing user item {} (new qty {})",
                            guestItem.getCartItemId(), guestItem.getQuantity(), existingUserItem.getCartItemId(), existingUserItem.getQuantity());
                } else {
                    // Add new item to user cart
                    CartItem newUserItem = CartItem.builder()
                            .cartItemId(UUID.randomUUID().toString()) // New ID for new item in user's cart
                            .orgId(orgId)
                            .cartId(userCart.getCartId()) // Link to user's cart
                            .productId(guestItem.getProductId())
                            .productName(guestItem.getProductName())
                            .productImageUrl(guestItem.getProductImageUrl())
                            .priceAtAddToCart(guestItem.getPriceAtAddToCart())
                            .quantity(guestItem.getQuantity())
                            .itemTotalPrice(guestItem.getItemTotalPrice())
                            .addedAt(LocalDateTime.now())
                            .lastModifiedAt(LocalDateTime.now())
                            .sku(guestItem.getSku())
                            .build();
                    transaction.set(firestore.collection("cartItems").document(newUserItem.getCartItemId()), newUserItem);
                    log.debug("Added new item {} from guest cart to user cart {}.", newUserItem.getCartItemId(), userCart.getCartId());
                }
                // Delete the guest cart item
                transaction.delete(firestore.collection("cartItems").document(guestItem.getCartItemId()));
            }

            // 4. Update user cart totals and save
            List<CartItem> finalUserCartItems = cartItemRepository.findByCartId(userCart.getCartId()); // Re-fetch for accurate totals
            userCart.setTotalItems(finalUserCartItems.stream().mapToInt(CartItem::getQuantity).sum());
            userCart.setSubtotalAmount(finalUserCartItems.stream().mapToDouble(CartItem::getItemTotalPrice).sum());
            userCart.setLastModifiedAt(LocalDateTime.now());
            userCart.setGuestId(null); // Clear guest ID from user's cart once merged
            transaction.set(firestore.collection("carts").document(userCart.getCartId()), userCart);
            log.info("User cart {} totals updated after merge. Total items: {}, Subtotal: {}",
                    userCart.getCartId(), userCart.getTotalItems(), userCart.getSubtotalAmount());

            // 5. Mark guest cart as merged and delete or set status
            guestCart.setStatus("MERGED_TO_USER_CART");
            guestCart.setLastModifiedAt(LocalDateTime.now());
            transaction.set(firestore.collection("carts").document(guestCart.getCartId()), guestCart);
            // Alternatively, transaction.delete(firestore.collection("carts").document(guestCart.getCartId())); if you want to remove it completely

            return cartMapper.toDto(userCart, finalUserCartItems);
        }).get();
    }

    // Helper method to get or create a user cart, used during merge if no guest cart or empty guest cart
    private CartDto getOrCreateUserCart(String orgId, String userId, Transaction transaction) throws ExecutionException, InterruptedException {
        Optional<Cart> userCartOptional = cartRepository.findByOrgIdAndUserId(orgId, userId);
        Cart userCart;
        if (userCartOptional.isPresent()) {
            userCart = transaction.get(firestore.collection("carts").document(userCartOptional.get().getCartId())).get().toObject(Cart.class);
            if (userCart == null) throw new CartNotFoundException("Internal error: User cart not found during transaction.");
            log.info("Found existing user cart {} for userId: {} in orgId: {}", userCart.getCartId(), userId, orgId);
        } else {
            userCart = Cart.builder()
                    .cartId(UUID.randomUUID().toString())
                    .orgId(orgId)
                    .userId(userId)
                    .status("ACTIVE")
                    .createdAt(LocalDateTime.now())
                    .totalItems(0)
                    .subtotalAmount(0.0)
                    .build();
            transaction.set(firestore.collection("carts").document(userCart.getCartId()), userCart);
            log.info("Created new user cart {} for userId: {} in orgId: {}", userCart.getCartId(), userId, orgId);
        }
        List<CartItem> items = cartItemRepository.findByCartId(userCart.getCartId());
        return cartMapper.toDto(userCart, items);
    }
}
