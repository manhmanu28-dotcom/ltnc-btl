package com.auction.client.controller;

import com.auction.client.model.AuthenticatedSession;
import com.auction.client.model.AuctionRoomSnapshot;
import com.auction.client.service.AuctionRoomService;
import com.auction.client.service.SkinCatalog;
import com.auction.client.ui.AuctionTheme;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static com.auction.client.ui.AuctionTheme.label;

/** Live room: all prices, history and outcomes come from the authenticated server. */
public final class AuctionRoomController implements AutoCloseable {
    private final BorderPane view = new BorderPane();
    private final AuthenticatedSession session;
    private final AuctionRoomService service;
    private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("vi-VN"));
    private final Label title = label("Đang tải sản phẩm…", "market-title");
    private final Label description = label("", "muted");
    private final Label status = label("Đang tải phiên…", "status-running");
    private final Label connection = label("Đang đăng ký theo dõi phiên…", "form-hint");
    private final Label price = label("—", "demo-price");
    private final Label leader = label("", "muted");
    private final Label countdown = label("—", "demo-clock");
    private final Label rules = label("", "form-hint");
    private final Label feedback = label("Giá đặt sẽ được server kiểm tra trước khi chấp nhận.", "muted");
    private final Label bidCount = label("0 lượt trả giá", "muted");
    private final TextField amount = new TextField();
    private final Button submit = new Button("Đặt giá ngay");
    private final HBox increments = new HBox(8);
    private final VBox history = new VBox(8);
    private final StackPane artwork = new StackPane();
    private final NumberAxis bidAxis = new NumberAxis(0, 5, 1);
    private final XYChart.Series<Number, Number> series = new XYChart.Series<>();
    private final Timeline timer;
    private ParallelTransition entrance;
    private AuctionRoomSnapshot snapshot;
    private boolean busy;
    private boolean ready;
    private boolean closed;
    private String imageTitle;

    public AuctionRoomController(AuthenticatedSession session, String auctionId, Runnable onExit) {
        this.session = session;
        service = new AuctionRoomService(session.client(), auctionId,
                value -> Platform.runLater(() -> { if (!closed) apply(value); }),
                error -> Platform.runLater(() -> {
                    if (closed) return;
                    ready = false;
                    connection.setText("Không thể đồng bộ phiên. Bấm Đồng bộ lại để thử.");
                    feedback.setText(error.getMessage());
                    updateControls();
                }));
        Button back = new Button("← Rời phòng");
        back.getStyleClass().add("ghost-button");
        back.setOnAction(event -> onExit.run());
        Button refresh = new Button("Đồng bộ lại");
        refresh.getStyleClass().add("secondary-button");
        refresh.setOnAction(event -> service.refresh());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox dragArea = new HBox(AuctionTheme.brand(), spacer);
        dragArea.setAlignment(Pos.CENTER_LEFT);
        dragArea.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(dragArea, Priority.ALWAYS);
        double[] dragOffset = new double[2];
        dragArea.setOnMousePressed(event -> {
            if (!event.isPrimaryButtonDown()) return;
            var window = view.getScene().getWindow();
            dragOffset[0] = event.getScreenX() - window.getX();
            dragOffset[1] = event.getScreenY() - window.getY();
        });
        dragArea.setOnMouseDragged(event -> {
            if (!event.isPrimaryButtonDown()) return;
            var window = view.getScene().getWindow();
            window.setX(event.getScreenX() - dragOffset[0]);
            window.setY(event.getScreenY() - dragOffset[1]);
        });
        HBox nav = new HBox(12, dragArea,
                AuctionTheme.themeToggle(), refresh, back);
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.getStyleClass().addAll("navbar", "live-room-header");

        title.setWrapText(true);
        description.setWrapText(true);
        artwork.getStyleClass().add("demo-artwork");
        artwork.setMinHeight(200);
        artwork.setPrefHeight(260);
        VBox details = new VBox(12, label("Thông tin sản phẩm", "ticket-title"), description);
        details.getStyleClass().add("demo-card");
        VBox left = new VBox(14, status, title, artwork, buildChart(), details);
        left.setMinWidth(0);
        VBox right = buildBidding();
        right.setMinWidth(0);
        GridPane columns = new GridPane();
        columns.setHgap(24);
        ColumnConstraints main = new ColumnConstraints();
        main.setPercentWidth(58);
        ColumnConstraints side = new ColumnConstraints();
        side.setPercentWidth(42);
        columns.getColumnConstraints().addAll(main, side);
        columns.add(left, 0, 0);
        columns.add(right, 1, 0);
        // Stack panels on compact windows so the input and chart remain usable.
        view.widthProperty().addListener((observable, before, width) -> {
            boolean compact = width.doubleValue() < 900;
            main.setPercentWidth(compact ? 100 : 58);
            side.setPercentWidth(compact ? 0 : 42);
            GridPane.setColumnIndex(right, compact ? 0 : 1);
            GridPane.setRowIndex(right, compact ? 1 : 0);
            columns.setVgap(compact ? 20 : 0);
        });
        connection.setWrapText(true);
        VBox content = new VBox(14, label("PHIÊN / " + auctionId + " · " + session.username(), "eyebrow"),
                connection, columns);
        content.getStyleClass().addAll("demo-content", "live-room-content");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("auth-scroll");
        view.setTop(nav);
        view.setCenter(scroll);
        view.getStyleClass().addAll("demo-page", "live-room-page");
        AuctionTheme.apply(view);
        timer = new Timeline(new KeyFrame(Duration.millis(100), event -> tick()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        updateControls();
        service.start();
    }

    private VBox buildChart() {
        NumberAxis x = bidAxis;
        x.setLabel("Lượt trả giá");
        x.setTickUnit(1);
        x.setMinorTickVisible(false);
        NumberAxis y = new NumberAxis();
        y.setLabel("Giá (triệu đồng)");
        y.setForceZeroInRange(false);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setMinWidth(0);
        chart.setMinHeight(220);
        chart.setPrefHeight(240);
        chart.getData().add(series);
        chart.getStyleClass().add("demo-price-chart");
        VBox card = new VBox(8, label("Diễn biến giá", "ticket-title"), chart);
        card.setMinWidth(0);
        card.getStyleClass().add("demo-card");
        return card;
    }

    private VBox buildBidding() {
        amount.setPromptText("Nhập số tiền bằng đồng, ví dụ 5500000");
        amount.setAccessibleText("Giá muốn đặt, đơn vị đồng");
        amount.setOnAction(event -> placeBid());
        for (int steps : new int[]{1, 2, 5}) {
            Button quick = new Button("+" + steps + " bước");
            quick.getStyleClass().add("secondary-button");
            quick.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(quick, Priority.ALWAYS);
            quick.setOnAction(event -> {
                if (snapshot != null) amount.setText(input(Math.max(snapshot.minimumNextBid(),
                        snapshot.highest() + steps * snapshot.increment())));
            });
            increments.getChildren().add(quick);
        }
        submit.getStyleClass().add("primary-button");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setOnAction(event -> placeBid());
        rules.setWrapText(true);
        leader.setWrapText(true);
        feedback.setWrapText(true);
        VBox bid = new VBox(12, label("THỜI GIAN CÒN LẠI", "eyebrow"), countdown,
                new Separator(), label("GIÁ CAO NHẤT HIỆN TẠI", "eyebrow"), price,
                leader, rules, amount, increments, submit, feedback);
        bid.getStyleClass().add("demo-card");
        ScrollPane historyScroll = new ScrollPane(history);
        historyScroll.setFitToWidth(true);
        historyScroll.setPrefHeight(240);
        historyScroll.getStyleClass().add("auth-scroll");
        VBox activity = new VBox(10, label("Lịch sử trả giá", "ticket-title"), bidCount, historyScroll);
        activity.getStyleClass().add("demo-card");
        return new VBox(14, bid, activity);
    }

    private void apply(AuctionRoomSnapshot value) {
        boolean outbid = snapshot != null && session.username().equals(snapshot.winner())
                && !session.username().equals(value.winner());
        boolean changedPrice = snapshot == null || snapshot.minimumNextBid() != value.minimumNextBid();
        snapshot = value;
        ready = true;
        title.setText(value.title());
        description.setText(value.description());
        price.setText(money.format(value.highest()));
        leader.setText(value.winner().isBlank() ? "Chưa có người trả giá"
                : "Đang dẫn đầu: " + value.winner() + (session.username().equals(value.winner()) ? " (Bạn)" : ""));
        rules.setText("Khởi điểm: " + money.format(value.startingPrice()) + "\nBước giá: "
                + money.format(value.increment()) + "\nGiá tối thiểu tiếp theo: " + money.format(value.minimumNextBid()));
        status.setText(switch (value.status()) {
            case "RUNNING" -> "● ĐANG ĐẤU GIÁ";
            case "OPEN" -> "● CHƯA BẮT ĐẦU";
            case "FINISHED" -> "● ĐÃ KẾT THÚC";
            case "PAID" -> "● ĐÃ THANH TOÁN";
            case "CANCELED" -> "● ĐÃ HỦY";
            default -> value.status();
        });
        connection.setText("● Đã kết nối · Đang nhận cập nhật trực tiếp từ server");
        if (changedPrice) amount.setText(input(value.minimumNextBid()));
        if (outbid) feedback.setText("Có người vừa vượt giá bạn. Giá đã được cập nhật.");
        if ("FINISHED".equals(value.status()) || "PAID".equals(value.status())) {
            feedback.setText(value.winner().isBlank() ? "Phiên kết thúc, chưa có người thắng."
                    : "Người thắng: " + value.winner() + " · " + money.format(value.highest()));
        } else if ("CANCELED".equals(value.status())) feedback.setText("Phiên đấu giá đã bị hủy.");
        renderHistory(value);
        if (!value.title().equals(imageTitle)) {
            imageTitle = value.title();
            artwork.getChildren().setAll(label("SẢN PHẨM ĐẤU GIÁ", "eyebrow"));
            SkinCatalog.findByTitle(value.title()).ifPresent(skin -> {
                var resource = getClass().getResource(skin.imagePath());
                if (resource == null) return;
                ImageView image = new ImageView(new Image(resource.toExternalForm()));
                image.setPreserveRatio(true);
                image.setManaged(false);
                image.fitWidthProperty().bind(artwork.widthProperty().subtract(32));
                image.setFitHeight(230);
                image.layoutXProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                        () -> (artwork.getWidth() - image.getBoundsInLocal().getWidth()) / 2,
                        artwork.widthProperty(), image.boundsInLocalProperty()));
                image.layoutYProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                        () -> (artwork.getHeight() - image.getBoundsInLocal().getHeight()) / 2,
                        artwork.heightProperty(), image.boundsInLocalProperty()));
                artwork.getChildren().setAll(image);
            });
        }
        tick();
    }

    private void renderHistory(AuctionRoomSnapshot value) {
        history.getChildren().clear();
        series.getData().clear();
        series.getData().add(new XYChart.Data<>(0, value.startingPrice() / 1_000_000));
        int index = 0;
        for (var bid : value.bids()) {
            series.getData().add(new XYChart.Data<>(++index, bid.amount() / 1_000_000));
            Label row = label(bid.bidder() + " · " + money.format(bid.amount()) + "\n"
                    + bid.time().format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")), "demo-bid-row");
            row.setWrapText(true);
            history.getChildren().add(0, row);
        }
        bidCount.setText(value.bids().size() + " lượt trả giá");
        bidAxis.setUpperBound(Math.max(5, value.bids().size()));
        bidAxis.setTickUnit(Math.max(1, Math.ceil(value.bids().size() / 8.0)));
        if (value.bids().isEmpty()) history.getChildren().add(label("Chưa có lượt trả giá.", "muted"));
    }

    private void placeBid() {
        if (submit.isDisabled() || snapshot == null) return;
        double value;
        try {
            String text = amount.getText().strip();
            if (!text.matches("[0-9]+(\\.[0-9]{1,2})?")) throw new IllegalArgumentException();
            value = Double.parseDouble(text);
            if (!Double.isFinite(value) || value < snapshot.minimumNextBid()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            feedback.setText("Nhập số tiền bằng chữ số, tối thiểu " + money.format(snapshot.minimumNextBid()) + ".");
            return;
        }
        busy = true;
        updateControls();
        feedback.setText("Đang chờ server xác nhận giá…");
        service.placeBid(value).whenComplete((message, error) -> Platform.runLater(() -> {
            if (closed) return;
            busy = false;
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                feedback.setText(cause.getMessage());
                service.refresh();
            } else if (snapshot != null && "RUNNING".equals(snapshot.status())) {
                feedback.setText(message + (session.username().equals(snapshot.winner())
                        ? " Bạn đang dẫn đầu." : " Giá mới nhất đã được đồng bộ."));
            }
            updateControls();
        }));
    }

    private long remainingSeconds() {
        if (snapshot == null) return 0;
        return snapshot.remainingSeconds(System.nanoTime());
    }

    private void tick() {
        if (snapshot != null) {
            long seconds = remainingSeconds();
            countdown.setText(String.format("%02d : %02d : %02d", seconds / 3600, seconds / 60 % 60, seconds % 60));
            if (seconds == 0 && "RUNNING".equals(snapshot.status())) {
                connection.setText("Đang chờ server xác nhận kết thúc hoặc gia hạn phiên…");
            }
        }
        if (!session.client().isConnected()) {
            ready = false;
            connection.setText("● Mất kết nối server. Rời phòng và đăng nhập lại để kết nối.");
        }
        updateControls();
    }

    private void updateControls() {
        boolean disabled = !ready || busy || !session.client().isConnected() || snapshot == null
                || !"RUNNING".equals(snapshot.status()) || remainingSeconds() == 0;
        submit.setDisable(disabled);
        amount.setDisable(disabled);
        increments.setDisable(disabled);
        submit.setText(busy ? "Đang gửi giá…" : "Đặt giá ngay");
    }

    private String input(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    public Parent getView() { return view; }

    /** Fade the header and gently lift the content after the room window opens. */
    public void playEntrance() {
        if (closed) return;
        if (entrance != null) entrance.stop();
        FadeTransition header = new FadeTransition(Duration.millis(220), view.getTop());
        header.setFromValue(0);
        header.setToValue(1);
        FadeTransition content = new FadeTransition(Duration.millis(320), view.getCenter());
        content.setFromValue(0);
        content.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(320), view.getCenter());
        slide.setFromY(14);
        slide.setToY(0);
        slide.setInterpolator(Interpolator.EASE_OUT);
        entrance = new ParallelTransition(header, content, slide);
        entrance.play();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (entrance != null) entrance.stop();
        timer.stop();
        service.close();
    }
}
