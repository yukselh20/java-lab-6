package client.network;

import common.dto.Request;
import common.dto.Response;
import common.exceptions.SerializationException;
import common.utility.SerializationUtils; // Yardımcı sınıfımızı import ediyoruz

import java.io.IOException;
import java.net.*; // SocketAddress, InetSocketAddress
import java.nio.ByteBuffer; // ByteBuffer kullanacağız
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel; // DatagramChannel kullanacağız


// import java.net.*;
// import java.nio.channels.DatagramChannel;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

/**
 * Handles UDP network communication for the client.
 * Sends serialized Command objects to the server and receives Response objects.
 * Operates in non-blocking mode with timeouts.
 */
public class UDPClient {

    // private static final Logger logger = LoggerFactory.getLogger(UDPClient.class);
    private static final int BUFFER_SIZE = 8192; // Alınacak yanıt için buffer boyutu (64KB'a kadar olabilir)
    private static final int TIMEOUT_MS = 5000; //5sn yanıt bekleme süresi
    private static final int MAX_RETRIES = 3; //max send recieve attempts

    private DatagramChannel channel;
    private SocketAddress serverAddress;
    //private Selector selector; // Yanıtı beklerken selector kullanmak daha gelişmiş bir yöntem olabilir

    public UDPClient(String host, int port) {
        // connect(); // Veya bağlantı ilk istekte kurulur
        try {
            // Sunucu adresini oluştur
            this.serverAddress = new InetSocketAddress(InetAddress.getByName(host), port);
            // Datagram Kanalını aç
            this.channel = DatagramChannel.open();
            this.channel.bind(null);
            // Engellemeyen (Non-blocking) moda ayarla - ÇOK ÖNEMLİ!
            this.channel.configureBlocking(false);
            // logger.info("UDP Client channel opened and configured non-blocking for server {}:{}", host, port);
            System.out.println("UDP Client channel opened for " + host + ":" + port);

            // Gelişmiş: Yanıt beklemek için Selector kullanmak isterseniz:
            // this.selector = Selector.open();
            // this.channel.register(selector, SelectionKey.OP_READ);

        } catch (UnknownHostException e) {
            // logger.error("Server host could not be found: {}", host, e);
            System.err.println("Server host could not be found: " + host);
            // Burada istemciyi durdurmak veya hata durumunu yönetmek gerekebilir
            System.exit(1);
        } catch (IOException e) {
            // logger.error("Failed to open DatagramChannel:", e);
            System.err.println("Failed to open DatagramChannel: " + e.getMessage());
            System.exit(1);
        }
    }


    /**
     * Sends a command object to the server and waits for a response.
     * Handles serialization and deserialization.
     * Includes logic for server unavailability.
     *
     * @param request The command object to send.
     * @return The Response object received from the server, or null if failed.
     */
    public Response sendAndReceive(Request request) {
        byte[] requestBytes;

        try {
            requestBytes = SerializationUtils.serialize(request);
            // logger.debug("Serialized request: {} bytes", requestBytes.length);
        } catch (SerializationException e) {
            // logger.error("Failed to serialize request: {}", request, e);
            System.err.println("ERROR: Failed to prepare request (serialization): " + e.getMessage());
            return null; // veya özel hata Response'u
        }

        ByteBuffer sendBuffer = ByteBuffer.wrap(requestBytes);
        ByteBuffer receiveBuffer = ByteBuffer.allocate(BUFFER_SIZE);

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                // logger.debug("Attempt {} to send request to {}", attempt + 1, serverAddress);
                System.out.println("DEBUG: Attempt " + (attempt + 1) + " sending request to " + serverAddress);
                int sentBytes = channel.send(sendBuffer, serverAddress);
                if (sentBytes == 0) {
                    // logger.warn("Send returned 0 bytes on attempt {}, possibly buffer full. Retrying...", attempt + 1);
                    System.err.println("WARN: Send returned 0 bytes on attempt " + (attempt + 1) + ". Retrying...");
                    sendBuffer.rewind(); // Buffer'ı başa sar
                    Thread.sleep(50); // Kısa bir bekleme
                    continue; // Gönderimi tekrar dene
                }
                // logger.trace("Sent {} bytes.", sentBytes);

                // Yanıt bekleme
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
                    receiveBuffer.clear();
                    SocketAddress fromAddress = channel.receive(receiveBuffer);

                    if (fromAddress != null) {
                        if (fromAddress.equals(serverAddress)) {
                            // logger.debug("Received response from {} ({} bytes)", serverAddress, receiveBuffer.position());
                            System.out.println("DEBUG: Received response from " + serverAddress + " (" + receiveBuffer.position() + " bytes)");
                            receiveBuffer.flip();
                            byte[] responseBytes = new byte[receiveBuffer.remaining()];
                            receiveBuffer.get(responseBytes);

                            try {
                                Object responseObject = SerializationUtils.deserialize(responseBytes);
                                if (responseObject instanceof Response) {
                                    return (Response) responseObject; // Başarılı yanıt!
                                } else {
                                    // logger.error("Received unexpected object type: {}", responseObject.getClass().getName());
                                    System.err.println("ERROR: Received unexpected data type from server.");
                                    return new Response("Received unexpected data type from server.", false); // Hata yanıtı
                                }
                            } catch (SerializationException e) {
                                // logger.error("Failed to deserialize response:", e);
                                System.err.println("ERROR: Failed to process server response: " + e.getMessage());
                                return new Response("Failed to process server response: " + e.getMessage(), false); // Hata yanıtı
                            }
                        } else {
                            // logger.warn("Received packet from unexpected address: {}", fromAddress);
                            System.err.println("WARN: Received packet from unexpected address: " + fromAddress);
                        }
                    }
                    // Yanıt gelmediyse kısa bekleme
                    Thread.sleep(100);
                } // while timeout sonu

                // Timeout oldu, bir sonraki denemeye geç
                // logger.warn("Timeout waiting for response (Attempt {}/{})", attempt + 1, MAX_RETRIES);
                System.err.println("WARN: Timeout waiting for response (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");

            } catch (ClosedChannelException e){
                // logger.error("Channel closed unexpectedly.", e);
                System.err.println("ERROR: Network channel closed unexpectedly. Cannot send/receive.");
                // Yeniden bağlanmayı deneyebilir veya çıkabiliriz. Şimdilik null dönelim.
                return null;
            } catch (IOException e) {
                // logger.error("Network I/O error on attempt {}: {}", attempt + 1, e.getMessage());
                System.err.println("ERROR: Network I/O error on attempt " + (attempt + 1) + ": " + e.getMessage());
                // Bir sonraki denemeden önce biraz bekleyebiliriz
                try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null;}
            } catch (InterruptedException e) {
                // logger.warn("Client thread interrupted during wait/sleep.");
                System.err.println("WARN: Client thread interrupted.");
                Thread.currentThread().interrupt();
                return null; // Veya hata Response'u
            }
            // Bir sonraki denemeden önce buffer'ı başa sar
            sendBuffer.rewind();
        } // for retries sonu

        // Tüm denemeler başarısız oldu
        // logger.error("Failed to get response from server after {} attempts.", MAX_RETRIES);
        System.err.println("ERROR: Failed to get response from server after " + MAX_RETRIES + " attempts.");
        return null; // Başarısızlığı null ile belirtiyoruz
    }

    public void close() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
                System.out.println("Client UDP channel closed.");
            }
        } catch (IOException e) {
            System.err.println("ERROR: Failed to close client network channel: " + e.getMessage());
        }
    }
}