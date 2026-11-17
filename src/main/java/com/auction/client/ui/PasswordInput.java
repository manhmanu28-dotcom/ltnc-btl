package com.auction.client.ui;

import javafx.geometry.Pos;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/** Một giá trị mật khẩu dùng chung giữa chế độ ẩn và hiện. */
public final class PasswordInput extends HBox {
    private final ToggleButton reveal = new ToggleButton();

    public PasswordInput(PasswordField password) {
        super(0);
        TextField plain = new TextField();
        plain.textProperty().bindBidirectional(password.textProperty());
        plain.promptTextProperty().bind(password.promptTextProperty());
        plain.setAccessibleText("Mật khẩu đang hiển thị");
        plain.setOnAction(event -> {
            if (password.getOnAction() != null) password.getOnAction().handle(event);
        });
        plain.visibleProperty().bind(reveal.selectedProperty());
        plain.managedProperty().bind(plain.visibleProperty());
        password.visibleProperty().bind(reveal.selectedProperty().not());
        password.managedProperty().bind(password.visibleProperty());
        StackPane fields = new StackPane(password, plain);
        fields.setMinWidth(0);
        HBox.setHgrow(fields, Priority.ALWAYS);
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("password-input");
        reveal.getStyleClass().add("password-eye");
        reveal.setMinSize(44, 42);
        reveal.setPrefSize(44, 42);
        updateEye();
        reveal.setOnAction(event -> {
            TextField before = reveal.isSelected() ? password : plain;
            TextField after = reveal.isSelected() ? plain : password;
            int caret = before.getCaretPosition();
            updateEye();
            after.requestFocus();
            after.positionCaret(caret);
        });
        getChildren().addAll(fields, reveal);
    }

    private void updateEye() {
        SVGPath eye = new SVGPath();
        eye.setContent("M 1 10 Q 10 -3 19 10 Q 10 23 1 10 Z "
                + "M 13 10 A 3 3 0 1 1 7 10 A 3 3 0 1 1 13 10"
                + (reveal.isSelected() ? " M 2 1 L 18 19" : ""));
        eye.setFill(Color.TRANSPARENT);
        eye.setStroke(Color.web("#a9bfdf"));
        eye.setStrokeWidth(1.6);
        reveal.setGraphic(eye);
        String action = reveal.isSelected() ? "Ẩn mật khẩu" : "Hiện mật khẩu";
        reveal.setAccessibleText(action);
        reveal.setTooltip(new Tooltip(action));
    }

    public void conceal() {
        reveal.setSelected(false);
        updateEye();
    }
}
