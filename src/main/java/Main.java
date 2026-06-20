import command.Command;
import command.CommandFactory;
import command.CommandOutput;
import command.functions.Exit;

import state.CurrentPath;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class Main {
    static void main(String[] args) throws Exception {
        CommandFactory commandFactory = new CommandFactory();
        boolean run = true;
        while (run) {
            System.out.print("$ ");
            Scanner scanner = new Scanner(System.in);
            String commandLine = scanner.nextLine();

            String[] arguments;

            boolean inSingleQuotes = false;
            boolean inDoubleQuotes = false;

            boolean isEscaped = false;
            boolean isEscapedConditionally = false;
            List<String> argsList = new ArrayList<>();

            StringBuilder currentArg = new StringBuilder();

            for (char current : commandLine.toCharArray()) {
                if (isEscaped) {
                    currentArg.append(current);
                    isEscaped = false;
                    continue;
                }

                if (isEscapedConditionally) {
                    switch (current) {
                        case '"', '\\':
                            currentArg.append(current);
                            break;
                        default:
                            currentArg.append('\\').append(current);
                    }
                    isEscapedConditionally = false;
                    continue;
                }

                switch (current) {
                    case '\'':
                        if (inDoubleQuotes) {
                            currentArg.append(current);
                        } else {
                            inSingleQuotes = !inSingleQuotes;
                        }
                        break;
                    case ' ':
                        if (inSingleQuotes || inDoubleQuotes) {
                            currentArg.append(current);
                        } else if (!currentArg.isEmpty()) {
                            argsList.add(currentArg.toString());
                            currentArg = new StringBuilder();
                        }
                        break;
                    case '"':
                        if (inSingleQuotes) {
                            currentArg.append(current);
                        } else {
                            inDoubleQuotes = !inDoubleQuotes;
                        }
                        break;
                    case '\\':
                        if (inSingleQuotes) {
                            currentArg.append(current);
                        } else if (inDoubleQuotes) {
                            isEscapedConditionally = true;
                        } else {
                            isEscaped = true;
                        }
                        break;
                    default:
                        currentArg.append(current);
                }
            }

            argsList.add(currentArg.toString());

            arguments = argsList.toArray(new String[0]);

            String[] execArgs = Arrays.copyOfRange(arguments, 1, arguments.length);

            Optional<Command> command = commandFactory.getCommandByName(arguments[0]);

            OutputStream outStd = System.out;
            OutputStream outErr = System.err;

            if (argsList.size() > 1) {
                String secondToLastArg = argsList.get(argsList.size() - 2);

                if (">".equals(secondToLastArg)
                        || "1>".equals(secondToLastArg)
                        || ">>".equals(secondToLastArg)
                        || "1>>".equals(secondToLastArg)) {
                    Path outputFilePath = Path.of(argsList.getLast());

                    boolean append = ">>".equals(secondToLastArg) || "1>>".equals(secondToLastArg);

                    if (!outputFilePath.isAbsolute()) {
                        outputFilePath = Path.of(
                                CurrentPath.INSTANCE.getPath().toString(),
                                argsList.getLast());
                    }

                    if (!Files.exists(outputFilePath)) {
                        Files.createFile(outputFilePath);
                    }

                    outStd = new FileOutputStream(outputFilePath.toFile(), append);

                    execArgs = Arrays.copyOfRange(arguments, 1, arguments.length - 2);
                }

                if ("2>".equals(secondToLastArg)) {
                    Path outputFilePath = Path.of(argsList.getLast());

                    if (!outputFilePath.isAbsolute()) {
                        outputFilePath = Path.of(
                                CurrentPath.INSTANCE.getPath().toString(),
                                argsList.getLast());
                    }

                    if (!Files.exists(outputFilePath)) {
                        Files.createFile(outputFilePath);
                    }

                    outErr = new FileOutputStream(outputFilePath.toFile());

                    execArgs = Arrays.copyOfRange(arguments, 1, arguments.length - 2);
                }
            }

            if (command.isEmpty()) {
                System.err.printf("%s: command not found%n", arguments[0]);
            } else if (command.get() instanceof Exit) {
                run = false;
            } else {
                CommandOutput output = command.get().execute(execArgs);

                try (InputStream std = output.standard()) {
                    std.transferTo(outStd);
                } finally {
                    if (!outStd.equals(System.out)) {
                        outStd.close();
                    }
                }

                try (InputStream err = output.error()) {
                    err.transferTo(outErr);
                } finally {
                    if (!outErr.equals(System.err)) {
                        outErr.close();
                    }
                }
            }
        }
    }
}