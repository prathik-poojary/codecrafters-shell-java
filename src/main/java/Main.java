import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        boolean exit = false;
        Scanner scanner = new Scanner(System.in);
        String cwd = System.getProperty("user.dir");
        while (!exit) {
            System.out.print("$ ");
            String input = scanner.nextLine();

            String[] words = input.split(" ");
            String command = words[0];
            String[] rest = Arrays.copyOfRange(words, 1, words.length);
            String result = String.join(" ", rest);

            if (Objects.equals(command, "exit"))
                exit = true;
            else if (Objects.equals(command, "echo"))
                System.out.println(result);
            else if (Objects.equals(command, "type"))
                System.out.println(type(result));
            else if (Objects.equals(command, "pwd"))
                System.out.println(cwd);
            else if (Objects.equals(command, "cd")) {
                File file = new File(result);
                if (file.isDirectory() && file.isAbsolute())
                    cwd = file.getAbsolutePath();
                else
                    System.out.println("cd: " + result + ": No such file or directory");
            } else if (getExecutable(command) != null) {
                Process process = Runtime.getRuntime().exec(input.split(" "));
                process.getInputStream().transferTo(System.out);
            } else
                System.out.println(input + ": command not found");
        }
        scanner.close();
    }

    public static String type(String command) {
        String[] commands = { "exit", "echo", "type", "pwd", "cd" };
        String path_commands = System.getenv("PATH");
        String[] path_command = path_commands.split(":");
        for (String text : commands) {
            if (Objects.equals(text, command))
                return command + " is a shell builtin";
        }
        for (String path : path_command) {
            File file = new File(path, command);
            if (file.exists() && file.canExecute()) {
                return command + " is " + file.getAbsolutePath();
            }
        }
        return command + ": not found";
    }

    public static String getExecutable(String command) {
        String path_commands = System.getenv("PATH");
        String[] path_command = path_commands.split(":");
        for (String path : path_command) {
            File file = new File(path, command);
            if (file.exists() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }
}