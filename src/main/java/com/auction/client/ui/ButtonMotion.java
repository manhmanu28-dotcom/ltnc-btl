package com.auction.client.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.util.Duration;

/** Hover and press feedback, including controls added by dialogs or filters. */
final class ButtonMotion {
    private static final Object INSTALLED = new Object();
    private static final Interpolator EASE = Interpolator.SPLINE(0.2, 0.8, 0.2, 1);

    private ButtonMotion() { }

    static void install(Node node) {
        if (node.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) != null) return;
        if (node instanceof ButtonBase button) {
            Timeline motion = new Timeline();
            Runnable update = () -> {
                motion.stop();
                boolean active = !button.isDisabled() && button.getScene() != null;
                boolean icon = button.getStyleClass().contains("theme-toggle");
                double scale = !active ? 1 : button.isPressed() ? 0.94 : button.isHover() ? (icon ? 1.07 : 1.018) : 1;
                double lift = active && button.isHover() && !button.isPressed() ? -1 : 0;
                double rotation = active && icon && button.isHover() && !button.isPressed() ? -12 : 0;
                motion.getKeyFrames().setAll(new KeyFrame(Duration.millis(button.isPressed() ? 85 : 180),
                        new KeyValue(button.scaleXProperty(), scale, EASE),
                        new KeyValue(button.scaleYProperty(), scale, EASE),
                        new KeyValue(button.translateYProperty(), lift, EASE),
                        new KeyValue(button.rotateProperty(), rotation, EASE)));
                motion.playFromStart();
            };
            button.hoverProperty().addListener((o, a, b) -> update.run());
            button.pressedProperty().addListener((o, a, b) -> update.run());
            button.disabledProperty().addListener((o, a, b) -> update.run());
            button.sceneProperty().addListener((o, a, b) -> {
                if (b == null) {
                    motion.stop();
                    button.setScaleX(1); button.setScaleY(1); button.setTranslateY(0); button.setRotate(0);
                }
            });
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(ButtonMotion::install);
            parent.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
                while (change.next()) {
                    if (change.wasAdded()) change.getAddedSubList().forEach(ButtonMotion::install);
                }
            });
        }
    }
}
