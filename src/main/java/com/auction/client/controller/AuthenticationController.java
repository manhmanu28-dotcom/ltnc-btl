package com.auction.client.controller;

import com.auction.client.model.AuthenticatedSession;
import com.auction.client.service.AuthenticationClientService;
import com.auction.client.service.ConnectionPreferences;
import com.auction.client.ui.AuctionTheme;
import com.auction.client.ui.PasswordInput;
import com.auction.client.ui.ServerBrowserDialog;
import com.auction.client.ui.SkinShowcase;
import com.auction.server.network.AuctionServer;
import com.auction.server.network.HostedServerManager;
import com.auction.server.network.NetworkAddressUtil;
import com.auction.server.network.ServerDirectoryEntry;
import com.auction.server.network.ServerEndpoint;
import com.auction.server.network.protocol.SocketResponse;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import static com.auction.client.ui.AuctionTheme.label;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Màn hình đăng ký và đăng nhập qua Socket với 2 nút nổi bật:
 * 1. "Tạo Server": Mở máy chủ mới và tự động chia sẻ cho người dùng cùng Wi-Fi lẫn khác Wi-Fi.
 * 2. "Tham gia Server": Hiển thị toàn bộ các Server đang hoạt động do bạn hoặc người khác tạo ra.
 */
public class AuthenticationController implements AutoCloseable {
    private final VBox view = new VBox(16);
    private final ScrollPane scrollPane = new ScrollPane(view);
    private final Button createServerButton = new Button("+ Tạo Server");
    private final Button joinServerButton = new Button("Tham gia Server");
    private final Button stopServerButton = new Button("Dừng Server");
    private final Label activeServerLabel = label("", "field-caption");
    private final Label connectionStatus = label("", "form-hint");
    private final TextField loginName = new TextField();
    private final PasswordField loginPassword = new PasswordField();
    private final TextField registerName = new TextField();
    private final TextField email = new TextField();
    private final PasswordField registerPassword = new PasswordField();
    private final PasswordField confirmation = new PasswordField();
    private final PasswordInput loginPasswordInput = new PasswordInput(loginPassword);
    private final PasswordInput registerPasswordInput = new PasswordInput(registerPassword);
    private final PasswordInput confirmationInput = new PasswordInput(confirmation);
    private final Label message = new Label();
    private final VBox formHost = new VBox();
    private final VBox serverPanel;
    private final SkinShowcase skinShowcase = new SkinShowcase();
    private Consumer<Boolean> showForm;
    private final AuthenticationClientService service = new AuthenticationClientService();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Auction-Authentication");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<AuthenticatedSession> onLogin;
    private ServerEndpoint selectedEndpoint;
    private String selectedServerName;
    private Task<?> pendingTask;
    private boolean closed;

    public AuthenticationController(Consumer<AuthenticatedSession> onLogin) {
        this(onLogin, null, ConnectionPreferences.loadLastEndpoint());
    }

    public AuthenticationController(Consumer<AuthenticatedSession> onLogin, Runnable onDemo) {
        this(onLogin, onDemo, ConnectionPreferences.loadLastEndpoint());
    }

