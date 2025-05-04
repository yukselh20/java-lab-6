package client.utility;

import client.forms.TicketForm; // Formları kullanacak
import client.network.UDPClient; // Ağı kullanacak
import client.utility.console.Console;
import common.dto.*;
import common.exceptions.*; // Exception'lar
import common.exceptions.NoSuchElementException;
import common.models.*; // Modeller
import common.Command;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
/**
 * Manages interactive and script modes for the client application.
 * Parses user input, creates Command objects, sends them via UDPClient,
 * and displays the response.
 */


//Uygulamanın interaktif ve script modlarını yöneten sınıf.
// CommandManager bağımlılığını kaldıralım. Komut nesneleri artık Runner içinde
// (veya yardımcı bir sınıfta) oluşturulacak.
// CollectionManager bağımlılığını kaldıralım.
// Komutlar artık doğrudan koleksiyonu değil, ağ üzerinden sunucuyu hedefleyecek.


/**
 * Manages interactive and script modes for the client application.
 * Parses user input, creates Request DTOs, sends them via UDPClient,
 * and displays the response.
 */
public class Runner {

    public enum ExitCode { OK, ERROR, EXIT }

    private final Console console;
    private final UDPClient udpClient;
    private final List<String> scriptStack = new ArrayList<>();

    public Runner(Console console, UDPClient udpClient) {
        this.console = console;
        this.udpClient = udpClient;
    }

    /**
     * Runs the client in interactive mode.
     */
    public void interactiveMode() {
        Scanner scanner = Interrogator.getUserScanner();
        Interrogator.setUserMode();
        try {
            ExitCode commandStatus;
            String[] userCommand;

            do {
                console.ps1();
                // Kullanıcıdan tam satırı alalım, boşluklu argümanlar olabilir
                String fullInputLine = scanner.nextLine().trim();
                // İlk kelimeyi komut olarak al, geri kalanı argüman string'i yap
                userCommand = fullInputLine.split(" ", 2);
                String commandName = userCommand[0];
                String commandArgs = (userCommand.length > 1) ? userCommand[1].trim() : ""; // Argüman yoksa boş string

                // Komutu işle
                commandStatus = processUserInput(commandName, commandArgs);

            } while (commandStatus != ExitCode.EXIT);

        } catch (IllegalStateException exception) {
            console.printError("Unexpected error! Exiting.");
        } finally {
            udpClient.close(); // İstemci kapanırken bağlantıyı kapat
        }
    }

    /**
     * Processes a single line of user input (from console or script).
     * @param commandName The command name entered by the user.
     * @param commandArgs The arguments string entered by the user.
     * @return Exit code.
     */
    private ExitCode processUserInput(String commandName, String commandArgs) {
        if (commandName.isEmpty()) return ExitCode.OK; // Boş satırı geç

        // EXIT komutunu hemen işle, sunucuya gönderme
        if ("exit".equalsIgnoreCase(commandName)) {
            if (!commandArgs.isEmpty()) {
                console.printError("Usage: exit (no arguments)");
                return ExitCode.ERROR;
            }
            console.println("Exiting program...");
            return ExitCode.EXIT;
        }

        // EXECUTE_SCRIPT komutunu özel olarak işle (scripti istemcide çalıştıracak)
        if ("execute_script".equalsIgnoreCase(commandName)) {
            if (commandArgs.isEmpty()) {
                console.printError("Usage: execute_script <file_name>");
                return ExitCode.ERROR;
            }
            return scriptMode(commandArgs); // Script modunu çağır
        }

        // Diğer komutlar için Request DTO oluştur
        Request requestToSend = null;
        try {
            requestToSend = createRequest(commandName, commandArgs);
        } catch (WrongAmountOfElementsException e) {
            console.printError("Incorrect number of arguments for command '" + commandName + "'.");
            // Burada komutların beklediği argümanları göstermek iyi olabilir (HelpCommand'dan alınabilir mi?)
            return ExitCode.ERROR;
        } catch (NumberFormatException e) {
            console.printError("Numeric argument expected for command '" + commandName + "' but got: '" + commandArgs + "'");
            return ExitCode.ERROR;
        } catch (InvalidFormException | IncorrectInputInScriptException e) { // Form hataları
            console.printError("Invalid data entered for command '" + commandName + "': " + e.getMessage());
            return ExitCode.ERROR;
        } catch (IllegalArgumentException e){ // Enum parse hatası vb.
            console.printError("Invalid argument for command '" + commandName + "': " + e.getMessage());
            return ExitCode.ERROR;
        }

        if (requestToSend == null) {
            console.printError("Command '" + commandName + "' not recognized or could not be prepared.");
            return ExitCode.ERROR;
        }

        // Oluşturulan isteği sunucuya gönder ve yanıtı al
        try {
            Response response = udpClient.sendAndReceive(requestToSend); // Eski sendCommandAndGetResponse yerine
            if (response != null) {
                // Yanıtı göster (Response.toString() hallediyor)
                console.println(response.toString());
                return response.isSuccess() ? ExitCode.OK : ExitCode.ERROR;
            } else {
                // udpClient.send null döndürdüyse (örn. max retries aşıldı)
                console.printError("Failed to get response from server (max retries exceeded or other issue).");
                return ExitCode.ERROR;
            }
        } catch (Exception e) { // Beklenmedik diğer hatalar
            console.printError("An unexpected error occurred: " + e.getMessage());
            // e.printStackTrace(); // Debug için
            return ExitCode.ERROR;
        }
    }

