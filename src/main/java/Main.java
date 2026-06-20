import java.io.*;
import java.util.*;

public class Main {
    static class Job {
        int number;
        long pid;
        String command;
        Process process;

        Job(int number, long pid, String command, Process process) {
            this.number = number;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        String currentDir = System.getProperty("user.dir");
        int jobCounter = 0;
        List<Job> jobs = new ArrayList<>();

        while (true) {
            System.out.print("$ ");
            String input = sc.nextLine();

            if (input.equals("exit")) {
                break;
            } else {
                List<String> tokens = tokenize(input);
                if (tokens.isEmpty()) {
                    continue;
                }

                String outputFile = null;
                String errorFile = null;
                int redirectIndex = -1;
                boolean appendOutput = false;
                boolean appendError = false;
                for (int i = 0; i < tokens.size(); i++) {
                    String t = tokens.get(i);
                    if (t.equals(">") || t.equals("1>")) {
                        redirectIndex = i;
                        if (i + 1 < tokens.size()) {
                            outputFile = tokens.get(i + 1);
                        }
                        appendOutput = false;
                        break;
                    }

                    if (t.equals(">>") || t.equals("1>>")) {
                        redirectIndex = i;

                        if (i + 1 < tokens.size()) {
                            outputFile = tokens.get(i + 1);
                        }

                        appendOutput = true;
                        break;
                    }

                    if (t.equals("2>")) {
                        redirectIndex = i;
                        if (i + 1 < tokens.size()) {
                            errorFile = tokens.get(i + 1);
                        }
                        appendError = false;
                        break;
                    }

                    if (t.equals("2>>")) {
                        redirectIndex = i;
                        if (i + 1 < tokens.size()) {
                            errorFile = tokens.get(i + 1);
                        }
                        appendError = true;
                        break;
                    }
                }

                if (redirectIndex != -1) {
                    tokens = new ArrayList<>(tokens.subList(0, redirectIndex));
                }

                if (tokens.isEmpty()) {
                    continue;
                }

                boolean background = false;
                if (tokens.get(tokens.size() - 1).equals("&")) {
                    background = true;
                    tokens = new ArrayList<>(tokens.subList(0, tokens.size() - 1));
                }

                String jobCommandText = String.join(" ", tokens);

                if (tokens.isEmpty()) {
                    continue;
                }

                String cmd = tokens.get(0);

                PrintStream originalOut = System.out;
                PrintStream fileOut = null;

                PrintStream originalErr = System.err;
                PrintStream fileErr = null;

                try {
                    if (outputFile != null) {
                        java.io.File outFile = new java.io.File(outputFile);
                        if (!outFile.isAbsolute()) {
                            outFile = new java.io.File(currentDir, outputFile);
                        }
                        fileOut = new PrintStream(new FileOutputStream(outFile, appendOutput));
                        System.setOut(fileOut);
                    }

                    if (errorFile != null) {
                        File errFile = new File(errorFile);
                        if (!errFile.isAbsolute()) {
                            errFile = new File(currentDir, errorFile);
                        }

                        fileErr = new PrintStream(new FileOutputStream(errFile, appendError));
                        System.setErr(fileErr);
                    }

                    if (cmd.equals("echo")) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 1; i < tokens.size(); i++) {
                            if (i > 1)
                                sb.append(" ");
                            sb.append(tokens.get(i));
                        }
                        System.out.println(sb.toString());
                    } else if (cmd.equals("type")) {
                        String rem = tokens.size() > 1 ? tokens.get(1) : "";
                        if (rem.equals("echo")
                                || rem.equals("exit")
                                || rem.equals("type")
                                || rem.equals("pwd")
                                || rem.equals("cd")
                                || rem.equals("jobs")) {
                            System.out.println(rem + " is a shell builtin");
                        } else {
                            String pathEnv = System.getenv("PATH");
                            String[] paths = pathEnv.split(java.io.File.pathSeparator);

                            boolean found = false;
                            for (String path : paths) {
                                java.io.File file = new java.io.File(path, rem);

                                if (file.exists() && file.canExecute()) {
                                    System.out.println(rem + " is " + file.getAbsolutePath());
                                    found = true;
                                    break;
                                }
                            }
                            if (!found)
                                System.out.println(rem + ": not found");
                        }
                    } else if (cmd.equals("pwd")) {
                        System.out.println(currentDir);
                    } else if (cmd.equals("jobs")) {
                        for (int i = 0; i < jobs.size(); i++) {
                            Job job = jobs.get(i);
                            String marker;
                            if (i == jobs.size() - 1) {
                                marker = "+";
                            } else if (i == jobs.size() - 2) {
                                marker = "-";
                            } else {
                                marker = " ";
                            }

                            boolean done = !job.process.isAlive();
                            String status = done ? "Done" : "Running";
                            String displayCommand = done ? job.command : (job.command + " &");
                            String statusField = String.format("%-24s", status);
                            System.out.println(
                                    "["
                                            + job.number
                                            + "]"
                                            + marker
                                            + "  "
                                            + statusField
                                            + displayCommand);
                        }
                        jobs.removeIf(job -> !job.process.isAlive());
                    } else if (cmd.equals("cd")) {
                        String targetDir = tokens.size() > 1 ? tokens.get(1) : "";

                        if (targetDir.equals("~"))
                            targetDir = System.getenv("HOME");

                        java.io.File dir;

                        if (targetDir.startsWith("/")) {
                            dir = new java.io.File(targetDir);
                        } else {
                            dir = new java.io.File(currentDir, targetDir);
                        }

                        try {
                            String resolvedPath = dir.getCanonicalPath();
                            java.io.File resolvedDir = new java.io.File(resolvedPath);

                            if (resolvedDir.exists() && resolvedDir.isDirectory()) {
                                currentDir = resolvedPath;
                            } else {
                                System.err.println(
                                        "cd: " + targetDir + ": No such file or directory");
                            }
                        } catch (java.io.IOException e) {
                            System.err.println("cd: " + targetDir + ": No such file or directory");
                        }
                    } else {
                        String pathEnv = System.getenv("PATH");
                        String[] paths = pathEnv.split(java.io.File.pathSeparator);

                        java.io.File executable = null;
                        for (String path : paths) {
                            java.io.File file = new java.io.File(path, cmd);

                            if (file.exists() && file.canExecute()) {
                                executable = file;
                                break;
                            }
                        }

                        if (executable != null) {
                            ProcessBuilder pb = new ProcessBuilder(tokens);
                            pb.directory(new java.io.File(currentDir));

                            if (outputFile != null) {
                                java.io.File outFile = new java.io.File(outputFile);
                                if (!outFile.isAbsolute()) {
                                    outFile = new java.io.File(currentDir, outputFile);
                                }
                                if (appendOutput) {
                                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
                                } else {
                                    pb.redirectOutput(outFile);
                                }
                            } else {
                                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                            }

                            if (errorFile != null) {
                                java.io.File errFile = new java.io.File(errorFile);
                                if (!errFile.isAbsolute()) {
                                    errFile = new java.io.File(currentDir, errorFile);
                                }
                                if (appendError) {
                                    pb.redirectError(ProcessBuilder.Redirect.appendTo(errFile));
                                } else {
                                    pb.redirectError(errFile);
                                }
                            } else {
                                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                            }

                            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                            Process process = pb.start();
                            if (background) {
                                jobCounter++;
                                jobs.add(
                                        new Job(
                                                jobCounter,
                                                process.pid(),
                                                jobCommandText,
                                                process));
                                System.out.println("[" + jobCounter + "] " + process.pid());
                            } else {
                                process.waitFor();
                            }
                        } else {
                            System.err.println(cmd + ": command not found");
                        }
                    }
                } finally {
                    if (fileOut != null) {
                        System.setOut(originalOut);
                        fileOut.close();
                    }

                    if (fileErr != null) {
                        System.setErr(originalErr);
                        fileErr.close();
                    }
                }
            }
        }
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean tokenStarted = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'')
                    inSingleQuotes = false;
                else
                    current.append(c);
            } else if (inDoubleQuotes) {
                if (c == '"')
                    inDoubleQuotes = false;
                else if (c == '\\'
                        && i + 1 < input.length()
                        && (input.charAt(i + 1) == '"' || input.charAt(i + 1) == '\\')) {
                    i++;
                    current.append(input.charAt(i));
                } else
                    current.append(c);
            } else {
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        i++;
                        current.append(input.charAt(i));
                        tokenStarted = true;
                    }
                } else if (c == '\'') {
                    inSingleQuotes = true;
                    tokenStarted = true;
                } else if (c == ' ' || c == '\t') {
                    if (tokenStarted) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        tokenStarted = false;
                    }
                } else if (c == '"') {
                    inDoubleQuotes = true;
                    tokenStarted = true;
                } else {
                    current.append(c);
                    tokenStarted = true;
                }
            }
        }

        if (tokenStarted)
            tokens.add(current.toString());

        return tokens;
    }
}