package server.processing; // Yeni paket

import common.dto.CommandType;
import common.dto.Request;
import common.dto.Response;
import common.models.Ticket; // Model lazım olabilir
// import common.exceptions.*; // Gerekirse özel exception'lar
import server.managers.CollectionManager; // CollectionManager'ı kullanacak

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

/**
 * Processes incoming Request DTOs, executes the corresponding logic
 * using the CollectionManager, and returns a Response DTO.
 */
public class CommandProcessor {
    // private static final Logger logger = LoggerFactory.getLogger(CommandProcessor.class);
    private final CollectionManager collectionManager;

    public CommandProcessor(CollectionManager collectionManager) {
        this.collectionManager = collectionManager;
    }

    /**
     * Processes a received request and returns a response.
     * @param request The request DTO from the client.
     * @return The response DTO to send back to the client.
     */
    public Response process(Request request) {
        if (request == null || request.getCommandType() == null) {
            // logger.warn("Received null request or request with null command type.");
            return new Response("Error: Invalid request received by server.", false);
        }

        CommandType type = request.getCommandType();
        // logger.debug("Processing command type: {}", type);
        System.out.println("DEBUG: Processing command type: " + type); // Geçici debug

        try {
            // Switch-case veya Strategy pattern ile komutları işle
            switch (type) {
                case HELP:
                    return handleHelp(request);
                case INFO:
                    return handleInfo(request);
                case SHOW:
                    return handleShow(request);
                case INSERT:
                    return handleInsert(request);
                case UPDATE:
                    return handleUpdate(request);
                case REMOVE_KEY:
                    return handleRemoveKey(request);
                case CLEAR:
                    return handleClear(request);
                // case EXECUTE_SCRIPT: // Sunucuda script işleme daha karmaşık, şimdilik atlayabiliriz veya basit tutabiliriz
                //     return handleExecuteScript(request);
                case REMOVE_LOWER:
                    return handleRemoveLower(request);
                case REPLACE_IF_LOWER:
                    return handleReplaceIfLower(request);
                case COUNT_GREATER_THAN_DISCOUNT:
                    return handleCountGreaterThanDiscount(request);
                case FILTER_STARTS_WITH_NAME:
                    return handleFilterStartsWithName(request);
                case PRINT_DESCENDING:
                    return handlePrintDescending(request);
                // EXIT istemcide işlenir, buraya gelmez. SAVE sunucuya özeldir, komutla gelmez.
                default:
                    // logger.warn("Unknown command type received: {}", type);
                    return new Response("Error: Unknown command type '" + type + "'.", false);
            }
        } catch (Exception e) {
            // Beklenmedik hataları yakala
            // logger.error("Unexpected error processing command {}: {}", type, e.getMessage(), e);
            return new Response("Error: An unexpected server error occurred while processing command '" + type + "'.", false);
        }
    }

    // --- Her Komut Tipi İçin Yardımcı Metotlar ---

    private Response handleHelp(Request request) {
        // Lab 5'teki Help komutunun mantığı buraya gelir (statik metin veya dinamik liste)
        String helpText = """
                Available commands:
                help : Display help for available commands
                info : Display collection information
                show : Display all items in the collection
                insert <key> {element} : Add a new item with the specified key
                update <id> {element} : Update the value of the collection item whose id is equal to the given one
                remove_key <key> : Remove an item from the collection by its key
                clear : Clear the collection
                # execute_script <file_name> : Read and execute a script from a specified file (Server side execution)
                remove_lower {element} : Remove from the collection all items smaller than the specified value
                replace_if_lower <key> {element} : Replace the value by key if the new value is less than the old one
                count_greater_than_discount <discount> : Output the number of items whose discount field value is greater than the specified value
                filter_starts_with_name <name> : Output items whose name field value starts with the specified substring
                print_descending : Output the elements of the collection in descending order
                """;
        return new Response(helpText, true);
    }

    private Response handleInfo(Request request) {
        String info = collectionManager.getCollectionInfo();
        return new Response(info, true);
    }

