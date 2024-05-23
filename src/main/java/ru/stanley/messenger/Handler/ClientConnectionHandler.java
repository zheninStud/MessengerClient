package ru.stanley.messenger.Handler;

import javafx.application.Platform;
import org.json.JSONObject;
import ru.stanley.messenger.Controllers.AuthController;
import ru.stanley.messenger.Controllers.MainController;
import ru.stanley.messenger.Controllers.RegistryController;
import ru.stanley.messenger.Database.DatabaseConnection;
import ru.stanley.messenger.Messenger;
import ru.stanley.messenger.Models.Message;
import ru.stanley.messenger.Models.User;
import ru.stanley.messenger.Utils.ControllerRegistry;
import ru.stanley.messenger.Utils.DHUtil;
import ru.stanley.messenger.Utils.MessageType;

import javax.net.ssl.*;
import java.io.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.sql.SQLException;


public class ClientConnectionHandler {

    private SSLSocket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Thread receiveThread;
    private boolean running;
    private AuthController authController;
    private RegistryController registryController;
    private MainController mainController;
    private static final DatabaseConnection database = Messenger.getDatabaseConnection();
    private static final ClientConnectionHandler clientConnectionHandler = Messenger.getClientConnectionHandler();

    public void connect(String serverAddress, int port) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            KeyStore keyStore = KeyStore.getInstance("JKS");
            char[] password = "testserver".toCharArray();
            keyStore.load(new FileInputStream("server_keystore.jks"), password);
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);

            sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());

            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            socket = (SSLSocket) sslSocketFactory.createSocket(serverAddress, port);

            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            System.out.println("Connected to the server");

            running = true;
            receiveThread = new Thread(this::receiveMessages);
            receiveThread.start();
        } catch (IOException | NoSuchAlgorithmException | KeyManagementException | KeyStoreException | CertificateException   e) {
            e.printStackTrace();
        }
    }

    public void disconnect() {
        try {
            running = false;
            receiveThread.join();
            if (socket != null && !socket.isClosed()) {
                socket.close();
                System.out.println("Disconnected from the server");
            }
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void sendMessage(Message message) {
        out.println(message.toJSON());
    }

    public void receiveMessages() {
        try {
            String serverResponse;
            while (running && (serverResponse = in.readLine()) != null) {
                System.out.println("Received message from server: " + serverResponse);

                Message message = Message.fromJSON(serverResponse);

                handleMessage(message);
            }
        } catch (IOException | SQLException | NoSuchAlgorithmException | NoSuchProviderException |
                 InvalidKeySpecException | InvalidKeyException e) {
            if (running) {
                e.printStackTrace();
            }
        }
    }

    private void handleMessage(Message message) throws SQLException, NoSuchAlgorithmException, NoSuchProviderException, InvalidKeySpecException, InvalidKeyException {
        String messageType = message.getType();
        User currentUser;
        User newUser;

        switch (messageType) {
            case "AUTH_SUCCESS":
                currentUser = new User(message.getData().getString("userId"), message.getData().getString("userName"),
                        message.getData().getString("email"), message.getData().getString("phone"));

                authController = (AuthController) ControllerRegistry.getController("AuthController");
                if (authController != null) {
                    Platform.runLater(() -> authController.openMainForm(currentUser));
                }
                break;
            case "AUTH_FAIL":
                authController = (AuthController) ControllerRegistry.getController("AuthController");
                if (authController != null) {
                    Platform.runLater(() -> authController.clearText());
                }
                break;
            case "REGISTER_SUCCESS":
                registryController = (RegistryController) ControllerRegistry.getController("RegistryController");
                if (registryController != null) {
                    Platform.runLater(() -> registryController.openAuthForm());
                }
                break;
            case "REGISTER_FAIL":
                registryController = (RegistryController) ControllerRegistry.getController("RegistryController");
                if (registryController != null) {
                    Platform.runLater(() -> registryController.checkAlert(message.getData().getString("alert")));
                }
                break;
            case "SET_SALT":
                String salt = message.getData().getString("salt");
                authController = (AuthController) ControllerRegistry.getController("AuthController");
                if (authController != null) {
                    Platform.runLater(() -> authController.authMessage(salt));
                }
                break;
            case "USER_SUCCESS":
                newUser = new User(message.getData().getString("userId"), message.getData().getString("userName"),
                        message.getData().getString("email"), message.getData().getString("phone"));
                if (database.insertUser(newUser)) {
                    mainController = (MainController) ControllerRegistry.getController("MainController");
                    if (mainController != null) {
                        Platform.runLater(() -> mainController.showSuccessNotification("User successfully added"));

                        KeyPair dhUtil = DHUtil.initDH();
                        PublicKey publicKey = dhUtil.getPublic();
                        PrivateKey privateKey = dhUtil.getPrivate();

                        if (database.insertUserKey(newUser.getUserId(), DHUtil.keyToString(publicKey), DHUtil.keyToString(privateKey))) {
                            MessageType messageTypeSend = MessageType.REGUEST_FRIEND;
                            JSONObject jsonMessage = messageTypeSend.createJsonObject();

                            jsonMessage.getJSONObject("data").put("userId", newUser.getUserId());
                            jsonMessage.getJSONObject("data").put("publicKey", DHUtil.keyToString(publicKey));

                            clientConnectionHandler.sendMessage(messageTypeSend.createMessage(jsonMessage));
                        }
                    }
                }
                break;
            case "USER_FAIL":
                mainController = (MainController) ControllerRegistry.getController("MainController");
                if (mainController != null) {
                    Platform.runLater(() -> mainController.showAlert("User not found!"));
                }
                break;
            case "REGUEST_FRIEND_CLIENT":
                newUser = new User(message.getData().getString("userId"), message.getData().getString("userName"),
                        message.getData().getString("email"), message.getData().getString("phone"));

                if (database.insertUserRequest(newUser)) {
                    if (database.insertUserKeyRequest(newUser.getUserId(), message.getData().getString("publicKey"))) {
                        MessageType messageTypeSend = MessageType.REGUEST_FRIEND_CLIENT_TAKEN;
                        JSONObject jsonMessage = messageTypeSend.createJsonObject();

                        jsonMessage.getJSONObject("data").put("userId", newUser.getUserId());

                        clientConnectionHandler.sendMessage(messageTypeSend.createMessage(jsonMessage));

                    }
                }
                break;
            case "REGUEST_FRIEND_CLIENT_TAKEN_CLIENT":
                String userReguestTaken = message.getData().getString("userId");

                if (database.updateUserKey(userReguestTaken, true)) {
                    mainController = (MainController) ControllerRegistry.getController("MainController");
                    if (mainController != null) {
                        Platform.runLater(() -> mainController.showSuccessNotification("User taken request friend"));
                    }
                }
                break;
            case "REGUEST_FRIEND_CLIENT_SUCCESS_SERVER":
                break;

            case "REGUEST_FRIEND_CLIENT_SUCCESS_CLIENT":

                String userIdFriend = message.getData().getString("userId");
                String publicKeyFriend = message.getData().getString("publicKey");

                if (database.selectSecretKey(userIdFriend, DHUtil.convertBytesToPublicKey(publicKeyFriend))) {

                }
        }
    }
}