    public AuthenticationController(Consumer<AuthenticatedSession> onLogin, Runnable onDemo, ServerEndpoint initialEndpoint) {
        this.onLogin = onLogin;
        this.selectedEndpoint = initialEndpoint != null ? initialEndpoint : ConnectionPreferences.DEFAULT_ENDPOINT;
        this.selectedServerName = selectedEndpoint.toAddress();

        Label title = label("Chào mừng trở lại", "card-title");
        Label introduction = label("Chọn hoặc tạo Server đấu giá, sau đó đăng nhập để tham gia.", "muted");
        introduction.setWrapText(true);

        // Khu vực 2 nút chính: Tạo Server & Tham gia Server
        createServerButton.setId("auth-create-server-button");
        createServerButton.getStyleClass().add("primary-button");
        createServerButton.setMaxWidth(Double.MAX_VALUE);
        createServerButton.setOnAction(event -> showCreateServerDialog());

        joinServerButton.setId("auth-join-server-button");
        joinServerButton.getStyleClass().add("secondary-button");
        joinServerButton.setMaxWidth(Double.MAX_VALUE);
        joinServerButton.setOnAction(event -> openServerBrowser());

        stopServerButton.getStyleClass().add("ghost-button");
        stopServerButton.setOnAction(event -> stopHostedServer());
        stopServerButton.setVisible(false);
        stopServerButton.setManaged(false);

        HBox.setHgrow(createServerButton, Priority.ALWAYS);
        HBox.setHgrow(joinServerButton, Priority.ALWAYS);
        HBox serverButtons = new HBox(10, createServerButton, joinServerButton);
        serverButtons.setAlignment(Pos.CENTER_LEFT);

        activeServerLabel.setWrapText(true);
        connectionStatus.setWrapText(true);
        HBox serverHeader = new HBox(8, label("MÁY CHỦ ĐẤU GIÁ", "eyebrow"), new Region(), stopServerButton);
        HBox.setHgrow(serverHeader.getChildren().get(1), Priority.ALWAYS);
        serverHeader.setAlignment(Pos.CENTER_LEFT);

        serverPanel = new VBox(8, serverHeader, serverButtons, activeServerLabel, connectionStatus);
        serverPanel.getStyleClass().add("summary-card");
        refreshServerUi();

        Button login = new Button("Đăng nhập");
        login.setId("auth-login-button");
        login.getStyleClass().add("primary-button");
        login.setMaxWidth(Double.MAX_VALUE);
        login.setOnAction(event -> submit(false));
        loginPassword.setOnAction(event -> submit(false));
        loginName.setPromptText("Nhập tên đăng nhập");
        loginPassword.setPromptText("Nhập mật khẩu của bạn");
        VBox loginForm = new VBox(10, label("Tên đăng nhập", "field-caption"), loginName,
                label("Mật khẩu", "field-caption"), loginPasswordInput, login);
        VBox.setMargin(login, new Insets(10, 0, 0, 0));

        Button register = new Button("Tạo tài khoản");
        register.setId("auth-register-button");
        register.getStyleClass().add("primary-button");
        register.setMaxWidth(Double.MAX_VALUE);
        register.setOnAction(event -> submit(true));
        Label hint = label("Tên gồm 3–32 chữ cái không dấu, số hoặc _. Mật khẩu từ 8–128 ký tự.", "form-hint");
        hint.setWrapText(true);
        registerName.setPromptText("Ví dụ: nguyen_an");
        email.setPromptText("ban@example.com");
        registerPassword.setPromptText("Ít nhất 8 ký tự");
        confirmation.setPromptText("Nhập lại mật khẩu");
        VBox registerForm = new VBox(8, label("Tên đăng nhập", "field-caption"), registerName,
                label("Email", "field-caption"), email, label("Mật khẩu", "field-caption"), registerPasswordInput,
                label("Nhập lại mật khẩu", "field-caption"), confirmationInput, hint, register);
        VBox.setMargin(register, new Insets(8, 0, 0, 0));
        Hyperlink createAccount = new Hyperlink("Tạo tài khoản");
        createAccount.setId("auth-create-account-link");
        createAccount.getStyleClass().add("account-link");
        createAccount.setOnAction(event -> showForm.accept(true));
        HBox registrationPrompt = new HBox(3, label("Bạn chưa có tài khoản?", "muted"), createAccount);
        registrationPrompt.setAlignment(Pos.CENTER);
        VBox.setMargin(registrationPrompt, new Insets(8, 0, 0, 0));
        loginForm.getChildren().add(registrationPrompt);
        if (onDemo != null) {
            Button demoButton = new Button("Khám phá giao diện mẫu →");
            demoButton.getStyleClass().add("secondary-button");
            demoButton.setMaxWidth(Double.MAX_VALUE);
            demoButton.setOnAction(event -> onDemo.run());
            loginForm.getChildren().add(demoButton);
        }

        Hyperlink returnToLogin = new Hyperlink("Đăng nhập");
        returnToLogin.setId("auth-return-login-link");
        returnToLogin.getStyleClass().add("account-link");
        returnToLogin.setOnAction(event -> showForm.accept(false));
        HBox loginPrompt = new HBox(3, label("Bạn đã có tài khoản?", "muted"), returnToLogin);
        loginPrompt.setAlignment(Pos.CENTER);
        registerForm.getChildren().add(loginPrompt);

        showForm = registering -> {
            if (closed || formHost.isDisabled()) {
                return;
            }
            clearPasswords();
            formHost.getChildren().setAll(registering ? registerForm : loginForm);
            title.setText(registering ? "Tạo tài khoản mới" : "Chào mừng trở lại");
            introduction.setText(registering ? "Một tài khoản. Khởi đầu nhiều cơ hội."
                    : "Chọn hoặc tạo Server đấu giá, sau đó đăng nhập để tham gia.");
            message.setText("");
        };
        showForm.accept(false);
        message.setWrapText(true);
        message.getStyleClass().add("message");
        message.setMinHeight(28);
        message.textProperty().addListener((observable, before, text) -> {
            message.getStyleClass().removeAll("message-error", "message-success");
            if (text.startsWith("Đăng ký thành công")) {
                message.getStyleClass().add("message-success");
            } else if (!text.isEmpty() && !text.startsWith("Đang")) {
                message.getStyleClass().add("message-error");
            }
        });
        VBox card = new VBox(14, label("TÀI KHOẢN BIDION", "eyebrow"), title, introduction,
                serverPanel, formHost, message);
        card.getStyleClass().add("auth-card");
        card.setMinWidth(380);
        card.setPrefWidth(440);
        card.setMaxWidth(460);

        Label heroTitle = label("Find your\nnext skin.", "hero-title");
        heroTitle.setWrapText(true);
        heroTitle.setMaxWidth(600);
        Label description = label("Chọn skin bạn thích. Theo dõi phiên và đặt giá.", "hero-description");
        description.setWrapText(true);
        description.setMaxWidth(520);
        VBox hero = new VBox(18, label("BIDION / SKINS", "eyebrow"),
                heroTitle, description, skinShowcase);
        hero.getStyleClass().add("hero-panel");
        hero.setMinWidth(0);
        GridPane body = new GridPane();
        body.setHgap(36);
        body.setVgap(24);
        body.setAlignment(Pos.CENTER);
        body.setMaxWidth(1280);
        GridPane.setHgrow(hero, Priority.ALWAYS);
        GridPane.setFillHeight(card, false);
        GridPane.setValignment(card, VPos.CENTER);
        body.add(hero, 0, 0);
        body.add(card, 1, 0);
        body.widthProperty().addListener((observable, before, width) -> {
            boolean compact = width.doubleValue() < 950;
            GridPane.setColumnIndex(card, compact ? 0 : 1);
            GridPane.setRowIndex(card, compact ? 1 : 0);
            heroTitle.setStyle(compact ? "-fx-font-size: 30px;" : "");
            card.setMaxWidth(compact ? 600 : 460);
        });
        Button signIn = new Button("Đăng nhập");
        signIn.getStyleClass().add("ghost-button");
        signIn.setOnAction(event -> { showForm.accept(false); loginName.requestFocus(); });
        Button signUp = new Button("Tạo tài khoản  →");
        signUp.getStyleClass().add("primary-button");
        signUp.setOnAction(event -> { showForm.accept(true); registerName.requestFocus(); });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox navigation = new HBox(12, AuctionTheme.brand(), spacer, AuctionTheme.themeToggle(), signIn, signUp);
        navigation.setAlignment(Pos.CENTER_LEFT);
        navigation.getStyleClass().add("navbar");
        view.getStyleClass().add("auth-page");
        view.setAlignment(Pos.TOP_CENTER);
        view.getChildren().addAll(navigation, body,
                label("BIDION · Đấu giá trực tuyến", "hero-footer"));
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("auth-scroll");
        AuctionTheme.apply(scrollPane);
    }

