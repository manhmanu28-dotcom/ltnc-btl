package com.auction.client.controller;

import com.auction.client.model.AuthenticatedSession;
import com.auction.client.service.AuthenticationClientService;
import com.auction.client.service.ConnectionPreferences;
import com.auction.client.ui.AuctionTheme;
import com.auction.client.ui.PasswordInput;
import com.auction.client.ui.SkinShowcase;
import com.auction.server.network.AuctionSocketClient;
import com.auction.server.network.LanDiscoveryService;
import com.auction.server.network.ServerEndpoint;
import com.auction.server.network.protocol.SocketResponse;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import static com.auction.client.ui.AuctionTheme.label;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Màn hình đăng ký và đăng nhập qua Socket, hỗ trợ kết nối cùng Wi-Fi (LAN) và khác Wi-Fi (Internet/Tunnel). */
public class AuthenticationController implements AutoCloseable {
    private final VBox view = new VBox(16);
    private final ScrollPane scrollPane = new ScrollPane(view);
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final Button probeButton = new Button("Kiểm tra");
    private final Button discoverButton = new Button("Tìm cùng Wi-Fi");
    private final Label connectionStatus = label("", "form-hint");
    private final TitledPane connectionOptions;
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
    private final SkinShowcase skinShowcase = new SkinShowcase();
    private Consumer<Boolean> showForm;
    private final VBox connectionPanel;
    private final AuthenticationClientService service = new AuthenticationClientService();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Auction-Authentication");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<AuthenticatedSession> onLogin;
    private Task<?> pendingTask;
    private boolean updatingConnectionFields;
    private boolean closed;

    public AuthenticationController(Consumer<AuthenticatedSession> onLogin) {
        this(onLogin, null, ConnectionPreferences.loadLastEndpoint());
    }

    public AuthenticationController(Consumer<AuthenticatedSession> onLogin, Runnable onDemo) {
        this(onLogin, onDemo, ConnectionPreferences.loadLastEndpoint());
    }

