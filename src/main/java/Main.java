import java.io.*;
import java.util.*;

/**
 * A small Unix-like shell implementing:
 * - Base: REPL, exit, echo, type, PATH lookup, external execution
 * - Navigation: pwd, cd (absolute/relative/~)
 * - Quoting: single quotes, double quotes, backslash escaping
 * - Redirection: >, 1>, 2>, >>, 1>>, 2>>
 * - Background Jobs: &, jobs, reaping, job-number recycling
 * - Pipelines: multi-command pipelines, including builtins in the pipeline
 */
public class Main {

    static final Set<String> BUILTINS = Set.of("exit", "echo", "type", "pwd", "cd", "jobs");
    static final TreeMap<Integer, JobInfo> jobs = new TreeMap<>();
    static String[] pathDirs;
    static File currentDir;

    public static void main(String[] args) throws Exception {
        String pathEnv = System.getenv("PATH");
        pathDirs = (pathEnv == null ? "" : pathEnv).split(File.pathSeparator);
        currentDir = new File(System.getProperty("user.dir")).getCanonicalFile();

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            reapFinishedJobs();
            System.out.print("$ ");
            System.out.flush();

            String line = stdin.readLine();
            if (line == null) {
                System.out.println();
                return;
            }
            line = line.trim();
            if (line.isEmpty())
                continue;

            try {
                runLine(line);
            } catch (ExitException e) {
                System.exit(e.code);
            } catch (Exception e) {
                System.err.println("shell: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Tokenizing (Quoting stage)
    // ------------------------------------------------------------------

    enum TokType {
        WORD, PIPE, REDIR_OUT, REDIR_OUT_APPEND, REDIR_ERR, REDIR_ERR_APPEND, BACKGROUND
    }

    static class Tok {
        TokType type;
        String text;

        Tok(TokType t, String s) {
            type = t;
            text = s;
        }
    }

    static List<Tok> tokenize(String line) {
        List<Tok> toks = new ArrayList<>();
        int i = 0, n = line.length();
        StringBuilder word = new StringBuilder();
        boolean hasWord = false;

        while (i < n) {
            char c = line.charAt(i);

            if (c == ' ' || c == '\t') {
                if (hasWord) {
                    toks.add(new Tok(TokType.WORD, word.toString()));
                    word.setLength(0);
                    hasWord = false;
                }
                i++;
                continue;
            }
            if (c == '|') {
                if (hasWord) {
                    toks.add(new Tok(TokType.WORD, word.toString()));
                    word.setLength(0);
                    hasWord = false;
                }
                toks.add(new Tok(TokType.PIPE, "|"));
                i++;
                continue;
            }
            if (c == '&') {
                if (hasWord) {
                    toks.add(new Tok(TokType.WORD, word.toString()));
                    word.setLength(0);
                    hasWord = false;
                }
                toks.add(new Tok(TokType.BACKGROUND, "&"));
                i++;
                continue;
            }
            if (c == '>') {
                // fd prefix: a bare "1" or "2" immediately before '>' becomes part of the
                // operator
                String fdPrefix = null;
                if (hasWord && (word.toString().equals("1") || word.toString().equals("2"))) {
                    fdPrefix = word.toString();
                    word.setLength(0);
                    hasWord = false;
                }
                i++;
                boolean append = false;
                if (i < n && line.charAt(i) == '>') {
                    append = true;
                    i++;
                }
                TokType tt;
                if ("2".equals(fdPrefix))
                    tt = append ? TokType.REDIR_ERR_APPEND : TokType.REDIR_ERR;
                else
                    tt = append ? TokType.REDIR_OUT_APPEND : TokType.REDIR_OUT;
                toks.add(new Tok(tt, ">"));
                continue;
            }
            if (c == '\'') {
                hasWord = true;
                i++;
                while (i < n && line.charAt(i) != '\'') {
                    word.append(line.charAt(i));
                    i++;
                }
                i++; // closing quote (no-op if missing, malformed input)
                continue;
            }
            if (c == '"') {
                hasWord = true;
                i++;
                while (i < n && line.charAt(i) != '"') {
                    char cc = line.charAt(i);
                    if (cc == '\\' && i + 1 < n) {
                        char next = line.charAt(i + 1);
                        if (next == '\\' || next == '$' || next == '"' || next == '\n') {
                            word.append(next);
                            i += 2;
                            continue;
                        } else {
                            word.append(cc);
                            i++;
                            continue;
                        }
                    }
                    word.append(cc);
                    i++;
                }
                i++; // closing quote
                continue;
            }
            if (c == '\\') {
                hasWord = true;
                if (i + 1 < n) {
                    word.append(line.charAt(i + 1));
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }

            hasWord = true;
            word.append(c);
            i++;
        }
        if (hasWord)
            toks.add(new Tok(TokType.WORD, word.toString()));
        return toks;
    }

    // ------------------------------------------------------------------
    // Parsing tokens into a pipeline of commands
    // ------------------------------------------------------------------

    static class Cmd {
        List<String> args = new ArrayList<>();
        String outFile;
        boolean outAppend;
        String errFile;
        boolean errAppend;
    }

    static class ParsedLine {
        List<Cmd> pipeline = new ArrayList<>();
        boolean background;
    }

    static ParsedLine parse(List<Tok> toks) {
        ParsedLine pl = new ParsedLine();
        Cmd cur = new Cmd();
        int i = 0;
        while (i < toks.size()) {
            Tok t = toks.get(i);
            switch (t.type) {
                case WORD -> {
                    cur.args.add(t.text);
                    i++;
                }
                case PIPE -> {
                    pl.pipeline.add(cur);
                    cur = new Cmd();
                    i++;
                }
                case BACKGROUND -> {
                    pl.background = true;
                    i++;
                }
                case REDIR_OUT, REDIR_OUT_APPEND, REDIR_ERR, REDIR_ERR_APPEND -> {
                    TokType type = t.type;
                    i++;
                    if (i >= toks.size() || toks.get(i).type != TokType.WORD)
                        throw new RuntimeException("syntax error: expected filename after redirection");
                    String fname = toks.get(i).text;
                    i++;
                    if (type == TokType.REDIR_OUT) {
                        cur.outFile = fname;
                        cur.outAppend = false;
                    } else if (type == TokType.REDIR_OUT_APPEND) {
                        cur.outFile = fname;
                        cur.outAppend = true;
                    } else if (type == TokType.REDIR_ERR) {
                        cur.errFile = fname;
                        cur.errAppend = false;
                    } else {
                        cur.errFile = fname;
                        cur.errAppend = true;
                    }
                }
            }
        }
        pl.pipeline.add(cur);
        return pl;
    }

    // ------------------------------------------------------------------
    // Top level dispatch
    // ------------------------------------------------------------------

    static void runLine(String line) throws Exception {
        List<Tok> toks = tokenize(line);
        ParsedLine pl = parse(toks);

        // drop empty trailing command produced by a trailing pipe, if any
        pl.pipeline.removeIf(c -> c.args.isEmpty() && pl.pipeline.size() > 1);

        if (pl.pipeline.size() == 1 && !pl.background) {
            Cmd c = pl.pipeline.get(0);
            if (c.args.isEmpty())
                return;
            runSingleForeground(c);
        } else {
            executePipeline(pl, line);
        }
    }

    // ------------------------------------------------------------------
    // Single foreground command (covers Base/Navigation/Quoting/Redirection)
    // ------------------------------------------------------------------

    static int runSingleForeground(Cmd cmd) throws Exception {
        String name = cmd.args.get(0);

        if (BUILTINS.contains(name)) {
            FileOutputStream outFos = null, errFos = null;
            PrintStream outPs = System.out, errPs = System.err;
            try {
                if (cmd.outFile != null) {
                    outFos = new FileOutputStream(resolvePath(cmd.outFile), cmd.outAppend);
                    outPs = new PrintStream(outFos);
                }
                if (cmd.errFile != null) {
                    errFos = new FileOutputStream(resolvePath(cmd.errFile), cmd.errAppend);
                    errPs = new PrintStream(errFos);
                }
                return runBuiltin(cmd.args, outPs, errPs);
            } finally {
                if (outFos != null)
                    outFos.close();
                if (errFos != null)
                    errFos.close();
            }
        } else {
            File exe = findExecutable(name);
            if (exe == null) {
                System.out.println(name + ": command not found");
                return 127;
            }
            ProcessBuilder pb = new ProcessBuilder(cmd.args);
            pb.directory(currentDir);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

            pb.redirectOutput(cmd.outFile == null
                    ? ProcessBuilder.Redirect.INHERIT
                    : (cmd.outAppend ? ProcessBuilder.Redirect.appendTo(resolvePath(cmd.outFile))
                            : ProcessBuilder.Redirect.to(resolvePath(cmd.outFile))));

            pb.redirectError(cmd.errFile == null
                    ? ProcessBuilder.Redirect.INHERIT
                    : (cmd.errAppend ? ProcessBuilder.Redirect.appendTo(resolvePath(cmd.errFile))
                            : ProcessBuilder.Redirect.to(resolvePath(cmd.errFile))));

            Process p = pb.start();
            return p.waitFor();
        }
    }

    // ------------------------------------------------------------------
    // Builtins
    // ------------------------------------------------------------------

    static class ExitException extends RuntimeException {
        final int code;

        ExitException(int c) {
            code = c;
        }
    }

    static int runBuiltin(List<String> args, PrintStream out, PrintStream err) {
        String name = args.get(0);
        switch (name) {
            case "exit": {
                int code = args.size() > 1 ? Integer.parseInt(args.get(1)) : 0;
                throw new ExitException(code);
            }
            case "echo": {
                out.println(String.join(" ", args.subList(1, args.size())));
                return 0;
            }
            case "pwd": {
                out.println(currentDir.getPath());
                return 0;
            }
            case "cd": {
                String home = System.getenv("HOME");
                String target = args.size() > 1 ? args.get(1) : home;
                if (target.equals("~"))
                    target = home;
                else if (target.startsWith("~/"))
                    target = home + target.substring(1);

                File dir = resolvePath(target);
                try {
                    dir = dir.getCanonicalFile();
                } catch (IOException ignored) {
                }

                if (!dir.isDirectory()) {
                    err.println("cd: " + target + ": No such file or directory");
                    return 1;
                }
                currentDir = dir;
                return 0;
            }
            case "type": {
                if (args.size() < 2) {
                    out.println("type: missing argument");
                    return 1;
                }
                String t = args.get(1);
                if (BUILTINS.contains(t)) {
                    out.println(t + " is a shell builtin");
                } else {
                    File f = findExecutable(t);
                    if (f != null)
                        out.println(t + " is " + f.getPath());
                    else
                        out.println(t + ": not found");
                }
                return 0;
            }
            case "jobs": {
                for (JobInfo j : jobs.values()) {
                    out.println("[" + j.number + "]+  Running                 " + j.cmdline + " &");
                }
                return 0;
            }
            default:
                err.println(name + ": not a builtin");
                return 1;
        }
    }

    // ------------------------------------------------------------------
    // PATH lookup / path resolution
    // ------------------------------------------------------------------

    static File resolvePath(String p) {
        File f = new File(p);
        if (f.isAbsolute())
            return f;
        return new File(currentDir, p);
    }

    static File findExecutable(String name) {
        if (name.contains("/")) {
            File f = resolvePath(name);
            return (f.isFile() && f.canExecute()) ? f : null;
        }
        for (String dir : pathDirs) {
            if (dir.isEmpty())
                continue;
            File f = new File(dir, name);
            if (f.isFile() && f.canExecute())
                return f;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Background Jobs
    // ------------------------------------------------------------------

    static class JobInfo {
        int number;
        String cmdline;
        volatile boolean finished = false;
    }

    static int assignJobNumber() {
        int n = 1;
        while (jobs.containsKey(n))
            n++;
        return n;
    }

    static void reapFinishedJobs() {
        Iterator<Map.Entry<Integer, JobInfo>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            JobInfo j = it.next().getValue();
            if (j.finished) {
                System.out.println("[" + j.number + "]+  Done                    " + j.cmdline);
                it.remove(); // frees the number for reuse -> "recycle job numbers"
            }
        }
    }

    // ------------------------------------------------------------------
    // Pipelines (and background execution of any command/pipeline)
    // ------------------------------------------------------------------

    static void executePipeline(ParsedLine pl, String originalLine) throws Exception {
        List<Cmd> cmds = pl.pipeline;
        int n = cmds.size();

        List<Process> procs = new ArrayList<>();
        List<Thread> threads = new ArrayList<>(); // builtin threads + IO pump threads

        InputStream prevPipeIn = null; // null => inherit real stdin for stage 0

        for (int idx = 0; idx < n; idx++) {
            Cmd cmd = cmds.get(idx);
            if (cmd.args.isEmpty())
                continue;
            boolean isLast = idx == n - 1;
            String name = cmd.args.get(0);

            PipedInputStream nextPipeIn = null;
            PipedOutputStream stageOutPipe = null;
            if (!isLast) {
                nextPipeIn = new PipedInputStream();
                stageOutPipe = new PipedOutputStream(nextPipeIn);
            }

            if (BUILTINS.contains(name)) {
                OutputStream targetOut;
                FileOutputStream outFos = null;
                if (cmd.outFile != null) {
                    outFos = new FileOutputStream(resolvePath(cmd.outFile), cmd.outAppend);
                    targetOut = outFos;
                } else if (!isLast) {
                    targetOut = stageOutPipe;
                } else {
                    targetOut = System.out;
                }
                PrintStream outPs = new PrintStream(targetOut, true);

                FileOutputStream errFos = null;
                PrintStream errPsTmp = System.err;
                if (cmd.errFile != null) {
                    errFos = new FileOutputStream(resolvePath(cmd.errFile), cmd.errAppend);
                    errPsTmp = new PrintStream(errFos, true);
                }
                final PrintStream errPs = errPsTmp;

                final FileOutputStream fOutFos = outFos, fErrFos = errFos;
                final OutputStream fStageOutPipe = stageOutPipe;
                Thread th = new Thread(() -> {
                    try {
                        runBuiltin(cmd.args, outPs, errPs);
                    } catch (ExitException ee) {
                        // background/piped exit shouldn't kill the whole shell process abruptly
                        // mid-pipe
                    } finally {
                        try {
                            if (fStageOutPipe != null)
                                fStageOutPipe.close();
                        } catch (IOException ignored) {
                        }
                        try {
                            if (fOutFos != null)
                                fOutFos.close();
                        } catch (IOException ignored) {
                        }
                        try {
                            if (fErrFos != null)
                                fErrFos.close();
                        } catch (IOException ignored) {
                        }
                    }
                });
                th.start();
                threads.add(th);
            } else {
                File exe = findExecutable(name);
                if (exe == null) {
                    System.out.println(name + ": command not found");
                    if (stageOutPipe != null)
                        stageOutPipe.close();
                    prevPipeIn = nextPipeIn;
                    continue;
                }
                ProcessBuilder pb = new ProcessBuilder(cmd.args);
                pb.directory(currentDir);

                pb.redirectInput(prevPipeIn == null ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);

                if (cmd.outFile != null) {
                    pb.redirectOutput(cmd.outAppend ? ProcessBuilder.Redirect.appendTo(resolvePath(cmd.outFile))
                            : ProcessBuilder.Redirect.to(resolvePath(cmd.outFile)));
                } else if (!isLast) {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }

                pb.redirectError(cmd.errFile != null
                        ? (cmd.errAppend ? ProcessBuilder.Redirect.appendTo(resolvePath(cmd.errFile))
                                : ProcessBuilder.Redirect.to(resolvePath(cmd.errFile)))
                        : ProcessBuilder.Redirect.INHERIT);

                Process p = pb.start();
                procs.add(p);

                if (prevPipeIn != null) {
                    final InputStream fIn = prevPipeIn;
                    final OutputStream procStdin = p.getOutputStream();
                    Thread pump = new Thread(() -> {
                        try {
                            fIn.transferTo(procStdin);
                        } catch (IOException ignored) {
                        } finally {
                            try {
                                procStdin.close();
                            } catch (IOException ignored) {
                            }
                            try {
                                fIn.close();
                            } catch (IOException ignored) {
                            }
                        }
                    });
                    pump.start();
                    threads.add(pump);
                }
                if (!isLast) {
                    final InputStream procStdout = p.getInputStream();
                    final OutputStream fOut = stageOutPipe;
                    Thread pump2 = new Thread(() -> {
                        try {
                            procStdout.transferTo(fOut);
                        } catch (IOException ignored) {
                        } finally {
                            try {
                                fOut.close();
                            } catch (IOException ignored) {
                            }
                            try {
                                procStdout.close();
                            } catch (IOException ignored) {
                            }
                        }
                    });
                    pump2.start();
                    threads.add(pump2);
                }
            }

            prevPipeIn = nextPipeIn;
        }

        if (pl.background) {
            JobInfo job = new JobInfo();
            job.number = assignJobNumber();
            job.cmdline = originalLine.replaceAll("\\s*&\\s*$", "");
            jobs.put(job.number, job);

            long pid = procs.isEmpty() ? -1 : procs.get(procs.size() - 1).pid();
            System.out.println("[" + job.number + "] " + pid);

            final List<Process> fProcs = procs;
            final List<Thread> fThreads = threads;
            Thread monitor = new Thread(() -> {
                try {
                    for (Process p : fProcs)
                        p.waitFor();
                    for (Thread t : fThreads)
                        t.join();
                } catch (InterruptedException ignored) {
                }
                job.finished = true;
            });
            monitor.setDaemon(true);
            monitor.start();
        } else {
            for (Process p : procs)
                p.waitFor();
            for (Thread t : threads)
                t.join();
        }
    }
}