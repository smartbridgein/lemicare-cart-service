package com.lemicare.shoppingcart.mapper;

import com.cosmicdoc.common.model.CartItem;
import com.lemicare.shoppingcart.dto.request.CartItemDto;
import org.springframework.stereotype.Component;

@Component
public class CartItemMapper {

    public CartItemDto toDto(CartItem cartItem) {
        if (cartItem == null) {
            return null;
        }
        return CartItemDto.builder()
                .cartItemId(cartItem.getCartItemId())
                .productId(cartItem.getProductId())
                .productName(cartItem.getProductName())
              //  .productImageUrl(cartItem.getProductImageUrl())
                .priceAtAddToCart(cartItem.getPriceAtAddToCart())
                .quantity(cartItem.getQuantity())
                .itemTotalPrice(cartItem.getItemTotalPrice())
                .addedAt(cartItem.getAddedAt())
                .build();
    }
}
