package server.network;

import common.dto.Request;
import common.dto.Response;
import common.exceptions.SerializationException;
import common.utility.SerializationUtils;
import server.managers.CollectionManager; // CollectionManager'ı kullanacak
import server.processing.CommandProcessor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

/**
 * Handles UDP network communication for the server using non-blocking I/O and Selector.
 */

public class UDPServer implements Runnable {

    // private static final Logger logger = LoggerFactory.getLogger(UDPServer.class);
    private static final int BUFFER_SIZE = 8192;

    private final int port;
    private final CommandProcessor commandProcessor; // CommandProcessor eklendi
    private DatagramChannel channel;
    private Selector selector;
    private boolean running = true;

    // Constructor güncellendi
    public UDPServer(int port, CollectionManager collectionManager) {
        this.port = port;
        // CommandProcessor doğrudan burada oluşturuluyor
        this.commandProcessor = new CommandProcessor(collectionManager);
    }

    @Override
    public void run() {
        try {
            initializeServer();
            // logger.info("UDP Server started successfully on port {}", port);
            System.out.println("UDP Server started successfully on port " + port);

            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE); // Buffer'ı döngü dışında oluşturmak daha iyi

            while (running) {
                try {
                    int readyCount = selector.select(1000);
                    if (readyCount == 0) continue;

                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        if (!key.isValid()) continue;

                        if (key.isReadable()) {
                            // Gelen veriyi işle (buffer parametre olarak veriliyor)
                            handleIncomingData(key, buffer);
                        }
                    }
                } catch (IOException e) {
                    // logger.error("Error during selector operation:", e);
                    System.err.println("ERROR: Network I/O error in selector loop: " + e.getMessage());
                } catch (CancelledKeyException e) {
                    // logger.warn("SelectionKey was cancelled.");
                    System.err.println("WARN: SelectionKey was cancelled.");
                }
            }
        } catch (IOException e) {
            // logger.error("Failed to initialize server:", e);
            System.err.println("FATAL: Server initialization failed: " + e.getMessage());
        } finally {
            closeServer();
        }
    }

    private void initializeServer() throws IOException {
        selector = Selector.open();
        channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.bind(new InetSocketAddress(port));
        channel.register(selector, SelectionKey.OP_READ);
        System.out.println("Selector and DatagramChannel initialized.");
    }

    /**
     * Handles incoming data using the provided buffer.
     * Deserializes the request, processes it, and sends the response.
     * @param key The SelectionKey associated with the readable channel.
     * @param buffer The ByteBuffer to use for reading data.
     */
    private void handleIncomingData(SelectionKey key, ByteBuffer buffer) {
        DatagramChannel currentChannel = (DatagramChannel) key.channel();
        SocketAddress clientAddress = null;

        try {
            buffer.clear(); // Buffer'ı her okumadan önce temizle
            clientAddress = currentChannel.receive(buffer);

            if (clientAddress == null) return; // Gerçek veri gelmedi
            // logger.debug(...)
            System.out.println("DEBUG: Received " + buffer.position() + " bytes from " + clientAddress);

            buffer.flip();
            if (buffer.remaining() == 0) return; // Boş paket
            byte[] receivedData = Arrays.copyOf(buffer.array(), buffer.limit()); // copyOf ile doğru boyutta al

            // 1. İsteği Deserileştir
            Request request;
            try {
                // SerializationUtils.deserialize çağırılıyor ve cast yapılıyor
                Object receivedObject = SerializationUtils.deserialize(receivedData);
                if (receivedObject instanceof Request) {
                    request = (Request) receivedObject; // Güvenli cast
                    System.out.println("INFO: Received request '" + request.getCommandType() + "' from " + clientAddress);
                } else {
                    // Yanlış tip gelirse
                    System.err.println("ERROR: Received object is not a Request: " + (receivedObject != null ? receivedObject.getClass().getName() : "null"));
                    sendResponse(new Response("Error: Invalid request type received.", false), clientAddress);
                    return;
                }
            } catch (SerializationException | ClassCastException e) { // Hata türleri eklendi
                // logger.error("Failed to deserialize request from {}: {}", clientAddress, e.getMessage());
                System.err.println("ERROR: Failed to deserialize request from " + clientAddress + ": " + e.getMessage());
                sendResponse(new Response("Error: Could not deserialize request. " + e.getMessage(), false), clientAddress);
                return;
            } catch (Exception e){ // Beklenmedik deserileştirme hataları
                System.err.println("ERROR: Unexpected deserialization error from " + clientAddress + ": " + e.getMessage());
                sendResponse(new Response("Error: Unexpected error processing request data.", false), clientAddress);
                return;
            }

            // 2. Komutu İşle
            Response response = commandProcessor.process(request);
            // logger.info(...)
            System.out.println("INFO: Processed command '" + request.getCommandType() + "' for " + clientAddress + ". Success: " + response.isSuccess());

            // 3. Yanıtı Gönder
            sendResponse(response, clientAddress);

        } catch (IOException e) {
            // logger.error(...)
            System.err.println("ERROR: I/O error handling data from " + (clientAddress != null ? clientAddress : "unknown") + ": " + e.getMessage());
            if (clientAddress != null) {
                sendResponse(new Response("Internal server error during request processing.", false), clientAddress);
            }
        }
    }

    /**
     * Serializes the Response object and sends it back to the client via UDP.
     * @param response The Response object to send.
     * @param clientAddress The address of the client to send the response to.
     */
    private void sendResponse(Response response, SocketAddress clientAddress) {
        try {
            // 1. Yanıtı Serileştir (SerializationUtils kullanılıyor)
            byte[] responseBytes = SerializationUtils.serialize(response);
            ByteBuffer sendBuffer = ByteBuffer.wrap(responseBytes);
            // logger.debug(...)
            System.out.println("DEBUG: Sending response ("+responseBytes.length+" bytes) to " + clientAddress);

            // 2. Yanıtı Gönder
            int sentBytes = channel.send(sendBuffer, clientAddress);
            if (sentBytes == 0) {
                // logger.warn(...)
                System.err.println("WARN: Response could not be sent immediately to " + clientAddress);
            }
            // logger.trace(...)

        } catch (SerializationException e) {
            // logger.error(...)
            System.err.println("ERROR: Failed to serialize response for " + clientAddress + ": " + e.getMessage());
        } catch (IOException e) {
            // logger.error(...)
            System.err.println("ERROR: I/O error sending response to " + clientAddress + ": " + e.getMessage());
        }
    }

    // stopServer ve closeServer metotları aynı kalabilir
    public void stop() {
        running = false;          // while(running) döngüsünü kırar
        if (selector != null) {
            selector.wakeup();    // select()’i uyandırır
        }
    }


    private void closeServer() {
        try {
            if (channel != null && channel.isOpen()) channel.close();
            if (selector != null && selector.isOpen()) selector.close();
        } catch (IOException e) {
            System.err.println("ERROR: Failed to close network resources: " + e.getMessage());
        }
    }
}