    public AuthenticationController(Consumer<AuthenticatedSession> onLogin, Runnable onDemo, ServerEndpoint initialEndpoint) {
        this.onLogin = onLogin;
        ServerEndpoint endpoint = initialEndpoint != null ? initialEndpoint : ConnectionPreferences.DEFAULT_ENDPOINT;
        host.setText(endpoint.host());
        port.setText(String.valueOf(endpoint.port()));

        Label title = label("Chào mừng trở lại", "card-title");
        Label introduction = label("Đăng nhập để khám phá các phiên đấu giá.", "muted");
        introduction.setWrapText(true);

        host.setPrefColumnCount(12);
        port.setPrefColumnCount(5);
        HBox connectionBar = new HBox(8, host, port);
        connectionBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(host, Priority.ALWAYS);
        host.setMinWidth(100);
        host.setPromptText("IP / domain hoặc dán host:port");
        host.setAccessibleText("Địa chỉ server");
        port.setPromptText("Cổng");
        port.setAccessibleText("Cổng kết nối");
        port.setMaxWidth(84);

        probeButton.getStyleClass().add("secondary-button");
        probeButton.setMaxWidth(Double.MAX_VALUE);
        probeButton.setOnAction(event -> probeServer());
        discoverButton.getStyleClass().add("secondary-button");
        discoverButton.setMaxWidth(Double.MAX_VALUE);
        discoverButton.setOnAction(event -> discoverLanServers());
        HBox.setHgrow(probeButton, Priority.ALWAYS);
        HBox.setHgrow(discoverButton, Priority.ALWAYS);
        HBox connectionActions = new HBox(8, discoverButton, probeButton);
        connectionActions.setAlignment(Pos.CENTER_LEFT);

        connectionStatus.setWrapText(true);
        connectionStatus.setText("Cùng Wi-Fi: bấm Tìm cùng Wi-Fi · Khác Wi-Fi: dán host:port từ Server -Public.");

        connectionPanel = new VBox(8, connectionBar, connectionActions, connectionStatus);
        connectionOptions = new TitledPane(connectionTitle(), connectionPanel);
        connectionOptions.getStyleClass().add("connection-options");
        connectionOptions.setExpanded(!endpoint.isLoopback());

        installSmartAddressPaste();
        host.textProperty().addListener((obs, before, after) -> connectionOptions.setText(connectionTitle()));
        port.textProperty().addListener((obs, before, after) -> connectionOptions.setText(connectionTitle()));

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
                    : "Đăng nhập để khám phá các phiên đấu giá.");
            message.setText("");
        };
        showForm.accept(false);
        message.setWrapText(true);
        message.getStyleClass().add("message");
        message.setMinHeight(30);
        message.textProperty().addListener((observable, before, text) -> {
            message.getStyleClass().removeAll("message-error", "message-success");
            if (text.startsWith("Đăng ký thành công")) {
                message.getStyleClass().add("message-success");
            } else if (!text.isEmpty() && !text.startsWith("Đang")) {
                message.getStyleClass().add("message-error");
            }
        });
        VBox card = new VBox(16, label("TÀI KHOẢN AUCTION", "eyebrow"), title, introduction,
                formHost, message, connectionOptions);
        card.getStyleClass().add("auth-card");
        card.setMinWidth(360);
        card.setPrefWidth(420);
        card.setMaxWidth(440);

        Label heroTitle = label("Find your\nnext skin.", "hero-title");
        heroTitle.setWrapText(true);
        heroTitle.setMaxWidth(600);
        Label description = label("Chọn skin bạn thích. Theo dõi phiên và đặt giá.", "hero-description");
        description.setWrapText(true);
        description.setMaxWidth(520);
        VBox hero = new VBox(18, label("AUCTION / SKINS", "eyebrow"),
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
            card.setMaxWidth(compact ? 600 : 440);
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
                label("Auction · Đấu giá trực tuyến", "hero-footer"));
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("auth-scroll");
        AuctionTheme.apply(scrollPane);
    }

    private void installSmartAddressPaste() {
        host.textProperty().addListener((observable, before, text) -> {
            if (updatingConnectionFields || text == null || !ServerEndpoint.containsHostAndPort(text)) {
                return;
            }
            try {
                ServerEndpoint parsed = ServerEndpoint.parse(text);
                Platform.runLater(() -> applyEndpoint(parsed));
            } catch (IllegalArgumentException ignored) {
                // Người dùng đang gõ dở địa chỉ
            }
        });
    }

    private void applyEndpoint(ServerEndpoint endpoint) {
        if (endpoint == null) {
            return;
        }
        updatingConnectionFields = true;
        try {
            host.setText(endpoint.host());
            port.setText(String.valueOf(endpoint.port()));
            connectionOptions.setText(connectionTitle());
        } finally {
            updatingConnectionFields = false;
        }
    }

    private String connectionTitle() {
        String h = host.getText() == null ? "" : host.getText().trim();
        String p = port.getText() == null ? "" : port.getText().trim();
        if (h.isEmpty()) {
            return "Kết nối máy chủ";
        }
        return p.isEmpty() ? "Kết nối máy chủ · " + h : "Kết nối máy chủ · " + h + ":" + p;
    }

    private void setConnectionStatus(String text, String styleClass) {
        connectionStatus.getStyleClass().removeAll("message-error", "message-success");
        if (styleClass != null) {
            connectionStatus.getStyleClass().add(styleClass);
        }
        connectionStatus.setText(text);
    }

    private ServerEndpoint parseEndpointFromFields() {
        ServerEndpoint endpoint = ServerEndpoint.fromInputs(host.getText(), port.getText());
        applyEndpoint(endpoint);
        return endpoint;
    }

    private void probeServer() {
        if (closed || (pendingTask != null && pendingTask.isRunning())) {
            return;
        }
        ServerEndpoint endpoint;
        try {
            endpoint = parseEndpointFromFields();
        } catch (IllegalArgumentException error) {
            setConnectionStatus(error.getMessage(), "message-error");
            return;
        }

        setBusy(true);
        setConnectionStatus("Đang kiểm tra kết nối tới " + endpoint.toAddress() + "…", null);

        Task<AuctionSocketClient.ServerProbeResult> task = new Task<>() {
            @Override
            protected AuctionSocketClient.ServerProbeResult call() throws Exception {
                return AuctionSocketClient.probe(endpoint, 5000);
            }
        };
        pendingTask = task;
        task.setOnSucceeded(event -> {
            if (closed) {
                return;
            }
            setBusy(false);
            pendingTask = null;
            AuctionSocketClient.ServerProbeResult result = task.getValue();
            ConnectionPreferences.save(result.endpoint());
            setConnectionStatus(
                    String.format("✓ Đã kết nối %s (%d ms · %d phiên · %d người online).",
                            result.endpoint().toAddress(),
                            result.latencyMillis(),
                            result.auctionCount(),
                            result.connectedClients()),
                    "message-success");
        });
        task.setOnFailed(event -> {
            if (closed) {
                return;
            }
            setBusy(false);
            pendingTask = null;
            String detail = task.getException() != null ? task.getException().getMessage() : "Không rõ nguyên nhân";
            setConnectionStatus(
                    "Không kết nối được " + endpoint.toAddress() + " (" + detail
                            + "). Kiểm tra Server đã bật, đúng IP:Cổng hoặc tường lửa Windows.",
                    "message-error");
        });
        executor.execute(task);
    }

    private void discoverLanServers() {
        if (closed || (pendingTask != null && pendingTask.isRunning())) {
            return;
        }
        setBusy(true);
        setConnectionStatus("Đang quét máy chủ trong cùng mạng Wi-Fi / LAN…", null);

        Task<List<LanDiscoveryService.DiscoveredServer>> task = new Task<>() {
            @Override
            protected List<LanDiscoveryService.DiscoveredServer> call() {
                return LanDiscoveryService.discoverServers(1200);
            }
        };
        pendingTask = task;
        task.setOnSucceeded(event -> {
            if (closed) {
                return;
            }
            setBusy(false);
            pendingTask = null;
            List<LanDiscoveryService.DiscoveredServer> servers = task.getValue();
            if (servers.isEmpty()) {
                setConnectionStatus(
                        "Không thấy máy chủ nào cùng Wi-Fi. Nếu khác Wi-Fi, hãy dán địa chỉ host:port từ Server -Public.",
                        "message-error");
                return;
            }
            if (servers.size() == 1) {
                LanDiscoveryService.DiscoveredServer found = servers.get(0);
                applyEndpoint(found.toEndpoint());
                ConnectionPreferences.save(found.toEndpoint());
                setConnectionStatus("✓ Đã tìm thấy " + found.displayLabel(), "message-success");
                return;
            }

            Map<String, LanDiscoveryService.DiscoveredServer> choices = new LinkedHashMap<>();
            for (LanDiscoveryService.DiscoveredServer server : servers) {
                choices.put(server.displayLabel(), server);
            }
            List<String> labels = List.copyOf(choices.keySet());
            ChoiceDialog<String> dialog = new ChoiceDialog<>(labels.get(0), labels);
            dialog.setTitle("Chọn máy chủ trong mạng LAN");
            dialog.setHeaderText("Tìm thấy " + servers.size() + " máy chủ đấu giá trong cùng mạng Wi-Fi:");
            dialog.setContentText("Máy chủ:");
            AuctionTheme.apply(dialog.getDialogPane());
            if (scrollPane.getScene() != null) {
                dialog.initOwner(scrollPane.getScene().getWindow());
            }
            dialog.showAndWait().ifPresent(selectedLabel -> {
                LanDiscoveryService.DiscoveredServer chosen = choices.get(selectedLabel);
                if (chosen != null) {
                    applyEndpoint(chosen.toEndpoint());
                    ConnectionPreferences.save(chosen.toEndpoint());
                    setConnectionStatus("✓ Đã chọn " + chosen.displayLabel(), "message-success");
                }
            });
        });
        task.setOnFailed(event -> {
            if (closed) {
                return;
            }
            setBusy(false);
            pendingTask = null;
            setConnectionStatus("Quét mạng LAN gặp lỗi. Hãy nhập thủ công địa chỉ Server và Cổng.", "message-error");
        });
        executor.execute(task);
    }

    public Parent getView() {
        return scrollPane;
    }

    private void submit(boolean register) {
        if (closed || (pendingTask != null && pendingTask.isRunning())) {
            return;
        }
        ServerEndpoint endpoint;
        try {
            endpoint = parseEndpointFromFields();
        } catch (IllegalArgumentException error) {
            message.setText(error.getMessage());
            connectionOptions.setExpanded(true);
            return;
        }
        String serverHost = endpoint.host();
        int serverPort = endpoint.port();

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
            ConnectionPreferences.save(endpoint);
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
        connectionPanel.setDisable(busy);
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
