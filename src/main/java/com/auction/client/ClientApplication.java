package com.auction.client;

import com.auction.client.controller.AuctionListController;
import com.auction.client.controller.AuthenticationController;
import com.auction.client.controller.AuctionDemoController;
import com.auction.client.model.AuthenticatedSession;
import com.auction.client.service.ConnectionPreferences;
import com.auction.client.ui.AuctionTheme;
import com.auction.client.ui.StartupIntro;
import com.auction.server.network.ServerEndpoint;
import javafx.application.Application;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import javafx.stage.Stage;

/** Entry point for the JavaFX auction client. */
public class ClientApplication extends Application {

    private AuctionListController controller;
    private AuthenticationController authentication;
    private AuctionDemoController demo;
    private Stage stage;
    private ServerEndpoint initialEndpoint;
    private StartupIntro intro;
    private Timeline introExit;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.initialEndpoint = ConnectionPreferences.resolveInitialEndpoint(getParameters().getRaw());
        stage.setTitle("BIDION");
        stage.getIcons().setAll(AuctionTheme.appIcon());
        stage.setMinWidth(760);
        stage.setMinHeight(440);
        intro = new StartupIntro(this::finishIntro);
        stage.setScene(new Scene(intro.getView(), 1280,
                getParameters().getRaw().contains("--preview") ? 920 : 860));
        stage.show();
        intro.play();
    }

    private void finishIntro() {
        Parent outgoing = stage.getScene().getRoot();
        intro = null;
        showInitialScreen();
        Parent incoming = stage.getScene().getRoot();

        // Retain both pages during the handoff instead of fading into an empty scene.
        StackPane transitionRoot = new StackPane();
        transitionRoot.setStyle("-fx-background-color: #0B111E;");
        transitionRoot.setMouseTransparent(true);
        transitionRoot.addEventFilter(javafx.scene.input.KeyEvent.ANY, event -> event.consume());
        stage.getScene().setRoot(transitionRoot);
        transitionRoot.getChildren().addAll(incoming, outgoing);
        incoming.setOpacity(0);
        incoming.setScaleX(0.985);
        incoming.setScaleY(0.985);
        incoming.setTranslateY(18);
        outgoing.setMouseTransparent(true);

        Interpolator smooth = Interpolator.SPLINE(0.22, 1, 0.36, 1);
        introExit = new Timeline(new KeyFrame(Duration.millis(750),
                new KeyValue(outgoing.opacityProperty(), 0, Interpolator.EASE_BOTH),
                new KeyValue(outgoing.scaleXProperty(), 0.96, smooth),
                new KeyValue(outgoing.scaleYProperty(), 0.96, smooth),
                new KeyValue(outgoing.translateYProperty(), -12, smooth),
                new KeyValue(incoming.opacityProperty(), 1, Interpolator.EASE_BOTH),
                new KeyValue(incoming.scaleXProperty(), 1, smooth),
                new KeyValue(incoming.scaleYProperty(), 1, smooth),
                new KeyValue(incoming.translateYProperty(), 0, smooth)));
        introExit.setOnFinished(event -> {
            transitionRoot.getChildren().clear();
            stage.getScene().setRoot(incoming);
            introExit = null;
        });
        introExit.play();
    }

    private void showInitialScreen() {
        if (getParameters().getRaw().contains("--preview")) {
            showPreview();
        } else if (getParameters().getRaw().contains("--demo")) {
            showDemo();
        } else {
            showLogin();
        }
    }

    private void showLogin() {
        stage.setTitle("BIDION");
        ServerEndpoint endpoint = initialEndpoint != null
                ? initialEndpoint
                : ConnectionPreferences.loadLastEndpoint();
        authentication = new AuthenticationController(this::showAuctions, null, endpoint);
        if (stage.getScene() == null) {
            stage.setScene(new Scene(authentication.getView(), 1280, 860));
        } else {
            stage.getScene().setRoot(authentication.getView());
        }
    }

    private void showPreview() {
        if (authentication != null) {
            authentication.close();
            authentication = null;
        }
        if (controller != null) controller.close();
        controller = AuctionListController.preview();
        Button room = new Button("Trải nghiệm phòng đấu  →");
        room.getStyleClass().add("primary-button");
        room.setOnAction(event -> {
            controller.close();
            controller = null;
            showDemo();
        });
        Button back = new Button("Đăng nhập");
        back.getStyleClass().add("ghost-button");
        back.setOnAction(event -> {
            controller.close();
            controller = null;
            showLogin();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label previewBadge = AuctionTheme.label("BẢN XEM UI · DỮ LIỆU MẪU", "feature-pill");
        HBox nav = new HBox(18, AuctionTheme.brand(), spacer, previewBadge, AuctionTheme.themeToggle(), back, room);
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.getStyleClass().add("navbar");
        BorderPane root = new BorderPane(controller.getView());
        previewBadge.visibleProperty().bind(root.widthProperty().greaterThan(1200));
        previewBadge.managedProperty().bind(previewBadge.visibleProperty());
        root.setTop(nav);
        AuctionTheme.apply(root);
        stage.setTitle("BIDION · Khám phá giao diện mẫu");
        if (stage.getScene() == null) stage.setScene(new Scene(root, 1280, 920));
        else stage.getScene().setRoot(root);
    }

    private void showDemo() {
        if (authentication != null) {
            authentication.close();
            authentication = null;
        }
        demo = new AuctionDemoController(() -> {
            demo.close();
            demo = null;
            stage.setTitle("BIDION");
            showLogin();
        });
        stage.setTitle("BIDION — Demo phòng đấu giá");
        if (stage.getScene() == null) {
            stage.setScene(new Scene(demo.getView(), 1280, 860));
        } else {
            stage.getScene().setRoot(demo.getView());
        }
    }

    private void showAuctions(AuthenticatedSession session) {
        stage.setTitle("BIDION · Sàn đấu giá");
        this.initialEndpoint = new ServerEndpoint(session.host(), session.port());
        controller = new AuctionListController(session);
        Button logout = new Button("Đăng xuất");
        logout.getStyleClass().add("ghost-button");
        logout.setOnAction(event -> {
            controller.close();
            controller = null;
            authentication.close();
            showLogin();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label account = AuctionTheme.label(session.username(), "account-chip");
        HBox navigation = new HBox(16, AuctionTheme.brand(),
                AuctionTheme.label("Sàn đấu giá", "nav-caption"), spacer, AuctionTheme.themeToggle(), account, logout);
        navigation.setAlignment(Pos.CENTER_LEFT);
        navigation.getStyleClass().add("navbar");
        HBox accountBar = new HBox(navigation);
        HBox.setHgrow(navigation, Priority.ALWAYS);
        accountBar.getStyleClass().add("account-bar");
        BorderPane root = new BorderPane();
        root.setTop(accountBar);
        root.setCenter(controller.getView());
        AuctionTheme.apply(root);
        stage.getScene().setRoot(root);
    }

    @Override
    public void stop() {
        if (intro != null) intro.close();
        if (introExit != null) introExit.stop();
        if (demo != null) {
            demo.close();
        }
        if (controller != null) {
            controller.close();
        }
        if (authentication != null) {
            authentication.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
