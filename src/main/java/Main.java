import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class Main {

    static class Job {
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

    private static List<Job> backgroundJobs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);
        String[] pathDirs = System.getenv("PATH").split(":");

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        InputStream originalIn = System.in;

        while (true) {
            reapCompletedJobs();

            System.out.print("$ ");
            System.out.flush();

            if (!sc.hasNextLine()) {
                break;
            }
            String input = sc.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }

            if (input.contains("|")) {
                handlePipeline(input, pathDirs, originalOut, originalErr, originalIn);
                continue;
            }

            List<String> parts = parseArguments(input);
            if (parts.isEmpty()) {
                continue;
            }

            boolean isBackground = false;
            if (parts.get(parts.size() - 1).equals("&")) {
                isBackground = true;
                parts.remove(parts.size() - 1);
            }

            if (parts.isEmpty()) {
                continue;
            }

            int redirectIndex = -1;
            boolean isStderrRedirection = false;
            boolean isAppend = false;

            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                if (part.equals(">") || part.equals("1>")) {
                    redirectIndex = i;
                    isStderrRedirection = false;
                    isAppend = false;
                    break;
                } else if (part.equals("2>")) {
                    redirectIndex = i;
                    isStderrRedirection = true;
                    isAppend = false;
                    break;
                } else if (part.equals(">>") || part.equals("1>>")) {
                    redirectIndex = i;
                    isStderrRedirection = false;
                    isAppend = true;
                    break;
                } else if (part.equals("2>>")) {
                    redirectIndex = i;
                    isStderrRedirection = true;
                    isAppend = true;
                    break;
                }
            }

            File outputFile = null;
            List<String> commandArgs = parts;

            if (redirectIndex != -1 && redirectIndex + 1 < parts.size()) {
                String outputPath = parts.get(redirectIndex + 1);
                outputFile = new File(outputPath);
                commandArgs = parts.subList(0, redirectIndex);
            }

            if (commandArgs.isEmpty()) {
                continue;
            }

            executeSingleCommand(
                    commandArgs, isBackground, outputFile, isStderrRedirection, isAppend, pathDirs);
            resetStreams(originalOut, originalErr);
        }
    }

    private static void executeSingleCommand(
            List<String> commandArgs,
            boolean isBackground,
            File outputFile,
            boolean isStderrRedirection,
            boolean isAppend,
            String[] pathDirs)
            throws Exception {
        String commandName = commandArgs.get(0);

        if (outputFile != null) {
            FileOutputStream fos = new FileOutputStream(outputFile, isAppend);
            PrintStream fileStream = new PrintStream(fos);
            if (isStderrRedirection) {
                System.setErr(fileStream);
            } else {
                System.setOut(fileStream);
            }
        }

        if (isBuiltin(commandName)) {
            runBuiltin(commandArgs, pathDirs);
        } else {
            File executableFile = null;
            for (String path : pathDirs) {
                File file = new File(path, commandName);
                if (file.exists() && file.canExecute()) {
                    executableFile = file;
                    break;
                }
            }

            if (executableFile != null) {
                ProcessBuilder pb = new ProcessBuilder(commandArgs);
                Map<String, String> env = pb.environment();
                env.put("PATH", executableFile.getParent() + ":" + System.getenv("PATH"));

                if (outputFile != null) {
                    ProcessBuilder.Redirect fileRedirect = isAppend
                            ? ProcessBuilder.Redirect.appendTo(outputFile)
                            : ProcessBuilder.Redirect.to(outputFile);
                    if (isStderrRedirection) {
                        pb.redirectError(fileRedirect);
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    } else {
                        pb.redirectOutput(fileRedirect);
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }
                } else {
                    pb.inheritIO();
                }

                Process process = pb.start();
                if (isBackground) {
                    int jobId = 1;
                    if (!backgroundJobs.isEmpty()) {
                        int maxId = 0;
                        for (Job j : backgroundJobs) {
                            if (j.id > maxId)
                                maxId = j.id;
                        }
                        jobId = maxId + 1;
                    }
                    System.out.println("[" + jobId + "] " + process.pid());
                    StringBuilder cmdBuilder = new StringBuilder();
                    for (int i = 0; i < commandArgs.size(); i++) {
                        cmdBuilder
                                .append(commandArgs.get(i))
                                .append(i < commandArgs.size() - 1 ? " " : "");
                    }
                    backgroundJobs.add(new Job(jobId, process, cmdBuilder.toString(), "Running"));
                } else {
                    process.waitFor();
                }
            } else {
                System.err.println(commandName + ": command not found");
            }
        }
    }

    private static boolean isBuiltin(String cmd) {
        return cmd.equals("echo")
                || cmd.equals("exit")
                || cmd.equals("type")
                || cmd.equals("pwd")
                || cmd.equals("cd")
                || cmd.equals("jobs");
    }

    private static void runBuiltin(List<String> commandArgs, String[] pathDirs) throws IOException {
        String commandName = commandArgs.get(0);
        if (commandName.equals("exit")) {
            System.exit(0);
        } else if (commandName.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < commandArgs.size(); i++) {
                sb.append(commandArgs.get(i));
                if (i < commandArgs.size() - 1)
                    sb.append(" ");
            }
            System.out.println(sb.toString());
        } else if (commandName.equals("type")) {
            if (commandArgs.size() >= 2) {
                String cmd = commandArgs.get(1);
                if (isBuiltin(cmd)) {
                    System.out.println(cmd + " is a shell builtin");
                } else {
                    String foundPath = null;
                    for (String path : pathDirs) {
                        File file = new File(path, cmd);
                        if (file.exists() && file.canExecute()) {
                            foundPath = file.getAbsolutePath();
                            break;
                        }
                    }
                    if (foundPath != null) {
                        System.out.println(cmd + " is " + foundPath);
                    } else {
                        System.err.println(cmd + " not found");
                    }
                }
            }
        } else if (commandName.equals("pwd")) {
            System.out.println(System.getProperty("user.dir"));
        } else if (commandName.equals("cd")) {
            if (commandArgs.size() > 1) {
                String path = commandArgs.get(1);
                File targetDirectory = path.startsWith("/")
                        ? new File(path)
                        : path.equals("~")
                                ? new File(System.getenv("HOME"))
                                : new File(System.getProperty("user.dir"), path);
                if (targetDirectory.exists() && targetDirectory.isDirectory()) {
                    System.setProperty("user.dir", targetDirectory.getCanonicalPath());
                } else {
                    System.err.println("cd: " + path + ": No such file or directory");
                }
            }
        } else if (commandName.equals("jobs")) {
            for (Job job : backgroundJobs) {
                if (!job.process.isAlive()) {
                    job.status = "Done";
                }
            }
            int totalJobs = backgroundJobs.size();
            List<Job> remainingJobs = new ArrayList<>();
            for (int i = 0; i < totalJobs; i++) {
                Job job = backgroundJobs.get(i);
                String marker = (i == totalJobs - 1) ? "+" : (i == totalJobs - 2) ? "-" : " ";
                String paddedStatus = String.format("%-24s", job.status);
                if (job.status.equals("Done")) {
                    System.out.println(
                            "[" + job.id + "]" + marker + "  " + paddedStatus + job.command);
                } else {
                    System.out.println(
                            "[" + job.id + "]" + marker + "  " + paddedStatus + job.command + " &");
                    remainingJobs.add(job);
                }
            }
            backgroundJobs = remainingJobs;
        }
    }

    private static void handlePipeline(
            String input,
            String[] pathDirs,
            PrintStream originalOut,
            PrintStream originalErr,
            InputStream originalIn)
            throws Exception {
        String[] pipeParts = input.split("\\|");
        int numCmds = pipeParts.length;

        List<List<String>> parsedCmds = new ArrayList<>();
        boolean containsBuiltin = false;

        for (String part : pipeParts) {
            List<String> cmdArgs = parseArguments(part.trim());
            if (!cmdArgs.isEmpty()) {
                parsedCmds.add(cmdArgs);
                if (isBuiltin(cmdArgs.get(0))) {
                    containsBuiltin = true;
                }
            }
        }

        if (parsedCmds.isEmpty())
            return;

        if (!containsBuiltin) {
            List<ProcessBuilder> builders = new ArrayList<>();
            for (int i = 0; i < parsedCmds.size(); i++) {
                ProcessBuilder pb = new ProcessBuilder(parsedCmds.get(i));
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                if (i == parsedCmds.size() - 1) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
                builders.add(pb);
            }
            List<Process> processes = ProcessBuilder.startPipeline(builders);
            for (Process p : processes) {
                p.waitFor();
            }
            return;
        }

        InputStream currentIn = originalIn;

        for (int i = 0; i < parsedCmds.size(); i++) {
            List<String> cmdArgs = parsedCmds.get(i);
            boolean isLast = (i == parsedCmds.size() - 1);
            PipedOutputStream pipedOut = null;
            PipedInputStream pipedIn = null;

            if (!isLast) {
                pipedOut = new PipedOutputStream();
                pipedIn = new PipedInputStream(pipedOut);
            }

            PrintStream cmdOut = isLast ? originalOut : new PrintStream(pipedOut, true);
            String commandName = cmdArgs.get(0);

            if (isBuiltin(commandName)) {
                System.setOut(cmdOut);
                runBuiltin(cmdArgs, pathDirs);
                if (pipedOut != null)
                    pipedOut.close();
            } else {
                ProcessBuilder pb = new ProcessBuilder(cmdArgs);
                if (currentIn != originalIn) {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                } else {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                }

                if (!isLast) {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                Process p = pb.start();

                if (currentIn != originalIn) {
                    final InputStream finalIn = currentIn;
                    final OutputStream procOut = p.getOutputStream();
                    Thread.startVirtualThread(
                            () -> {
                                try (finalIn;
                                        procOut) {
                                    byte[] buffer = new byte[4096];
                                    int read;
                                    while ((read = finalIn.read(buffer)) != -1) {
                                        procOut.write(buffer, 0, read);
                                    }
                                } catch (IOException ignored) {
                                }
                            });
                }

                if (!isLast) {
                    final InputStream procIn = p.getInputStream();
                    final PipedOutputStream finalPipedOut = pipedOut;
                    Thread.startVirtualThread(
                            () -> {
                                try (procIn;
                                        finalPipedOut) {
                                    byte[] buffer = new byte[4096];
                                    int read;
                                    while ((read = procIn.read(buffer)) != -1) {
                                        finalPipedOut.write(buffer, 0, read);
                                    }
                                } catch (IOException ignored) {
                                }
                            });
                }
                p.waitFor();
            }

            currentIn = pipedIn;
        }

        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private static void reapCompletedJobs() {
        int totalJobs = backgroundJobs.size();
        List<Job> remainingJobs = new ArrayList<>();
        for (int i = 0; i < totalJobs; i++) {
            Job job = backgroundJobs.get(i);
            if (!job.process.isAlive()) {
                String marker = (i == totalJobs - 1) ? "+" : (i == totalJobs - 2) ? "-" : " ";
                System.out.println(
                        "["
                                + job.id
                                + "]"
                                + marker
                                + "  "
                                + String.format("%-24s", "Done")
                                + job.command);
            } else {
                remainingJobs.add(job);
            }
        }
        backgroundJobs = remainingJobs;
    }

    private static void resetStreams(PrintStream originalOut, PrintStream originalErr) {
        if (System.out != originalOut) {
            System.out.flush();
            System.out.close();
            System.setOut(originalOut);
        }
        if (System.err != originalErr) {
            System.err.flush();
            System.err.close();
            System.setErr(originalErr);
        }
    }

    private static List<String> parseArguments(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean hasContent = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') {
                    inSingleQuotes = false;
                    hasContent = true;
                } else {
                    currentToken.append(c);
                }
            } else if (inDoubleQuotes) {
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);
                        if (next == '"' || next == '\\' || next == '$' || next == '`') {
                            currentToken.append(next);
                            i++;
                        } else {
                            currentToken.append(c);
                        }
                        hasContent = true;
                    } else {
                        currentToken.append(c);
                        hasContent = true;
                    }
                } else if (c == '"') {
                    inDoubleQuotes = false;
                    hasContent = true;
                } else {
                    currentToken.append(c);
                }
            } else {
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        i++;
                        currentToken.append(input.charAt(i));
                        hasContent = true;
                    }
                } else if (c == '\'') {
                    inSingleQuotes = true;
                    hasContent = true;
                } else if (c == '"') {
                    inDoubleQuotes = true;
                    hasContent = true;
                } else if (Character.isWhitespace(c)) {
                    if (currentToken.length() > 0 || hasContent) {
                        tokens.add(currentToken.toString());
                        currentToken.setLength(0);
                        hasContent = false;
                    }
                } else {
                    currentToken.append(c);
                }
            }
        }

        if (currentToken.length() > 0 || hasContent) {
            tokens.add(currentToken.toString());
        }

        return tokens;
    }
}