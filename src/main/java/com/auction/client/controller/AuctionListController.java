package com.auction.client.controller;

import com.auction.client.model.AuctionSummary;
import com.auction.client.model.AuthenticatedSession;
import com.auction.client.service.AuctionListService;
import com.auction.client.ui.AuctionTheme;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
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
    private final ObservableList<AuctionSummary> auctions = FXCollections.observableArrayList();
    private final FilteredList<AuctionSummary> filteredAuctions = new FilteredList<>(auctions);
    private final TextField searchField = new TextField();
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final Label totalCount = AuctionTheme.label("0", "summary-value");
    private final Label runningCount = AuctionTheme.label("0", "summary-value");
    private final Label openCount = AuctionTheme.label("0", "summary-value");
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
    private AuthenticatedSession session;
    private final NumberFormat currencyFormat =
            NumberFormat.getCurrencyInstance(Locale.forLanguageTag("vi-VN"));

    public AuctionListController() {
        buildView();
    }

    public AuctionListController(AuthenticatedSession session) {
        this();
        this.session = session;
        hostField.setText(session.host());
        portField.setText(String.valueOf(session.port()));
        hostField.setEditable(false);
        portField.setEditable(false);
        loadAuctions();
    }

    public Parent getView() {
        return view;
    }

    private void buildView() {
        Label title = AuctionTheme.label("Khám phá phiên đấu giá", "market-title");
        Label subtitle = AuctionTheme.label("Tìm sản phẩm bạn quan tâm. Chọn phiên để xem thông tin chi tiết.", "muted");
        subtitle.setWrapText(true);

        connectionLabel.getStyleClass().add("muted");
        connectionLabel.setWrapText(true);
        selectionLabel.getStyleClass().add("muted");
        selectionLabel.setWrapText(true);
        enterRoomButton.getStyleClass().add("primary-button");
        reloadButton.getStyleClass().add("secondary-button");
        reloadButton.setOnAction(event -> loadAuctions());
        hostField.setPrefColumnCount(12);
        portField.setPrefColumnCount(5);
        HBox connectionBar = new HBox(10, new Label("Server:"), hostField,
                new Label("Cổng:"), portField);
        connectionBar.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox heading = new HBox(16, title, spacer, reloadButton);
        heading.setAlignment(Pos.CENTER_LEFT);

        VBox total = summaryCard("Tổng số phiên", totalCount);
        VBox running = summaryCard("Đang diễn ra", runningCount);
        VBox upcoming = summaryCard("Sắp bắt đầu", openCount);
        runningCount.getStyleClass().add("status-running");
        openCount.getStyleClass().add("status-open");
        HBox statistics = new HBox(14, total, running, upcoming);
        for (VBox card : List.of(total, running, upcoming)) {
            HBox.setHgrow(card, Priority.ALWAYS);
            card.setMaxWidth(Double.MAX_VALUE);
        }
        searchField.setPromptText("Tìm theo tên sản phẩm hoặc mã phiên…");
        searchField.setAccessibleText("Tìm phiên đấu giá");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        statusFilter.getItems().add("Tất cả trạng thái");
        for (String status : List.of("OPEN", "RUNNING", "FINISHED", "PAID", "CANCELED")) {
            statusFilter.getItems().add(statusText(status));
        }
        statusFilter.getSelectionModel().selectFirst();
        statusFilter.setAccessibleText("Lọc trạng thái phiên");
        searchField.textProperty().addListener((observable, before, value) -> filterAuctions());
        statusFilter.valueProperty().addListener((observable, before, value) -> filterAuctions());
        HBox filters = new HBox(12, searchField, statusFilter);
        VBox header = new VBox(16, heading, subtitle, statistics, filters, connectionBar, connectionLabel);
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

        view.getStyleClass().add("market-page");
        view.setTop(header);
        view.setCenter(auctionTable);
        view.setBottom(footer);
        AuctionTheme.apply(view);
    }

    private VBox summaryCard(String caption, Label value) {
        VBox card = new VBox(6, AuctionTheme.label(caption, "summary-caption"), value);
        card.getStyleClass().add("summary-card");
        return card;
    }

    private void filterAuctions() {
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        String selectedStatus = statusFilter.getValue();
        filteredAuctions.setPredicate(auction -> (auction.itemTitle().toLowerCase(Locale.ROOT).contains(query)
                || auction.auctionId().toLowerCase(Locale.ROOT).contains(query))
                && (statusFilter.getSelectionModel().getSelectedIndex() == 0
                || statusText(auction.status()).equals(selectedStatus)));
        if (!loading) {
            updateEmptyState();
        }
    }

    private void updateEmptyState() {
        auctionTable.setPlaceholder(new Label(auctions.isEmpty()
                ? "Chưa có phiên đấu giá. Bấm Tải danh sách để cập nhật."
                : "Không có phiên phù hợp. Thử từ khóa hoặc trạng thái khác."));
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
            {
                getStyleClass().add("price-cell");
            }
            @Override
            protected void updateItem(Double amount, boolean empty) {
                super.updateItem(amount, empty);
                setText(empty || amount == null ? null : currencyFormat.format(amount));
            }
        });
        priceColumn.setPrefWidth(180);

        TableColumn<AuctionSummary, String> statusColumn = new TableColumn<>("Trạng thái");
        statusColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().status()));
        statusColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                getStyleClass().removeAll("status-running", "status-open", "status-finished", "status-canceled");
                setText(empty || status == null ? null : statusText(status));
                if (!empty && status != null) {
                    getStyleClass().add(switch (status) {
                        case "RUNNING", "PAID" -> "status-running";
                        case "OPEN" -> "status-open";
                        case "CANCELED" -> "status-canceled";
                        default -> "status-finished";
                    });
                }
            }
        });
        statusColumn.setPrefWidth(150);

        auctionTable.getColumns().add(idColumn);
        auctionTable.getColumns().add(titleColumn);
        auctionTable.getColumns().add(priceColumn);
        auctionTable.getColumns().add(statusColumn);
        auctionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        auctionTable.getStyleClass().add("market-table");
        SortedList<AuctionSummary> sortedAuctions = new SortedList<>(filteredAuctions);
        sortedAuctions.comparatorProperty().bind(auctionTable.comparatorProperty());
        auctionTable.setItems(sortedAuctions);
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
        auctions.clear();
        totalCount.setText("0");
        runningCount.setText("0");
        openCount.setText("0");
        auctionTable.setPlaceholder(new Label("Đang tải danh sách…"));
        connectionLabel.setText("Đang tải từ " + host + ":" + port + "…");

        loadTask = new Task<>() {
            @Override
            protected List<AuctionSummary> call() throws Exception {
                return session == null ? auctionService.fetchAuctions(host, port)
                        : auctionService.fetchAuctions(session.client());
            }
        };
        // Task tự chạy các callback này trên luồng JavaFX, an toàn để cập nhật UI.
        loadTask.setOnSucceeded(event -> {
            if (closed) {
                return;
            }
            auctions.setAll(loadTask.getValue());
            totalCount.setText(String.valueOf(auctions.size()));
            runningCount.setText(String.valueOf(auctions.stream().filter(a -> a.status().equals("RUNNING")).count()));
            openCount.setText(String.valueOf(auctions.stream().filter(a -> a.status().equals("OPEN")).count()));
            auctionTable.sort();
            connectionLabel.setText("Đã tải " + loadTask.getValue().size()
                    + " phiên từ " + host + ":" + port + ".");
            setLoading(false);
            updateEmptyState();
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
        AuctionTheme.apply(preview.getDialogPane());
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
