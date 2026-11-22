package com.auction.client;

import com.auction.client.controller.AuctionListController;
import com.auction.client.controller.AuthenticationController;
import com.auction.client.controller.AuctionDemoController;
import com.auction.client.model.AuthenticatedSession;
import com.auction.client.ui.AuctionTheme;
import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/** Entry point for the JavaFX auction client. */
public class ClientApplication extends Application {

    private AuctionListController controller;
    private AuthenticationController authentication;
    private AuctionDemoController demo;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("Auction Client");
        if (getParameters().getRaw().contains("--preview")) {
            showPreview();
        } else if (getParameters().getRaw().contains("--demo")) {
            showDemo();
        } else {
            showLogin();
        }
        stage.setMinWidth(760);
        stage.setMinHeight(440);
        stage.show();
    }

    private void showLogin() {
        authentication = new AuthenticationController(this::showAuctions, this::showPreview);
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
        stage.setTitle("Auction · Khám phá giao diện mẫu");
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
            stage.setTitle("Auction Client");
            showLogin();
        });
        stage.setTitle("Auction Client — Demo phòng đấu giá");
        if (stage.getScene() == null) {
            stage.setScene(new Scene(demo.getView(), 1280, 860));
        } else {
            stage.getScene().setRoot(demo.getView());
        }
    }

    private void showAuctions(AuthenticatedSession session) {
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
