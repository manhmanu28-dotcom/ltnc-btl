package com.auction.client.controller;

import com.auction.client.model.AuctionSummary;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import com.auction.client.model.AuthenticatedSession;
import com.auction.client.service.AuctionListService;
import com.auction.client.service.SkinCatalog;
import com.auction.client.ui.SkinPicker;
import com.auction.client.ui.SkinPromoBanner;
import com.auction.client.ui.AuctionTheme;
import com.auction.server.network.ServerEndpoint;
import com.auction.server.network.protocol.ActionType;
import com.auction.server.network.protocol.SocketResponse;
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
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
    private final Button copyInviteButton = new Button("Sao chép địa chỉ mời");
    private final TextField hostField = new TextField("127.0.0.1");
    private final TextField portField = new TextField("8888");
    private TitledPane connectionSettings;
    private final Label connectionLabel = new Label("Nhập địa chỉ server rồi bấm Tải danh sách.");
    private final AuctionListService auctionService = new AuctionListService();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Auction-List-Loader");
        thread.setDaemon(true);
        return thread;
    });
    private Task<List<AuctionSummary>> loadTask;
    private Consumer<SocketResponse> realtimeListener;
    private boolean loading;
    private boolean closed;
    private boolean previewMode;
    private final TilePane productGrid = new TilePane();
    private final ScrollPane products = new ScrollPane();
    private final SkinPromoBanner promoBanner = new SkinPromoBanner();
    private final Label emptyMessage = AuctionTheme.label("Chưa có phiên đấu giá. Bấm Tải danh sách để cập nhật.", "muted");
    private AuthenticatedSession session;
    private javafx.stage.Stage roomStage;
    private AuctionRoomController roomController;
    private final DecimalFormat currencyFormat =
            new DecimalFormat("#,##0.## 'VNĐ'", DecimalFormatSymbols.getInstance(Locale.US));

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
        if (connectionSettings != null) {
            connectionSettings.setText("Cài đặt kết nối · " + session.host() + ":" + session.port());
        }
        registerRealtimeUpdates();
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
        controller.createAuctionButton.setText("+ Xem thử đăng bán skin");
        controller.copyInviteButton.setDisable(true);
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
        copyInviteButton.getStyleClass().add("secondary-button");
        copyInviteButton.setOnAction(event -> copyServerInvite());
        hostField.setPrefColumnCount(14);
        portField.setPrefColumnCount(5);
        HBox connectionBar = new HBox(10, new Label("Server:"), hostField,
                new Label("Cổng:"), portField, copyInviteButton);
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
        connectionSettings = new TitledPane("Cài đặt kết nối", connectionBar);
        connectionSettings.setExpanded(false);
        connectionSettings.getStyleClass().add("connection-options");
        VBox header = new VBox(14, AuctionTheme.label("KHÁM PHÁ / PHIÊN ĐẤU GIÁ", "eyebrow"), heading, subtitle, statistics, filters, connectionSettings, connectionLabel);

        configureTable();
        productGrid.setHgap(20);
        productGrid.setVgap(20);
        productGrid.setPrefTileWidth(300);
        productGrid.setTileAlignment(Pos.TOP_LEFT);
        productGrid.setPadding(new Insets(2, 4, 16, 2));
        VBox marketContent = new VBox(24, promoBanner, header, productGrid);
        marketContent.setFillWidth(true);
        products.setContent(marketContent);
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
            var catalogSkin = SkinCatalog.findByTitle(auction.itemTitle());
            if (catalogSkin.isPresent()) {
                artwork.getChildren().add(SkinPicker.image(catalogSkin.get(), 260, 160));
            } else if (previewMode) {
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
        String host;
        int port;
        try {
            ServerEndpoint endpoint = ServerEndpoint.fromInputs(hostField.getText(), portField.getText());
            host = endpoint.host();
            port = endpoint.port();
            if (hostField.isEditable()) {
                hostField.setText(host);
                portField.setText(String.valueOf(port));
            }
        } catch (IllegalArgumentException error) {
            connectionLabel.setText(error.getMessage());
            return;
        }

        setLoading(true);
        auctionTable.setPlaceholder(new Label("Đang tải danh sách…"));
        if (auctions.isEmpty()) emptyMessage.setText("Đang tải các phiên đấu giá…");
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
            double scrollPosition = captureMarketplaceScroll();
            AuctionSummary selection = auctionTable.getSelectionModel().getSelectedItem();
            auctions.setAll(loadTask.getValue());
            if (selection != null) {
                auctions.stream().filter(a -> a.auctionId().equals(selection.auctionId())).findFirst()
                        .ifPresent(a -> auctionTable.getSelectionModel().select(a));
            }
            updateSummaryCounters();
            auctionTable.sort();
            connectionLabel.setText("Đã tải " + loadTask.getValue().size()
                    + " phiên từ " + host + ":" + port + ".");
            setLoading(false);
            updateEmptyState();
            restoreMarketplaceScroll(scrollPosition);
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

    private void registerRealtimeUpdates() {
        if (session == null || session.client() == null) {
            return;
        }
        realtimeListener = response -> {
            if (closed || response == null || response.getAction() == null) {
                return;
            }
            ActionType action = response.getAction();
            if (action != ActionType.STATUS_UPDATE
                    && action != ActionType.BID_UPDATE
                    && action != ActionType.NOTIFY_UPDATE) {
                return;
            }
            Object auctionObj = response.getData().get("auction");
            if (!(auctionObj instanceof Map<?, ?> auctionMap)) {
                return;
            }
            AuctionSummary updated = AuctionListService.parseAuctionSummary(auctionMap);
            if (updated == null) {
                return;
            }
            String eventType = String.valueOf(response.getData().get("eventType"));
            String serverMessage = response.getMessage();
            Platform.runLater(() -> {
                if (closed) {
                    return;
                }
                upsertAuction(updated);
                if ("AUCTION_CREATED".equals(eventType)) {
                    connectionLabel.setText("Cập nhật trực tiếp: " + (serverMessage != null ? serverMessage : updated.itemTitle()));
                } else if (action == ActionType.BID_UPDATE && serverMessage != null) {
                    connectionLabel.setText("Cập nhật giá trực tiếp: " + serverMessage);
                }
            });
        };
        session.client().addRealtimeListener(realtimeListener);
    }

    private void upsertAuction(AuctionSummary updated) {
        double scrollPosition = captureMarketplaceScroll();
        AuctionSummary selection = auctionTable.getSelectionModel().getSelectedItem();
        int existingIndex = -1;
        for (int i = 0; i < auctions.size(); i++) {
            if (auctions.get(i).auctionId().equals(updated.auctionId())) {
                existingIndex = i;
                break;
            }
        }
        if (existingIndex >= 0) {
            auctions.set(existingIndex, updated);
        } else {
            auctions.add(0, updated);
        }
        updateSummaryCounters();
        updateEmptyState();
        if (selection != null) {
            auctions.stream().filter(a -> a.auctionId().equals(selection.auctionId())).findFirst()
                    .ifPresent(a -> auctionTable.getSelectionModel().select(a));
        }
        restoreMarketplaceScroll(scrollPosition);
    }

    private double captureMarketplaceScroll() {
        double position = products.getVvalue();
        // Move focus off a card that is about to be replaced during a refresh or push.
        javafx.scene.Node focused = view.getScene() == null ? null : view.getScene().getFocusOwner();
        for (javafx.scene.Node node = focused; node != null; node = node.getParent()) {
            if (node == productGrid) {
                products.requestFocus();
                break;
            }
        }
        return position;
    }

    private void restoreMarketplaceScroll(double position) {
        view.applyCss();
        view.layout();
        products.setVvalue(position);
    }

    private void updateSummaryCounters() {
        totalCount.setText(String.valueOf(auctions.size()));
        runningCount.setText(String.valueOf(auctions.stream().filter(a -> a.status().equals("RUNNING")).count()));
        openCount.setText(String.valueOf(auctions.stream().filter(a -> a.status().equals("OPEN")).count()));
    }

    private void copyServerInvite() {
        try {
            ServerEndpoint endpoint = ServerEndpoint.fromInputs(hostField.getText(), portField.getText());
            ClipboardContent content = new ClipboardContent();
            content.putString(endpoint.toAddress());
            Clipboard.getSystemClipboard().setContent(content);
            if (endpoint.isLoopback()) {
                connectionLabel.setText("Đã sao chép " + endpoint.toAddress()
                        + " (địa chỉ nội bộ). Để máy khác cùng vào, hãy chạy Server và gửi IP LAN hoặc địa chỉ -Public.");
            } else {
                connectionLabel.setText("Đã sao chép địa chỉ mời: " + endpoint.toAddress()
                        + " — gửi cho bạn bè dán vào ô Server để cùng tham gia!");
            }
        } catch (IllegalArgumentException error) {
            connectionLabel.setText(error.getMessage());
        }
    }

    @Override
    public void close() {
        closed = true;
        if (roomStage != null) roomStage.close();
        if (roomController != null) roomController.close();
        if (session != null && session.client() != null && realtimeListener != null) {
            session.client().removeRealtimeListener(realtimeListener);
        }
        promoBanner.close();
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

        if (session != null && !previewMode) {
            if (roomStage != null) {
                roomStage.toFront();
                roomStage.requestFocus();
                return;
            }
            javafx.stage.Stage stage = new javafx.stage.Stage();
            stage.initStyle(javafx.stage.StageStyle.UNDECORATED);
            stage.initOwner(view.getScene().getWindow());
            roomController = new AuctionRoomController(session, selected.auctionId(), stage::close);
            roomStage = stage;
            stage.setTitle("BIDION · " + selected.itemTitle());
            stage.getIcons().setAll(AuctionTheme.appIcon());
            stage.setScene(new javafx.scene.Scene(roomController.getView(), 1200, 850));
            stage.setMinWidth(720);
            stage.setMinHeight(520);
            stage.setOnHidden(event -> {
                roomController.close();
                roomController = null;
                roomStage = null;
                if (!closed) loadAuctions();
            });
            stage.show();
            roomController.playEntrance();
            return;
        }

        Alert preview = new Alert(Alert.AlertType.INFORMATION);
        AuctionTheme.apply(preview.getDialogPane());
        preview.initOwner(view.getScene().getWindow());
        preview.setTitle("Thông tin phiên đấu giá");
        preview.setHeaderText(selected.itemTitle());
        preview.setContentText("Mã phiên: " + selected.auctionId()
                + "\nGiá hiện tại: " + currencyFormat.format(selected.currentHighestBid())
                + "\nTrạng thái: " + statusText(selected.status())
                + "\n\nĐăng nhập vào server để tham gia phòng đấu giá thật.");
        preview.showAndWait();
    }

    private void showCreateAuctionDialog() {
        if (session == null && !previewMode) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "Vui lòng đăng nhập để đăng bán sản phẩm.");
            AuctionTheme.apply(alert.getDialogPane());
            alert.initOwner(view.getScene().getWindow());
            alert.showAndWait();
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(previewMode ? "Xem thử đăng bán skin" : "Đăng bán skin");
        dialog.setHeaderText("Chọn danh mục, chọn skin rồi nhập thông tin đăng bán.");
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefWidth(940);
        dialog.initOwner(view.getScene().getWindow());
        AuctionTheme.apply(dialog.getDialogPane());

        ButtonType submitType = new ButtonType(previewMode ? "Hoàn tất bản xem" : "Tạo phiên đấu giá", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(submitType, ButtonType.CANCEL);

        TextField titleField = new TextField();
        titleField.setId("auction-skin-title");
        titleField.setPromptText("Chọn một skin từ danh mục phía trên");
        titleField.setEditable(false);
        SkinPicker picker = new SkinPicker();
        picker.selectedSkinProperty().addListener((observable, before, skin) ->
                titleField.setText(skin == null ? "" : skin.name()));
        ComboBox<String> wearField = new ComboBox<>();
        wearField.setId("auction-skin-wear");
        wearField.setAccessibleText("Độ hao mòn của skin");
        wearField.setPromptText("Chọn độ hao mòn");
        wearField.setMaxWidth(Double.MAX_VALUE);
        wearField.getItems().addAll(
                "Factory New (FN) · Mới xuất xưởng · 0.00 – 0.07",
                "Minimal Wear (MW) · Hao mòn nhẹ · 0.07 – 0.15",
                "Field-Tested (FT) · Đã qua sử dụng · 0.15 – 0.38",
                "Well-Worn (WW) · Hao mòn nhiều · 0.38 – 0.45",
                "Battle-Scarred (BS) · Hao mòn nặng · 0.45 – 1.00");
        dialog.getDialogPane().lookupButton(submitType).disableProperty().bind(
                picker.selectedSkinProperty().isNull().or(wearField.valueProperty().isNull()));

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
        grid.addRow(1, new Label("Độ hao mòn:"), wearField);
        grid.addRow(2, new Label("Giá khởi điểm (VNĐ):"), priceField);
        grid.addRow(3, new Label("Bước giá tối thiểu (VNĐ):"), incrementField);
        grid.addRow(4, new Label("Thời lượng (phút):"), durationField);
        GridPane.setHgrow(titleField, Priority.ALWAYS);
        GridPane.setHgrow(wearField, Priority.ALWAYS);

        Label source = AuctionTheme.label("Danh mục mẫu gồm " + SkinCatalog.all().size()
                + " skin · Ảnh tham khảo: CSGOSKINS.GG", "form-hint");
        source.setWrapText(true);
        VBox form = new VBox(16, picker, source, grid);
        ScrollPane content = new ScrollPane(form);
        content.setFitToWidth(true);
        content.setPrefViewportHeight(590);
        content.getStyleClass().add("auth-scroll");
        dialog.getDialogPane().setContent(content);

        dialog.showAndWait().ifPresent(result -> {
            if (result != submitType) {
                return;
            }
            if (previewMode) {
                connectionLabel.setText("Đã chọn " + picker.getSelectedSkin().name()
                        + " · " + wearField.getValue() + " trong bản xem. Đăng nhập để đăng bán thật.");
                return;
            }
            try {
                String title = titleField.getText().trim();
                var skin = picker.getSelectedSkin();
                String desc = "Skin CS2 · " + skin.group() + " / " + skin.weapon()
                        + "\nĐộ hao mòn: " + wearField.getValue();
                // Existing server supports ART, ELECTRONICS and VEHICLE. Keep the
                // skin subgroup in the description until a dedicated server type is added.
                String category = "ART";
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
    /** Tự động định dạng số tiền VNĐ khi nhập, hỗ trợ phần thập phân (ví dụ: 1200.12 -> 1,200.12 VNĐ). */
    private void attachVndFormatter(TextField field) {
        final boolean[] updating = {false};
        field.textProperty().addListener((obs, oldText, newText) -> {
            if (updating[0] || newText == null) {
                return;
            }
            updating[0] = true;
            try {
                String working = newText;
                // Nếu người dùng bấm Backspace xóa vào phần đuôi " VNĐ" thì xóa luôn ký tự số/dấu chấm cuối
                if (oldText != null && oldText.endsWith(" VNĐ")
                        && working.length() < oldText.length()
                        && working.replaceAll("[^0-9.]", "").equals(oldText.replaceAll("[^0-9.]", ""))) {
                    String rawOld = oldText.replaceAll("[^0-9.]", "");
                    working = rawOld.isEmpty() ? "" : rawOld.substring(0, rawOld.length() - 1);
                }

                // Chỉ giữ lại chữ số và dấu chấm thập phân đầu tiên
                StringBuilder cleaned = new StringBuilder();
                boolean hasDot = false;
                for (char c : working.toCharArray()) {
                    if (c >= '0' && c <= '9') {
                        cleaned.append(c);
                    } else if (c == '.' && !hasDot) {
                        cleaned.append(c);
                        hasDot = true;
                    }
                }

                String raw = cleaned.toString();
                if (raw.isEmpty()) {
                    field.setText("");
                } else {
                    String intPart;
                    String decPart = "";
                    int dotIndex = raw.indexOf('.');
                    if (dotIndex >= 0) {
                        intPart = raw.substring(0, dotIndex);
                        decPart = raw.substring(dotIndex + 1);
                        // Giới hạn tối đa 2 chữ số thập phân
                        if (decPart.length() > 2) {
                            decPart = decPart.substring(0, 2);
                        }
                    } else {
                        intPart = raw;
                    }

                    long intValue = intPart.isEmpty() ? 0 : Long.parseLong(intPart);
                    String formattedInt = String.format(Locale.US, "%,d", intValue);
                    String formatted = (dotIndex >= 0)
                            ? formattedInt + "." + decPart + " VNĐ"
                            : formattedInt + " VNĐ";

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
