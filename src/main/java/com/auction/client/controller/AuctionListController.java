package com.auction.client.controller;

import com.auction.client.model.AuctionSummary;
import com.auction.client.model.AuthenticatedSession;
import com.auction.client.service.AuctionListService;
import com.auction.client.ui.AuctionTheme;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
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
    private final Button createAuctionButton = new Button("+ Đăng bán sản phẩm");
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
    private boolean previewMode;
    private final TilePane productGrid = new TilePane();
    private final Label emptyMessage = AuctionTheme.label("Chưa có phiên đấu giá. Bấm Tải danh sách để cập nhật.", "muted");
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

    /** Bản xem UI độc lập, không gọi mạng hoặc tạo giao dịch. */
    public static AuctionListController preview() {
        AuctionListController controller = new AuctionListController();
        controller.previewMode = true;
        controller.reloadButton.setDisable(true);
        controller.createAuctionButton.setDisable(true);
        controller.hostField.setDisable(true);
        controller.portField.setDisable(true);
        controller.connectionLabel.setText("BẢN XEM GIAO DIỆN · Tất cả sản phẩm và mức giá dưới đây là dữ liệu mẫu.");
        controller.auctions.setAll(
                new AuctionSummary("DEMO-01", "Phố cổ trong nắng", 5000000, "RUNNING"),
                new AuctionSummary("DEMO-02", "Đồng hồ cổ điển", 12500000, "RUNNING"),
                new AuctionSummary("DEMO-03", "Máy ảnh film 35mm", 2800000, "OPEN"),
                new AuctionSummary("DEMO-04", "Bình gốm thủ công", 1500000, "OPEN"),
                new AuctionSummary("DEMO-05", "Góc phố mùa thu", 7200000, "RUNNING"),
                new AuctionSummary("DEMO-06", "Bộ sưu tập giới hạn", 3200000, "FINISHED"));
        controller.totalCount.setText("6");
        controller.runningCount.setText("3");
        controller.openCount.setText("2");
        return controller;
    }

    private void buildView() {
        Label title = AuctionTheme.label("Tìm thấy điều đặc biệt.", "market-title");
        title.setMinWidth(0);
        title.setWrapText(true);
        Label subtitle = AuctionTheme.label("Khám phá những món đồ có câu chuyện. Bắt đầu phiên đấu giá của bạn hôm nay.", "muted");
        subtitle.setWrapText(true);

        connectionLabel.getStyleClass().add("muted");
        connectionLabel.setWrapText(true);
        selectionLabel.getStyleClass().add("muted");
        selectionLabel.setWrapText(true);
        enterRoomButton.getStyleClass().add("primary-button");
        reloadButton.getStyleClass().add("secondary-button");
        reloadButton.setOnAction(event -> loadAuctions());
        createAuctionButton.getStyleClass().add("primary-button");
        createAuctionButton.setOnAction(event -> showCreateAuctionDialog());
        hostField.setPrefColumnCount(12);
        portField.setPrefColumnCount(5);
        HBox connectionBar = new HBox(10, new Label("Server:"), hostField,
                new Label("Cổng:"), portField);
        connectionBar.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox heading = new HBox(12, title, spacer, createAuctionButton, reloadButton);
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
        TitledPane connectionSettings = new TitledPane("Cài đặt kết nối", connectionBar);
        connectionSettings.setExpanded(false);
        connectionSettings.getStyleClass().add("connection-options");
        VBox header = new VBox(14, AuctionTheme.label("KHÁM PHÁ / PHIÊN ĐẤU GIÁ", "eyebrow"), heading, subtitle, statistics, filters, connectionSettings, connectionLabel);
        BorderPane.setMargin(header, new Insets(0, 0, 20, 0));

        configureTable();
        productGrid.setHgap(20);
        productGrid.setVgap(20);
        productGrid.setPrefTileWidth(300);
        productGrid.setTileAlignment(Pos.TOP_LEFT);
        productGrid.setPadding(new Insets(2, 4, 16, 2));
        ScrollPane products = new ScrollPane(productGrid);
        products.setFitToWidth(true);
        products.viewportBoundsProperty().addListener((observable, before, bounds) -> {
            double available = Math.max(280, bounds.getWidth() - 24);
            int columns = Math.max(1, (int) ((available + 20) / 340));
            productGrid.setPrefTileWidth((available - (columns - 1) * 20) / columns);
        });
        products.getStyleClass().add("auth-scroll");
        filteredAuctions.addListener((ListChangeListener<AuctionSummary>) change -> renderProducts());
        renderProducts();

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
        view.setCenter(products);
        AuctionTheme.apply(view);
    }

    private void renderProducts() {
        productGrid.getChildren().clear();
        if (filteredAuctions.isEmpty()) {
            productGrid.getChildren().add(emptyMessage);
            return;
        }
        int index = 0;
        for (AuctionSummary auction : filteredAuctions) {
            StackPane artwork = new StackPane();
            artwork.getStyleClass().addAll("product-art", "product-art-" + (index++ % 3));
            if (previewMode) {
                artwork.getChildren().add(com.auction.client.ui.ProductArtwork.create(auction.itemTitle()));
            } else {
                artwork.getChildren().add(AuctionTheme.label("Ảnh sản phẩm chưa được cung cấp", "muted"));
            }
            Label badge = AuctionTheme.label(statusText(auction.status()), "product-badge");
            badge.getStyleClass().add(switch (auction.status()) {
                case "RUNNING", "PAID" -> "status-running";
                case "OPEN" -> "status-open";
                default -> "status-finished";
            });
            Label title = AuctionTheme.label(auction.itemTitle(), "product-title");
            title.setWrapText(true);
            title.setMinHeight(48);
            Button inspect = new Button("Xem phiên  →");
            inspect.getStyleClass().add("secondary-button");
            inspect.setMaxWidth(Double.MAX_VALUE);
            inspect.setOnAction(event -> {
                auctionTable.getSelectionModel().select(auction);
                showSelectedAuction();
            });
            VBox details = new VBox(10, badge, title,
                    AuctionTheme.label("Giá hiện tại", "muted"),
                    AuctionTheme.label(currencyFormat.format(auction.currentHighestBid()), "product-price"),
                    AuctionTheme.label(auction.auctionId() + (previewMode ? " · Minh họa mẫu" : ""), "form-hint"), inspect);
            details.getStyleClass().add("product-details");
            VBox card = new VBox(artwork, details);
            card.prefWidthProperty().bind(productGrid.prefTileWidthProperty());
            card.maxWidthProperty().bind(productGrid.prefTileWidthProperty());
            card.getStyleClass().add("product-card");
            productGrid.getChildren().add(card);
        }
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
        emptyMessage.setText(auctions.isEmpty()
                ? "Chưa có phiên đấu giá. Bấm Tải danh sách để cập nhật."
                : "Không có phiên phù hợp. Thử từ khóa hoặc trạng thái khác.");
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
        emptyMessage.setText("Đang tải các phiên đấu giá…");
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
            emptyMessage.setText("Không tải được dữ liệu. Bấm Tải danh sách để thử lại.");
            setLoading(false);
        });
        networkExecutor.execute(loadTask);
    }

    private void setLoading(boolean value) {
        loading = value;
        reloadButton.setDisable(value);
        reloadButton.setText(value ? "Đang tải…" : "Tải danh sách");
        createAuctionButton.setDisable(value);
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

    private void showCreateAuctionDialog() {
        if (session == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Vui lòng đăng nhập để đăng bán sản phẩm.");
            AuctionTheme.apply(alert.getDialogPane());
            alert.initOwner(view.getScene().getWindow());
            alert.showAndWait();
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Đăng bán sản phẩm mới");
        dialog.setHeaderText("Nhập thông tin vật phẩm để mở phiên đấu giá (Bạn sẽ là Seller của phiên này)");
        dialog.initOwner(view.getScene().getWindow());
        AuctionTheme.apply(dialog.getDialogPane());

        ButtonType submitType = new ButtonType("Tạo phiên đấu giá", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(submitType, ButtonType.CANCEL);

        TextField titleField = new TextField();
        titleField.setPromptText("Ví dụ: MacBook Pro M3");

        ComboBox<String> categoryBox = new ComboBox<>();
        categoryBox.getItems().addAll("ELECTRONICS", "ART", "VEHICLE");
        categoryBox.setValue("ELECTRONICS");
        categoryBox.setMaxWidth(Double.MAX_VALUE);

        TextArea descField = new TextArea();
        descField.setPromptText("Mô tả tình trạng, phụ kiện đi kèm...");
        descField.setPrefRowCount(3);

        TextField priceField = new TextField();
        priceField.setPromptText("Ví dụ: 12,000 VNĐ");
        attachVndFormatter(priceField);

        TextField incrementField = new TextField("500,000 VNĐ");
        attachVndFormatter(incrementField);

        TextField durationField = new TextField("60");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16, 16, 8, 16));
        grid.addRow(0, new Label("Tên sản phẩm:"), titleField);
        grid.addRow(1, new Label("Danh mục:"), categoryBox);
        grid.addRow(2, new Label("Mô tả:"), descField);
        grid.addRow(3, new Label("Giá khởi điểm (VNĐ):"), priceField);
        grid.addRow(4, new Label("Bước giá tối thiểu (VNĐ):"), incrementField);
        grid.addRow(5, new Label("Thời lượng (phút):"), durationField);
        GridPane.setHgrow(titleField, Priority.ALWAYS);

        dialog.getDialogPane().setContent(grid);

        dialog.showAndWait().ifPresent(result -> {
            if (result != submitType) {
                return;
            }
            try {
                String title = titleField.getText().trim();
                String desc = descField.getText().trim();
                String category = categoryBox.getValue();
                double startingPrice = parseVndCurrency(priceField.getText());
                double minIncrement = parseVndCurrency(incrementField.getText());
                int duration = Integer.parseInt(durationField.getText().trim());

                if (title.isEmpty() || startingPrice <= 0 || minIncrement <= 0 || duration <= 0) {
                    throw new IllegalArgumentException("Thông tin sản phẩm hoặc giá không hợp lệ.");
                }

                setLoading(true);
                connectionLabel.setText("Đang tạo phiên đấu giá mới…");
                Task<Void> createTask = new Task<>() {
                    @Override
                    protected Void call() throws Exception {
                        auctionService.createAuction(session.client(), title, desc, category,
                                startingPrice, minIncrement, duration);
                        return null;
                    }
                };
                createTask.setOnSucceeded(e -> {
                    setLoading(false);
                    connectionLabel.setText("Đã tạo phiên đấu giá thành công: " + title);
                    loadAuctions();
                });
                createTask.setOnFailed(e -> {
                    setLoading(false);
                    connectionLabel.setText("Tạo phiên thất bại: " + createTask.getException().getMessage());
                });
                networkExecutor.execute(createTask);
            } catch (Exception ex) {
                Alert error = new Alert(Alert.AlertType.ERROR,
                        "Vui lòng nhập đầy đủ tên sản phẩm và các giá trị số hợp lệ (> 0).");
                AuctionTheme.apply(error.getDialogPane());
                error.initOwner(view.getScene().getWindow());
                error.showAndWait();
            }
        });
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

    /** Tự động định dạng số nguyên VNĐ khi nhập (ví dụ: 12000 -> 12,000 VNĐ). */
    private void attachVndFormatter(TextField field) {
        final boolean[] updating = {false};
        field.textProperty().addListener((obs, oldText, newText) -> {
            if (updating[0] || newText == null) {
                return;
            }
            updating[0] = true;
            try {
                String working = newText;
                // Nếu người dùng bấm Backspace xóa vào phần đuôi " VNĐ" thì xóa luôn chữ số cuối
                if (oldText != null && oldText.endsWith(" VNĐ")
                        && working.length() < oldText.length()
                        && working.replaceAll("[^0-9]", "").equals(oldText.replaceAll("[^0-9]", ""))) {
                    String oldDigits = oldText.replaceAll("[^0-9]", "");
                    working = oldDigits.isEmpty() ? "" : oldDigits.substring(0, oldDigits.length() - 1);
                }

                String digits = working.replaceAll("[^0-9]", "");
                if (digits.isEmpty()) {
                    field.setText("");
                } else {
                    long value = Long.parseLong(digits);
                    String formatted = String.format(Locale.US, "%,d VNĐ", value);
                    field.setText(formatted);
                    Platform.runLater(() -> field.positionCaret(formatted.length() - 4));
                }
            } catch (NumberFormatException ignored) {
                field.setText(oldText == null ? "" : oldText);
            } finally {
                updating[0] = false;
            }
        });
    }

    /** Lọc bỏ dấu phẩy và chữ "VNĐ" trước khi chuyển sang double. */
    private double parseVndCurrency(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Giá trị trống");
        }
        String cleaned = text.replaceAll("[^0-9.]", "");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Giá trị không hợp lệ");
        }
        return Double.parseDouble(cleaned);
    }
}