package server;


//Değişiklikler: Sunucu uygulamasının ana giriş noktası.
//Manager'ları başlatır, shutdown hook ekler ve ağ sunucusunu
// (henüz oluşturmadık) başlatıp çalıştırır.

//Neden? Uygulamanın sunucu tarafını başlatmak ve yönetmek için
//merkezi bir nokta.

import server.managers.CollectionManager;
import server.managers.DumpManager;
import server.network.UDPServer;

public class ServerMain {

    // private static final Logger logger = LoggerFactory.getLogger(ServerMain.class);
    // Port numarasını bir yapılandırma dosyasından veya argümandan almak daha iyi olur
    private static final int DEFAULT_PORT = 54321; // Örnek port

    public static void main(String[] args) {
        // Portu argümandan almayı deneyelim, yoksa varsayılanı kullanalım
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port <= 0 || port > 65535) {
                    System.err.println("ERROR: Invalid port number: " + args[0] + ". Default port: " + DEFAULT_PORT );
                    port = DEFAULT_PORT;
                }
            } catch (NumberFormatException e) {
                System.err.println("ERROR: Port number must be a number: " + args[0] + ". Default port: " + DEFAULT_PORT );
                port = DEFAULT_PORT;
            }
        }

        // logger.info("Sunucu başlatılıyor...");
        System.out.println("Initialising server...");


        String fileName = System.getenv("TICKET_FILE");
        if (fileName == null || fileName.isEmpty()) {
            // logger.error("Ortam değişkeni 'TICKET_FILE' tanımlanmamış. Sunucu durduruluyor.");
            System.err.println("ERROR: Environment variable ‘TICKET_FILE’ is not defined. The server is being stopped.");
            System.exit(1); // Dosya adı olmadan çalışamaz
        }
        // logger.info("Koleksiyon dosyası kullanılacak: {}", fileName);
        System.out.println("Collection file: " + fileName);


        // Manager'ları başlat
        DumpManager dumpManager = new DumpManager(fileName);
        CollectionManager collectionManager = new CollectionManager(dumpManager);

        // Shutdown hook ekle (uygulama kapanırken koleksiyonu kaydetmek için)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // logger.info("Shutdown hook tetiklendi. Koleksiyon kaydediliyor...");
            System.out.println("\nShutting down the server, saving the collection.");
            collectionManager.saveCollection();
            System.out.println("Collection saved. Safe exit.");
            // logger.info("Koleksiyon kaydedildi. Sunucu durduruldu.");
        }));


        // Ağ sunucusunu başlat ve çalıştır
        UDPServer udpServer = new UDPServer(port, collectionManager);
        // logger.info("UDP Sunucu {} portunda dinlemeye başlıyor.", port);
        System.out.println("UDP Server starts listening on port: " + port);
        udpServer.run(); // Bu metot sunucu ana döngüsünü içerecek
    }
}