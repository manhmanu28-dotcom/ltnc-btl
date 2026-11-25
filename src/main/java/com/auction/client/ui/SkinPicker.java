package com.auction.client.ui;

import com.auction.client.model.SkinDefinition;
import com.auction.client.service.SkinCatalog;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Pick a category and weapon first, then choose an illustrated skin. */
public final class SkinPicker extends VBox {
    private final ReadOnlyObjectWrapper<SkinDefinition> selected = new ReadOnlyObjectWrapper<>();
    private final Map<String, MenuButton> menus = new LinkedHashMap<>();
    private static final Map<String, Image> IMAGES = new HashMap<>();
    private final TilePane cards = new TilePane(12, 12);
    private final TextField search = new TextField();
    private final Label path = AuctionTheme.label("Chọn danh mục để bắt đầu", "skin-picker-path");
    private final Label result = AuctionTheme.label("", "muted");
    private final Label chosen = AuctionTheme.label("Chưa chọn skin", "muted");
    private final VBox results;
    private String group;
    private String weapon;

    public SkinPicker() {
        super(14);
        getStyleClass().add("skin-picker");
        FlowPane navigation = new FlowPane(4, 6);
        navigation.getStyleClass().add("skin-category-bar");
        for (String category : SkinCatalog.GROUPS) {
            MenuButton menu = new MenuButton(category);
            menu.setId("skin-category-" + category.toLowerCase(java.util.Locale.ROOT));
            menu.getStyleClass().add("skin-category-menu");
            MenuItem all = new MenuItem("All " + category);
            all.setOnAction(event -> selectCategory(category, null));
            menu.getItems().addAll(all, new SeparatorMenuItem());
            for (String type : SkinCatalog.weapons(category)) {
                MenuItem item = new MenuItem(type);
                item.setOnAction(event -> selectCategory(category, type));
                menu.getItems().add(item);
            }
            menus.put(category, menu);
            navigation.getChildren().add(menu);
        }
        search.setId("skin-catalog-search");
        search.setPromptText("Tìm skin trong danh mục đã chọn…");
        search.setAccessibleText("Tìm skin theo tên");
        search.textProperty().addListener((o, before, after) -> render());
        cards.setPrefTileWidth(220);
        cards.setPrefTileHeight(210);
        cards.setTileAlignment(Pos.TOP_LEFT);
        cards.setAlignment(Pos.TOP_LEFT);
        cards.setStyle("-fx-padding: 4 6 12 4;");
        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(270);
        scroll.getStyleClass().add("auth-scroll");
        scroll.viewportBoundsProperty().addListener((o, before, bounds) -> {
            double available = Math.max(200, bounds.getWidth() - 20);
            int columns = Math.max(1, (int) ((available + 12) / 230));
            cards.setPrefTileWidth((available - (columns - 1) * 12) / columns);
        });
        results = new VBox(10, search, result, scroll, chosen);
        results.setVisible(false);
        results.setManaged(false);
        chosen.setWrapText(true);
        path.setWrapText(true);
        getChildren().addAll(navigation, path, results);
    }

    private void selectCategory(String category, String type) {
        group = category;
        weapon = type;
        selected.set(null);
        chosen.setText("Chọn một skin bên dưới để đăng bán.");
        menus.forEach((name, menu) -> {
            menu.getStyleClass().remove("selected-category");
            if (name.equals(category))
                menu.getStyleClass().add("selected-category");
        });
        path.setText(category + "  /  " + (type == null ? "All" : type));
        search.clear();
        results.setVisible(true);
        results.setManaged(true);
        render();
    }

    private void render() {
        cards.getChildren().clear();
        var matches = SkinCatalog.search(group, weapon, search.getText());
        result.setText(matches.isEmpty() ? "Không tìm thấy skin. Thử từ khóa khác." : matches.size() + " skin");
        for (SkinDefinition skin : matches) {
            ImageView image = image(skin, 180, 108);
            Label name = AuctionTheme.label(skin.name().substring(skin.name().indexOf(" | ") + 3), "skin-choice-title");
            name.setWrapText(true);
            name.setMinHeight(40);
            VBox content = new VBox(6, image, AuctionTheme.label(skin.weapon(), "form-hint"), name);
            content.setAlignment(Pos.CENTER_LEFT);
            Button card = new Button();
            card.setGraphic(content);
            card.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            card.setAccessibleText("Chọn " + skin.name());
            card.setUserData(skin);
            card.getStyleClass().add("skin-choice");
            card.setMinHeight(210);
            card.setMaxHeight(210);
            card.prefWidthProperty().bind(cards.prefTileWidthProperty());
            card.maxWidthProperty().bind(cards.prefTileWidthProperty());
            if (skin.equals(selected.get()))
                card.getStyleClass().add("selected-skin");
            card.setOnAction(event -> {
                selected.set(skin);
                chosen.setText("Đã chọn: " + skin.name());
                cards.getChildren().forEach(node -> node.getStyleClass().remove("selected-skin"));
                card.getStyleClass().add("selected-skin");
            });
            cards.getChildren().add(card);
        }
    }

    public static ImageView image(SkinDefinition skin, double width, double height) {
        Image image = IMAGES.computeIfAbsent(skin.id(), id -> new Image(Objects.requireNonNull(
                SkinPicker.class.getResource(skin.imagePath()), "Missing skin image").toExternalForm()));
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitWidth(width);
        view.setFitHeight(height);
        return view;
    }

    public ReadOnlyObjectProperty<SkinDefinition> selectedSkinProperty() {
        return selected.getReadOnlyProperty();
    }

    public SkinDefinition getSelectedSkin() {
        return selected.get();
    }
}
