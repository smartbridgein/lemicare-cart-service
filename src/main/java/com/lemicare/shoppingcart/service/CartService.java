package com.lemicare.shoppingcart.service;


import com.cosmicdoc.common.model.*;
import com.cosmicdoc.common.repository.CartItemRepository;
import com.cosmicdoc.common.repository.CartRepository;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import com.lemicare.shoppingcart.client.DeliveryServiceClient;
import com.lemicare.shoppingcart.client.InventoryServiceClient;
import com.lemicare.shoppingcart.client.StorefrontServiceClient;
import com.lemicare.shoppingcart.dto.request.*;
import com.lemicare.shoppingcart.dto.response.CourierServiceabilityResponse;
import com.lemicare.shoppingcart.dto.response.DeliveryOption;
import com.lemicare.shoppingcart.dto.response.ShippingEstimate;
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


import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    private final DeliveryServiceClient deliveryServiceClient;

    public CartDto addItemToCart(String orgId, AddItemRequest request)
            throws ExecutionException, InterruptedException {

        var ref = new Object() {
            StorefrontProduct productDetails = null;
        };
        try {
            ref.productDetails = storefrontServiceClient.getProductDetails(orgId, request.getProductId());
            if (ref.productDetails == null) {
                log.warn("Product details not found for productId: {} in orgId: {}", request.getProductId(), orgId);
                throw new ProductNotFoundException("Product not found: " + request.getProductId());
            }
        } catch (FeignException e) {
            log.error("Failed to get product details from Storefront Service for productId: {} in orgId: {}. Error: {}",
                    request.getProductId(), orgId, e.getMessage());
            throw new ServiceCommunicationException("Failed to retrieve product details.", e);
        }
/*
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
        }*/
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
                        .createdAt(Timestamp.now())
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
                cartItem.setLastModifiedAt(Timestamp.now());
            } else {
                // Create a new cart item
                cartItem = CartItem.builder()
                        .cartItemId(UUID.randomUUID().toString())
                        .orgId(orgId)
                        .cartId(cart.getCartId())
                        .productId(request.getProductId())
                        .productName(ref.productDetails.getProductName())
                       // .productImageUrl(productDetails.imageUrl())
                        .priceAtAddToCart(ref.productDetails.getMrp()) // Store price at the time of adding
                        .quantity(request.getQuantity())
                        .itemTotalPrice(ref.productDetails.getMrp() * request.getQuantity())
                        .addedAt(Timestamp.now())
                        .lastModifiedAt(Timestamp.now())
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
            cart.setLastModifiedAt(Timestamp.now());

            // Persist changes within the transaction
            transaction.set(firestore.collection("carts").document(cart.getCartId()), cart);
            transaction.set(firestore.collection("cartItems").document(cartItem.getCartItemId()), cartItem);
            log.debug("Cart {} and CartItem {} updated/created within transaction.", cart.getCartId(), cartItem.getCartItemId());

            // Re-fetch all cart items to return a complete DTO (optional, but ensures consistency after transaction)
            List<CartItem> finalCartItems = cartItemRepository.findByCartId(cart.getCartId());
            return cartMapper.toDto(cart, finalCartItems);
        }).get(); // Execute and wait for transaction to complete
    }

    /*public CartDto getCartDetails(String orgId, String userId, String guestId)
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
    }*/

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
            cartItem.setLastModifiedAt(Timestamp.now());

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
            cart.setLastModifiedAt(Timestamp.now());

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
            cart.setLastModifiedAt(Timestamp.now());

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
            cart.setLastModifiedAt(Timestamp.now());
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
                        .createdAt(Timestamp.now())
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
                    existingUserItem.setLastModifiedAt(Timestamp.now());
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
                           // .productImageUrl(guestItem.getProductImageUrl())
                            .priceAtAddToCart(guestItem.getPriceAtAddToCart())
                            .quantity(guestItem.getQuantity())
                            .itemTotalPrice(guestItem.getItemTotalPrice())
                            .addedAt(Timestamp.now())
                            .lastModifiedAt(Timestamp.now())
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
            userCart.setLastModifiedAt(Timestamp.now());
            userCart.setGuestId(null); // Clear guest ID from user's cart once merged
            transaction.set(firestore.collection("carts").document(userCart.getCartId()), userCart);
            log.info("User cart {} totals updated after merge. Total items: {}, Subtotal: {}",
                    userCart.getCartId(), userCart.getTotalItems(), userCart.getSubtotalAmount());

            // 5. Mark guest cart as merged and delete or set status
            guestCart.setStatus("MERGED_TO_USER_CART");
            guestCart.setLastModifiedAt(Timestamp.now());
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
            if (userCart == null)
                throw new CartNotFoundException("Internal error: User cart not found during transaction.");
            log.info("Found existing user cart {} for userId: {} in orgId: {}", userCart.getCartId(), userId, orgId);
        } else {
            userCart = Cart.builder()
                    .cartId(UUID.randomUUID().toString())
                    .orgId(orgId)
                    .userId(userId)
                    .status("ACTIVE")
                    .createdAt(Timestamp.now())
                    .totalItems(0)
                    .subtotalAmount(0.0)
                    .build();
            transaction.set(firestore.collection("carts").document(userCart.getCartId()), userCart);
            log.info("Created new user cart {} for userId: {} in orgId: {}", userCart.getCartId(), userId, orgId);
        }
        List<CartItem> items = cartItemRepository.findByCartId(userCart.getCartId());
        return cartMapper.toDto(userCart, items);
    }

   /* public ShippingEstimate estimateShipping(String orgId, String userId, String guestId, int destinationPincode)
            throws CartNotFoundException, ServiceCommunicationException, ExecutionException, InterruptedException, ProductNotFoundException {

        // 1. Retrieve the current cart
        CartDto cart = getCartDetails(orgId, userId, guestId);

        if (cart.getItems().isEmpty()) {
            throw new IllegalArgumentException("Cannot estimate shipping for an empty cart.");
        }

        // 2. Fetch product details for all cart items concurrently from CMS
        List<String> productIds = cart.getItems().stream()
                .map(CartItemDto::getProductId)
                .distinct()
                .collect(Collectors.toList());

        // Map to hold product details by ID, for easy lookup
        Map<String, StorefrontProduct> productDetailsMap = new ConcurrentHashMap<>();

        // Create a list of CompletableFuture for fetching all product details

        // Wait for all product details to be fetched

        // Check if all products were found
       *//* if (productDetailsMap.size() != productIds.size()) {
            // This means some products were not found in CMS
            throw new ProductNotFoundException("Some products in the cart could not be found for shipping estimation.");
        }*//*

        // 3. Aggregate total weight and volume
        BigDecimal totalWeightKg = BigDecimal.ZERO;
        BigDecimal totalVolumeCubicCm = BigDecimal.ZERO; // Simplified: Assuming product volume, not packed volume

        for (CartItemDto item : cart.getItems()) {
            StorefrontProduct productDetails = productDetailsMap.get(item.getProductId());
            if (productDetails == null) {
                // This shouldn't happen if the above check passed, but good for defensive programming
                throw new ProductNotFoundException("Product details missing for cart item: " + item.getProductId());
            }

            // Accumulate weight, converting to KG if necessary (assuming CMS returns kg)
            if (productDetails.getWeight() != null && productDetails.getWeight().getValue() != null && productDetails.getWeight().getUnit() != null) {
                BigDecimal itemWeight = convertWeightToKg(productDetails.getWeight());
                totalWeightKg = totalWeightKg.add(itemWeight.multiply(BigDecimal.valueOf(item.getQuantity())));
            } else {
                log.warn("Product {} missing weight details, skipping for shipping calculation.", item.getProductId());
                // You might throw an error here if weight is mandatory for ALL products
            }

            // Accumulate volume, assuming cm for dimensions
            if (productDetails.getDimensions() != null ) {
                PhysicalDimensions dims = productDetails.getDimensions();
                // Ensure dimensions are in CM for calculation
                BigDecimal heightCm = convertDimensionToCm(dims.getHeight(), dims.getUnit());
                BigDecimal widthCm = convertDimensionToCm(dims.getWidth(), dims.getUnit());
                BigDecimal lengthCm = convertDimensionToCm(dims.getLength(), dims.getUnit());
                BigDecimal itemVolume = heightCm.multiply(widthCm).multiply(lengthCm);
                totalVolumeCubicCm = totalVolumeCubicCm.add(itemVolume.multiply(BigDecimal.valueOf(item.getQuantity())));
            } else {
                log.warn("Product {} missing valid dimension details, skipping for shipping calculation.", item.getProductId());
                // You might throw an error here if dimensions are mandatory for ALL products
            }
        }

        log.debug("Aggregated cart for shipping: Total Weight={}kg, Total Volume={}cm³", totalWeightKg, totalVolumeCubicCm);

        // 4. Determine Source Pincode (Example: Hardcoded for org, or fetched from org profile)
        Integer sourcePincode = Integer.valueOf(getOrganizationSourcePincode(orgId)); // Implement this method

        // 5. Call Delivery Partner Service to get quotes
        CourierServiceabilityRequest quoteRequest = CourierServiceabilityRequest.builder()
                .organizationId(orgId)
                .pickup_postcode(sourcePincode)
                .delivery_postcode(destinationPincode)
                .weight(totalWeightKg)
                .totalVolumeCubicCm(totalVolumeCubicCm)
               // .numberOfPackages(1) // Simplified: Assume all items can be in one package for now
                .build();

        List<DeliveryOption> deliveryOptions = Collections.singletonList(deliveryServiceClient.getAvailableCourierService(quoteRequest));
                // Block for simplicity, handle async properly in real app

        if (deliveryOptions.isEmpty()) {
            throw new ServiceCommunicationException("No delivery options available for the specified destination.");
        }

        // 6. Select the "best" option (e.g., cheapest) and mark it
        DeliveryOption bestOption = deliveryOptions.stream()
                .min(Comparator.comparing((java.util.function.Function<? super DeliveryOption, ? extends BigDecimal>) DeliveryOption::getCost).reversed())
                .orElseThrow(() -> new IllegalStateException("No options found after filtering.")); // Should not happen if list not empty

       // bestOption.setBestOption(true); // Mark the best one

        // 7. Build and return ShippingEstimateDto
        return ShippingEstimate.builder()
                .cartId(cart.getCartId())
                .destinationPincode(destinationPincode)
                .estimatedTotalShippingCost(bestOption.getCost()) // Only the cost of the best option as the "total"
                .deliveryOptions(deliveryOptions) // All options
                .build();
    }

    // --- Helper for Unit Conversion (Implement robustly for production) ---
    private BigDecimal convertWeightToKg(Weight weight) {
        if ("kg".equalsIgnoreCase(weight.getUnit())) {
            return weight.getValue();
        } else if ("g".equalsIgnoreCase(weight.getUnit())) {
            return weight.getValue().divide(new BigDecimal("1000"), 3, BigDecimal.ROUND_HALF_UP);
        }
        // Add more units as needed. Throw IllegalArgumentException for unknown units.
        log.warn("Unknown weight unit: {}. Assuming KG.", weight.getUnit());
        return weight.getValue(); // Fallback
    }

    private BigDecimal convertDimensionToCm(BigDecimal value, String unit) {
        if ("cm".equalsIgnoreCase(unit)) {
            return value;
        } else if ("mm".equalsIgnoreCase(unit)) {
            return value.divide(new BigDecimal("10"), 2, BigDecimal.ROUND_HALF_UP);
        } else if ("inch".equalsIgnoreCase(unit)) {
            return value.multiply(new BigDecimal("2.54")).setScale(2, BigDecimal.ROUND_HALF_UP);
        }
        // Add more units. Throw IllegalArgumentException for unknown units.
        log.warn("Unknown dimension unit: {}. Assuming CM.", unit);
        return value; // Fallback
    }


    // Placeholder: In a real app, this would come from organization configuration or a dedicated service.
    private String getOrganizationSourcePincode(String orgId) {
        // For demonstration, use a fixed pincode
        // In a real application, you'd fetch this from a configuration service or organization's profile
        return "600024"; // Example: A central warehouse/pickup location for the organization
    }
*/

    public ShippingEstimate estimateShipping(String orgId, String userId, String guestId, int destinationPincode)
            throws CartNotFoundException, ServiceCommunicationException, ExecutionException, InterruptedException, ProductNotFoundException {

        // 1. Retrieve the current cart
        CartDto cart = getCartDetails(orgId, userId, guestId);

        if (cart.getItems().isEmpty()) {
            throw new IllegalArgumentException("Cannot estimate shipping for an empty cart.");
        }

        // 2. Fetch product details for all cart items concurrently
        List<String> productIds = cart.getItems().stream()
                .map(CartItemDto::getProductId)
                .distinct()
                .collect(Collectors.toList());

        Map<String, StorefrontProduct> productDetailsMap = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String productId : productIds) {
            // Using CompletableFuture.runAsync or CompletableFuture.supplyAsync to run blocking calls concurrently
            // It's good practice to provide an Executor if you have a custom thread pool for blocking I/O
            // Otherwise, it uses ForkJoinPool.commonPool(), which might not be ideal for I/O bound tasks.
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    StorefrontProduct product = storefrontServiceClient.getProductDetails(orgId, productId);
                    if (product != null) {
                        productDetailsMap.put(productId, product);
                    } else {
                        log.warn("Product with ID {} not found via Storefront Service Client for org {}. Skipping.", productId, orgId);
                    }
                } catch (ProductNotFoundException e) {
                    log.warn("Product with ID {} not found via Storefront Service Client for org {}: {}", productId, orgId, e.getMessage());
                } catch (ServiceCommunicationException e) {
                    log.error("Service communication error fetching product {} from Storefront Service: {}", productId, e.getMessage());
                    // Decide if this should fail the whole process. For now, it will be added to the failed list.
                } catch (Exception e) {
                    log.error("Unexpected error fetching product {} from Storefront Service: {}", productId, e.getMessage(), e);
                }
            } /* , taskExecutor */ ); // Optionally pass taskExecutor here

            futures.add(future);
        }

        // Wait for all product details to be fetched
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            log.error("Failed to fetch all product details concurrently for cart {}: {}", cart.getCartId(), e.getMessage(), e);
            // Re-wrap and rethrow as a more specific exception if needed
            throw new ServiceCommunicationException("Failed to retrieve product details for shipping estimation.", e);
        }

        // Check if all products were found
        // If productDetailsMap size is less than productIds size, it means some products were not found or failed to fetch.
        // We compare against the *distinct* product IDs from the cart.
        if (productDetailsMap.size() != productIds.size()) {
            List<String> missingOrFailedProductIds = productIds.stream()
                    .filter(id -> !productDetailsMap.containsKey(id))
                    .collect(Collectors.toList());
            log.error("Some products in the cart could not be found or fetched for shipping estimation. Missing/Failed: {}", missingOrFailedProductIds);
            throw new ProductNotFoundException("Some products in the cart could not be found for shipping estimation. Missing IDs: " + missingOrFailedProductIds);
        }


        // 3. Aggregate total weight and volume
        BigDecimal totalWeightKg = BigDecimal.ZERO;
        BigDecimal maxItemLengthCm = BigDecimal.ZERO;
        BigDecimal maxItemWidthCm = BigDecimal.ZERO;
        BigDecimal maxItemHeightCm = BigDecimal.ZERO;
        BigDecimal cumulativeVolumeCubicCm = BigDecimal.ZERO;

        for (CartItemDto item : cart.getItems()) {
            StorefrontProduct productDetails = productDetailsMap.get(item.getProductId());
            // This check is now robust because we verified productDetailsMap earlier
            if (productDetails == null) {
                // This condition should ideally not be met if the above productDetailsMap check is thorough
                throw new ProductNotFoundException("Product details missing for cart item: " + item.getProductId() + " after initial fetch check.");
            }

            // Accumulate weight, converting to KG
            if (productDetails.getWeight() != null && productDetails.getWeight().getValue() != null && productDetails.getWeight().getUnit() != null) {
                BigDecimal itemWeight = convertWeightToKg(productDetails.getWeight());
                totalWeightKg = totalWeightKg.add(itemWeight.multiply(BigDecimal.valueOf(item.getQuantity())));
            } else {
                log.warn("Product {} missing weight details, assuming 0 for shipping calculation.", item.getProductId());
                // Depending on business rules, you might throw an error here if weight is mandatory
            }

            // Accumulate dimensions/volume
            if (productDetails.getDimensions() != null ) {
                PhysicalDimensions dims = productDetails.getDimensions();
                // Ensure values are not null before converting
                BigDecimal heightCm = convertDimensionToCm(dims.getHeight(), dims.getUnit());
                BigDecimal widthCm = convertDimensionToCm(dims.getWidth(), dims.getUnit());
                BigDecimal lengthCm = convertDimensionToCm(dims.getLength(), dims.getUnit());

                // Take the maximum dimensions among all items for a simplified "outer package"
                maxItemLengthCm = maxItemLengthCm.max(lengthCm);
                maxItemWidthCm = maxItemWidthCm.max(widthCm);
                maxItemHeightCm = maxItemHeightCm.max(heightCm);

                BigDecimal itemVolume = heightCm.multiply(widthCm).multiply(lengthCm);
                cumulativeVolumeCubicCm = cumulativeVolumeCubicCm.add(itemVolume.multiply(BigDecimal.valueOf(item.getQuantity())));
            } else {
                log.warn("Product {} missing valid dimension details, assuming 0 for shipping calculation.", item.getProductId());
                // You might throw an error here if dimensions are mandatory for ALL products
            }
        }

        log.debug("Aggregated cart for shipping: Total Weight={}kg, Cumulative Volume={}cm³, Max Item Dims L:{}cm W:{}cm H:{}cm",
                totalWeightKg, cumulativeVolumeCubicCm, maxItemLengthCm, maxItemWidthCm, maxItemHeightCm);

        // Handle cases where total weight or dimensions might be zero (e.g., all products missing data)
        if (totalWeightKg.compareTo(BigDecimal.ZERO) <= 0 && cumulativeVolumeCubicCm.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Cannot estimate shipping: Total weight and volume could not be determined for the cart items.");
        }


        // 4. Determine Source Pincode (Implement this method)
        Integer sourcePincode = getOrganizationSourcePincode(orgId);

        // 5. Call Delivery Partner Service to get quotes
        BigDecimal finalLength = maxItemLengthCm.compareTo(BigDecimal.ZERO) > 0 ? maxItemLengthCm : new BigDecimal("10.0"); // Default 10cm
        BigDecimal finalWidth = maxItemWidthCm.compareTo(BigDecimal.ZERO) > 0 ? maxItemWidthCm : new BigDecimal("10.0");   // Default 10cm
        BigDecimal finalHeight = maxItemHeightCm.compareTo(BigDecimal.ZERO) > 0 ? maxItemHeightCm : new BigDecimal("10.0"); // Default 10cm

        CourierServiceabilityRequest request = CourierServiceabilityRequest.builder()
                .pickup_postcode(sourcePincode)
                .delivery_postcode(destinationPincode)
                .weight(totalWeightKg.max(new BigDecimal("0.5")))
                .cod(0) // 0 for Prepaid, 1 for COD. Crucial!
                // .order_id("DEL_order123") // Only provide if checking an EXISTING Shiprocket order
                .length(finalLength)
                .width(finalWidth)
                .height(finalHeight)// Example dimension
                .declared_value(BigDecimal.valueOf(100.00)) // Example declared value. Crucial!
                .items_count(1) // Example item count. Crucial!
                // .is_international(0) // 0 for domestic, 1 for international
                // .currency("INR") // If required, otherwise Shiprocket often defaults
                // .mode("SURFACE") // "AIR" or "SURFACE"
                .build();

        List<DeliveryOption> deliveryOptions = deliveryServiceClient.getAvailableCourierService(request);
        if (deliveryOptions == null) {
            deliveryOptions = Collections.emptyList();
        }


        // 6. Select the "best" option (cheapest) and mark it
        DeliveryOption bestOption = deliveryOptions.stream()
                .min(Comparator.comparing(DeliveryOption::getCost))
                .orElseThrow(() -> new IllegalStateException("No options found after filtering. This should not happen."));

        List<DeliveryOption> finalDeliveryOptions = deliveryOptions.stream()
                .map(option -> option.equals(bestOption) ? option.toBuilder().withBestOption(true).build() : option)
                .collect(Collectors.toList());

        // 7. Build and return ShippingEstimateDto
        return ShippingEstimate.builder()
                .cartId(cart.getCartId())
                .destinationPincode(destinationPincode)
                .estimatedTotalShippingCost(bestOption.getCost())
                .deliveryOptions(finalDeliveryOptions)
                .build();
    }

    // --- Helper for Unit Conversion ---
    private BigDecimal convertWeightToKg(Weight weight) {
        if (weight == null || weight.getValue() == null || weight.getUnit() == null) {
            return BigDecimal.ZERO;
        }
        switch (weight.getUnit().toLowerCase()) {
            case "kg":
                return weight.getValue();
            case "g":
                return weight.getValue().divide(new BigDecimal("1000"), 3, RoundingMode.HALF_UP);
            case "lb":
                return weight.getValue().multiply(new BigDecimal("0.453592")).setScale(3, RoundingMode.HALF_UP);
            default:
                log.warn("Unknown weight unit: {}. Assuming KG.", weight.getUnit());
                return weight.getValue();
        }
    }

    private BigDecimal convertDimensionToCm(BigDecimal value, String unit) {
        if (value == null || unit == null) {
            return BigDecimal.ZERO;
        }
        switch (unit.toLowerCase()) {
            case "cm":
                return value;
            case "mm":
                return value.divide(new BigDecimal("10"), 2, RoundingMode.HALF_UP);
            case "inch":
                return value.multiply(new BigDecimal("2.54")).setScale(2, RoundingMode.HALF_UP);
            default:
                log.warn("Unknown dimension unit: {}. Assuming CM.", unit);
                return value;
        }
    }

    private Integer getOrganizationSourcePincode(String orgId) {
        if ("org_ae1e6ea1-0de2-4b6a-bc86-9d8d043fd75b".equals(orgId)) {
            return 600029;
        }
        log.error("Source pincode not configured for organization: {}", orgId);
        throw new IllegalArgumentException("Source pincode not configured for organization: " + orgId);
    }

    public CartDto getCartDetails(String orgId, String userId, String guestId)
            throws CartNotFoundException {
        Optional<Cart> cartOptional;
        if (userId != null && !userId.isBlank()) {
            try {
                cartOptional = cartRepository.findByOrgIdAndUserId(orgId, userId);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else if (guestId != null && !guestId.isBlank()) {
            try {
                cartOptional = cartRepository.findByOrgIdAndGuestId(orgId, guestId);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            log.error("Attempted to get cart without userId or guestId for orgId: {}", orgId);
            throw new IllegalArgumentException("Either userId or guestId must be provided.");
        }

        Cart cart = cartOptional.orElseThrow(() -> new CartNotFoundException("Cart not found for the given user/guest ID."));
        if (!cart.getOrgId().equals(orgId)) {
            log.warn("Cart {} found but does not belong to orgId {}. Potential data access issue.", cart.getCartId(), orgId);
            throw new CartNotFoundException("Cart not found or does not belong to the organization.");
        }

        List<CartItem> items = null;
        try {
            items = cartItemRepository.findByCartId(cart.getCartId());
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        log.debug("Fetched cart details for cartId: {} with {} items.", cart.getCartId(), items.size());
        return cartMapper.toDto(cart, items);
    }





}