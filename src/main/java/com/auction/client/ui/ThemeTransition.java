package com.auction.client.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Smoothly cross-fades the entire screen between light and dark palettes. */
final class ThemeTransition {
    private static final Map<Scene, ThemeTransition> ACTIVE = new WeakHashMap<>();
    private static final Interpolator SMOOTH_FADE = Interpolator.SPLINE(0.42, 0.0, 0.18, 1.0);
    private static final Interpolator VEIL_IN = Interpolator.SPLINE(0.35, 0.0, 0.25, 1.0);
    private static final Interpolator VEIL_OUT = Interpolator.SPLINE(0.22, 0.0, 0.36, 1.0);
    private static final Duration TRANSITION_DURATION = Duration.millis(540);
    private static final Duration VEIL_PEAK_TIME = Duration.millis(180);

    private final Scene scene;
    private final Parent content;
    private final StackPane layer = new StackPane();
    private final Timeline animation = new Timeline();
    private final Node focus;
    private final List<ThemePausable> pausedAnimations = new ArrayList<>();
    private ChangeListener<Parent> rootListener;
    private ChangeListener<Number> sizeListener;
    private boolean changed;
    private boolean finished;

    private ThemeTransition(Scene scene, Parent content) {
        this.scene = scene;
        this.content = content;
        this.focus = scene.getFocusOwner();
    }

    static void play(Button source, Runnable changeTheme) {
        play(source, false, changeTheme);
    }

    static void play(Button source, boolean toDark, Runnable changeTheme) {
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
            transition.start(toDark, changeTheme);
        } catch (RuntimeException error) {
            // A snapshot may be unavailable while a window is closing.
            transition.finish();
            if (!transition.changed) changeTheme.run();
        }
    }

    private void start(boolean toDark, Runnable changeTheme) {
        pauseAnimations(content);
        double width = scene.getWidth();
        double height = scene.getHeight();

        ImageView oldPalette = createSnapshotView(width, height);
        changeTheme.run();
        changed = true;
        content.applyCss();
        content.layout();
        ImageView newPalette = createSnapshotView(width, height);
        newPalette.setOpacity(0.0);

        // Lớp phủ ánh sáng nhẹ giúp toàn màn hình sáng dần (Dark -> Light) hoặc dịu tối dần (Light -> Dark) mượt mắt.
        Rectangle ambientVeil = new Rectangle(width, height,
                toDark ? Color.web("#0B1220") : Color.web("#FFFFFF"));
        ambientVeil.setOpacity(0.0);
        ambientVeil.setMouseTransparent(true);

        Pane overlay = new Pane(oldPalette, newPalette, ambientVeil);
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

        double peakVeilOpacity = toDark ? 0.10 : 0.12;
        animation.getKeyFrames().setAll(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(newPalette.opacityProperty(), 0.0),
                        new KeyValue(ambientVeil.opacityProperty(), 0.0)),
                new KeyFrame(VEIL_PEAK_TIME,
                        new KeyValue(ambientVeil.opacityProperty(), peakVeilOpacity, VEIL_IN)),
                new KeyFrame(TRANSITION_DURATION,
                        new KeyValue(newPalette.opacityProperty(), 1.0, SMOOTH_FADE),
                        new KeyValue(ambientVeil.opacityProperty(), 0.0, VEIL_OUT)));
        animation.setOnFinished(event -> finish());
        animation.play();
    }

    private ImageView createSnapshotView(double width, double height) {
        ImageView view = new ImageView(scene.snapshot(null));
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setSmooth(true);
        return view;
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
