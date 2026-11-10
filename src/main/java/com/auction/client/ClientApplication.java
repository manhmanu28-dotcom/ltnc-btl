package com.auction.client;

import com.auction.client.controller.AuctionListController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Entry point for the JavaFX auction client. */
public class ClientApplication extends Application {

    private AuctionListController controller;

    @Override
    public void start(Stage stage) {
        controller = new AuctionListController();

        stage.setTitle("Auction Client");
        stage.setScene(new Scene(controller.getView(), 960, 600));
        stage.setMinWidth(760);
        stage.setMinHeight(440);
        stage.show();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
