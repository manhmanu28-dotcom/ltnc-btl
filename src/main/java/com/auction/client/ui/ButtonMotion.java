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

    private ButtonMotion() { }

    static void install(Node node) {
        if (node.getProperties().putIfAbsent(INSTALLED, Boolean.TRUE) != null) return;
        if (node instanceof ButtonBase button) {
            Timeline motion = new Timeline();
            Runnable update = () -> {
                motion.stop();
                boolean active = !button.isDisabled() && button.getScene() != null;
                double scale = !active ? 1 : button.isPressed() ? 0.97 : button.isHover() ? 1.025 : 1;
                double lift = active && button.isHover() && !button.isPressed() ? -1.5 : 0;
                motion.getKeyFrames().setAll(new KeyFrame(Duration.millis(140),
                        new KeyValue(button.scaleXProperty(), scale, Interpolator.EASE_OUT),
                        new KeyValue(button.scaleYProperty(), scale, Interpolator.EASE_OUT),
                        new KeyValue(button.translateYProperty(), lift, Interpolator.EASE_OUT)));
                motion.playFromStart();
            };
            button.hoverProperty().addListener((o, a, b) -> update.run());
            button.pressedProperty().addListener((o, a, b) -> update.run());
            button.disabledProperty().addListener((o, a, b) -> update.run());
            button.sceneProperty().addListener((o, a, b) -> {
                if (b == null) {
                    motion.stop();
                    button.setScaleX(1); button.setScaleY(1); button.setTranslateY(0);
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
