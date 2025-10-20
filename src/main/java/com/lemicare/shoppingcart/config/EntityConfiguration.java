package com.lemicare.shoppingcart.config;

import com.cosmicdoc.common.repository.*;
import com.cosmicdoc.common.repository.impl.*;
import com.google.cloud.firestore.Firestore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EntityConfiguration {

    Firestore firestore;

    @Bean
    CartRepository cartRepository (Firestore firestore) {
        return new CartRepositoryImpl(firestore);
    }

    @Bean
    CartItemRepository cartItemRepository (Firestore firestore) {
        return new CartItemRepositoryImpl(firestore);
    }

    @Bean
    StorefrontOrderRepository storefrontOrderRepository (Firestore firestore) {
        return new StorefrontOrderRepositoryImpl(firestore);
    }
    @Bean
    WishlistRepository wishlistRepository (Firestore firestore) {
        return new WishlistRepositoryImpl(firestore);
    }

    @Bean
    StorefrontProductRepository storefrontProductRepository (Firestore firestore) {
        return new StorefrontProductRepositoryImpl(firestore);
    }
}
