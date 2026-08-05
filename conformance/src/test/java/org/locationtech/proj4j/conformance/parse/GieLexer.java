/*
 * Copyright 2026 The Proj4J Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.locationtech.proj4j.conformance.parse;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code .gie} lexer — a transliteration of the {@code ffio} state machine
 * in {@code 9.8.1:src/apps/gie.cpp} ({@code get_inp}, {@code skip_to_next_tag},
 * {@code step_into_gie_block}, {@code nextline}, {@code at_tag},
 * {@code at_decorative_element}, {@code append_args}).
 *
 * <h2>The two modes</h2>
 *
 * <p><strong>Strict</strong> ({@code <gie-strict>} … {@code </gie-strict>},
 * used by 43 of 44 corpus files): every line must be blank, decorative, or
 * start with a verb — otherwise the whole file aborts. Comment lines survive
 * because {@code pj_chomp} deletes {@code #} to end-of-line before the verb
 * test, leaving a blank. Continuation is explicit: a line whose shrunken form
 * ends in a backslash pulls in the next line.
 *
 * <p><strong>Non-strict</strong> ({@code <gie>} … {@code </gie>}, only
 * {@code builtins.gie}): text outside the block is ignored entirely, and a
 * verb's arguments run on across lines, joined with a single space, until the
 * next verb or a decorative element. An unrecognised bare word inside the
 * block is therefore not an error — it is argument text.
 *
 * <h2>Encoding</h2>
 *
 * <p>Files are decoded as ISO-8859-1, deliberately. The C is byte-oriented,
 * and the DMS parser distinguishes the two UTF-8 bytes of U+00B0 from the
 * single-byte 0xB0 degree sign. Latin-1 is the decoding that makes one Java
 * {@code char} equal one C {@code char}, so behaviour matches byte for byte.
 * All {@code .gie} argument text is ASCII; only comments are ever otherwise.
 */
public final class GieLexer {

    /** Byte-transparent decoding; see the class comment. */
    public static final Charset GIE_CHARSET = Charset.forName("ISO-8859-1");

    // ---------------------------------------------------------------- input

    private final String source;
    private final List<String> lines;
    /**
     * Emulates the C FILE end-of-file indicator. {@code fgets} sets it both
     * when it returns NULL and when it consumes a final line that has no
     * trailing newline — and in the latter case {@code nextline()} discards
     * that line. Reproduced so a file lacking a final newline behaves here
     * exactly as it does under gie.
     */
    private final boolean endsWithNewline;

    // ------------------------------------------------------------ ffio state

    private String nextArgs = "";
    private String nextRaw = "";
    private String args = "";
    private GieVerb tag;
    private int cursor;
    private int lineno;
    private int nextLineno;
    private int level;
    private boolean strictMode;
    private boolean eof;
    private boolean abandoned;

    /** First and last physical line, and raw text, of the command being built. */
    private int commandFirstLine;
    private int commandLastLine;
    private final List<String> rawParts = new ArrayList<String>();

