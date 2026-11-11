package com.auction.client.ui;

import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Các thành phần nhận diện và stylesheet dùng chung cho JavaFX client. */
public final class AuctionTheme {
    private AuctionTheme() {
    }

    public static void apply(Parent root) {
        var stylesheet = AuctionTheme.class.getResource("/com/auction/client/auction.css");
        if (stylesheet == null) {
            throw new IllegalStateException("Không tìm thấy auction.css. Hãy build lại tài nguyên client.");
        }
        root.getStylesheets().add(stylesheet.toExternalForm());
        root.getStyleClass().add("auction-root");
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
