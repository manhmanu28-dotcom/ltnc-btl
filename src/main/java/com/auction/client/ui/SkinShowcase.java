package com.auction.client.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import java.util.Objects;

/** Two stacked skin cards that move vertically, with local image resources. */
public final class SkinShowcase extends VBox implements AutoCloseable {
    private final StackPane[] cards = new StackPane[2];
    private static final Interpolator SNAP = Interpolator.SPLINE(0.16, 1, 0.3, 1);
    private final PauseTransition hold = new PauseTransition(Duration.seconds(4));
    private final Timeline slide = new Timeline();
    private final Label position = AuctionTheme.label("01 / 02", "skin-position");
    private final Button previous = control("↑", "Thẻ trước", "skin-previous");
    private final Button next = control("↓", "Thẻ tiếp theo", "skin-next");
    private boolean paused;
    private boolean transitioning;
    private boolean closed;
    private int active;

    public SkinShowcase() {
        super(12);
        getStyleClass().add("skin-showcase");
        setMaxWidth(540);
        StackPane deck = new StackPane();
        deck.getStyleClass().add("skin-deck");
        deck.setMinHeight(0);
        deck.setMaxHeight(Region.USE_PREF_SIZE);
        deck.prefHeightProperty().bind(deck.widthProperty().multiply(9.0 / 16).add(24));
        cards[0] = card("skin-01.jpg");
        cards[1] = card("skin-02.jpg");
        for (StackPane card : cards) {
            StackPane.setMargin(card, new Insets(0, 0, 24, 0));
            deck.getChildren().add(card);
        }
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(deck.widthProperty());
        clip.heightProperty().bind(deck.heightProperty());
        deck.setClip(clip);
        arrangeCards();

        Button pause = control("Ⅱ", "Tạm dừng chuyển thẻ", "skin-pause");
        pause.setOnAction(event -> {
            paused = !paused;
            pause.setText(paused ? "▶" : "Ⅱ");
            String action = paused ? "Tiếp tục chuyển thẻ" : "Tạm dừng chuyển thẻ";
            pause.setAccessibleText(action);
            pause.getTooltip().setText(action);
            updatePlayback();
        });
        previous.setOnAction(event -> changeCard(1));
        next.setOnAction(event -> changeCard(-1));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox caption = new HBox(8, AuctionTheme.label("SKIN GALLERY", "eyebrow"), spacer,
                position, previous, next, pause);
        caption.setAlignment(Pos.CENTER_LEFT);
        ProgressBar progress = new ProgressBar(0);
        progress.getStyleClass().add("skin-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setMouseTransparent(true);
        progress.progressProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> hold.getCurrentTime().toMillis() / hold.getDuration().toMillis(),
                hold.currentTimeProperty()));
        getChildren().addAll(deck, progress, caption);

