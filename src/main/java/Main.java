import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {
    private static final Set<String> BUILTINS = new HashSet<>(
            Arrays.asList("echo", "exit", "type", "pwd", "cd", "jobs"));
    private static int jobCount = 0;

    private static class Job {
        int id;
        Process process;
        String command;
        String status;

        Job(int id, Process process, String command, String status) {
            this.id = id;
            this.process = process;
            this.command = command;
            this.status = status;
        }
    }

    private static final List<Job> bgJobs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine())
                break;
            String input = scanner.nextLine();
            if (input.trim().isEmpty())
                continue;

            List<String> parts = parseArgs(input);
            if (parts.isEmpty())
                continue;

            boolean isBackground = false;
            if (parts.get(parts.size() - 1).equals("&")) {
                isBackground = true;
                parts.remove(parts.size() - 1);
            }

            if (parts.isEmpty())
                continue;

            String stdoutFile = null;
            String stderrFile = null;
            boolean appendStdout = false;
            boolean appendStderr = false;
            int redirectIndex = -1;

            for (int i = 0; i < parts.size(); i++) {
                String token = parts.get(i);
                if ((token.equals(">") || token.equals("1>")) && i + 1 < parts.size()) {
                    stdoutFile = parts.get(i + 1);
                    appendStdout = false;
                    redirectIndex = i;
                    break;
                } else if ((token.equals(">>") || token.equals("1>>")) && i + 1 < parts.size()) {
                    stdoutFile = parts.get(i + 1);
                    appendStdout = true;
                    redirectIndex = i;
                    break;
                } else if (token.equals("2>") && i + 1 < parts.size()) {
                    stderrFile = parts.get(i + 1);
                    appendStderr = false;
                    redirectIndex = i;
                    break;
                } else if (token.equals("2>>") && i + 1 < parts.size()) {
                    stderrFile = parts.get(i + 1);
                    appendStderr = true;
                    redirectIndex = i;
                    break;
                }
            }

            List<String> cmdArgs = (redirectIndex != -1) ? parts.subList(0, redirectIndex) : parts;
            if (cmdArgs.isEmpty())
                continue;
            String command = cmdArgs.get(0);

            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream outStream = originalOut;
            PrintStream errStream = originalErr;

            if (stdoutFile != null) {
                File f = new File(stdoutFile);
                if (f.getParentFile() != null)
                    f.getParentFile().mkdirs();
                outStream = new PrintStream(new FileOutputStream(f, appendStdout));
            }
            if (stderrFile != null) {
                File f = new File(stderrFile);
                if (f.getParentFile() != null)
                    f.getParentFile().mkdirs();
                errStream = new PrintStream(new FileOutputStream(f, appendStderr));
            }

            switch (command) {
                case "exit":
                    if (outStream != originalOut)
                        outStream.close();
                    if (errStream != originalErr)
                        errStream.close();
                    System.exit(cmdArgs.size() > 1 ? Integer.parseInt(cmdArgs.get(1)) : 0);
                    break;
                case "echo":
                    String[] echoArgs = cmdArgs.subList(1, cmdArgs.size()).toArray(new String[0]);
                    outStream.println(String.join(" ", echoArgs));
                    break;
                case "type":
                    handleType(cmdArgs.size() > 1 ? cmdArgs.get(1) : "", outStream);
                    break;
                case "pwd":
                    outStream.println(System.getProperty("user.dir"));
                    break;
                case "cd":
                    handleCd(cmdArgs.size() > 1 ? cmdArgs.get(1) : "", errStream);
                    break;
                case "jobs":
                    for (Job job : bgJobs) {
                        if (job.status.equals("Running") && !job.process.isAlive()) {
                            job.status = "Done";
                        }
                    }

                    int totalJobs = bgJobs.size();
                    for (int i = 0; i < totalJobs; i++) {
                        Job job = bgJobs.get(i);
                        char marker = ' ';
                        if (i == totalJobs - 1) {
                            marker = '+';
                        } else if (i == totalJobs - 2) {
                            marker = '-';
                        }

                        if (job.status.equals("Done")) {
                            outStream.printf(
                                    "[%d]%c  %-24s%s%n", job.id, marker, job.status, job.command);
                        } else {
                            outStream.printf(
                                    "[%d]%c  %-24s%s &%n", job.id, marker, job.status, job.command);
                        }
                    }

                    bgJobs.removeIf(job -> job.status.equals("Done"));
                    break;
                default:
                    runExternal(
                            cmdArgs,
                            stdoutFile,
                            appendStdout,
                            stderrFile,
                            appendStderr,
                            isBackground);
                    break;
            }

            if (outStream != originalOut)
                outStream.close();
            if (errStream != originalErr)
                errStream.close();
        }
    }

    private static List<String> parseArgs(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false, inDoubleQuote = false, escaped = false, hasToken = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingleQuote) {
                if (c == '\'')
                    inSingleQuote = false;
                else
                    current.append(c);
            } else if (escaped) {
                current.append(c);
                hasToken = true;
                escaped = false;
            } else if (inDoubleQuote) {
                if (c == '\\'
                        && i + 1 < input.length()
                        && (input.charAt(i + 1) == '"' || input.charAt(i + 1) == '\\')) {
                    current.append(input.charAt(++i));
                } else if (c == '"') {
                    inDoubleQuote = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\\') {
                    escaped = true;
                    hasToken = true;
                } else if (c == '\'') {
                    inSingleQuote = true;
                    hasToken = true;
                } else if (c == '"') {
                    inDoubleQuote = true;
                    hasToken = true;
                } else if (c == ' ' || c == '\t') {
                    if (hasToken || current.length() > 0) {
                        args.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                } else {
                    current.append(c);
                    hasToken = true;
                }
            }
        }
        if (hasToken || current.length() > 0)
            args.add(current.toString());
        return args;
    }

    private static void handleType(String cmd, PrintStream outStream) {
        if (cmd.isEmpty())
            return;
        if (BUILTINS.contains(cmd)) {
            outStream.println(cmd + " is a shell builtin");
            return;
        }
        String found = findInPath(cmd);
        outStream.println(found != null ? cmd + " is " + found : cmd + ": not found");
    }

    private static void handleCd(String path, PrintStream errStream) {
        if (path.isEmpty() || path.equals("~")) {
            path = System.getenv("HOME");
        }

        File dir = new File(path);
        // If the path is relative, evaluate it starting from our tracked directory
        // context
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), path);
        }

        dir = dir.getAbsoluteFile();

        if (dir.exists() && dir.isDirectory()) {
            try {
                // Canonical path resolves '.' and '..' cleanly
                System.setProperty("user.dir", dir.getCanonicalPath());
            } catch (IOException e) {
                System.setProperty("user.dir", dir.getAbsolutePath());
            }
        } else {
            errStream.println("cd: " + path + ": No such file or directory");
        }
    }

    private static void runExternal(
            List<String> parts,
            String stdoutFile,
            boolean appendStdout,
            String stderrFile,
            boolean appendStderr,
            boolean isBackground)
            throws IOException, InterruptedException {
        String execPath = findInPath(parts.get(0));
        if (execPath == null) {
            System.out.println(parts.get(0) + ": command not found");
            return;
        }
        List<String> cmd = new ArrayList<>(
                Arrays.asList(
                        "/bin/bash",
                        "-c",
                        "exec -a \"$1\" \"$2\" \"${@:3}\"",
                        "_",
                        parts.get(0),
                        execPath));
        for (int i = 1; i < parts.size(); i++)
            cmd.add(parts.get(i));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (stdoutFile != null) {
            File f = new File(stdoutFile);
            if (f.getParentFile() != null)
                f.getParentFile().mkdirs();
            pb.redirectOutput(
                    appendStdout
                            ? ProcessBuilder.Redirect.appendTo(f)
                            : ProcessBuilder.Redirect.to(f));
        } else
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        if (stderrFile != null) {
            File f = new File(stderrFile);
            if (f.getParentFile() != null)
                f.getParentFile().mkdirs();
            pb.redirectError(
                    appendStderr
                            ? ProcessBuilder.Redirect.appendTo(f)
                            : ProcessBuilder.Redirect.to(f));
        } else
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = pb.start();
        if (isBackground) {
            jobCount++;
            System.out.println("[" + jobCount + "] " + process.pid());

            String fullCmd = String.join(" ", parts);
            bgJobs.add(new Job(jobCount, process, fullCmd, "Running"));
        } else {
            process.waitFor();
        }
    }

    private static String findInPath(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty())
            return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File file = new File(dir, cmd);
            if (file.isFile() && file.canExecute())
                return file.getAbsolutePath();
        }
        return null;
    }
}