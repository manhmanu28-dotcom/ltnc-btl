package com.auction.client.ui;

import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.util.Duration;

/** A short, skippable brand introduction, played once when the application starts. */
public final class StartupIntro implements AutoCloseable {
    private final StackPane view = new StackPane();
    private final Runnable onFinished;
    private final SequentialTransition animation;
    private boolean finished;

    public StartupIntro(Runnable onFinished) {
        this.onFinished = onFinished;
        view.getStyleClass().add("startup-intro");

        ImageView logo = new ImageView(AuctionTheme.appIcon());
        logo.setFitWidth(150);
        logo.setFitHeight(150);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.getStyleClass().add("startup-logo");
        logo.setOpacity(0);
        logo.setScaleX(0.94);
        logo.setScaleY(0.94);

        HBox name = new HBox(1);
        ParallelTransition letterFades = new ParallelTransition();
        for (int i = 0; i < "BIDION".length(); i++) {
            Label letter = AuctionTheme.label(String.valueOf("BIDION".charAt(i)), "startup-name");
            if (i >= 3) letter.getStyleClass().add("startup-name-blue");
            letter.setOpacity(0);
            name.getChildren().add(letter);
            FadeTransition arrival = fade(letter, 450, 0, 1);
            arrival.setDelay(Duration.millis(i * 100));
            letterFades.getChildren().add(arrival);
        }
        name.setAlignment(Pos.CENTER_LEFT);
        StackPane nameFrame = new StackPane(name);
        nameFrame.setMinWidth(330);
        nameFrame.setPrefWidth(330);
        nameFrame.setMaxWidth(330);
        Region divider = new Region();
        divider.getStyleClass().add("startup-divider");
        divider.setMinSize(24, 2);
        divider.setPrefSize(24, 2);
        divider.setMaxSize(24, 2);
        divider.setOpacity(0);
        divider.setScaleX(0);

        HBox lockup = new HBox(18, logo, divider, nameFrame);
        lockup.setAlignment(Pos.CENTER);
        lockup.setMaxWidth(Region.USE_PREF_SIZE);
        double logoOffset = (330 + 24 + 18 * 2) / 2.0;
        logo.setTranslateX(logoOffset);

        Label slogan = AuctionTheme.label("WHERE RARITY MEETS DESTINY", "startup-slogan");
        slogan.setOpacity(0);
        slogan.setScaleX(0.97);
        slogan.setScaleY(0.97);
        slogan.setTranslateY(10);
        VBox identity = new VBox(16, lockup, slogan);
        identity.setAlignment(Pos.CENTER);
        identity.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        identity.setAccessibleText("BIDION — Where rarity meets destiny");

        Button skip = new Button("Bỏ qua  →");
        skip.getStyleClass().add("startup-skip");
        skip.setOnAction(event -> finish());
        StackPane.setAlignment(skip, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(skip, new Insets(24));
        view.getChildren().addAll(identity, skip);
        AuctionTheme.apply(view);
        view.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE || event.getCode() == KeyCode.ENTER) {
                finish();
                event.consume();
            }
        });

        Interpolator smooth = Interpolator.SPLINE(0.22, 1, 0.36, 1);
        FadeTransition logoFade = fade(logo, 550, 0, 1);
        ScaleTransition logoScale = new ScaleTransition(Duration.millis(550), logo);
        logoScale.setToX(1);
        logoScale.setToY(1);
        logoScale.setInterpolator(smooth);
        ParallelTransition logoArrival = new ParallelTransition(logoFade, logoScale);

        TranslateTransition logoSlide = new TranslateTransition(Duration.millis(600), logo);
        logoSlide.setFromX(logoOffset);
        logoSlide.setToX(0);
        logoSlide.setInterpolator(smooth);
        // After the logo settles, the line and first letter appear together.
        // Staggered opacity changes reveal the name without moving its layout.
        ScaleTransition dividerGrow = new ScaleTransition(Duration.millis(400), divider);
        dividerGrow.setFromX(0);
        dividerGrow.setToX(1);
        dividerGrow.setInterpolator(smooth);
        ParallelTransition nameArrival = new ParallelTransition(letterFades, dividerGrow,
                fade(divider, 400, 0, 1));

        Timeline sloganPop = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(slogan.opacityProperty(), 0),
                        new KeyValue(slogan.scaleXProperty(), 0.97),
                        new KeyValue(slogan.scaleYProperty(), 0.97),
                        new KeyValue(slogan.translateYProperty(), 10)),
                new KeyFrame(Duration.millis(420),
                        new KeyValue(slogan.opacityProperty(), 1, smooth),
                        new KeyValue(slogan.scaleXProperty(), 1, smooth),
                        new KeyValue(slogan.scaleYProperty(), 1, smooth),
                        new KeyValue(slogan.translateYProperty(), 0, smooth)));

        animation = new SequentialTransition(logoArrival, new PauseTransition(Duration.millis(80)),
                logoSlide, nameArrival, new PauseTransition(Duration.millis(60)), sloganPop,
                new PauseTransition(Duration.millis(750)));
        animation.setOnFinished(event -> finish());
    }

    private FadeTransition fade(javafx.scene.Node node, int millis, double from, double to) {
        FadeTransition transition = new FadeTransition(Duration.millis(millis), node);
        transition.setFromValue(from);
        transition.setToValue(to);
        transition.setInterpolator(Interpolator.EASE_BOTH);
        return transition;
    }

    public Parent getView() { return view; }

    public void play() {
        if (finished) return;
        view.requestFocus();
        animation.playFromStart();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        animation.stop();
        onFinished.run();
    }

    @Override
    public void close() {
        finished = true;
        animation.stop();
    }
}
