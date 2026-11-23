package dev.societybots.core;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Local model settings and an untrusted, proposal-only response protocol. */
public record LocalModel(boolean chat, boolean plan, boolean publicContext, String tag, int port) {
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*(?::[A-Za-z0-9][A-Za-z0-9_.-]*)?");
    private static final Pattern CLOUD = Pattern.compile("(?i)(?:^|[-_.:])cloud(?:$|[-_.:])");
    private static final Pattern PROPOSAL = Pattern.compile(
            "[ \\t\\r\\n]*\\{[ \\t\\r\\n]*\"goal\"[ \\t\\r\\n]*:[ \\t\\r\\n]*\"(MINE|UNLOAD|STOP)\"[ \\t\\r\\n]*\\}[ \\t\\r\\n]*");

    public LocalModel {
        if(tag==null || tag.length()>96 || !TAG.matcher(tag).matches() || CLOUD.matcher(tag).find())
            throw new IllegalArgumentException("expected a local Ollama model tag");
        if(port<1 || port>65535) throw new IllegalArgumentException("expected a loopback port between 1 and 65535");
        publicContext=chat && publicContext;
    }
    public static LocalModel from(Map<String,String> env) {
        boolean enabled="1".equals(env.get("SOCIETYBOTS_OLLAMA"));
        if(!enabled) return disabled();
        String rawPort=env.getOrDefault("SOCIETYBOTS_OLLAMA_PORT","11434");
        // Reject malformed settings; never silently route to a different endpoint or model.
        if(!rawPort.matches("[0-9]{1,5}")) throw new IllegalArgumentException("invalid local Ollama port");
        return new LocalModel("1".equals(env.getOrDefault("SOCIETYBOTS_OLLAMA_CHAT","1")),
                "1".equals(env.getOrDefault("SOCIETYBOTS_OLLAMA_PLAN","1")),
                "1".equals(env.get("SOCIETYBOTS_OLLAMA_PUBLIC_CONTEXT")),
                env.getOrDefault("SOCIETYBOTS_OLLAMA_MODEL","qwen3:4b"),Integer.parseInt(rawPort));
    }
    public static LocalModel disabled() { return new LocalModel(false,false,false,"qwen3:4b",11434); }

    public static Optional<PlanGate.Goal> proposal(String body) {
        if(body==null || body.length()>1024) return Optional.empty();
        var match=PROPOSAL.matcher(body);
        return match.matches()?Optional.of(PlanGate.Goal.valueOf(match.group(1))):Optional.empty();
    }
}
