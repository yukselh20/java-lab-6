package common;

import common.dto.Response;
import server.managers.CollectionManager;
import java.io.Serializable;

public interface Command extends Serializable {
    /**
     Komutun sunucu tarafında çalıştırılmasını sağlar.
     @param manager Koleksiyon yöneticisi (Receiver)
     @return İşlem sonucu istemciye gönderilecek Response nesnesi.
     **/
    Response execute(CollectionManager manager);
    String getName();
}
