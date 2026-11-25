package com.auction.client.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.ArrayList;
import java.util.List;

/** Reveals the new palette from the theme button without rebuilding the screen. */
final class ThemeTransition {
    private static final Map<Scene, ThemeTransition> ACTIVE = new WeakHashMap<>();
    private static final Interpolator EASE = Interpolator.SPLINE(0.22, 1, 0.36, 1);
    private final Scene scene;
    private final Parent content;
    private final StackPane layer = new StackPane();
    private final Timeline animation = new Timeline();
    private final Node focus;
    private final List<ThemePausable> pausedAnimations = new ArrayList<>();
    private ChangeListener<Parent> rootListener;
    private ChangeListener<Number> sizeListener;
    private boolean finished;

    private ThemeTransition(Scene scene, Parent content) {
        this.scene = scene;
        this.content = content;
        this.focus = scene.getFocusOwner();
    }

    static void play(Button source, Runnable changeTheme) {
        Scene scene = source.getScene();
        if (scene == null || scene.getWindow() == null || !scene.getWindow().isShowing()
                || scene.getWidth() < 1 || scene.getHeight() < 1) {
            changeTheme.run();
            return;
        }
        ThemeTransition previous = ACTIVE.get(scene);
        if (previous != null) previous.finish();
        ThemeTransition transition = new ThemeTransition(scene, scene.getRoot());
        try {
            transition.start(source, changeTheme);
        } catch (RuntimeException error) {
            // A snapshot may be unavailable while a window is closing.
            transition.finish();
            if (!transition.changed) changeTheme.run();
        }
    }

    private boolean changed;

    private void start(Button source, Runnable changeTheme) {
        pauseAnimations(content);
        Bounds origin = source.localToScene(source.getBoundsInLocal());
        double x = (origin.getMinX() + origin.getMaxX()) / 2;
        double y = (origin.getMinY() + origin.getMaxY()) / 2;
        double width = scene.getWidth();
        double height = scene.getHeight();
        ImageView oldPalette = new ImageView(scene.snapshot(null));
        changeTheme.run();
        changed = true;
        content.applyCss();
        content.layout();
        ImageView newPalette = new ImageView(scene.snapshot(null));
        Circle reveal = new Circle(x, y, 0);
        newPalette.setClip(reveal);
        Circle edge = new Circle(x, y, 0, Color.TRANSPARENT);
        edge.setStroke(Color.web("#87AEFF", 0.25));
        edge.setStrokeWidth(2);
        edge.radiusProperty().bind(reveal.radiusProperty());
        Pane overlay = new Pane(oldPalette, newPalette, edge);
        overlay.setId("theme-transition-overlay");
        overlay.setManaged(false);
        overlay.setMouseTransparent(true);
        overlay.resize(width, height);
        overlay.setClip(new Rectangle(width, height));
        layer.setId("theme-transition-layer");
        layer.setMouseTransparent(false);
        scene.setRoot(layer);
        layer.getChildren().addAll(content, overlay);
        layer.applyCss();
        layer.layout();
        if (focus != null && focus.getScene() == scene) focus.requestFocus();
        ACTIVE.put(scene, this);
        rootListener = (observable, before, after) -> { if (after != layer) finish(); };
        sizeListener = (observable, before, after) -> finish();
        scene.rootProperty().addListener(rootListener);
        scene.widthProperty().addListener(sizeListener);
        scene.heightProperty().addListener(sizeListener);
        double radius = Math.hypot(Math.max(x, width - x), Math.max(y, height - y)) + 4;
        animation.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO, new KeyValue(reveal.radiusProperty(), 0)),
                new KeyFrame(Duration.millis(620),
                        new KeyValue(reveal.radiusProperty(), radius, EASE),
                        new KeyValue(edge.opacityProperty(), 0, EASE)));
        animation.setOnFinished(event -> finish());
        animation.play();
    }

    private void pauseAnimations(Node node) {
        if (node instanceof ThemePausable animation) {
            animation.pauseForTheme();
            pausedAnimations.add(animation);
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(this::pauseAnimations);
        }
    }

    private void finish() {
        if (finished) return;
        finished = true;
        animation.stop();
        if (rootListener != null) scene.rootProperty().removeListener(rootListener);
        if (sizeListener != null) {
            scene.widthProperty().removeListener(sizeListener);
            scene.heightProperty().removeListener(sizeListener);
        }
        layer.getChildren().clear();
        if (scene.getRoot() == layer) {
            scene.setRoot(content);
            if (focus != null && focus.getScene() == scene) focus.requestFocus();
        }
        if (ACTIVE.get(scene) == this) ACTIVE.remove(scene);
        pausedAnimations.forEach(ThemePausable::resumeAfterTheme);
        pausedAnimations.clear();
    }
}
