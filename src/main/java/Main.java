import java.io.File;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        String path = System.getenv("PATH");
        String[] pathDirs = path.split(":");

        while (true) {
            System.out.print("$ ");
            String command = sc.nextLine();

            if (command.equals("exit")) {
                break;
            } else if (command.startsWith("echo")) {
                System.out.println(command.substring(5));
            } else if (command.startsWith("type")) {
                String typeArg = command.substring(5);
                System.out.println(type(typeArg));
            } else {
                System.out.println(command + ": command not found");
            }
        }

        sc.close();
    }

    public static String type(String command) {
        String[] commands = { "exit", "echo", "type" };
        String path = System.getenv("PATH");
        String[] pathDirs = path.split(":");

        boolean isBuiltIn = false;
        for (int i = 0; i < commands.length; i++) {
            if (commands[i].equals(command)) {
                return command + " is a shell builtin";
            }
        }

        for (int i = 0; i < pathDirs.length; i++) {
            File file = new File(pathDirs[i], command);
            if (file.exists() && file.canExecute()) {
                return command + " is " + file.getAbsolutePath();
            }
        }

        return command + ": not found";
    }
}