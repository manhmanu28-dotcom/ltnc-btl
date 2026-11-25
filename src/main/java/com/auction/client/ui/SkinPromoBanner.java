package com.auction.client.ui;

import com.auction.client.model.SkinDefinition;
import com.auction.client.service.SkinCatalog;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import java.util.List;

/** Informational skin spotlight; never implies the displayed design is a live auction. */
public final class SkinPromoBanner extends StackPane implements AutoCloseable, ThemePausable {
    private record Slide(String id, String description) { }
    private static final List<Slide> SLIDES = List.of(
            new Slide("ak-47-inheritance", "Sắc trắng và xanh với họa tiết gốm, điểm thêm những đường viền vàng."),
            new Slide("ak-47-wild-lotus", "Hoa sen nổi bật trên nền xanh lá. Một thiết kế nhiều màu sắc cho AK-47."),
            new Slide("m4a1-s-printstream", "Hai tông đen trắng, họa tiết đồ họa sắc nét và phong cách tối giản."),
            new Slide("awp-asiimov", "Cam, trắng và đen kết hợp trong thiết kế mang cảm hứng khoa học viễn tưởng."));
    private static final Interpolator EASE = Interpolator.SPLINE(0.2, 0.8, 0.2, 1);
    private final PauseTransition hold = new PauseTransition(Duration.seconds(5));
    private final Timeline transition = new Timeline();
    private HBox current;
    private boolean changing;
    private boolean closed;
    private int pauses;
    private int index;

    public SkinPromoBanner() {
        setId("skin-promo-banner");
        getStyleClass().add("skin-promo-banner");
        setMinHeight(230); setPrefHeight(230); setMaxHeight(230);
        current = slide(SLIDES.get(0));
        getChildren().add(current);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty()); clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(32); clip.setArcHeight(32);
        setClip(clip);
        hold.setOnFinished(event -> next());
        sceneProperty().addListener((o, before, after) -> updatePlayback());
        visibleProperty().addListener((o, before, after) -> updatePlayback());
    }

    private HBox slide(Slide slide) {
        SkinDefinition skin = SkinCatalog.all().stream().filter(s -> s.id().equals(slide.id())).findFirst().orElseThrow();
        String name = skin.name().substring(skin.name().indexOf(" | ") + 3);
        Label title = AuctionTheme.label(name, "promo-title");
        title.setWrapText(true);
        Label description = AuctionTheme.label(slide.description(), "promo-description");
        description.setWrapText(true);
        Label note = AuctionTheme.label("Khám phá skin trong danh mục đăng bán", "promo-note");
        note.setWrapText(true);
        VBox copy = new VBox(8, AuctionTheme.label("SKIN SPOTLIGHT  /  " + skin.weapon(), "eyebrow"),
                title, description, note);
        copy.setAlignment(Pos.CENTER_LEFT);
        copy.setMinWidth(0);
        copy.prefWidthProperty().bind(widthProperty().multiply(0.43));
        StackPane art = new StackPane();
        art.getStyleClass().add("promo-art");
        art.setMinWidth(0);
        ImageView image = SkinPicker.image(skin, 460, 184);
        image.setViewport(artworkBounds(image));
        image.setManaged(false);
        image.fitWidthProperty().bind(javafx.beans.binding.Bindings.max(1, art.widthProperty().subtract(12)));
        image.fitHeightProperty().bind(javafx.beans.binding.Bindings.max(1, art.heightProperty().subtract(8)));
        image.layoutXProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> (art.getWidth() - image.getBoundsInLocal().getWidth()) / 2,
                art.widthProperty(), image.boundsInLocalProperty()));
        image.layoutYProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> (art.getHeight() - image.getBoundsInLocal().getHeight()) / 2,
                art.heightProperty(), image.boundsInLocalProperty()));
        image.setAccessibleText(skin.name());
        art.getChildren().add(image);
        HBox.setHgrow(art, Priority.ALWAYS);
        HBox body = new HBox(24, copy, art);
        body.setUserData(skin);
        body.setAlignment(Pos.CENTER_LEFT);
        body.getStyleClass().add("promo-slide");
        return body;
    }

    private static Rectangle2D artworkBounds(ImageView view) {
        var image = view.getImage();
        var pixels = image.getPixelReader();
        int width = (int)image.getWidth(), height = (int)image.getHeight();
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if ((pixels.getArgb(x, y) >>> 24) > 16) {
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
        }
        if (right < left) return new Rectangle2D(0, 0, width, height);
        left = Math.max(0, left - 12); top = Math.max(0, top - 12);
        right = Math.min(width - 1, right + 12); bottom = Math.min(height - 1, bottom + 12);
        return new Rectangle2D(left, top, right - left + 1, bottom - top + 1);
    }

    private void next() {
        if (closed || changing || pauses > 0 || getScene() == null) return;
        hold.stop(); changing = true;
        index = (index + 1) % SLIDES.size();
        HBox outgoing = current;
        HBox incoming = slide(SLIDES.get(index));
        incoming.setOpacity(0); incoming.setTranslateX(36);
        getChildren().add(incoming);
        transition.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO, value(outgoing.opacityProperty(), 1),
                        value(outgoing.translateXProperty(), 0), value(incoming.opacityProperty(), 0),
                        value(incoming.translateXProperty(), 36)),
                new KeyFrame(Duration.millis(580), value(outgoing.opacityProperty(), 0),
                        value(outgoing.translateXProperty(), -36), value(incoming.opacityProperty(), 1),
                        value(incoming.translateXProperty(), 0)));
        transition.setOnFinished(event -> {
            getChildren().remove(outgoing);
            current = incoming; changing = false;
            updatePlayback();
        });
        transition.playFromStart();
    }

    private static KeyValue value(javafx.beans.value.WritableValue<Number> property, double value) {
        return new KeyValue(property, value, EASE);
    }

    private void updatePlayback() {
        if (closed || pauses > 0 || !isVisible() || getScene() == null) {
            hold.pause(); transition.pause();
        } else if (changing) transition.play();
        else hold.play();
    }

    @Override public void pauseForTheme() { pauses++; updatePlayback(); }
    @Override public void resumeAfterTheme() { if (pauses > 0) pauses--; updatePlayback(); }
    @Override public void close() { closed = true; hold.stop(); transition.stop(); }
}