    private void refreshServerUi() {
        HostedServerManager.HostedServerInfo hosted = HostedServerManager.getInstance().currentInfo();
        if (hosted != null) {
            ServerDirectoryEntry entry = hosted.entry();
            this.selectedEndpoint = new ServerEndpoint("127.0.0.1", entry.lanPort());
            this.selectedServerName = entry.serverName();
            createServerButton.setText("Server đang chạy");
            createServerButton.setDisable(true);
            stopServerButton.setVisible(true);
            stopServerButton.setManaged(true);

            StringBuilder detail = new StringBuilder("Đang chạy: ")
                    .append(entry.serverName())
                    .append(" · LAN: ").append(entry.lanHost()).append(":").append(entry.lanPort());
            if (entry.hasPublicEndpoint()) {
                detail.append(" · Internet: ").append(entry.publicHost()).append(":").append(entry.publicPort());
            }
            activeServerLabel.setText(detail.toString());

            if (entry.hasPublicEndpoint() && hosted.registryPublished()) {
                setConnectionStatus("✓ Mọi người cùng Wi-Fi hay khác Wi-Fi đều sẽ thấy Server này khi bấm Tham gia Server.", "message-success");
            } else if (hosted.publicTunnelWarning() != null) {
                setConnectionStatus("Đang chạy cho cùng Wi-Fi (chưa mở được đường hầm khác Wi-Fi: "
                        + hosted.publicTunnelWarning() + ").", "message-error");
            } else {
                setConnectionStatus("✓ Server đã sẵn sàng cho người dùng trong cùng mạng Wi-Fi.", "message-success");
            }
            return;
        }

        createServerButton.setText("+ Tạo Server");
        createServerButton.setDisable(false);
        stopServerButton.setVisible(false);
        stopServerButton.setManaged(false);
        activeServerLabel.setText("Đang chọn: " + selectedServerName
                + (selectedServerName.equals(selectedEndpoint.toAddress()) ? "" : " (" + selectedEndpoint.toAddress() + ")"));
        if (connectionStatus.getText() == null || connectionStatus.getText().isBlank()) {
            setConnectionStatus("Bấm Tạo Server để mở phòng mới hoặc Tham gia Server để xem danh sách tất cả Server.", null);
        }
    }

