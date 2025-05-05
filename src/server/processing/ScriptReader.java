package server.processing; // Veya server.processing

import common.exceptions.IncorrectInputInScriptException;
import common.exceptions.InvalidFormException;
import common.models.*; // Modelleri import et

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Scanner;

/**
 * Helper class to read and validate data for object creation from a script Scanner.
 */
public class ScriptReader {
    private final Scanner scanner;

    public ScriptReader(Scanner scanner) {
        this.scanner = scanner;
    }

    /**
     * Reads Ticket data line by line from the script scanner and builds a Ticket object.
     * Mimics the input process of TicketForm but reads from the scanner.
     * @return The constructed Ticket object.
     * @throws IncorrectInputInScriptException If reading fails or data is invalid in script context.
     * @throws InvalidFormException If constructed object data is logically invalid.
     */
    public Ticket readTicket() throws IncorrectInputInScriptException, InvalidFormException {
        String name = readString("Enter Ticket name:");
        Coordinates coordinates = readCoordinates();
        int price = readInt("Enter price (integer > 0):", 0);
        long discount = readLong("Enter discount (long > 0 and <= 100):", 0, 100);
        String comment = readStringOrNull("Enter comment (max 631 chars, or blank):", 631);
        TicketType type = readEnum("Enter Ticket type (" + TicketType.names() + "):", TicketType.class);
        Event event = readEventOptional();

        try {
            // Validate using the static factory method (if available) or constructor logic
            return Ticket.createTicket(name, coordinates, price, discount, comment, type, event);
        } catch (IllegalArgumentException e) {
            throw new InvalidFormException("Invalid Ticket data read from script: " + e.getMessage());
        }
    }

    private Coordinates readCoordinates() throws IncorrectInputInScriptException, InvalidFormException {
        float x = readFloat("Enter coordinate X (float > -661):", -661f);
        float y = readFloat("Enter coordinate Y (float > -493):", -493f);
        try {
            return Coordinates.createCoordinates(x, y);
        } catch (IllegalArgumentException e) {
            throw new InvalidFormException("Invalid Coordinates data read from script: " + e.getMessage());
        }
    }

    private Event readEventOptional() throws IncorrectInputInScriptException, InvalidFormException {
        String createEvent = readYesNo("Create an Event for the Ticket? (yes/no):");
        if ("no".equalsIgnoreCase(createEvent)) {
            return null;
        }
        // Read Event data
        String eventName = readString("Enter Event name:");
        ZonedDateTime eventDate = readDateTimeOptional("Enter Event date (ISO Zoned Date Time or blank):");
        EventType eventType = readEnum("Enter Event type (" + EventType.names() + "):", EventType.class);
        try {
            return Event.createEvent(eventName, eventDate, eventType);
        } catch (IllegalArgumentException e) {
            throw new InvalidFormException("Invalid Event data read from script: " + e.getMessage());
        }
    }

    // --- Helper methods for reading specific types from script ---

    private String readString(String prompt) throws IncorrectInputInScriptException {
        System.out.println("SCRIPT_INPUT: " + prompt); // Simulate prompt for clarity
        String value = safeNextLine();
        if (value.isEmpty()) {
            throw new IncorrectInputInScriptException("Empty input where non-empty string was expected.");
        }
        return value;
    }

    private String readStringOrNull(String prompt, int maxLength) throws IncorrectInputInScriptException, InvalidFormException {
        System.out.println("SCRIPT_INPUT: " + prompt);
        String value = safeNextLine();
        if (value.isEmpty()) return null;
        if (value.length() > maxLength) {
            throw new InvalidFormException("Input exceeds max length of " + maxLength);
        }
        return value;
    }


    private int readInt(String prompt, int lowerBoundExclusive) throws IncorrectInputInScriptException {
        System.out.println("SCRIPT_INPUT: " + prompt);
        String line = safeNextLine();
        try {
            int value = Integer.parseInt(line);
            if (value > lowerBoundExclusive) return value;
            throw new IncorrectInputInScriptException("Integer value must be greater than " + lowerBoundExclusive);
        } catch (NumberFormatException e) {
            throw new IncorrectInputInScriptException("Invalid integer format read from script.");
        }
    }

    private long readLong(String prompt, long lowerBoundExclusive, long upperBoundInclusive) throws IncorrectInputInScriptException {
        System.out.println("SCRIPT_INPUT: " + prompt);
        String line = safeNextLine();
        try {
            long value = Long.parseLong(line);
            if (value > lowerBoundExclusive && value <= upperBoundInclusive) return value;
            throw new IncorrectInputInScriptException("Long value must be > " + lowerBoundExclusive + " and <= " + upperBoundInclusive);
        } catch (NumberFormatException e) {
            throw new IncorrectInputInScriptException("Invalid long format read from script.");
        }
    }

    private float readFloat(String prompt, float lowerBoundExclusive) throws IncorrectInputInScriptException {
        System.out.println("SCRIPT_INPUT: " + prompt);
        String line = safeNextLine();
        try {
            float value = Float.parseFloat(line);
            if (value > lowerBoundExclusive) return value;
            throw new IncorrectInputInScriptException("Float value must be greater than " + lowerBoundExclusive);
        } catch (NumberFormatException e) {
            throw new IncorrectInputInScriptException("Invalid float format read from script.");
        }
    }


    private <T extends Enum<T>> T readEnum(String prompt, Class<T> enumClass) throws IncorrectInputInScriptException {
        System.out.println("SCRIPT_INPUT: " + prompt);
        String line = safeNextLine();
        try {
            return Enum.valueOf(enumClass, line.toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException e) {
            throw new IncorrectInputInScriptException("Invalid enum value '" + line + "' for type " + enumClass.getSimpleName());
        }
    }

    private ZonedDateTime readDateTimeOptional(String prompt) throws IncorrectInputInScriptException {
        System.out.println("SCRIPT_INPUT: " + prompt);
        String line = safeNextLine();
        if (line.isEmpty()) return null;
        try {
            return ZonedDateTime.parse(line); // Assumes ISO format
        } catch (DateTimeParseException e) {
            throw new IncorrectInputInScriptException("Invalid date-time format read from script. Expected ISO Zoned Date Time.");
        }
    }

    private String readYesNo(String prompt) throws IncorrectInputInScriptException {
        System.out.println("SCRIPT_INPUT: " + prompt);
        String line = safeNextLine().toLowerCase(Locale.ENGLISH);
        if ("yes".equals(line) || "no".equals(line)) {
            return line;
        }
        throw new IncorrectInputInScriptException("Expected 'yes' or 'no'.");
    }


    private String safeNextLine() throws IncorrectInputInScriptException {
        try {
            if (!scanner.hasNextLine()) {
                throw new IncorrectInputInScriptException("Script ended unexpectedly while reading input.");
            }
            return scanner.nextLine().trim();
        } catch (NoSuchElementException | IllegalStateException e) {
            throw new IncorrectInputInScriptException("Error reading next line from script.");
        }
    }
}