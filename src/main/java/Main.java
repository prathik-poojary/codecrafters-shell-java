import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        String currentDirectory = Paths.get("").toAbsolutePath().normalize().toString();

        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine();

            if (input.isEmpty()) {
                continue;
            }

            String[] parts = input.split(" ");
            String command = parts[0];

            if (command.equals("exit")) {
                break;
            } else if (command.equals("echo")) {
                System.out.println(input.substring(5));
            } else if (command.equals("pwd")) {
                System.out.println(currentDirectory);
            } else if (command.equals("cd")) {
                String targetDir = input.substring(3).trim();
                Path resolvedPath = Paths.get(currentDirectory).resolve(targetDir).normalize();

                if (Files.exists(resolvedPath) && Files.isDirectory(resolvedPath)) {
                    currentDirectory = resolvedPath.toString();
                } else {
                    System.out.println("cd: " + targetDir + ": No such file or directory");
                }
            } else if (command.equals("type")) {
                String cmd = input.substring(5);
                if (cmd.equals("echo")
                        || cmd.equals("exit")
                        || cmd.equals("type")
                        || cmd.equals("pwd")
                        || cmd.equals("cd")) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String path = getPath(cmd);
                    if (path != null) {
                        System.out.println(cmd + " is " + path);
                    } else {
                        System.out.println(cmd + ": not found");
                    }
                }
            } else {
                String path = getPath(command);
                if (path != null) {
                    ProcessBuilder pb = new ProcessBuilder(parts);
                    pb.directory(new File(currentDirectory));
                    pb.inheritIO();
                    Process process = pb.start();
                    process.waitFor();
                } else {
                    System.out.println(input + ": command not found");
                }
            }
        }
    }

    private static String getPath(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] paths = pathEnv.split(File.pathSeparator);
            for (String p : paths) {
                Path filePath = Paths.get(p, cmd);
                if (Files.isRegularFile(filePath) && Files.isExecutable(filePath)) {
                    return filePath.toString();
                }
            }
        }
        return null;
    }
}