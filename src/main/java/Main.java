import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Main {

    private static Optional<Path> getValidPath(String input) {

        String[] path = Optional.ofNullable(System.getenv("PATH")).orElse("").split(":");
        Optional<Path> filePath;

        for (String dir : path) {

            filePath = getFile(input, dir);

            if (filePath.isPresent())
                return filePath;
        }

        return Optional.empty();
    }

    private static Optional<Path> getFile(final String fileName, final String dir) {

        Path path = Paths.get(dir, fileName);

        return path.toFile().exists() && path.toFile().canExecute()
                ? Optional.of(path)
                : Optional.empty();
    }

    public static void executeScript(Path pathToScript, List<String> tokens) {

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(tokens);
        processBuilder.directory(new File(pathToScript.getParent().toString()));
        processBuilder.inheritIO();

        try {
            processBuilder.start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {

        Set<String> builtinCommands = new HashSet<>();
        fillBuiltinCommands(builtinCommands);

        Path currpath = Paths.get("");
        Scanner scanner = new Scanner(System.in);

        while (true) {

            System.out.print("$ ");
            String input = scanner.nextLine();

            List<String> tokens = tokenize(input);
            String cmd = tokens.getFirst();
            String firstArg = tokens.size() > 1 ? tokens.get(1) : null;

            switch (cmd) {
                case "exit" -> System.exit(0);
                case "echo" -> echo(tokens);
                case "type" -> type(builtinCommands, firstArg);
                case "pwd" -> System.out.println(currpath.toAbsolutePath());
                case "cd" -> currpath = changeDirectory(firstArg, currpath);
                default -> execute(tokens);
            }
        }
    }

    private static void echo(List<String> tokens) {

        for (int i = 1; i < tokens.size(); i++) {
            System.out.print(tokens.get(i));
            if (i < tokens.size() - 1)
                System.out.print(" ");
        }

        System.out.println();
    }

    private static List<String> tokenize(String rawInput) {

        String input = rawInput.strip().replaceAll("\"\"|''", "");
        StringBuilder builder = new StringBuilder();
        List<String> tokens = new ArrayList<>();

        for (int i = 0, next; i < input.length(); i++) {
            switch (input.charAt(i)) {
                case '\'', '\"' -> {
                    next = findNext(input, i);
                    tokens.add(input.substring(i + 1, next));
                    i = next;
                }
                case '\\' -> builder.append(input.charAt(++i));
                case ' ' -> {
                    if (builder.isEmpty())
                        continue;

                    tokens.add(builder.toString());
                    builder = new StringBuilder();
                }
                default -> builder.append(input.charAt(i));
            }
        }

        if (!builder.isEmpty())
            tokens.add(builder.toString());
        return tokens;
    }

    private static int findNext(final String input, final int index) {

        char target = input.charAt(index);

        for (int i = index + 1; i < input.length(); i++)
            if (target == input.charAt(i))
                return i;

        return -1;
    }

    private static void fillBuiltinCommands(Set<String> builtinCommands) {
        builtinCommands.add("exit");
        builtinCommands.add("echo");
        builtinCommands.add("type");
        builtinCommands.add("pwd");
        builtinCommands.add("cd");
    }

    private static void execute(List<String> tokens) {

        getValidPath(tokens.getFirst())
                .ifPresentOrElse(
                        path -> executeScript(path, tokens),
                        () -> System.err.printf("%s: command not found%n", tokens.getFirst()));
    }

    private static void type(Set<String> st, String argument) {

        if (st.contains(argument)) {
            System.out.printf("%s is a shell builtin%n", argument);
            return;
        }

        Optional<Path> path = getValidPath(argument);

        if (path.isPresent()) {
            System.out.printf("%s is %s%n", argument, path.get());
            return;
        }

        System.out.printf("%s: not found%n", argument);
    }

    private static Path changeDirectory(String arguments, Path currpath) {

        if (arguments == null)
            return currpath;
        if ("~".equals(arguments.strip()))
            arguments = System.getenv("HOME");

        Path newPath = currpath.resolve(arguments).toAbsolutePath().normalize();
        if (newPath.toFile().exists()) {
            currpath = newPath;
        } else {
            System.out.printf("cd: %s: No such file or directory%n", newPath.toAbsolutePath());
        }
        return currpath;
    }
}