        hold.setOnFinished(event -> changeCard(-1));
        slide.setOnFinished(event -> {
            active = 1 - active;
            transitioning = false;
            arrangeCards();
            position.setText(active == 0 ? "01 / 02" : "02 / 02");
            previous.setDisable(false);
            next.setDisable(false);
            hold.stop();
            updatePlayback();
        });
        sceneProperty().addListener((observable, before, scene) -> updatePlayback());
        visibleProperty().addListener((observable, before, visible) -> updatePlayback());
    }

    private void arrangeCards() {
        StackPane front = cards[active];
        StackPane back = cards[1 - active];
        front.setTranslateY(0);
        front.setRotate(0);
        front.setScaleX(1); front.setScaleY(1); front.setOpacity(1);
        back.setTranslateY(20);
        back.setRotate(0);
        back.setScaleX(0.94); back.setScaleY(0.97); back.setOpacity(0.6);
        front.toFront();
    }

    private void changeCard(int direction) {
        if (closed || transitioning || getScene() == null) return;
        hold.stop();
        transitioning = true;
        previous.setDisable(true);
        next.setDisable(true);
        StackPane outgoing = cards[active];
        StackPane incoming = cards[1 - active];
        incoming.setTranslateY(-direction * 24);
        incoming.setOpacity(1);
        outgoing.toFront();
        double travel = Math.max(160, outgoing.getHeight());
        // Explicit initial values prevent a repeated cycle from reusing old poses.
        slide.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO,
                        value(outgoing.translateYProperty(), 0), value(outgoing.rotateProperty(), 0),
                        value(outgoing.opacityProperty(), 1), value(outgoing.scaleXProperty(), 1),
                        value(incoming.translateYProperty(), -direction * 24),
                        value(incoming.rotateProperty(), 0), value(incoming.opacityProperty(), 1),
                        value(incoming.scaleXProperty(), 0.94), value(incoming.scaleYProperty(), 0.97)),
                new KeyFrame(Duration.millis(180), event -> incoming.toFront(),
                        value(outgoing.translateYProperty(), direction * travel * 0.55),
                        value(outgoing.rotateProperty(), direction * 3),
                        value(outgoing.opacityProperty(), 1),
                        value(incoming.translateYProperty(), -direction * 8),
                        value(incoming.scaleXProperty(), 0.98), value(incoming.scaleYProperty(), 0.99)),
                new KeyFrame(Duration.millis(380),
                        value(outgoing.translateYProperty(), direction * (travel + 24)),
                        value(outgoing.rotateProperty(), direction * 5),
                        value(outgoing.opacityProperty(), 0), value(outgoing.scaleXProperty(), 0.94),
                        value(incoming.translateYProperty(), direction * 2),
                        value(incoming.scaleXProperty(), 1), value(incoming.scaleYProperty(), 1)),
                new KeyFrame(Duration.millis(540),
                        value(incoming.translateYProperty(), 0)));
        slide.playFromStart();
    }

    private static Button control(String text, String description, String id) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().addAll("ghost-button", "skin-playback");
        button.setAccessibleText(description);
        button.setTooltip(new Tooltip(description));
        return button;
    }

    private static KeyValue value(javafx.beans.value.WritableValue<Number> property, double number) {
        return new KeyValue(property, number, SNAP);
    }

    private static StackPane card(String filename) {
        StackPane frame = new StackPane();
        frame.getStyleClass().addAll("skin-frame", "skin-card");
        frame.setMinSize(0, 0);
        var resource = Objects.requireNonNull(SkinShowcase.class.getResource(
                "/com/auction/client/images/" + filename), "Missing skin image: " + filename);
        ImageView image = new ImageView(new Image(resource.toExternalForm()));
        image.setId("skin-image-" + filename.substring(5, 7));
        image.setPreserveRatio(true);
        image.setSmooth(true);
        image.setManaged(false);
        image.fitWidthProperty().bind(javafx.beans.binding.Bindings.max(0, frame.widthProperty().subtract(2)));
        image.fitHeightProperty().bind(javafx.beans.binding.Bindings.max(0, frame.heightProperty().subtract(2)));
        // Match the card ratio exactly; the two source files differ slightly in ratio.
        image.viewportProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(() -> {
            double width = Math.max(1, frame.getWidth() - 2);
            double height = Math.max(1, frame.getHeight() - 2);
            double sourceWidth = image.getImage().getWidth();
            double sourceHeight = image.getImage().getHeight();
            double ratio = width / height;
            double cropWidth = Math.min(sourceWidth, sourceHeight * ratio);
            double cropHeight = Math.min(sourceHeight, sourceWidth / ratio);
            return new Rectangle2D((sourceWidth - cropWidth) / 2,
                    (sourceHeight - cropHeight) / 2, cropWidth, cropHeight);
        }, frame.widthProperty(), frame.heightProperty()));
        image.setAccessibleText("Ảnh skin trong game " + filename.substring(5, 7));
        image.layoutXProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> (frame.getWidth() - image.getBoundsInLocal().getWidth()) / 2,
                frame.widthProperty(), image.boundsInLocalProperty()));
        image.layoutYProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> (frame.getHeight() - image.getBoundsInLocal().getHeight()) / 2,
                frame.heightProperty(), image.boundsInLocalProperty()));
        frame.getChildren().add(image);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(frame.widthProperty());
        clip.heightProperty().bind(frame.heightProperty());
        clip.setArcWidth(24); clip.setArcHeight(24);
        frame.setClip(clip);
        return frame;
    }

    private void updatePlayback() {
        if (closed || paused || !isVisible() || getScene() == null) {
            hold.pause(); slide.pause();
        } else if (transitioning) {
            slide.play();
        } else {
            hold.play();
        }
    }

    @Override public void close() {
        closed = true;
        hold.stop(); slide.stop();
    }
}
