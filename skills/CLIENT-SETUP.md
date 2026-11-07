# Client setup

Everything here is about getting the same two files in front of a different agent. Nothing in the
skill is client-specific.

Two layers, and they fail independently:

| Layer | What it provides | If missing |
|---|---|---|
| `SKILL.md` + `reference/` | **judgement** — which tool to call, how to read the result, what not to do | the agent can still call the tools, and will produce confident wrong answers |
| the `spring-mcp` MCP server | **capability** — the 11 tools that actually read source | nothing to call; no amount of instruction helps |

Configure both, then verify with one call to `list-projects`.

---

## Where `SKILL.md` goes

| Client | Method | Confidence |
|---|---|---|
| **WorkBuddy** | Copy the folder to `~/.workbuddy/skills/spring-mcp/`, restart the app. Or drag `SKILL.md` into the chat box. Or install from SkillHub. | **Verified** — loaded and invoked on 2026-11-07 |
| **Claude Code** | Copy the folder to `~/.claude/skills/spring-mcp/` (personal) or `<repo>/.claude/skills/spring-mcp/` (per project) | Vendor convention |
| **Cursor / Codex CLI / Gemini CLI / VS Code Copilot** | Follow the client's own skill or rule mechanism | Listed as compatible by Agensi; not tested here one by one |
| **Anything else** (Trae, 通义灵码, CodeGeeX, 文心快码, 豆包, …) | Use the fallback below | Unknown — do not assume it reads `SKILL.md` |

The `SKILL.md` frontmatter carries both English and Chinese fields (`description`,
`description_zh`, `examples_zh`, `display_name`), so clients that render skill metadata in a Chinese
marketplace show it correctly instead of falling back to English.

---

## The fallback that works everywhere

Almost every coding agent reads **project-level instructions**: a file in the repo, or a "custom
rules" box in settings. Paste the skill in there and the judgement layer works even when the client
has never heard of `SKILL.md`.

Create `AGENTS.md` at the repository root:

```markdown
## spring-mcp

This is a Spring project. For questions about its HTTP surface, service call graph, persistence
layer, Feign clients or gateway routes, use the spring-mcp MCP server and follow the rules in
`skills/spring-mcp/SKILL.md` (read that file first).

Non-negotiable, in short:

- Every path and count comes from a tool result. No estimates, no reading source by hand and
  calling it equivalent.
- `pathResolved=false` is the answer. Never complete `${...}` into a URL that looks plausible.
- Before reporting "0 endpoints", confirm `projectRoot` is the source root — run `list-modules`.
- Surface `warnings[]`. A dropped warning becomes a wrong answer two sentences later.
```

Where to put it, per client: `AGENTS.md` (Codex CLI, and increasingly others), `.cursor/rules/`
(Cursor), the 自定义规则 / 项目知识 field in domestic IDEs, or the system-prompt box if the client
has one.

If the client cannot read files at all — a plain chat window — paste the **中文速查** section at the
end of `SKILL.md` as custom instructions. It is five lines and carries the rules that matter most.

---

## The MCP server, per client

The server is a plain stdio process; every client needs the same three facts: the command, the
arguments, and a restart.

```json
{
  "mcpServers": {
    "spring-mcp": {
      "command": "/abs/path/spring-mcp/spring-mcp.sh",
      "args": ["--project=/abs/path/to/your-spring-app"]
    }
  }
}
```

- Windows: `"command": "C:/abs/path/spring-mcp/spring-mcp.cmd"`, or the absolute `java.exe` that
  `scripts/preflight.sh` prints if `java` is not on `PATH`.
- Clients with a UI ("add MCP server", "自定义 MCP") take the same command and arguments through
  form fields instead of JSON.
- VS Code style clients use a `servers` key inside `.vscode/mcp.json` rather than `mcpServers` at
  the user level — check the client's own doc, the values are identical.
- Some clients require an explicit **trust / enable** step for a newly added server. If
  `list-projects` is missing from the tool list, that step is usually why.

`scripts/preflight.sh` prints the exact JSON for the machine it runs on, which removes the two
commonest mistakes: a relative jar path, and a `java` that is not on `PATH`.

---

## Verifying

1. Ask the agent to call `list-projects`. It must return JSON, not an error.
2. Ask a question that needs a real read: *"which endpoints does this project expose?"*
3. Check the answer for the two signatures of a working setup: `file:line` on every endpoint, and
   `warnings[]` reported rather than swallowed.

If step 1 works and step 2 is vague, the MCP layer is fine and the skill layer is missing. If step 1
fails, the skill layer is irrelevant — the server is not running.
