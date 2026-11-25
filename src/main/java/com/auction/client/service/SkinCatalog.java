package com.auction.client.service;

import com.auction.client.model.SkinDefinition;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Bundled catalog: picking a skin never needs a network request. */
public final class SkinCatalog {
    public static final List<String> GROUPS = List.of("Knives", "Gloves", "Rifles", "Pistols");
    private static final List<SkinDefinition> SKINS = load();

    private SkinCatalog() { }

    private static List<SkinDefinition> load() {
        var input = Objects.requireNonNull(SkinCatalog.class.getResourceAsStream(
                "/com/auction/client/skin-catalog.json"), "Missing skin catalog");
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            List<SkinDefinition> skins = new Gson().fromJson(reader,
                    new TypeToken<List<SkinDefinition>>() { }.getType());
            return List.copyOf(skins);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read skin catalog", e);
        }
    }

    public static List<SkinDefinition> all() { return SKINS; }

    public static List<String> weapons(String group) {
        return SKINS.stream().filter(s -> s.group().equals(group)).map(SkinDefinition::weapon)
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public static List<SkinDefinition> search(String group, String weapon, String query) {
        if (group == null) return List.of();
        String term = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return SKINS.stream().filter(s -> s.group().equals(group))
                .filter(s -> weapon == null || s.weapon().equals(weapon))
                .filter(s -> s.name().toLowerCase(Locale.ROOT).contains(term)).toList();
    }

    public static Optional<SkinDefinition> findByTitle(String title) {
        return SKINS.stream().filter(s -> s.name().equalsIgnoreCase(title)).findFirst();
    }
}
