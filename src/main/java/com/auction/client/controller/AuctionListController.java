package com.auction.client.controller;

import com.auction.client.model.AuctionSummary;
import com.auction.client.service.AuctionListService;
import javafx.concurrent.Task;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dựng màn hình danh sách và tải các phiên thật từ Socket Server. */
public class AuctionListController implements AutoCloseable {

    private final BorderPane view = new BorderPane();
    private final TableView<AuctionSummary> auctionTable = new TableView<>();
    private final Label selectionLabel = new Label("Chọn một phiên trong bảng để vào phòng.");
    private final Button enterRoomButton = new Button("Vào phòng");
    private final Button reloadButton = new Button("Tải danh sách");
    private final TextField hostField = new TextField("127.0.0.1");
    private final TextField portField = new TextField("8888");
    private final Label connectionLabel = new Label("Nhập địa chỉ server rồi bấm Tải danh sách.");
    private final AuctionListService auctionService = new AuctionListService();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Auction-List-Loader");
        thread.setDaemon(true);
        return thread;
    });
    private Task<List<AuctionSummary>> loadTask;
    private boolean loading;
    private boolean closed;
    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(Locale.forLanguageTag("vi-VN"));

    public AuctionListController() {
        buildView();
    }

    public Parent getView() {
        return view;
    }

    private void buildView() {
        Label title = new Label("Danh sách phiên đấu giá");
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;");

        connectionLabel.setStyle("-fx-text-fill: #666666;");
        reloadButton.setOnAction(event -> loadAuctions());
        hostField.setPrefColumnCount(18);
        portField.setPrefColumnCount(5);
        HBox connectionBar = new HBox(10, new Label("Server:"), hostField,
                new Label("Cổng:"), portField);
        connectionBar.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox heading = new HBox(16, title, spacer, reloadButton);
        heading.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(10, heading, connectionBar, connectionLabel);
        BorderPane.setMargin(header, new Insets(0, 0, 20, 0));

        configureTable();

        enterRoomButton.setDisable(true);
        enterRoomButton.setOnAction(event -> showSelectedAuction());
        auctionTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldSelection, selected) -> {
                    enterRoomButton.setDisable(loading || selected == null);
                    selectionLabel.setText(selected == null
                            ? "Chọn một phiên trong bảng để vào phòng."
                            : "Đã chọn: " + selected.itemTitle());
                });

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(16, selectionLabel, footerSpacer, enterRoomButton);
        footer.setAlignment(Pos.CENTER_LEFT);
        BorderPane.setMargin(footer, new Insets(16, 0, 0, 0));

        view.setPadding(new Insets(24));
        view.setTop(header);
        view.setCenter(auctionTable);
        view.setBottom(footer);
    }

    private void configureTable() {
        TableColumn<AuctionSummary, String> idColumn = new TableColumn<>("Mã phiên");
        idColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().auctionId()));
        idColumn.setPrefWidth(130);

        TableColumn<AuctionSummary, String> titleColumn = new TableColumn<>("Tên sản phẩm");
        titleColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().itemTitle()));
        titleColumn.setPrefWidth(330);

        // Giữ giá ở dạng số để thao tác sắp xếp theo giá chính xác.
        TableColumn<AuctionSummary, Double> priceColumn = new TableColumn<>("Giá hiện tại");
        priceColumn.setCellValueFactory(cell ->
                new ReadOnlyObjectWrapper<>(cell.getValue().currentHighestBid()));
        priceColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Double amount, boolean empty) {
                super.updateItem(amount, empty);
                setText(empty || amount == null ? null : currencyFormat.format(amount));
            }
        });
        priceColumn.setPrefWidth(180);

        TableColumn<AuctionSummary, String> statusColumn = new TableColumn<>("Trạng thái");
        statusColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(statusText(cell.getValue().status())));
        statusColumn.setPrefWidth(150);

        auctionTable.getColumns().add(idColumn);
        auctionTable.getColumns().add(titleColumn);
        auctionTable.getColumns().add(priceColumn);
        auctionTable.getColumns().add(statusColumn);
        auctionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        auctionTable.setPlaceholder(new Label("Chưa có phiên đấu giá nào."));
    }

    private void loadAuctions() {
        if (loading || closed) {
            return;
        }
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (host.isEmpty() || port < 1 || port > 65535) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            connectionLabel.setText("Hãy nhập địa chỉ server và cổng từ 1 đến 65535.");
            return;
        }

        setLoading(true);
        auctionTable.getSelectionModel().clearSelection();
        auctionTable.getItems().clear();
        auctionTable.setPlaceholder(new Label("Đang tải danh sách…"));
        connectionLabel.setText("Đang tải từ " + host + ":" + port + "…");

        loadTask = new Task<>() {
            @Override
            protected List<AuctionSummary> call() throws Exception {
                return auctionService.fetchAuctions(host, port);
            }
        };
        // Task tự chạy các callback này trên luồng JavaFX, an toàn để cập nhật UI.
        loadTask.setOnSucceeded(event -> {
            if (closed) {
                return;
            }
            auctionTable.getItems().setAll(loadTask.getValue());
            auctionTable.sort();
            auctionTable.setPlaceholder(new Label("Server chưa có phiên đấu giá nào."));
            connectionLabel.setText("Đã tải " + loadTask.getValue().size()
                    + " phiên từ " + host + ":" + port + ".");
            setLoading(false);
        });
        loadTask.setOnFailed(event -> {
            if (closed) {
                return;
            }
            Throwable error = loadTask.getException();
            String detail = error.getMessage();
            connectionLabel.setText("Không tải được danh sách: "
                    + (detail == null ? error.getClass().getSimpleName() : detail)
                    + ". Kiểm tra server rồi thử lại.");
            auctionTable.setPlaceholder(new Label("Không tải được dữ liệu. Hãy thử lại."));
            setLoading(false);
        });
        networkExecutor.execute(loadTask);
    }

    private void setLoading(boolean value) {
        loading = value;
        reloadButton.setDisable(value);
        reloadButton.setText(value ? "Đang tải…" : "Tải danh sách");
        hostField.setDisable(value);
        portField.setDisable(value);
        enterRoomButton.setDisable(value || auctionTable.getSelectionModel().getSelectedItem() == null);
    }

    @Override
    public void close() {
        closed = true;
        if (loadTask != null) {
            loadTask.cancel(true);
        }
        networkExecutor.shutdownNow();
    }

    private void showSelectedAuction() {
        AuctionSummary selected = auctionTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }

        // Bản xem trước để kiểm tra chọn dòng; phòng realtime sẽ được làm sau.
        Alert preview = new Alert(Alert.AlertType.INFORMATION);
        preview.initOwner(view.getScene().getWindow());
        preview.setTitle("Thông tin phiên đấu giá");
        preview.setHeaderText(selected.itemTitle());
        preview.setContentText("Mã phiên: " + selected.auctionId()
                + "\nGiá hiện tại: " + currencyFormat.format(selected.currentHighestBid())
                + "\nTrạng thái: " + statusText(selected.status())
                + "\n\nĐây là bản xem trước. Phòng đấu giá realtime chưa được kết nối.");
        preview.showAndWait();
    }

    private String statusText(String status) {
        return switch (status) {
            case "OPEN" -> "Sắp diễn ra";
            case "RUNNING" -> "Đang đấu giá";
            case "FINISHED" -> "Đã kết thúc";
            case "PAID" -> "Đã thanh toán";
            case "CANCELED" -> "Đã hủy";
            default -> status;
        };
    }
}