    private void showCreateServerDialog() {
        if (closed || (pendingTask != null && pendingTask.isRunning())) {
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Tạo Server đấu giá mới");
        dialog.setHeaderText("Khởi chạy Server trên máy của bạn để mọi người cùng tham gia.");
        if (scrollPane.getScene() != null) {
            dialog.initOwner(scrollPane.getScene().getWindow());
        }
        AuctionTheme.apply(dialog.getDialogPane());

        ButtonType startType = new ButtonType("Khởi tạo Server", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(startType, ButtonType.CANCEL);

        TextField nameField = new TextField("Server của " + NetworkAddressUtil.resolveLocalHostName());
        nameField.setPromptText("Nhập tên hiển thị cho Server");
        TextField portField = new TextField(String.valueOf(AuctionServer.DEFAULT_PORT));
        portField.setPromptText("Mặc định 8888");
        CheckBox publicCheckBox = new CheckBox("Mở công khai cho người khác mạng Wi-Fi / 4G (Internet)");
        publicCheckBox.setSelected(true);
        publicCheckBox.setWrapText(true);

        Label hint = label("Server sẽ tự động xuất hiện trong mục 'Tham gia Server' của mọi người (cả cùng Wi-Fi lẫn khác Wi-Fi).", "form-hint");
        hint.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(12, 8, 8, 8));
        grid.addRow(0, new Label("Tên Server:"), nameField);
        grid.addRow(1, new Label("Cổng TCP:"), portField);
        GridPane.setHgrow(nameField, Priority.ALWAYS);

        VBox content = new VBox(12, grid, publicCheckBox, hint);
        content.setPrefWidth(460);
        dialog.getDialogPane().setContent(content);

        dialog.showAndWait().ifPresent(result -> {
            if (result != startType) {
                return;
            }
            int requestedPort;
            try {
                requestedPort = Integer.parseInt(portField.getText().trim());
                if (requestedPort < 1 || requestedPort > 65535) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException error) {
                setConnectionStatus("Cổng TCP phải nằm trong khoảng 1–65535.", "message-error");
                return;
            }
            String serverName = nameField.getText().trim();
            boolean enablePublic = publicCheckBox.isSelected();
            startHostedServer(serverName, requestedPort, enablePublic);
        });
    }

    private void startHostedServer(String serverName, int requestedPort, boolean enablePublic) {
        setBusy(true);
        setConnectionStatus(enablePublic
                ? "Đang khởi tạo Server & mở đường hầm công khai cho khác Wi-Fi (khoảng 3–8 giây)…"
                : "Đang khởi tạo Server đấu giá…", null);

        Task<HostedServerManager.HostedServerInfo> task = new Task<>() {
            @Override
            protected HostedServerManager.HostedServerInfo call() throws Exception {
                return HostedServerManager.getInstance().startServer(serverName, requestedPort, enablePublic);
            }
        };
        pendingTask = task;
        task.setOnSucceeded(event -> {
            if (closed) {
                return;
            }
            setBusy(false);
            pendingTask = null;
            HostedServerManager.HostedServerInfo info = task.getValue();
            ConnectionPreferences.save(new ServerEndpoint("127.0.0.1", info.entry().lanPort()));
            refreshServerUi();
            loginName.requestFocus();
        });
        task.setOnFailed(event -> {
            if (closed) {
                return;
            }
            setBusy(false);
            pendingTask = null;
            String err = task.getException() != null ? task.getException().getMessage() : "Không rõ nguyên nhân";
            setConnectionStatus("Không thể tạo Server: " + err, "message-error");
            refreshServerUi();
        });
        executor.execute(task);
    }

    private void stopHostedServer() {
        if (closed || (pendingTask != null && pendingTask.isRunning())) {
            return;
        }
        setBusy(true);
        setConnectionStatus("Đang dừng Server…", null);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                HostedServerManager.getInstance().stopServer();
                return null;
            }
        };
        pendingTask = task;
        task.setOnSucceeded(event -> {
            if (closed) {
                return;
            }
            setBusy(false);
            pendingTask = null;
            setConnectionStatus("Đã dừng Server của bạn.", null);
            refreshServerUi();
        });
        task.setOnFailed(event -> {
            if (closed) {
                return;
            }
            setBusy(false);
            pendingTask = null;
            refreshServerUi();
        });
        executor.execute(task);
    }

    private void openServerBrowser() {
        if (closed || (pendingTask != null && pendingTask.isRunning())) {
            return;
        }
        ServerBrowserDialog.showAndWait(scrollPane.getScene() != null ? scrollPane.getScene().getWindow() : null)
                .ifPresent(selection -> {
                    this.selectedEndpoint = selection.endpoint();
                    this.selectedServerName = selection.serverName();
                    ConnectionPreferences.save(selection.endpoint());
                    activeServerLabel.setText("Đang chọn: " + selection.serverName()
                            + (selection.serverName().equals(selection.endpoint().toAddress())
                                ? ""
                                : " (" + selection.endpoint().toAddress() + ")"));
                    setConnectionStatus(selection.statusSummary(), "message-success");
                    message.setText("");
                    loginName.requestFocus();
                });
    }

    private void setConnectionStatus(String text, String styleClass) {
        connectionStatus.getStyleClass().removeAll("message-error", "message-success");
        if (styleClass != null) {
            connectionStatus.getStyleClass().add(styleClass);
        }
        connectionStatus.setText(text);
    }

    public Parent getView() {
        return scrollPane;
    }

    private void submit(boolean register) {
        if (closed || (pendingTask != null && pendingTask.isRunning())) {
            return;
        }
        if (selectedEndpoint == null) {
            message.setText("Vui lòng chọn hoặc tạo Server trước khi đăng nhập.");
            return;
        }
        String serverHost = selectedEndpoint.host();
        int serverPort = selectedEndpoint.port();

        String username = (register ? registerName : loginName).getText().trim();
        String password = (register ? registerPassword : loginPassword).getText();
        String emailAddress = email.getText().trim();
        if (username.isEmpty() || password.isEmpty()) {
            message.setText("Vui lòng nhập tên đăng nhập và mật khẩu.");
            return;
        }
        if (register && (!password.equals(confirmation.getText()) || emailAddress.isEmpty())) {
            message.setText("Vui lòng nhập email và xác nhận mật khẩu trùng khớp.");
            return;
        }
        setBusy(true);
        message.setText(register ? "Đang tạo tài khoản…" : "Đang đăng nhập…");
        Task<SocketResponse> task = new Task<>() {
            @Override
            protected SocketResponse call() throws Exception {
                return service.submit(serverHost, serverPort, register, username, password, emailAddress);
            }
        };
        pendingTask = task;
        task.setOnSucceeded(event -> {
            if (closed) {
                return;
            }
            setBusy(false);
            clearPasswords();
            ConnectionPreferences.save(selectedEndpoint);
            if (register) {
                loginName.setText(username);
                showForm.accept(false);
                message.setText("Đăng ký thành công! Nhập mật khẩu để đăng nhập.");
                loginPassword.requestFocus();
            } else {
                onLogin.accept(new AuthenticatedSession(
                        String.valueOf(task.getValue().getData().get("username")),
                        serverHost, serverPort, service.getClient()));
            }
            pendingTask = null;
        });
        task.setOnFailed(event -> {
            if (!closed) {
                setBusy(false);
                clearPasswords();
                message.setText("Không thành công: " + task.getException().getMessage());
                pendingTask = null;
            }
        });
        executor.execute(task);
    }

    private void setBusy(boolean busy) {
        formHost.setDisable(busy);
        serverPanel.setDisable(busy);
    }

    private void clearPasswords() {
        loginPasswordInput.conceal();
        registerPasswordInput.conceal();
        confirmationInput.conceal();
        loginPassword.clear();
        registerPassword.clear();
        confirmation.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        skinShowcase.close();
        clearPasswords();
        if (pendingTask != null) {
            pendingTask.cancel(true);
        }
        // Đóng kết nối ở luồng nền để thoát màn hình không phải chờ mạng.
        executor.execute(service::close);
        executor.shutdown();
    }
}
