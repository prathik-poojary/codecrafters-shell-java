import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static String findCmd(String cmd) {
        String path = System.getenv("PATH");

        String[] dirs = path.split(":");

        for (String dir : dirs) {
            File file = new File(dir, cmd);

            if (file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }

    public static List<String> parseCmd(String input) {
        List<String> parts = new ArrayList<>();
        StringBuilder curr = new StringBuilder();

        boolean isSingleQuotes = false;
        boolean isDoubleQuotes = false;

        boolean escaped = false;

        for (char c : input.toCharArray()) {
            if (escaped) {
                curr.append(c);
                escaped = false;
            } else if (c == '\\' && !isSingleQuotes && !isDoubleQuotes) {
                escaped = true;
            } else if (c == '\'' && !isDoubleQuotes) {
                isSingleQuotes = !isSingleQuotes;
            } else if (c == '"' && !isSingleQuotes) {
                isDoubleQuotes = !isDoubleQuotes;
            } else if (c == ' ' && !isSingleQuotes && !isDoubleQuotes) {
                if (curr.length() > 0) {
                    parts.add(curr.toString());
                    curr.setLength(0);
                }
            } else {
                curr.append(c);
            }
        }

        if (curr.length() > 0) {
            parts.add(curr.toString());
        }

        return parts;
    }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String currDir = System.getProperty("user.dir");
        while (true) {
            System.out.print("$ ");
            String input = sc.nextLine();
            if (input.equals("exit")) {
                break;
            }

            List<String> parts = parseCmd(input);

            if (parts.get(0).equals("echo")) {
                for (int i = 1; i < parts.size(); i++) {
                    System.out.print(parts.get(i));

                    if (i != parts.size() - 1) {
                        System.out.print(" ");
                    }
                }

                System.out.println();
            } else if (parts.get(0).equals("type")) {
                String cmd = parts.get(1);

                if (cmd.equals("echo")
                        || cmd.equals("exit")
                        || cmd.equals("type")
                        || cmd.equals("pwd")) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String loc = findCmd(cmd);

                    if (loc != null) {
                        System.out.println(cmd + " is " + loc);
                    } else {
                        System.out.println(cmd + ": not found");
                    }
                }
            } else if (parts.get(0).equals("pwd")) {
                System.out.println(currDir);
            } else if (parts.get(0).equals("cd")) {
                File dir;
                if (parts.get(1).equals("~")) {
                    dir = new File(System.getenv("HOME"));
                } else if (new File(parts.get(1)).isAbsolute()) {
                    dir = new File(parts.get(1));
                } else {
                    dir = new File(currDir, parts.get(1));
                }

                if (dir.exists() && dir.isDirectory()) {
                    try {
                        currDir = dir.getCanonicalPath();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println("cd: " + parts.get(1) + ": No such file or directory");
                }
            } else {
                String exec = findCmd(parts.get(0));
                if (exec != null) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(parts);
                        pb.inheritIO();
                        Process process = pb.start();
                        process.waitFor();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    System.out.println(input + ": command not found");
                }
            }
        }
    }
}