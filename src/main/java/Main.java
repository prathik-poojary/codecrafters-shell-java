import java.io.File;
import java.util.Scanner;

public class Main {

    public static String findExecutable(String command) {

        String pathEnv = System.getenv("PATH");

        if (pathEnv == null) {
            return null;
        }

        String[] paths = pathEnv.split(File.pathSeparator);

        for (String path : paths) {

            File file = new File(path, command);

            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    public static java.util.List<String> parseCommand(String input) {

        java.util.List<String> tokens = new java.util.ArrayList<>();

        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {

            char c = input.charAt(i);

            // Backslash escaping outside quotes
            if (c == '\\' && !inSingleQuotes && !inDoubleQuotes) {

                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            } else if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (inDoubleQuotes && c == '\\') {

                if (i + 1 < input.length()) {

                    char next = input.charAt(i + 1);

                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append('\\');
                    }
                } else {
                    current.append('\\');
                }
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {

                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);

        String currentDirectory = System.getProperty("user.dir");

        while (true) {

            System.out.print("$ ");

            String command = scanner.nextLine();

            boolean stdoutRedirect = false;
            boolean stderrRedirect = false;
            boolean stdoutAppend = false;
            boolean stderrAppend = false;

            String outputFile = null;

            java.util.List<String> tokens = parseCommand(command);
            StringBuilder output = new StringBuilder();

            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i).equals(">") || tokens.get(i).equals("1>")) {

                    stdoutRedirect = true;
                    outputFile = tokens.get(i + 1);

                    tokens = new java.util.ArrayList<>(tokens.subList(0, i));

                    break;
                } else if (tokens.get(i).equals(">>") || tokens.get(i).equals("1>>")) {

                    stdoutAppend = true;
                    outputFile = tokens.get(i + 1);

                    tokens = new java.util.ArrayList<>(tokens.subList(0, i));

                    break;
                } else if (tokens.get(i).equals("2>")) {

                    stderrRedirect = true;
                    outputFile = tokens.get(i + 1);

                    tokens = new java.util.ArrayList<>(tokens.subList(0, i));

                    break;
                } else if (tokens.get(i).equals("2>>")) {

                    stderrAppend = true;
                    outputFile = tokens.get(i + 1);

                    tokens = new java.util.ArrayList<>(tokens.subList(0, i));

                    break;
                }
            }

            if (command.equals("exit")) {
                break;
            } else if (command.equals("pwd")) {

                if (stdoutRedirect) {

                    java.nio.file.Files.writeString(
                            java.nio.file.Paths.get(outputFile),
                            currentDirectory + System.lineSeparator());

                } else if (stderrRedirect) {

                    new File(outputFile).createNewFile();
                    System.out.println(currentDirectory);

                } else {

                    System.out.println(currentDirectory);
                }
            } else if (command.startsWith("cd ")) {

                String path = command.substring(3);

                if (path.equals("~")) {

                    currentDirectory = System.getenv("HOME");

                } else {

                    File dir;

                    if (path.startsWith("/")) {
                        dir = new File(path);
                    } else {
                        dir = new File(currentDirectory, path);
                    }

                    if (dir.exists() && dir.isDirectory()) {
                        currentDirectory = dir.getCanonicalPath();
                    } else {
                        System.out.println("cd: " + path + ": No such file or directory");
                    }
                }
            } else if (command.startsWith("type ")) {

                String cmd = command.substring(5);

                if (cmd.equals("echo")
                        || cmd.equals("exit")
                        || cmd.equals("type")
                        || cmd.equals("pwd")
                        || cmd.equals("cd")) {

                    System.out.println(cmd + " is a shell builtin");
                } else {

                    String executablePath = findExecutable(cmd);

                    if (executablePath != null) {
                        System.out.println(cmd + " is " + executablePath);
                    } else {
                        System.out.println(cmd + ": not found");
                    }
                }
            } else if (command.startsWith("echo")) {

                java.util.List<String> parts = tokens;

                output.setLength(0);

                for (int i = 1; i < parts.size(); i++) {

                    if (i > 1) {
                        output.append(" ");
                    }

                    output.append(parts.get(i));
                }

                if (stdoutRedirect) {

                    java.nio.file.Files.writeString(
                            java.nio.file.Paths.get(outputFile),
                            output.toString() + System.lineSeparator());

                } else if (stdoutAppend) {

                    java.nio.file.Files.writeString(
                            java.nio.file.Paths.get(outputFile),
                            output.toString() + System.lineSeparator(),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);

                } else if (stderrRedirect) {

                    new File(outputFile).createNewFile();

                    System.out.println(output);

                } else {

                    System.out.println(output);
                }
            } else {

                java.util.List<String> parts = tokens;

                if (parts.isEmpty()) {
                    continue;
                }

                String executablePath = findExecutable(parts.get(0));

                if (executablePath != null) {

                    java.util.List<String> processArgs = new java.util.ArrayList<>();

                    processArgs.add(parts.get(0));

                    for (int i = 1; i < parts.size(); i++) {
                        processArgs.add(parts.get(i));
                    }

                    ProcessBuilder pb = new ProcessBuilder(processArgs);

                    pb.directory(new File(currentDirectory));

                    if (stdoutRedirect) {

                        pb.redirectOutput(new File(outputFile));
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                    } else if (stdoutAppend) {

                        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(outputFile)));
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                    } else if (stderrRedirect) {

                        pb.redirectError(new File(outputFile));
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

                    } else if (stderrAppend) {

                        pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(outputFile)));

                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

                    } else {

                        pb.inheritIO();
                    }

                    Process process = pb.start();

                    process.waitFor();

                } else {

                    System.out.println(command + ": command not found");
                }
            }
        }

        scanner.close();
    }
}
