package com.auction.client.ui;

import com.auction.server.network.AuctionSocketClient;
import com.auction.server.network.GlobalServerRegistry;
import com.auction.server.network.HostedServerManager;
import com.auction.server.network.LanDiscoveryService;
import com.auction.server.network.ServerDirectoryEntry;
import com.auction.server.network.ServerEndpoint;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ServerBrowserDialog: Cửa sổ "Tham gia Server" hiển thị TẤT CẢ các Server đang hoạt động
 * do chính bạn hoặc người khác tạo ra (cả cùng mạng Wi-Fi/LAN lẫn khác mạng Wi-Fi/Internet).
 */
public final class ServerBrowserDialog {

    public record JoinSelection(ServerEndpoint endpoint, String serverName, String statusSummary) {
    }

    private ServerBrowserDialog() {
    }

    public static Optional<JoinSelection> showAndWait(Window owner) {
        Dialog<JoinSelection> dialog = new Dialog<>();
        dialog.setTitle("Tham gia Server đấu giá");
        dialog.setHeaderText("Danh sách tất cả các Server đang hoạt động (Cùng Wi-Fi & Khác Wi-Fi / Internet)");
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefWidth(760);
        dialog.getDialogPane().setPrefHeight(500);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        AuctionTheme.apply(dialog.getDialogPane());

        ButtonType closeType = new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(closeType);

        ObservableList<ServerDirectoryEntry> serverItems = FXCollections.observableArrayList();
        TableView<ServerDirectoryEntry> table = buildServerTable(serverItems);

        Label statusLabel = AuctionTheme.label("Đang tìm tất cả các Server đang hoạt động…", "muted");
        statusLabel.setWrapText(true);

        Button refreshButton = new Button("Làm mới danh sách");
        refreshButton.getStyleClass().add("secondary-button");

        Button joinButton = new Button("Tham gia Server đã chọn");
        joinButton.getStyleClass().add("primary-button");
        joinButton.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topActions = new HBox(10, statusLabel, spacer, refreshButton, joinButton);
        topActions.setAlignment(Pos.CENTER_LEFT);

        // Thanh nhập thủ công dự phòng nếu có địa chỉ host:port riêng
        TextField manualAddress = new TextField();
        manualAddress.setPromptText("Hoặc dán địa chỉ thủ công (vd: 192.168.0.106:8888 hoặc tcp://...pinggy.link:43210)");
        HBox.setHgrow(manualAddress, Priority.ALWAYS);
        Button manualJoinButton = new Button("Kết nối thủ công");
        manualJoinButton.getStyleClass().add("secondary-button");
        HBox manualBar = new HBox(8, manualAddress, manualJoinButton);
        manualBar.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12, topActions, table, AuctionTheme.label("Kết nối trực tiếp bằng địa chỉ (tùy chọn)", "form-hint"), manualBar);
        content.setPadding(new Insets(12, 8, 8, 8));
        VBox.setVgrow(table, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);

        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "Auction-Server-Browser");
            t.setDaemon(true);
            return t;
        });

        final boolean[] busy = {false};

        Runnable updateJoinState = () -> joinButton.setDisable(
                busy[0] || table.getSelectionModel().getSelectedItem() == null);
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> updateJoinState.run());

        Runnable scanServers = () -> {
            if (busy[0]) {
                return;
            }
            busy[0] = true;
            refreshButton.setDisable(true);
            manualJoinButton.setDisable(true);
            updateJoinState.run();
            statusLabel.getStyleClass().removeAll("message-error", "message-success");
            statusLabel.setText("Đang quét Server cùng Wi-Fi và danh bạ Internet…");
            table.setPlaceholder(new Label("Đang tìm tất cả các Server đang hoạt động…"));

            Task<List<ServerDirectoryEntry>> scanTask = new Task<>() {
                @Override
                protected List<ServerDirectoryEntry> call() {
                    ServerDirectoryEntry localHosted = HostedServerManager.getInstance().getHostedDirectoryEntry();
                    CompletableFuture<List<LanDiscoveryService.DiscoveredServer>> lanFuture =
                            CompletableFuture.supplyAsync(() -> LanDiscoveryService.discoverServers(1200), executor);
                    CompletableFuture<List<ServerDirectoryEntry>> globalFuture =
                            CompletableFuture.supplyAsync(() -> new GlobalServerRegistry().fetchOnlineServers(), executor);

                    List<LanDiscoveryService.DiscoveredServer> lanServers = lanFuture.join();
                    List<ServerDirectoryEntry> globalServers = globalFuture.join();
                    return GlobalServerRegistry.mergeServers(localHosted, lanServers, globalServers);
                }
            };
            scanTask.setOnSucceeded(event -> {
                busy[0] = false;
                refreshButton.setDisable(false);
                manualJoinButton.setDisable(false);
                List<ServerDirectoryEntry> found = scanTask.getValue();
                serverItems.setAll(found);
                if (found.isEmpty()) {
                    table.setPlaceholder(new Label("Chưa thấy Server nào đang mở. Hãy bấm 'Tạo Server' hoặc nhập địa chỉ thủ công."));
                    statusLabel.setText("Không tìm thấy Server nào đang mở.");
                } else {
                    table.getSelectionModel().selectFirst();
                    statusLabel.getStyleClass().add("message-success");
                    statusLabel.setText("Tìm thấy " + found.size() + " Server đang hoạt động. Chọn 1 Server để tham gia.");
                }
                updateJoinState.run();
            });
            scanTask.setOnFailed(event -> {
                busy[0] = false;
                refreshButton.setDisable(false);
                manualJoinButton.setDisable(false);
                table.setPlaceholder(new Label("Không thể quét danh sách Server. Hãy bấm Làm mới để thử lại."));
                statusLabel.getStyleClass().add("message-error");
                statusLabel.setText("Lỗi khi quét danh sách Server.");
                updateJoinState.run();
            });
            executor.execute(scanTask);
        };

        java.util.function.Consumer<ServerDirectoryEntry> connectSelected = selected -> {
            if (busy[0] || selected == null) {
                return;
            }
            busy[0] = true;
            refreshButton.setDisable(true);
            manualJoinButton.setDisable(true);
            updateJoinState.run();
            statusLabel.getStyleClass().removeAll("message-error", "message-success");
            statusLabel.setText("Đang kết nối tới " + selected.serverName() + "…");

            Task<AuctionSocketClient.ServerProbeResult> probeTask = new Task<>() {
                @Override
                protected AuctionSocketClient.ServerProbeResult call() throws Exception {
                    return AuctionSocketClient.probeBestEndpoint(selected, 5000);
                }
            };
            probeTask.setOnSucceeded(event -> {
                busy[0] = false;
                AuctionSocketClient.ServerProbeResult probe = probeTask.getValue();
                String summary = String.format("✓ Đã kết nối %s (%s · %d ms · %d phiên · %d online)",
                        selected.serverName(),
                        probe.endpoint().toAddress(),
                        probe.latencyMillis(),
                        probe.auctionCount(),
                        probe.connectedClients());
                dialog.setResult(new JoinSelection(probe.endpoint(), selected.serverName(), summary));
                dialog.close();
            });
            probeTask.setOnFailed(event -> {
                busy[0] = false;
                refreshButton.setDisable(false);
                manualJoinButton.setDisable(false);
                updateJoinState.run();
                String err = probeTask.getException() != null
                        ? probeTask.getException().getMessage()
                        : "Không phản hồi";
                statusLabel.getStyleClass().add("message-error");
                statusLabel.setText("Không thể kết nối tới " + selected.serverName() + " (" + err
                        + "). Kiểm tra Server còn mở hoặc tường lửa Windows.");
            });
            executor.execute(probeTask);
        };

        Runnable connectManual = () -> {
            if (busy[0]) {
                return;
            }
            ServerEndpoint endpoint;
            try {
                endpoint = ServerEndpoint.parse(manualAddress.getText(), 8888);
            } catch (IllegalArgumentException error) {
                statusLabel.getStyleClass().removeAll("message-success");
                statusLabel.getStyleClass().add("message-error");
                statusLabel.setText(error.getMessage());
                return;
            }
            busy[0] = true;
            refreshButton.setDisable(true);
            manualJoinButton.setDisable(true);
            updateJoinState.run();
            statusLabel.getStyleClass().removeAll("message-error", "message-success");
            statusLabel.setText("Đang kiểm tra kết nối tới " + endpoint.toAddress() + "…");

            Task<AuctionSocketClient.ServerProbeResult> probeTask = new Task<>() {
                @Override
                protected AuctionSocketClient.ServerProbeResult call() throws Exception {
                    return AuctionSocketClient.probe(endpoint, 5000);
                }
            };
            probeTask.setOnSucceeded(event -> {
                busy[0] = false;
                AuctionSocketClient.ServerProbeResult probe = probeTask.getValue();
                String summary = String.format("✓ Đã kết nối %s (%d ms · %d phiên · %d online)",
                        probe.endpoint().toAddress(),
                        probe.latencyMillis(),
                        probe.auctionCount(),
                        probe.connectedClients());
                dialog.setResult(new JoinSelection(probe.endpoint(), probe.endpoint().toAddress(), summary));
                dialog.close();
            });
            probeTask.setOnFailed(event -> {
                busy[0] = false;
                refreshButton.setDisable(false);
                manualJoinButton.setDisable(false);
                updateJoinState.run();
                String err = probeTask.getException() != null ? probeTask.getException().getMessage() : "Không phản hồi";
                statusLabel.getStyleClass().add("message-error");
                statusLabel.setText("Không kết nối được " + endpoint.toAddress() + " (" + err + ").");
            });
            executor.execute(probeTask);
        };

        refreshButton.setOnAction(event -> scanServers.run());
        joinButton.setOnAction(event -> connectSelected.accept(table.getSelectionModel().getSelectedItem()));
        manualJoinButton.setOnAction(event -> connectManual.run());
        manualAddress.setOnAction(event -> connectManual.run());

        table.setRowFactory(tv -> {
            TableRow<ServerDirectoryEntry> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    connectSelected.accept(row.getItem());
                }
            });
            return row;
        });

        dialog.setResultConverter(buttonType -> null);
        dialog.setOnShowing(event -> scanServers.run());
        dialog.setOnHidden(event -> executor.shutdownNow());

        return dialog.showAndWait();
    }

    private static TableView<ServerDirectoryEntry> buildServerTable(ObservableList<ServerDirectoryEntry> items) {
        TableView<ServerDirectoryEntry> table = new TableView<>(items);
        table.getStyleClass().add("market-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ServerDirectoryEntry, String> nameCol = new TableColumn<>("Tên Server");
        nameCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().serverName()));
        nameCol.setPrefWidth(210);

        TableColumn<ServerDirectoryEntry, String> scopeCol = new TableColumn<>("Phạm vi mạng");
        scopeCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().sourceBadge()));
        scopeCol.setPrefWidth(190);

        TableColumn<ServerDirectoryEntry, String> addressCol = new TableColumn<>("Địa chỉ");
        addressCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().displayAddress()));
        addressCol.setPrefWidth(210);

        TableColumn<ServerDirectoryEntry, String> statsCol = new TableColumn<>("Trạng thái");
        statsCol.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().statsSummary()));
        statsCol.setPrefWidth(130);

        table.getColumns().add(nameCol);
        table.getColumns().add(scopeCol);
        table.getColumns().add(addressCol);
        table.getColumns().add(statsCol);
        return table;
    }
}
