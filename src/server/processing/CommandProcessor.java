package server.processing; // Yeni paket

import common.dto.CommandType;
import common.dto.Request;
import common.dto.Response;
import common.exceptions.IncorrectInputInScriptException;
import common.exceptions.InvalidFormException;
import common.exceptions.WrongAmountOfElementsException;
import common.models.Ticket; // Model lazım olabilir
import server.managers.CollectionManager; // CollectionManager'ı kullanacak

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
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
    private final Deque<String> scriptExecutionStack = new ArrayDeque<>();
    private enum ScriptStatus {
        OK,     // İşlem başarılı, devam et
        ERROR   // Bir hata oluştu, scripti durdur
    }


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
            return switch (type) {
                case HELP -> handleHelp(request);
                case INFO -> handleInfo(request);
                case SHOW -> handleShow(request);
                case INSERT -> handleInsert(request);
                case UPDATE -> handleUpdate(request);
                case REMOVE_KEY -> handleRemoveKey(request);
                case CLEAR -> handleClear(request);
                case EXECUTE_SCRIPT -> // Sunucuda script işleme daha karmaşık, şimdilik atlayabiliriz veya basit tutabiliriz
                        handleExecuteScript(request);
                case REMOVE_LOWER -> handleRemoveLower(request);
                case REPLACE_IF_LOWER -> handleReplaceIfLower(request);
                case COUNT_GREATER_THAN_DISCOUNT -> handleCountGreaterThanDiscount(request);
                case FILTER_STARTS_WITH_NAME -> handleFilterStartsWithName(request);
                case PRINT_DESCENDING -> handlePrintDescending(request);
                // logger.warn("Unknown command type received: {}", type);
            };
        } catch (Exception e) {
            // Beklenmedik hataları yakala
            // logger.error("Unexpected error processing command {}: {}", type, e.getMessage(), e);
            return new Response("Error: An unexpected server error occurred while processing command '" + type + "'.", false);
        }
    }

    private Response handleExecuteScript(Request request) {
        String fileName = request.getArguments();
        if (fileName == null || fileName.isEmpty()) {
            return new Response("Error: Script file name is missing.", false);
        }

        // Tam dosya yolunu oluştur (güvenlik açısından dikkatli olmalı)
        // String scriptPath = SCRIPT_BASE_DIR + fileName; // BASE_DIR tanımlanmalı
        // Şimdilik göreceli yol varsayalım
        File scriptFile = new File(fileName);

        // Rekürsiyon kontrolü (normalize edilmiş yolla yapmak daha güvenli olabilir)
        String absolutePath;
        try {
            absolutePath = scriptFile.getCanonicalPath(); // Döngüsel referansları çözer
        } catch (IOException e) {
            absolutePath = scriptFile.getAbsolutePath(); // Fallback
        }

        if (scriptExecutionStack.contains(absolutePath)) {
            return new Response("Error: Script recursion detected for file '" + fileName + "'. Aborting script.", false);
        }

        if (!scriptFile.exists() || !scriptFile.isFile()) { return new Response("Error: Script file not found or is a directory: '" + fileName + "'.", false); }
        if (!scriptFile.canRead()) { return new Response("Error: Cannot read script file (permission denied): '" + fileName + "'.", false); }

        // logger.info("Executing script: {}", absolutePath);
        System.out.println("INFO: Executing script: " + absolutePath);
        scriptExecutionStack.push(absolutePath); // Stack'e ekle

        StringBuilder scriptOutput = new StringBuilder("--- Executing script '").append(fileName).append("' ---\n");
        // ScriptStatus enum'ını kullanıyoruz
        ScriptStatus scriptStatus = ScriptStatus.OK; // Başlangıç durumu OK

        try (Scanner scriptScanner = new Scanner(scriptFile)) {
            ScriptReader scriptReader = new ScriptReader(scriptScanner);

            // Döngü koşulunu ScriptStatus'e göre güncelle
            while (scriptStatus == ScriptStatus.OK && scriptScanner.hasNextLine()) {
                String fullInputLine = scriptScanner.nextLine().trim();
                if (fullInputLine.isEmpty() || fullInputLine.startsWith("#")) continue;

                // logger.debug("Script line: {}", fullInputLine);

                String[] userCommand = fullInputLine.split(" ", 2);
                String commandName = userCommand[0];
                String commandArgs = (userCommand.length > 1) ? userCommand[1].trim() : "";

                // Rekürsiyon kontrolü (aynı kalır)
                if ("execute_script".equalsIgnoreCase(commandName)) {
                    // ... (rekürsiyon kontrolü ve hata durumunda scriptStatus = ScriptStatus.ERROR; break;) ...
                    File innerScriptFile = new File(commandArgs);
                    String innerAbsolutePath;
                    try { innerAbsolutePath = innerScriptFile.getCanonicalPath();}
                    catch (IOException e) { innerAbsolutePath = innerScriptFile.getAbsolutePath();}
                    if (scriptExecutionStack.contains(innerAbsolutePath)) {
                        scriptOutput.append("! Error: Script recursion detected for '").append(commandArgs).append("'. Stopping execution.\n");
                        scriptStatus = ScriptStatus.ERROR; // Durumu ERROR yap
                        break; // Döngüden çık
                    }
                }

                Request scriptRequest;
                try {
                    scriptRequest = createRequestFromScript(commandName, commandArgs, scriptReader);
                } catch (Exception e) {
                    scriptOutput.append("! Error parsing script command '").append(commandName).append("': ").append(e.getMessage()).append("\n");
                    scriptStatus = ScriptStatus.ERROR; // Durumu ERROR yap
                    break; // Döngüden çık
                }

                Response lineResponse = process(scriptRequest);
                scriptOutput.append("> ").append(fullInputLine).append("\n");
                scriptOutput.append("  ").append(lineResponse.toString().replace("\n", "\n  ")).append("\n");
                if (!lineResponse.isSuccess()) {
                    System.out.println("WARN: Command '" + commandName + "' in script '" + fileName + "' failed.");
                    scriptStatus = ScriptStatus.ERROR; // Durumu ERROR yap (döngü bir sonraki iterasyonda duracak)
                }

            } // while sonu
        } catch (FileNotFoundException e) {
            scriptOutput.append("! Error: Script file not found: '").append(fileName).append("'.\n");
            scriptStatus = ScriptStatus.ERROR; // Durumu ERROR yap
        } catch (NoSuchElementException e){
            scriptOutput.append("! Warning: Script '").append(fileName).append("' might be empty or finished unexpectedly.\n");
            // Script boşsa hata sayılmaz genellikle, scriptStatus OK kalır
        } catch (Exception e) {
            scriptOutput.append("! Error: Unexpected error during script execution '").append(fileName).append("': ").append(e.getMessage()).append("\n");
            scriptStatus = ScriptStatus.ERROR; // Durumu ERROR yap
        } finally {
            scriptExecutionStack.pop();
            System.out.println("INFO: Finished script execution: " + absolutePath);
            scriptOutput.append("--- Finished script '").append(fileName).append("' with status: ").append(scriptStatus).append(" ---");
        }

        // Dönen Response'un başarı durumunu scriptStatus'e göre ayarla
        return new Response(scriptOutput.toString(), scriptStatus == ScriptStatus.OK);
    }


    // --- createRequestFromScript Metodu (ScriptReader ile) ---
    private Request createRequestFromScript(String commandName, String commandArgs, ScriptReader scriptReader)
            throws WrongAmountOfElementsException, NumberFormatException, InvalidFormException, IncorrectInputInScriptException {

        CommandType type;
        try {
            type = CommandType.valueOf(commandName.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown command in script: " + commandName);
        }

        switch (type) {
            case HELP: case INFO: case SHOW: case CLEAR: case PRINT_DESCENDING:
                if (!commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                return new Request(type);

            case INSERT:
                if (commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                Long insertKey = Long.parseLong(commandArgs); // Key komut satırından
                Ticket insertTicket = scriptReader.readTicket(); // Ticket scriptten okunur
                return new Request(type, commandArgs, insertTicket); // Key'i string olarak saklayalım

            case REMOVE_KEY:
                if (commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                long parsedRemoveKey = Long.parseLong(commandArgs); // Sadece format kontrolü
                return new Request(type, commandArgs); // Key'i string olarak gönder

            case REPLACE_IF_LOWER:
                if (commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                Long replaceKey = Long.parseLong(commandArgs);
                Ticket replaceTicket = scriptReader.readTicket(); // Yeni Ticket scriptten
                return new Request(type, commandArgs, replaceTicket); // Key'i string olarak gönder

            case UPDATE:
                if (commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                Integer.parseInt(commandArgs); // Sadece format kontrolü
                Ticket updateTicket = scriptReader.readTicket(); // Yeni Ticket verisi scriptten
                return new Request(type, commandArgs, updateTicket); // ID'yi string olarak gönder

            case REMOVE_LOWER:
                if (!commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                Ticket refTicket = scriptReader.readTicket(); // Referans Ticket scriptten
                return new Request(type, refTicket);

            case COUNT_GREATER_THAN_DISCOUNT:
                if (commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                Long.parseLong(commandArgs); // Format kontrolü
                return new Request(type, commandArgs); // Argümanı string olarak gönder

            case FILTER_STARTS_WITH_NAME:
                if (commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                return new Request(type, commandArgs); // Argümanı string olarak gönder

            case EXECUTE_SCRIPT: // Bu case'e normalde process metodundan gelinmez ama güvenlik için ekleyelim
                if (commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                return new Request(type, commandArgs);

            default:
                throw new IllegalArgumentException("Command not processable within script: " + commandName);
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
                execute_script <file_name> : Read and execute a script from a specified file (Server side execution)
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
            int id = Integer.parseInt(request.getArguments()); // ID'yi al
            Ticket ticketData = request.getTicketArgument(); // Yeni veriyi al
            if (ticketData == null) {
                return new Response("Error: Ticket data is missing for update command.", false);
            }

            Ticket existingTicket = collectionManager.getById(id);
            if (existingTicket == null) {
                return new Response("Error: Ticket with ID " + id + " not found.", false);
            }

            existingTicket.update(ticketData);
            // Gerekirse collectionManager.getCollection().put(existingTicket.getKey(), existingTicket);

            return new Response("Ticket with ID " + id + " successfully updated.", true);
        } catch (NumberFormatException e) {
            return new Response("Error: Invalid ID format. ID must be an integer.", false);
        } catch (NullPointerException e) {
            return new Response("Error: Missing ID or ticket data for update.", false);
        } catch (Exception e) {
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


}
