package org.teamknowledge.mcp.service;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class TextIndex {
    public static final String VERSION = "unicode-bigram-2";
    private static final Pattern WORDS = Pattern.compile("[\\p{IsHan}]+|[A-Za-z0-9_$]+(?:[._][A-Za-z0-9_$]+)*");
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    public String normalize(String value) {
        return Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC)
                .replaceAll("(?m)[ \\t]+$", "").strip();
    }
    public List<String> tokens(String text) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        // Keep complete identifiers as well as their CamelCase/underscore components.
        var identifiers = SYMBOL.matcher(text);
        while (identifiers.find() && result.size() < 1000) result.add(identifiers.group().toLowerCase(Locale.ROOT));
        var matcher = WORDS.matcher(text.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT));
        while (matcher.find() && result.size() < 4000) {
            String word = matcher.group();
            if (word.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                int[] cps = word.codePoints().toArray();
                if (cps.length == 1) result.add(word);
                for (int i = 0; i < cps.length - 1; i++) result.add(new String(cps, i, 2));
            } else {
                result.add(word);
                result.addAll(Arrays.asList(word.split("[._]")));
            }
        }
        result.remove("");
        return new ArrayList<>(result);
    }
    public String lexemes(String text) { return String.join(" ", tokens(text)); }
    public List<String> queryTokens(String query) {
        return tokens(query.replaceAll("请问|帮我|如何|怎么|为什么|是什么|是不是|还是|是否|一下", " ")
                .replaceAll("(?i)(?<![A-Za-z0-9_$.])(please|explain|describe|tell|me|what|how|why|is|are|the|a|an|of|in|for|does|do)(?![A-Za-z0-9_$.])", " "));
    }
    /** A system already constrained by scope need not occur in every matching source chunk. */
    public String scopedQuery(String query,String system) {
        return query.replaceAll("(?i)(?<![A-Za-z0-9_$.])"+Pattern.quote(system)+"(?![A-Za-z0-9_$.])", " ").strip();
    }
    public String queryAnchors(String query) {
        List<String> anchors = new ArrayList<>();
        var matcher = SYMBOL.matcher(query);
        while (matcher.find()) {
            String word=matcher.group();
            if(queryTokens(word).isEmpty())continue;
            boolean symbol=word.contains("_") || word.contains(".") || word.matches(".*[a-z][A-Z].*");
            boolean acronym=word.matches("[A-Z]{2,}[0-9]*") && !Set.of("SQL","SUM","AVG","COUNT","MIN","MAX").contains(word);
            if(symbol || acronym)anchors.addAll(tokens(word));
        }
        return String.join(" ", new LinkedHashSet<>(anchors));
    }
    public boolean question(String query) {
        return query.matches("(?s).*(怎么|如何|为什么|是什么|是不是|还是|是否|[?？]).*");
    }
    public List<String> symbols(String title, String body, List<String> declared) {
        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        declared.forEach(s -> symbols.add(s.toLowerCase(Locale.ROOT)));
        var matcher = SYMBOL.matcher(title + "\n" + body);
        while (matcher.find() && symbols.size() < 100) {
            String value = matcher.group();
            if (value.length() <= 200 && (value.contains("_") || value.contains(".")
                    || value.matches(".*[a-z][A-Z].*") || value.matches("[A-Z]{2,}[0-9]*"))) symbols.add(value.toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(symbols);
    }
    public String excerpt(String body, String query) {
        String lower = body.toLowerCase(Locale.ROOT);
        int position = lower.indexOf(query.toLowerCase(Locale.ROOT));
        if (position < 0) {
            List<String> terms = new ArrayList<>(queryTokens(query));
            terms.sort(Comparator.comparingInt(String::length).reversed());
            for (String term : terms) {
                position = lower.indexOf(term);
                if (position >= 0) break;
            }
        }
        int count = body.codePointCount(0, body.length());
        int start = position < 0 ? 0 : Math.max(0, body.codePointCount(0, position) - 80);
        int end = Math.min(count, start + 360);
        return (start > 0 ? "…" : "") + body.substring(body.offsetByCodePoints(0, start), body.offsetByCodePoints(0, end))
                + (end < count ? "…" : "");
    }
    public List<String> chunks(String body) {
        List<String> result = new ArrayList<>();
        // Code-point boundaries avoid splitting UTF-16 surrogate pairs.
        int[] cps = body.codePoints().toArray();
        for (int start = 0; start < cps.length; start += 1100) {
            result.add(new String(cps, start, Math.min(1200, cps.length - start)));
        }
        return result;
    }
    public String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