    /**
     * Creates a Request DTO based on user input.
     * Handles argument parsing and form building.
     * @param commandName Name of the command.
     * @param commandArgs String containing arguments.
     * @return The created Request object.
     * @throws WrongAmountOfElementsException If arguments are incorrect.
     * @throws NumberFormatException If a numeric argument is invalid.
     * @throws InvalidFormException If form data is invalid.
     * @throws IncorrectInputInScriptException If script input is invalid.
     * @throws IllegalArgumentException If command name is unknown or enum parse fails.
     */
    private Request createRequest(String commandName, String commandArgs)
            throws WrongAmountOfElementsException, NumberFormatException, InvalidFormException, IncorrectInputInScriptException {

        CommandType type = CommandType.valueOf(commandName.toUpperCase()); // Enum'a çevir

        switch (type) {
            case HELP:
            case INFO:
            case SHOW:
            case CLEAR:
            case PRINT_DESCENDING:
                if (!commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                return new Request(type); // Argümansız

            case INSERT:
            case REMOVE_KEY:
            case REPLACE_IF_LOWER: // Bu komutlar key + (bazıları) Ticket alır
                if (commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                Long key = Long.parseLong(commandArgs); // Sadece key alınıyor şimdilik
                Ticket ticketForKeyCommands = null;
                if (type == CommandType.INSERT || type == CommandType.REPLACE_IF_LOWER) {
                    console.println("=> Enter Ticket data" + (type == CommandType.REPLACE_IF_LOWER ? " for replacement (key: " + key +"):" : " for key " + key + ":"));
                    ticketForKeyCommands = new TicketForm(console).build(); // Formu build et
                }
                // Key'i String olarak gönderiyoruz, sunucu parse edecek
                return new Request(type, commandArgs, ticketForKeyCommands);

            case UPDATE:
                if (commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                int id = Integer.parseInt(commandArgs);
                console.println("=> Enter new data for Ticket ID#" + id + ":");
                Ticket ticketForUpdate = new TicketForm(console).build();
                // ID'yi String olarak gönderiyoruz
                return new Request(type, commandArgs, ticketForUpdate);

            // case EXECUTE_SCRIPT: // Zaten yukarıda handle edildi

            case REMOVE_LOWER:
                if (!commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                console.println("=> Enter reference Ticket data for comparison:");
                Ticket refTicket = new TicketForm(console).build();
                return new Request(type, refTicket); // Sadece Ticket gönder

            case COUNT_GREATER_THAN_DISCOUNT:
            case FILTER_STARTS_WITH_NAME:
                if (commandArgs.isEmpty()) throw new WrongAmountOfElementsException();
                // Argümanı direkt String olarak gönder
                return new Request(type, commandArgs);

            default:
                // Bilinmeyen komut tipi (yukarıda processUserInput'te yakalanmalı ama yine de)
                throw new IllegalArgumentException("Unknown command: " + commandName);
        }
    }


    /**
     * Runs the client in script mode. Reads commands from the file line by line.
     * @param fileName Script file name.
     * @return Final exit code of the script execution.
     */
    public ExitCode scriptMode(String fileName) {
        String[] userCommand;
        ExitCode commandStatus = ExitCode.OK; // Başlangıç durumu
        scriptStack.add(fileName);
        // fileName = SCRIPT_BASE_DIR + fileName; // Tam dosya yolu mantığı gerekebilir

        console.println("Executing script: " + fileName);


        try (Scanner scriptScanner = new Scanner(new File(fileName))) {
            if (!scriptScanner.hasNext()) {
                throw new NoSuchElementException("Script file is empty!");
            }

            Scanner tmpScanner = Interrogator.getUserScanner();
            Interrogator.setUserScanner(scriptScanner);
            Interrogator.setFileMode();

            while (commandStatus == ExitCode.OK && scriptScanner.hasNextLine()) {
                String fullInputLine = scriptScanner.nextLine().trim();
                if (fullInputLine.isEmpty()) continue; // Boş satırları atla

                console.println(console.getPS1() + fullInputLine); // Komutu ekrana yazdır

                userCommand = fullInputLine.split(" ", 2);
                String commandName = userCommand[0];
                String commandArgs = (userCommand.length > 1) ? userCommand[1].trim() : "";

                // Script içinde execute_script reküresif kontrolü
                if ("execute_script".equalsIgnoreCase(commandName)) {
                    for (String script : scriptStack) {
                        if (commandArgs.equals(script)) {
                            throw new ScriptRecursionException();
                        }
                    }
                }
                // Script satırını işle
                commandStatus = processUserInput(commandName, commandArgs);
            } // while sonu

            Interrogator.setUserScanner(tmpScanner);
            Interrogator.setUserMode();

            if (commandStatus == ExitCode.ERROR) {
                console.printError("Script execution aborted due to an error in the script.");
            } else {
                console.println("Script '" + fileName + "' executed successfully.");
            }
            return commandStatus; // Scriptin son durumunu döndür

        } catch (FileNotFoundException exception) {
            console.printError("Script file not found: " + fileName);
            commandStatus = ExitCode.ERROR;
        } catch (NoSuchElementException exception) {
            console.printError(exception.getMessage());
            commandStatus = ExitCode.ERROR;
        } catch (ScriptRecursionException exception) {
            console.printError("Script recursion detected! Aborting script.");
            commandStatus = ExitCode.ERROR;
        } catch (IllegalStateException exception) {
            console.printError("Unexpected error during script execution!");
            commandStatus = ExitCode.ERROR;
        } finally {
            scriptStack.remove(scriptStack.size() - 1);
            Interrogator.setUserMode();
        }
        return commandStatus; // Hata durumunda ERROR döndür
    }
}












//public class Runner {
//
//    public enum ExitCode { OK, ERROR, EXIT }
//
//    private final Console console;
//    private final UDPClient udpClient; //ağ yöneticisi
//    private final List<String> scriptStack = new ArrayList<>();
//    //private static final String SCRIPT_BASE_DIR = System.getProperty("user.dir") +File.separator + "src" + File.separator;
//
//    // CommandManager kaldırıldı, UDPClient eklendi
//    public Runner(Console console, UDPClient udpClient) {
//        this.console = console;
//        this.udpClient=udpClient;
//    }
//
//    /**
//     * Runs the client in interactive mode.
//     */
//    public void interactiveMode() {
//        Scanner scanner = Interrogator.getUserScanner();
//        Interrogator.setUserMode(); //script modunda değiliz
//        try {
//            ExitCode commandStatus;
//            String[] userCommand;
//
//            do {
//                console.ps1();
//                userCommand = (scanner.nextLine().trim() + " ").split(" ", 2);
//                userCommand[1] = userCommand[1].trim();
//                commandStatus = launchCommand(userCommand);
//
//            } while (commandStatus != ExitCode.EXIT);
//        } catch (NoSuchElementException e) {
//            console.printError("No user input detected!");
//        } catch (IllegalStateException e) {
//            console.printError("Unforeseen mistake!");
//        }
//    }
//
//    /**
//     * Runs the client in script mode.
//     * @param fileName Script file name.
//     * @return Exit code.
//     */
//    public ExitCode scriptMode(String fileName) {
//
//        // !!! Bu metodun implementasyonu Lab 6 için yeniden düşünülmeli !!!
//        // İstemci mi scripti satır satır okuyup komut gönderecek,
//        // yoksa sadece execute_script komutunu mu gönderecek?
//        // Görev metni ExecuteScript'in sunucuda çalıştırılmasını ima ediyor gibi.
//        // Şimdilik Lab 5'teki gibi bırakalım, ama execute_script komutunu
//        // sunucuya gönderecek şekilde launchCommand'ı uyarlayacağız.
//
//        String[] userCommand = {"", ""};
//        ExitCode commandStatus;
//        scriptStack.add(fileName);
//
//        //fileName = SCRIPT_BASE_DIR + fileName;
//
//        try (Scanner scriptScanner = new Scanner(new File(fileName))){
//            if (!scriptScanner.hasNext()){
//                throw new NoSuchElementException();
//            }
//            Scanner tmpScanner = Interrogator.getUserScanner();
//            Interrogator.setUserScanner(scriptScanner);
//            Interrogator.setFileMode();
//
//
//        }
//        if(!new File(fileName).exists()){
//            fileName = "../" + fileName;
//        }
//        try (Scanner scriptScanner = new Scanner(new File(fileName))) {
//            if(!scriptScanner.hasNext()) throw new NoSuchElementException();
//            Scanner tempScanner = Interrogator.getUserScanner();
//            Interrogator.setUserScanner(scriptScanner);
//            Interrogator.setFileMode();
//

//            do {
//                String input = scriptScanner.nextLine().trim();
//                commandArgs = (input + " ").split(" ", 2);
//                commandArgs[1] = commandArgs[1].trim();
//                while(scriptScanner.hasNextLine() && commandArgs[0].isEmpty()){
//                    input = scriptScanner.nextLine().trim();
//                    commandArgs = (input + " ").split(" ", 2);
//                    commandArgs[1] = commandArgs[1].trim();
//                }
//                console.println(console.getPS1() + input);
//                if(commandArgs[0].equals("execute_script")){
//                    for(String script : scriptStack){
//                        if(commandArgs[1].equals(script)) throw new ScriptRecursionException();
//                        // Bunu düzeltmek için, script dosyasının zaten scriptStack içindeyse
//                        // tekrar çalıştırılmamasını sağlamanız gerekir.
//                    }
//                }
//                code = launchCommand(commandArgs);
//            } while(code == ExitCode.OK && scriptScanner.hasNextLine());
//
//            Interrogator.setUserScanner(tempScanner);
//            Interrogator.setUserMode();
//
//            if(code == ExitCode.ERROR && !(commandArgs[0].equals("execute_script") && !commandArgs[1].isEmpty())){
//                console.println("Check the script to make sure the data is correct!");
//            }
//            return code;
//        } catch(FileNotFoundException e) {
//            console.printError("Script file not found!");
//        } catch(NoSuchElementException e) {
//            console.printError("The script file is empty!");
//        } catch(ScriptRecursionException e) {
//            console.printError("Scripts cannot be called recursively!");
//        } catch(IllegalStateException e) {
//            console.printError("Unforeseen mistake!");
//            System.exit(0);
//        } finally {
//            if (!scriptStack.isEmpty()) scriptStack.remove(scriptStack.size() - 1);
//        }
//        return ExitCode.ERROR;
//    }
//
//    private ExitCode launchCommand(String[] commandArgs) {
//        if(commandArgs[0].isEmpty()) return ExitCode.OK;
//        var command = commandManager.getCommands().get(commandArgs[0]);
//        if(command == null){
//            console.printError("Command '" + commandArgs[0] + "' not found. Type 'help' for help");
//            return ExitCode.ERROR;
//        }
//        switch(commandArgs[0]){
//            case "exit":
//                if(!command.apply(commandArgs)) return ExitCode.ERROR;
//                else return ExitCode.EXIT;
//            case "execute_script":
//                if(!command.apply(commandArgs)) return ExitCode.ERROR;
//                else return scriptMode(commandArgs[1]);
//            default:
//                if(!command.apply(commandArgs)) return ExitCode.ERROR;
//        }
//        return ExitCode.OK;
//    }
//}