    private Response handleShow(Request request) {
        // Koleksiyonu al ve İSME GÖRE SIRALA (Lab 6 gereksinimi)
        List<Ticket> sortedList = new ArrayList<>(collectionManager.getAllTicketsCollection()) // Koleksiyonun kopyasını al
                .stream()
                .sorted(Comparator.comparing(Ticket::getName)) // İsme göre sırala
                .collect(Collectors.toList());

        if (sortedList.isEmpty()) {
            return new Response("Collection is empty.", true, sortedList); // Boş listeyi payload olarak gönder
        } else {
            // Sıralı listeyi payload olarak gönder
            return new Response("Collection elements (sorted by name):", true, sortedList);
        }
    }

    private Response handleInsert(Request request) {
        try {
            Long key = Long.parseLong(request.getArguments()); // Anahtarı argümanlardan al
            Ticket ticket = request.getTicketArgument(); // Ticket nesnesini al
            if (ticket == null) {
                return new Response("Error: Ticket data is missing for insert command.", false);
            }
            if (collectionManager.getCollection().containsKey(key)) {
                return new Response("Error: Key " + key + " already exists.", false);
            }
            // ID ve Tarih ataması add içinde yapılmalı
            boolean success = collectionManager.addToCollection(key, ticket);
            if (success) {
                return new Response("Ticket successfully added with key " + key + ".", true);
            } else {
                // Bu durum normalde yukarıdaki containsKey ile yakalanır
                return new Response("Error: Failed to add ticket (unexpected).", false);
            }
        } catch (NumberFormatException e) {
            return new Response("Error: Invalid key format. Key must be a number.", false);
        } catch (NullPointerException e) {
            return new Response("Error: Missing key or ticket data for insert.", false);
        } catch (Exception e) { // CollectionManager.add'dan gelebilecek diğer hatalar
            // logger.error("Insert error:", e);
            return new Response("Error inserting ticket: " + e.getMessage(), false);
        }
    }

    private Response handleUpdate(Request request) {
        try {
            int id = Integer.parseInt(request.getArguments()); // ID'yi argümanlardan al
            Ticket ticketData = request.getTicketArgument(); // Yeni veriyi al
            if (ticketData == null) {
                return new Response("Error: Ticket data is missing for update command.", false);
            }

            Ticket existingTicket = collectionManager.getById(id);
            if (existingTicket == null) {
                return new Response("Error: Ticket with ID " + id + " not found.", false);
            }

            existingTicket.update(ticketData); // Nesneyi güncelle
            // CollectionManager'da ayrıca bir 'update' veya 'put' gerekebilir
            // collectionManager.updateTicket(existingTicket);

            return new Response("Ticket with ID " + id + " successfully updated.", true);
        } catch (NumberFormatException e) {
            return new Response("Error: Invalid ID format. ID must be an integer.", false);
        } catch (NullPointerException e) {
            return new Response("Error: Missing ID or ticket data for update.", false);
        } catch (Exception e) {
            // logger.error("Update error:", e);
            return new Response("Error updating ticket: " + e.getMessage(), false);
        }
    }

    private Response handleRemoveKey(Request request) {
        try {
            Long key = Long.parseLong(request.getArguments());
            boolean removed = collectionManager.removeFromCollection(key);
            if (removed) {
                return new Response("Ticket with key " + key + " successfully removed.", true);
            } else {
                return new Response("Error: Ticket with key " + key + " not found.", false);
            }
        } catch (NumberFormatException e) {
            return new Response("Error: Invalid key format. Key must be a number.", false);
        } catch (NullPointerException e) {
            return new Response("Error: Missing key for remove_key.", false);
        } catch (Exception e) {
            // logger.error("RemoveKey error:", e);
            return new Response("Error removing ticket: " + e.getMessage(), false);
        }
    }

    private Response handleClear(Request request) {
        collectionManager.clearCollection();
        return new Response("Collection cleared successfully.", true);
    }

