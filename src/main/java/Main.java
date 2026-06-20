package commands;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Main {
    public static List<String> parseArgs(String input) {
        final Pattern commandPattern = Pattern.compile("^\\s*(\\S+)");

        final Pattern space = Pattern.compile("\\s+");
        final Pattern word = Pattern.compile("([^\\s'\"]+)|('[^']*')|(\"[^\"]*\")");

        final List<String> args = new ArrayList<>();

        final Matcher matcher = commandPattern.matcher(input);
        if (!matcher.lookingAt()) {
            return args;
        }

        args.add(matcher.group(1));

        final int end = input.length();
        int currentEnd = matcher.end();

        while (matcher.usePattern(space).region(currentEnd, end).lookingAt()) {
            currentEnd = matcher.end();

            final StringBuilder sb = new StringBuilder();
            while (matcher.usePattern(word).region(currentEnd, end).lookingAt()) {
                if (matcher.group(1) != null) {
                    sb.append(matcher.group(1));
                } else if (matcher.group(2) != null) {
                    sb.append(matcher.group(2).replace("'", ""));
                } else if (matcher.group(3) != null) {
                    sb.append(matcher.group(3).replace("\"", ""));
                }
                currentEnd = matcher.end();
            }
            args.add(sb.toString());
        }

        return args;
    }

    public static Command parseCommand(String command) {
        return switch (command) {
            case "cd" -> new Cd();
            case "echo" -> new Echo();
            case "exit" -> new Exit();
            case "pwd" -> new Pwd();
            case "type" -> new Type();
            default -> {
                final String[] paths = System.getenv("PATH").split(":");
                for (final String path : paths) {
                    final File file = new File(path, command);
                    if (file.canExecute()) {
                        yield new Program(file);
                    }
                }

                yield new CommandNotFound();
            }
        };
    }
}