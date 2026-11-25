package com.auction.client.ui;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.prefs.Preferences;

/** Các thành phần nhận diện và stylesheet dùng chung cho JavaFX client. */
public final class AuctionTheme {
    private static final Set<Parent> ROOTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Preferences SETTINGS = preferences();
    private static final BooleanProperty DARK = new SimpleBooleanProperty(readDark());

    static {
        DARK.addListener((observable, before, dark) -> {
            ROOTS.forEach(AuctionTheme::updateTheme);
            if (SETTINGS != null) {
                try { SETTINGS.putBoolean("darkMode", dark); }
                catch (SecurityException ignored) { /* Theme still works for this session. */ }
            }
        });
    }

    private static Preferences preferences() {
        try { return Preferences.userNodeForPackage(AuctionTheme.class); }
        catch (SecurityException ignored) { return null; }
    }

    private static boolean readDark() {
        try { return SETTINGS != null && SETTINGS.getBoolean("darkMode", false); }
        catch (SecurityException ignored) { return false; }
    }

    private static void updateTheme(Parent root) {
        root.getStyleClass().remove("dark");
        if (DARK.get()) root.getStyleClass().add("dark");
    }

    public static Button themeToggle() {
        Button toggle = new Button();
        toggle.getStyleClass().addAll("ghost-button", "theme-toggle");
        toggle.textProperty().bind(javafx.beans.binding.Bindings.when(DARK)
                .then("☀").otherwise("☾"));
        toggle.accessibleTextProperty().bind(javafx.beans.binding.Bindings.when(DARK)
                .then("Chuyển sang giao diện sáng").otherwise("Chuyển sang giao diện tối"));
        toggle.setTooltip(new Tooltip("Chuyển giao diện sáng / tối"));
        toggle.setOnAction(event -> ThemeTransition.play(toggle, () -> DARK.set(!DARK.get())));
        return toggle;
    }
    private AuctionTheme() {
    }

    public static void apply(Parent root) {
        var stylesheet = AuctionTheme.class.getResource("/com/auction/client/auction.css");
        if (stylesheet == null) {
            throw new IllegalStateException("Không tìm thấy auction.css. Hãy build lại tài nguyên client.");
        }
        if (!root.getStylesheets().contains(stylesheet.toExternalForm())) {
            root.getStylesheets().add(stylesheet.toExternalForm());
        }
        if (!root.getStyleClass().contains("auction-root")) root.getStyleClass().add("auction-root");
        ROOTS.add(root);
        updateTheme(root);
        ButtonMotion.install(root);
    }

    public static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    public static HBox brand() {
        Label mark = label("a", "brand-mark");
        VBox name = new VBox(1, label("AUCTION", "brand-name"), label("LIVE MARKETPLACE", "brand-caption"));
        HBox brand = new HBox(12, mark, name);
        brand.setAlignment(Pos.CENTER_LEFT);
        return brand;
    }
}