    private Response handleRemoveLower(Request request) {
        try {
            Ticket referenceTicket = request.getTicketArgument();
            if (referenceTicket == null) {
                return new Response("Error: Reference ticket data is missing for remove_lower.", false);
            }
            int initialSize = collectionManager.collectionSize();
            // Fiyata göre karşılaştırma
            collectionManager.getCollection().values().removeIf(t -> t != null && t.getPrice() < referenceTicket.getPrice());
            int removedCount = initialSize - collectionManager.collectionSize();
            return new Response(removedCount + " elements removed (those with price lower than reference).", true);
        } catch (Exception e) {
            // logger.error("RemoveLower error:", e);
            return new Response("Error during remove_lower operation: " + e.getMessage(), false);
        }
    }

    private Response handleReplaceIfLower(Request request) {
        try {
            Long key = Long.parseLong(request.getArguments());
            Ticket newTicket = request.getTicketArgument();
            if (newTicket == null) {
                return new Response("Error: New ticket data is missing for replace_if_lower.", false);
            }

            Ticket existingTicket = collectionManager.getCollection().get(key);
            if (existingTicket == null) {
                return new Response("Error: Ticket with key " + key + " not found.", false);
            }

            if (newTicket.getPrice() < existingTicket.getPrice()) {
                existingTicket.update(newTicket); // Update existing to keep ID
                collectionManager.getCollection().put(key, existingTicket); // Put it back (if map doesn't update automatically)
                return new Response("Ticket with key " + key + " replaced as new price was lower.", true);
            } else {
                return new Response("Ticket with key " + key + " not replaced as new price was not lower.", true); // İşlem başarısız değil, sadece yapılmadı
            }
        } catch (NumberFormatException e) {
            return new Response("Error: Invalid key format. Key must be a number.", false);
        } catch (NullPointerException e) {
            return new Response("Error: Missing key or new ticket data for replace_if_lower.", false);
        } catch (Exception e) {
            // logger.error("ReplaceIfLower error:", e);
            return new Response("Error during replace_if_lower operation: " + e.getMessage(), false);
        }
    }

    private Response handleCountGreaterThanDiscount(Request request) {
        try {
            long discount = Long.parseLong(request.getArguments());
            long count = collectionManager.getCollection().values().stream()
                    .filter(ticket -> ticket.getDiscount() > discount)
                    .count();
            return new Response("Number of tickets with discount greater than " + discount + ": " + count, true);
        } catch (NumberFormatException e) {
            return new Response("Error: Invalid discount format. Discount must be a number.", false);
        } catch (NullPointerException e) {
            return new Response("Error: Missing discount value.", false);
        } catch (Exception e) {
            // logger.error("CountGreaterThanDiscount error:", e);
            return new Response("Error counting tickets: " + e.getMessage(), false);
        }
    }

    private Response handleFilterStartsWithName(Request request) {
        try {
            String prefix = request.getArguments();
            if (prefix == null) { // Argüman null olabilir mi? Request'e bağlı.
                return new Response("Error: Name prefix is missing for filter.", false);
            }
            List<Ticket> filteredList = collectionManager.getCollection().values().stream()
                    .filter(ticket -> ticket.getName() != null && ticket.getName().startsWith(prefix))
                    .collect(Collectors.toList());

            if (filteredList.isEmpty()) {
                return new Response("No tickets found with name starting with '" + prefix + "'.", true, filteredList);
            } else {
                return new Response("Tickets with name starting with '" + prefix + "':", true, filteredList);
            }
        } catch (Exception e) {
            // logger.error("FilterStartsWithName error:", e);
            return new Response("Error filtering tickets: " + e.getMessage(), false);
        }
    }

    private Response handlePrintDescending(Request request) {
        try {
            List<Ticket> sortedList = collectionManager.getAllTicketsCollection().stream()
                    .sorted(Comparator.comparing(Ticket::getPrice).reversed()) // Fiyata göre ters
                    .collect(Collectors.toList());

            if (sortedList.isEmpty()) {
                return new Response("Collection is empty.", true, sortedList);
            } else {
                return new Response("Collection elements (sorted by price descending):", true, sortedList);
            }
        } catch (Exception e) {
            // logger.error("PrintDescending error:", e);
            return new Response("Error sorting tickets: " + e.getMessage(), false);
        }
    }

    // handleExecuteScript(Request request) metodu daha sonra eklenebilir.
}
