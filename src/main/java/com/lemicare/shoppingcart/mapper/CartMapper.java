package com.lemicare.shoppingcart.mapper;

import com.cosmicdoc.common.model.Cart;
import com.cosmicdoc.common.model.CartItem;
import com.lemicare.shoppingcart.dto.request.CartDto;
import com.lemicare.shoppingcart.dto.request.CartItemDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class CartMapper {

    private final CartItemMapper cartItemMapper;

    public CartMapper(CartItemMapper cartItemMapper) {
        this.cartItemMapper = cartItemMapper;
    }

    public CartDto toDto(Cart cart, List<CartItem> items) {
        if (cart == null) {
            return null;
        }
        List<CartItemDto> itemDtos = items.stream()
                .map(cartItemMapper::toDto)
                .collect(Collectors.toList());

        return CartDto.builder()
                .cartId(cart.getCartId())
                .orgId(cart.getOrgId())
                .userId(cart.getUserId())
                .guestId(cart.getGuestId())
                .status(cart.getStatus())
                .totalItems(cart.getTotalItems())
                .subtotalAmount(cart.getSubtotalAmount())
                .createdAt(cart.getCreatedAt())
                .lastModifiedAt(cart.getLastModifiedAt())
                .items(itemDtos)
                .build();
    }

    // No `toEntity` for Cart as carts are created/updated with specific business logic.
}