    private GieLexer(String source, String content) {
        this.source = source;
        this.lines = new ArrayList<String>();
        int start = 0;
        for (int k = 0; k < content.length(); k++) {
            if (content.charAt(k) == '\n') {
                lines.add(content.substring(start, k));
                start = k + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
            this.endsWithNewline = false;
        } else {
            this.endsWithNewline = true;
        }
    }

    // ------------------------------------------------------------ public API

    /** Lex {@code content} as if it were the file named {@code source}. */
    public static GieFile lex(String source, String content) {
        return new GieLexer(source, content).run();
    }

    /** Lex a {@code .gie} file from disk. */
    public static GieFile lex(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return lex(path.toString(), new String(bytes, GIE_CHARSET));
    }

    /** Lex a {@code .gie} file from a classpath stream. Does not close it. */
    public static GieFile lex(String source, InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return lex(source, new String(buf.toByteArray(), GIE_CHARSET));
    }

    // ---------------------------------------------------------------- driver

    private GieFile run() {
        List<GieCommand> commands = new ArrayList<GieCommand>();
        while (getInp()) {
            commands.add(new GieCommand(tag, args, commandFirstLine, commandLastLine, joinRaw()));
            if (tag == GieVerb.SKIP) {
                /* gie.cpp:1223 skip(): sets T.skip, which makes nextline()
                 * fail forever, and forces level=2 to silence the missing
                 * '</gie>' complaint. dispatch() then returns SKIP and
                 * process_file() bails out immediately. */
                abandoned = true;
                level = 2;
                break;
            }
        }

        /* gie.cpp:466-474, process_file() epilogue. */
        if (level == 0) {
            throw new GieSyntaxException(source, 0, "Missing '<gie>' cmnd");
        }
        if (level % 2 != 0) {
            throw new GieSyntaxException(source, 0,
                    strictMode ? "Missing '</gie-strict>' cmnd" : "Missing '</gie>' cmnd");
        }
        return new GieFile(source, commands);
    }

    // ------------------------------------------------------------- ffio port

    /** Port of {@code nextline()}. */
    private boolean nextline() {
        nextArgs = "";
        nextRaw = "";
        if (abandoned) {
            return false;
        }
        if (cursor >= lines.size()) {
            eof = true;
            return false;
        }
        String raw = lines.get(cursor++);
        if (cursor == lines.size() && !endsWithNewline) {
            /* fgets consumed the last line and hit EOF in doing so. */
            eof = true;
            return false;
        }
        nextRaw = raw;
        nextArgs = PjText.chomp(raw);
        nextLineno++;
        return true;
    }

    /**
     * Port of {@code at_decorative_element()}: the first five characters are
     * all identical. Not "starts with dashes" — {@code aaaaa} is decorative
     * and {@code -=-=-} is not. Shorter lines fail because {@code c[4]} is
     * then the NUL terminator.
     */
    private boolean atDecorativeElement() {
        return isDecorativeElement(nextArgs);
    }

    /** Exposed for tests; same rule as {@code at_decorative_element()}. */
    public static boolean isDecorativeElement(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        char c0 = line.charAt(0);
        for (int i = 1; i < 5; i++) {
            if (PjText.charAt(line, i) != c0) {
                return false;
            }
        }
        return true;
    }

    private GieVerb atTag() {
        return GieVerb.matchPrefix(nextArgs);
    }

    private boolean atEndDelimiter() {
        return atDecorativeElement() || atTag() != null;
    }

    /** Port of {@code step_into_gie_block()}. */
    private boolean stepIntoGieBlock() {
        if (level % 2 != 0) {
            return true; /* already inside */
        }
        while (!nextArgs.startsWith(GieVerb.OPEN_GIE.token())
                && !nextArgs.startsWith(GieVerb.OPEN_GIE_STRICT.token())) {
            if (!nextline()) {
                return false;
            }
        }
        level++;
        if (nextArgs.startsWith(GieVerb.OPEN_GIE_STRICT.token())) {
            /* Deliberate "failure": the caller unwinds to get_inp(), which
             * sees strict_mode and re-enters down the strict path. */
            strictMode = true;
            return false;
        }
        /* We're ready at the start - now step into the block */
        return nextline();
    }

    /**
     * Port of {@code skip_to_next_tag()}. The C recurses after each
     * {@code </gie>}; this is the same walk written as a loop.
     */
    private boolean skipToNextTag() {
        while (true) {
            if (!stepIntoGieBlock()) {
                return false;
            }
            GieVerb c = atTag();
            while (c == null) {
                if (!nextline()) {
                    return false;
                }
                c = atTag();
            }
            if (c == GieVerb.CLOSE_GIE) {
                level++;
                if (eof) {
                    return false;
                }
                args = "";
                continue; /* locate the next block and retry */
            }
            lineno = nextLineno;
            return true;
        }
    }

    /**
     * Port of {@code append_args()}. Always inserts a separating space, and
     * strips a leading verb token if the appended line happens to start with
     * one — which for continuation lines is a real (if never-exercised)
     * behaviour of the C, not an accident of this port.
     */
    private void appendArgs() {
        GieVerb lineTag = atTag();
        int skipChars = lineTag == null ? 0 : lineTag.token().length();
        if (rawParts.isEmpty()) {
            commandFirstLine = nextLineno;
        }
        commandLastLine = nextLineno;
        rawParts.add(nextRaw);
        args = args + " " + nextArgs.substring(skipChars);
        nextArgs = "";
        nextRaw = "";
    }

    /** Port of {@code get_inp()}, the primary command reader. */
    private boolean getInp() {
        args = "";
        rawParts.clear();
        commandFirstLine = 0;

        // Special parsing in strict_mode:
        // - All non-comment/decoration lines must start with a valid tag
        // - Commands split on several lines should be terminated with " \"
        if (strictMode) {
            while (nextline()) {
                lineno = nextLineno;
                if (nextArgs.isEmpty() || atDecorativeElement()) {
                    continue;
                }
                tag = atTag();
                if (tag == null) {
                    throw new GieSyntaxException(source, lineno,
                            "unsupported command: " + nextArgs);
                }

                appendArgs();
                args = PjText.shrink(args);
                while (!args.isEmpty() && args.charAt(args.length() - 1) == '\\') {
                    args = args.substring(0, args.length() - 1);
                    if (!nextline()) {
                        return false;
                    }
                    lineno = nextLineno;
                    appendArgs();
                    args = PjText.shrink(args);
                }
                if (tag == GieVerb.CLOSE_GIE_STRICT) {
                    level++;
                    strictMode = false;
                }
                return true;
            }
            return false;
        }

        if (!skipToNextTag()) {
            // If we just entered <gie-strict>, re-enter to read the first command
            if (strictMode) {
                return getInp();
            }
            return false;
        }
        tag = atTag();
        if (tag == null) {
            return false;
        }

        do {
            appendArgs();
            if (!nextline()) {
                return false;
            }
        } while (!atEndDelimiter());

        args = PjText.shrink(args);
        return true;
    }

    private String joinRaw() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rawParts.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(rawParts.get(i));
        }
        return sb.toString();
    }
}
