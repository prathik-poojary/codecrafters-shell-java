package shell;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import shell.command.Command;
import shell.command.Commands;

public class InputExecutor {

    public void excute(String input) {
        if (input == null || input.isEmpty()) {
            return;
        }
        List<String> inputs = parseInput(input);
        if (Commands.isCommands(inputs.getFirst())) {
            Optional<Command> findCommand = Commands.findCommand(inputs.getFirst());
            if (findCommand.isPresent()) {
                findCommand.get().execute(inputs);
                return;
            }
        }
        File executablePath = FileManager.findFile(inputs.getFirst());
        if (executablePath != null) {
            ProcessBuilder pb = new ProcessBuilder(inputs);
            pb.inheritIO();
            try {
                Process process = pb.start();
                process.waitFor();
            } catch (Exception _) {
            }
            return;
        }
        System.out.println(inputs.getFirst() + ": command not found");
    }

    public List<String> parseInput(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder currentArg = new StringBuilder();
        boolean insideSingleQuote = false;

        for (char c : input.toCharArray()) {
            if (c == '\'') {
                insideSingleQuote = !insideSingleQuote;
            } else if (c == ' ' && !insideSingleQuote) {
                if (!currentArg.isEmpty()) {
                    args.add(currentArg.toString());
                    currentArg = new StringBuilder();
                }
            } else {
                currentArg.append(c);
            }
        }

        if (!currentArg.isEmpty()) {
            args.add(currentArg.toString());
        }
        return args;
    }
}
