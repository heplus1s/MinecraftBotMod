package dev.societybots.core;

import java.util.*;
import java.util.regex.*;

/** Normal chat @name recognition. No action or authorization data is emitted by this parser. */
public final class Mentions {
    private Mentions() {}
    private static final Pattern AT = Pattern.compile("(?<![\\p{L}\\p{N}_@])[@＠]([A-Za-z0-9_]{1,16})(?![A-Za-z0-9_])");
    public static Set<String> namedBots(String message, Set<String> onlineNames) {
        if (message==null || message.length()>512) return Set.of();
        var result = new LinkedHashSet<String>();
        var lookup = new HashMap<String,String>();
        for (String name:onlineNames) lookup.put(name.toLowerCase(Locale.ROOT),name);
        var m=AT.matcher(message);
        while(m.find() && result.size()<2) {
            var match=lookup.get(m.group(1).toLowerCase(Locale.ROOT));
            if(match!=null) result.add(match);
        }
        return Collections.unmodifiableSet(result);
    }
}
