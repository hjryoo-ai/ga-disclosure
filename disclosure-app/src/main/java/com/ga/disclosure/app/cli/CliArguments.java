package com.ga.disclosure.app.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 운영자 CLI 인자: {@code <group> <verb> --name value ...}. {@code --spring.*} 등 {@code =}가 있는 인자는 Spring 속성이므로 무시한다.
 */
record CliArguments(List<String> words, Map<String, String> options) {

    static CliArguments parse(String[] args) {
        List<String> words = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--") && a.contains("=")) {
                continue;
            }
            if (a.startsWith("--")) {
                if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                    throw new CliFailure("option " + a + " needs a value");
                }
                options.put(a.substring(2), args[++i]);
            } else {
                words.add(a);
            }
        }
        return new CliArguments(List.copyOf(words), Map.copyOf(options));
    }

    String command() {
        return String.join(" ", words);
    }

    String required(String name) {
        return optional(name).orElseThrow(() -> new CliFailure("missing --" + name + " for '" + command() + "'"));
    }

    Optional<String> optional(String name) {
        return Optional.ofNullable(options.get(name));
    }
}
