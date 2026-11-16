package com.auction.client.controller;

import com.auction.client.ui.AuctionTheme;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.text.NumberFormat;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static com.auction.client.ui.AuctionTheme.label;

/** Phòng đấu giá mô phỏng trong bộ nhớ, không gửi yêu cầu đến server. */
public final class AuctionDemoController implements AutoCloseable {
    private static final long STEP = 500_000;
    private final BorderPane view = new BorderPane();
    private final NumberFormat money = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("vi-VN"));
    private final Label price = label("", "demo-price");
    private final Label countdown = label("", "demo-clock");
    private final Label leader = label("", "status-running");
    private final Label feedback = label("Chọn mức giá và bắt đầu lượt đấu của bạn.", "muted");
    private final Label bidCount = label("", "muted");
    private final Label status = label("● ĐANG DIỄN RA", "status-running");
    private final TextField amount = new TextField();
    private final VBox history = new VBox(8);
    private final XYChart.Series<Number, Number> priceSeries = new XYChart.Series<>();
    private final NumberAxis bidAxis = new NumberAxis(0, 5, 1);
    private final Button submit = new Button("Đặt giá ngay");
    private final Button opponent = new Button("Mô phỏng người khác trả giá");
    private final Button lastSeconds = new Button("Thử 10 giây cuối");
    private final Timeline timer;
    private long highest;
    private long deadline;
    private int count;
    private String winner;
    private boolean finished;

    public AuctionDemoController(Runnable onExit) {
        Button back = new Button("← Thoát demo");
        back.getStyleClass().add("ghost-button");
        back.setOnAction(event -> onExit.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox nav = new HBox(18, AuctionTheme.brand(), spacer,
                label("DEMO • Dữ liệu mô phỏng", "feature-pill"), back);
        nav.setAlignment(Pos.CENTER_LEFT);
        nav.getStyleClass().add("navbar");

        VBox item = buildItem();
        VBox bidding = buildBidding();
        HBox columns = new HBox(24, item, bidding);
        HBox.setHgrow(item, Priority.ALWAYS);
        item.setMinWidth(300);
        bidding.setPrefWidth(390);
        bidding.setMinWidth(320);
        VBox content = new VBox(20,
                label("SÀN ĐẤU GIÁ  /  NGHỆ THUẬT  /  AUC-DEMO-01", "eyebrow"), columns);
        content.getStyleClass().add("demo-content");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("auth-scroll");
        view.setTop(nav);
        view.setCenter(scroll);
        view.getStyleClass().add("demo-page");
        AuctionTheme.apply(view);
        reset();
        timer = new Timeline(new KeyFrame(Duration.seconds(1), event -> tick()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private VBox buildItem() {
        Label title = label("Phố cổ trong nắng", "market-title");
        title.setWrapText(true);
        Canvas art = new Canvas(600, 290);
        var g = art.getGraphicsContext2D();
        g.setFill(Color.web("#e4caa0"));
        g.fillRect(0, 0, 600, 290);
        g.setFill(Color.web("#efdcb5"));
        g.fillOval(420, 26, 76, 76);
        String[] colors = {"#a45f44", "#d8ae73", "#7f8b83", "#c48b5b", "#aabdac", "#be7857"};
        for (int i = 0; i < 6; i++) {
            double x = i * 100;
            double y = 85 + (i % 3) * 21;
            g.setFill(Color.web(colors[i]));
            g.fillRect(x, y, 98, 250 - y);
            g.setFill(Color.web("#564e46"));
            g.fillPolygon(new double[]{x - 5, x + 49, x + 103}, new double[]{y, y - 30, y}, 3);
            g.setFill(Color.web("#424e4c"));
            g.fillRect(x + 19, y + 22, 18, 33);
            g.fillRect(x + 60, y + 22, 18, 33);
            g.fillRect(x + 37, 203, 27, 47);
        }
        g.setFill(Color.web("#8c8070"));
        g.fillPolygon(new double[]{0, 600, 600, 0}, new double[]{250, 250, 290, 290}, 4);
        g.setStroke(Color.web("#c9b79a"));
        for (int i = 0; i < 12; i++) g.strokeLine(i * 60, 290, 300 + (i - 6) * 18, 250);
        ImageView illustration = new ImageView(art.snapshot(null, null));
        illustration.setPreserveRatio(true);
        illustration.setSmooth(true);
        illustration.setManaged(false);
        StackPane artwork = new StackPane(illustration);
        artwork.getStyleClass().add("demo-artwork");
        artwork.setMinHeight(220);
        artwork.setPrefHeight(280);
        artwork.setMaxHeight(280);
        illustration.fitWidthProperty().bind(artwork.widthProperty().subtract(40));
        illustration.fitHeightProperty().bind(artwork.heightProperty().subtract(40));
        // Unmanaged images do not affect the frame's preferred layout size.
        illustration.layoutXProperty().bind(Bindings.createDoubleBinding(
                () -> (artwork.getWidth() - illustration.getBoundsInLocal().getWidth()) / 2,
                artwork.widthProperty(), illustration.boundsInLocalProperty()));
        illustration.layoutYProperty().bind(Bindings.createDoubleBinding(
                () -> (artwork.getHeight() - illustration.getBoundsInLocal().getHeight()) / 2,
                artwork.heightProperty(), illustration.boundsInLocalProperty()));
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(artwork.widthProperty());
        clip.heightProperty().bind(artwork.heightProperty());
        clip.setArcWidth(32);
        clip.setArcHeight(32);
        artwork.setClip(clip);
        Label description = label("Một góc phố Hà Nội với những mái ngói cũ và sắc nắng ấm. "
                + "Hình minh họa được dựng cho phiên trải nghiệm giao diện.", "muted");
        description.setWrapText(true);
        VBox details = new VBox(12, label("Thông tin tác phẩm", "ticket-title"), description,
                label("Chất liệu: Sơn dầu trên toan   •   Kích thước: 60 × 80 cm", "muted"),
                label("Người bán: Hà Nội Art Gallery  •  Sản phẩm mẫu", "muted"));
        details.getStyleClass().add("demo-card");
        VBox panel = new VBox(14, status, title,
                label("Bộ sưu tập nghệ thuật Việt Nam  /  Lô #001", "muted"), artwork, buildPriceChart(), details);
        return panel;
    }

    private VBox buildPriceChart() {
        bidAxis.setLabel("Lượt trả giá (0 = giá khởi điểm)");
        bidAxis.setMinorTickVisible(false);
        NumberAxis priceAxis = new NumberAxis();
        priceAxis.setLabel("Giá (triệu đồng)");
        priceAxis.setForceZeroInRange(false);
        priceAxis.setMinorTickVisible(false);
        LineChart<Number, Number> chart = new LineChart<>(bidAxis, priceAxis);
        chart.setId("demo-price-chart");
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setMinWidth(0);
        chart.setMinHeight(210);
        chart.setPrefHeight(235);
        chart.setMaxHeight(235);
        chart.setHorizontalZeroLineVisible(false);
        chart.setVerticalZeroLineVisible(false);
        chart.getData().add(priceSeries);
        chart.getStyleClass().add("demo-price-chart");
        Label hint = label("Giá cập nhật sau mỗi lượt trả giá. Di chuột vào điểm để xem chi tiết.", "form-hint");
        hint.setWrapText(true);
        VBox card = new VBox(6, label("Diễn biến giá", "ticket-title"), hint, chart);
        card.setMinWidth(0);
        card.getStyleClass().add("demo-card");
        return card;
    }

    private void addPricePoint(String bidder) {
        XYChart.Data<Number, Number> point = new XYChart.Data<>(count, highest / 1_000_000.0);
        String detail = (count == 0 ? "Giá khởi điểm" : "Lượt " + count + " · " + bidder)
                + "\n" + money.format(highest);
        point.nodeProperty().addListener((observable, before, node) -> {
            if (node != null) {
                Tooltip.install(node, new Tooltip(detail));
            }
        });
        priceSeries.getData().add(point);
        bidAxis.setUpperBound(Math.max(5, count));
        bidAxis.setTickUnit(Math.max(1, Math.ceil(count / 8.0)));
    }

    private VBox buildBidding() {
        amount.setAccessibleText("Giá muốn đặt, đơn vị đồng");
        amount.setPromptText("Nhập số tiền (VNĐ)");
        amount.setOnAction(event -> placeBid("Bạn", false));
        HBox increments = new HBox(8);
        for (long increment : new long[]{STEP, STEP * 2, STEP * 4}) {
            Button choice = new Button("+" + (increment / 1_000) + "k");
            choice.setAccessibleText("Chọn giá cao hơn giá hiện tại " + money.format(increment));
            choice.getStyleClass().add("secondary-button");
            choice.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(choice, Priority.ALWAYS);
            choice.setOnAction(event -> amount.setText(Long.toString(highest + increment)));
            increments.getChildren().add(choice);
        }
        submit.getStyleClass().add("primary-button");
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setOnAction(event -> placeBid("Bạn", false));
        feedback.setWrapText(true);
        VBox auction = new VBox(12, label("THỜI GIAN CÒN LẠI", "eyebrow"), countdown,
                new Separator(), label("GIÁ CAO NHẤT HIỆN TẠI", "eyebrow"), price, leader,
                label("Giá khởi điểm: 5.000.000 ₫  •  Bước giá: 500.000 ₫", "form-hint"),
                amount, increments, submit, feedback);
        auction.getStyleClass().add("demo-card");
        ScrollPane historyScroll = new ScrollPane(history);
        historyScroll.setFitToWidth(true);
        historyScroll.setPrefHeight(170);
        historyScroll.getStyleClass().add("auth-scroll");
        VBox activity = new VBox(10, label("Lịch sử trả giá", "ticket-title"), bidCount, historyScroll);
        activity.getStyleClass().add("demo-card");
        opponent.setMaxWidth(Double.MAX_VALUE);
        opponent.setOnAction(event -> placeBid("Người chơi mẫu", true));
        lastSeconds.setOnAction(event -> {
            if (finished) return;
            deadline = System.nanoTime() + 10_000_000_000L;
            tick();
            feedback.setText("Đặt giá trong 10 giây cuối để thử gia hạn thêm 60 giây.");
        });
        Button restart = new Button("Chơi lại");
        restart.setOnAction(event -> reset());
        HBox controls = new HBox(8, lastSeconds, restart);
        Label hint = label("Chế độ demo: thao tác chỉ thay đổi dữ liệu trên màn hình, không lưu tài khoản hay giao dịch.", "form-hint");
        hint.setWrapText(true);
        return new VBox(14, auction, activity, opponent, controls, hint);
    }

    private void reset() {
        highest = 5_000_000;
        count = 0;
        priceSeries.getData().clear();
        addPricePoint("Giá khởi điểm");
        winner = "Chưa có người trả giá";
        finished = false;
        status.setText("● ĐANG DIỄN RA");
        deadline = System.nanoTime() + 180_000_000_000L;
        history.getChildren().clear();
        history.getChildren().add(label("Phiên đã mở. Hãy là người trả giá đầu tiên!", "muted"));
        submit.setDisable(false);
        opponent.setDisable(false);
        lastSeconds.setDisable(false);
        amount.setDisable(false);
        feedback.setText("Chọn mức giá và bắt đầu lượt đấu của bạn.");
        refresh();
        tick();
    }

    private void placeBid(String bidder, boolean simulated) {
        tick();
        if (finished) return;
        long value;
        try {
            value = simulated ? Math.addExact(highest, STEP) : Long.parseLong(amount.getText().trim());
            if (value < highest + STEP || value > 1_000_000_000_000L) {
                feedback.setText("Giá phải từ " + money.format(highest + STEP) + " đến 1.000.000.000.000 ₫.");
                return;
            }
        } catch (IllegalArgumentException | ArithmeticException error) {
            feedback.setText("Nhập số tiền bằng chữ số, ví dụ: 5500000.");
            return;
        }
        boolean extended = deadline - System.nanoTime() <= 10_000_000_000L;
        if (extended) deadline += 60_000_000_000L;
        highest = value;
        winner = bidder;
        if (count == 0) history.getChildren().clear();
        count++;
        addPricePoint(bidder);
        Label entry = label(bidder + "  ·  " + money.format(value) + "  ·  "
                + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), "demo-bid-row");
        entry.setWrapText(true);
        history.getChildren().add(0, entry);
        feedback.setText((simulated ? "Người chơi mẫu vừa trả giá." : "Đặt giá thành công. Bạn đang dẫn đầu!")
                + (extended ? " Phiên được gia hạn thêm 60 giây." : ""));
        refresh();
        tick();
    }

    private void refresh() {
        price.setText(money.format(highest));
        leader.setText(count == 0 ? winner : "Đang dẫn đầu: " + winner);
        amount.setText(Long.toString(highest + STEP));
        bidCount.setText(count + " lượt trả giá");
    }

    private void tick() {
        long remaining = Math.max(0, (deadline - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
        countdown.setText(String.format("%02d : %02d", remaining / 60, remaining % 60));
        if (remaining == 0 && !finished) {
            finished = true;
            status.setText("● ĐÃ KẾT THÚC");
            submit.setDisable(true);
            opponent.setDisable(true);
            lastSeconds.setDisable(true);
            amount.setDisable(true);
            feedback.setText(count == 0 ? "Phiên kết thúc, chưa có lượt trả giá."
                    : "Phiên kết thúc. Người thắng: " + winner + " với " + money.format(highest));
        }
    }

    public Parent getView() { return view; }

    @Override
    public void close() { timer.stop(); }
}
