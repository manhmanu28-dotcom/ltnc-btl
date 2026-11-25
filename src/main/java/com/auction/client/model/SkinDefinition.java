package com.auction.client.model;

/** A catalog entry describes a skin design, not a Steam inventory item. */
public record SkinDefinition(String id, String name, String group, String weapon,
                             String imagePath, String sourceUrl, String imageUrl) {
}
