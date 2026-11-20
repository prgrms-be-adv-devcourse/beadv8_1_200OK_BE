ALTER TABLE cart_item
    ADD CONSTRAINT uk_cart_item_cart_option UNIQUE (cart_id, product_option_id);