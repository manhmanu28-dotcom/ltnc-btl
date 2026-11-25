package com.auction.client;

import com.auction.client.service.SkinCatalog;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class SkinCatalogTest {
    @Test void catalogContainsUniqueSkinsWithBundledImagesInAllFourGroups() throws Exception {
        var ids = new HashSet<String>();
        assertFalse(SkinCatalog.all().isEmpty());
        for (var skin : SkinCatalog.all()) {
            assertTrue(ids.add(skin.id()), "Duplicate skin: " + skin.id());
            assertTrue(SkinCatalog.GROUPS.contains(skin.group()));
            assertTrue(skin.name().contains(" | "));
            assertTrue(skin.sourceUrl().startsWith("https://csgoskins.gg/items/"));
            try (var image = getClass().getResourceAsStream(skin.imagePath())) {
                assertNotNull(image, "Missing image: " + skin.id());
                assertArrayEquals(new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10}, image.readNBytes(8));
            }
        }
        SkinCatalog.GROUPS.forEach(group -> assertFalse(SkinCatalog.weapons(group).isEmpty()));
    }

    @Test void selectingAWeaponKeepsSearchWithinItsCategory() {
        assertTrue(SkinCatalog.search(null, null, "").isEmpty());
        var results = SkinCatalog.search("Rifles", "AK-47", "INHERITANCE");
        assertEquals(1, results.size());
        assertEquals("ak-47-inheritance", results.get(0).id());
        assertTrue(SkinCatalog.search("Gloves", null, "Inheritance").isEmpty());
        assertTrue(SkinCatalog.search("Pistols", null, "no-such-skin-name").isEmpty());
    }

    @Test void persistedAuctionTitlesResolveToTheSameImage() {
        var skin = SkinCatalog.search("Rifles", "AK-47", "Inheritance").get(0);
        assertEquals(skin, SkinCatalog.findByTitle(skin.name().toUpperCase(java.util.Locale.ROOT)).orElseThrow());
        assertTrue(SkinCatalog.findByTitle("A non-catalog product").isEmpty());
    }
}
