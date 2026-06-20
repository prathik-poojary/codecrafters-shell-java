import static java.lang.IO.readln;

Set<String> BUILTINS = Set.of("cd", "echo", "exit", "pwd", "type");

void main() {

    // noinspection InfiniteLoopStatement
    while (true) {
        final String input = readln("$ ");
        final String[] tokens = tokenize(input);
        if (tokens.length == 0)
            continue;

        var stdout = Optional.<String>empty();
        var stderr = Optional.<String>empty();

        int i = 1;
        while (stdout.isEmpty() && i < tokens.length - 1) {
            switch (tokens[i++]) {
                case "1>", ">" -> stdout = Optional.of(tokens[i]);
                case "2>" -> stderr = Optional.of(tokens[i]);
            }
        }

        var output = handle(Arrays.copyOfRange(tokens, 0,
                stdout.isPresent() || stderr.isPresent()
                        ? i - 1
                        : tokens.length));

        BiConsumer<Optional<String>, String> lambda = (o,
                str) -> o.ifPresentOrElse(
                        fs -> {
                            try {
                                var path = Paths.get(fs);
                                Files.createDirectories(path.getParent());
                                try (var fw = Files.newBufferedWriter(path,
                                        StandardCharsets.UTF_8)) {
                                    // Overwrites the file
                                    fw.write(str);
                                }
                            } catch (IOException e) {
                                e.printStackTrace(System.out);
                            }
                        },
                        () -> System.out.append(str));

        lambda.accept(stdout, output.std());
        lambda.accept(stderr, output.err());
    }
}

Output handle(String[] tokens) {
    try (var outStream = new ByteArrayOutputStream();
            var errStream = new ByteArrayOutputStream();
            var out = new PrintStream(outStream);
            var err = new PrintStream(errStream)) {

        switch (tokens[0]) {
            case "exit" -> System.exit(0);
            case "echo" -> {
                if (tokens.length != 1) {
                    int i = 1;
                    while (i < tokens.length - 1)
                        out.print(tokens[i++] + " ");
                    out.println(tokens[i]);
                }
            }
            case "pwd" -> out.println(System.getProperty("user.dir"));
            case "cd" -> {
                if (tokens.length == 1)
                    err.println("cd: missing operand");
                else if (tokens.length > 2)
                    err.println("cd: too many arguments");
                else if (tokens[1].equals("~"))
                    System.setProperty("user.dir",
                            System.getenv("HOME"));
                else {
                    var currentPath = Paths.get(
                            System.getProperty("user.dir"));
                    var newPath = currentPath.resolve(tokens[1])
                            .normalize();

                    if (Files.exists(newPath))
                        System.setProperty("user.dir",
                                newPath.toAbsolutePath().toString());
                    else
                        err.printf("cd: %s: No such file or directory%n", tokens[1]);
                }
            }
            case "type" -> {
                if (tokens.length == 1)
                    err.println("type: missing operand");
                else if (tokens.length > 2)
                    err.println("type: too many arguments");
                else if (BUILTINS.contains(tokens[1]))
                    out.println(tokens[1] + " is a shell builtin");
                else
                    getFile(tokens[1]).ifPresentOrElse(
                            f -> out.println(tokens[1]
                                    + " is " + f.getAbsolutePath()),
                            () -> err.println(tokens[1] + ": not found"));

            }
            default -> getFile(tokens[0]).ifPresentOrElse(
                    _ -> runProgram(tokens, out, err),
                    () -> err.println(tokens[0] + ": command not found"));
        }
        out.flush();
        err.flush();
        return new Output(outStream.toString(), errStream.toString());
    } catch (IOException e) {
        e.printStackTrace(System.err);
        return null;
    }
}

Optional<File> getFile(String token) {
    Objects.requireNonNull(token);

    final String PATH = System.getenv("PATH");
    assert PATH != null : "PATH can't be null";

    // Check if PATH contains the file
    for (final String filePath : PATH.split(File.pathSeparator)) {
        final var file = new File(
                filePath + File.separator + token);
        if (file.exists() && file.canExecute())
            return Optional.of(file);
    }
    return Optional.empty();
}

void runProgram(String[] tokens, PrintStream out, PrintStream err) {
    assert tokens != null;
    assert tokens.length > 0;

    try {
        var process = Runtime.getRuntime().exec(tokens);
        process.getInputStream().transferTo(out);
        process.getErrorStream().transferTo(err);
    } catch (IOException e) {
        err.println("An I/O error occurred: " + e.getMessage());
    } catch (Exception e) {
        e.printStackTrace(err);
    }
}

String[] tokenize(String s) {
    var tokens = new ArrayList<String>();

    var sb = new StringBuilder();
    boolean inSingleQuote = false, inDoubleQuote = false;

    int i = 0;
    while (i < s.length()) {
        final char c = s.charAt(i);
        if (c == '\\' && i + 1 < s.length())
            sb.append(inSingleQuote ? c
                    : s.charAt(++i));
        else if (c == '\'' && !inDoubleQuote)
            inSingleQuote = !inSingleQuote;
        else if (c == '"' && !inSingleQuote) {
            if (inDoubleQuote && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                // Skip the next character
                i++;
                continue;
            }
            inDoubleQuote = !inDoubleQuote;
        } else if (!inSingleQuote && !inDoubleQuote
                && Character.isWhitespace(c)) {
            if (!sb.isEmpty()) {
                tokens.add(sb.toString());
                sb.setLength(0);
            }
        } else {
            sb.append(c);
        }
        i++;
    }

    if (!sb.isEmpty())
        tokens.add(sb.toString());
    return tokens.toArray(String[]::new);
}

record Output(String std, String err) {
    Output {
        Objects.requireNonNull(std);
        Objects.requireNonNull(err);
    }
}