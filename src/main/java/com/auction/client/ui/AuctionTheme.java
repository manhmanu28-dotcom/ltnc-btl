package com.auction.client.ui;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
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
        toggle.setOnAction(event -> {
            boolean toDark = !DARK.get();
            ThemeTransition.play(toggle, toDark, () -> DARK.set(toDark));
        });
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
        Image source = Branding.LOGO;
        ImageView symbol = new ImageView(source);
        symbol.setViewport(new Rectangle2D(source.getWidth() * 0.22, source.getHeight() * 0.30,
                source.getWidth() * 0.55, source.getHeight() * 0.38));
        symbol.setPreserveRatio(true);
        symbol.setFitWidth(50);
        symbol.setFitHeight(38);
        symbol.setSmooth(true);
        symbol.getStyleClass().add("bidion-symbol");
        Label bid = label("BID", "bidion-word");
        bid.getStyleClass().add("bidion-word-base");
        Label ion = label("ION", "bidion-word");
        ion.getStyleClass().add("bidion-word-accent");
        HBox wordmark = new HBox(bid, ion);
        wordmark.setAlignment(Pos.CENTER_LEFT);
        VBox name = new VBox(0, wordmark,
                label("WHERE RARITY MEETS DESTINY", "bidion-tagline"));
        name.setAlignment(Pos.CENTER_LEFT);
        HBox brand = new HBox(10, symbol, name);
        brand.getStyleClass().add("bidion-brand");
        brand.setAccessibleText("BIDION — Where rarity meets destiny");
        brand.setAlignment(Pos.CENTER_LEFT);
        return brand;
    }

    public static Image appIcon() { return Branding.ICON; }

    private static final class Branding {
        private static final Image LOGO = load("bidion-logo-blue.png");
        private static final Image ICON = new WritableImage(LOGO.getPixelReader(),
                (int) (LOGO.getWidth() * 0.21), (int) (LOGO.getHeight() * 0.21),
                (int) (LOGO.getWidth() * 0.58), (int) (LOGO.getHeight() * 0.58));

        private static Image load(String name) {
            return new Image(java.util.Objects.requireNonNull(AuctionTheme.class.getResource(
                    "/com/auction/client/branding/" + name), "Missing BIDION branding").toExternalForm());
        }
    }
}
