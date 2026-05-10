# plan-review-deep.md — Cross-cutting governance

> Source of truth for `/pf plan-review-deep` runs in this template.
> Authored by Phase 14a (2026-05-01). **byte-identical** across `spring-template` (canonical), `python-template`, `typescript-template`.
> Hard review limit: **300 lines**. Weighted target: **200**.

## Table of Contents

1. F1 4 subfacet acceptance checklist
2. V0a / V0e / V_seed schema contract
3. §0 schema 5 strict items
4. Ratchet template
5. Phase E entry rubric
6. Phase 13c case study + external references
7. Critical-0 convergence loop policy
8. Phase 14b / 14c stack policy

---

## 1. F1 4 subfacet acceptance checklist

The 4 subfacets of **F1 (Failure-First Discipline)** — top-3 abandonment-cost mindset principle from `docs/superpowers/specs/2026-04-30-programming-principles-to-templates/step4-application-matrix-detailed.md`.

### F1.a Reproducible Failure
A failure that can be reproduced deterministically by another reviewer running the same command on the same source state. No "works on my machine".
**Verify**: capture exact command + working tree SHA + last 20 lines of stderr/stdout in the PR description.

### F1.b Staged Gate
The failure surfaces at a specific lifecycle stage (`clone` / `scaffold` / `verify` / `ci`) before downstream stages run. No silent late surface.
**Verify**: each `validate.sh` echo header `=== F1.b ... ===` precedes the gate it is gating; `ci` step that runs `validate.sh` exits non-zero on failure.

### F1.c Immutable Verification
The verify command and its expected exit code do not depend on local mutable state (timezone, locale, date, random network state). Same input → same output.
**Verify**: re-run the verify chain twice in a row; both must produce byte-identical stdout (modulo absolute timestamps that are masked in `awk`/`sed` filters).

### F1.d Full-Solution Verification
The verify covers the full solution path, not just a unit. A passing F1.d means the deliverable is shippable end-to-end, not just compilable.
**Verify**: at least one happy-path E2E or integration check is part of the verify chain. Unit-only verify is insufficient.

PASS counter convention: each subfacet emits a single `=== F1.x ... ===` header echo from `validate.sh`; CI counts headers via `grep -cE 'echo[[:space:]]+"=== F1\.[a-d]' validate.sh -eq 4`.

---

## 2. V0a / V0e / V_seed schema contract

The schema below is the binding contract between this rules file and `validate.sh` of all 3 templates. Per-template variation is allowed only on the values explicitly tagged "per-template" — the surrounding code structure must remain byte-identical (cross-template structure diff is part of Tier 2 manual review).

### V0a Self-monolithic guard

**Purpose**: prevent `validate.sh` / `scaffold.sh` / `SETUP.md` from accreting beyond their per-template line budget without an explicit ratchet (Section 4).

**Contract** (9 actual lines of bash, executed at the top of `validate.sh` after the seed echo): a `for spec in "validate.sh:N1" "scaffold.sh:N2"` loop that reads `wc -l` of each file and exits 1 with `FAIL: V0a $f has $n lines (limit $limit). 14a self-ratchet forbidden -- STOP and open a new review round.` on violation. Then a separate `wc -l < SETUP.md` block: `<= hard` exits 1, `<= soft` emits `WARN`. Per-template limits are the only values that may differ between templates.

**Per-template values** (Phase 14a rev.6 R5-corrected): spring `validate:560 / scaffold:500 / SETUP 220 soft / 260 hard` ; python `validate:400 / scaffold:395 / SETUP 220 soft / 250 hard` ; typescript `validate:570 / scaffold:490 / SETUP 220 soft / 260 hard`.

### V0e §0 anchor guard

**Purpose**: ensure `SETUP.md` §0 contains the 5 strict items of Section 3 below — fail-closed before any V1+ verify runs.

**Contract** (11 actual lines of bash): one `grep -q '^## Phase 0: System Overview'` header check + one `grep -q '^` ` `mermaid'` block check + a `for node in clone scaffold verify ci; do grep -qE "(^|[^[:alnum:]_-])${node}([^[:alnum:]_-]|$)" SETUP.md; done` 4-iteration word-boundary check + one `grep -q 'Change blast radius'` ENV column check + a `for h in 'Adding a new archetype' '...'; do grep -q "^### ${h}" SETUP.md; done` 4-iteration BRE heading check. Each missing item exits 1 with explicit `FAIL: V0e ...` message. Word-boundary for the 4 core nodes is required because `ci` is a substring of `initializr`. Phase 14a R6 ASCII canonical: heading literal contains no `§` (was `## §0 Phase 0: System Overview` before R6) so source bytes are pure ASCII -- ensures compatibility with templates that enforce ASCII-only-source guards (e.g. typescript-template V23).

### V_seed Worked example seed

**Purpose**: ensure the canonical entry seed file exists, has no stub phrases, and has a minimum number of lines — preventing a template from shipping a hollow archetype.

**Contract** (5 actual lines of bash): one `find <archetype seed dir> -name '<canonical filename>' | sort | head -1 || true` discovery + one `[[ -n "$seed" && -f "$seed" ]]` existence check + one `grep -qE '<per-template stub regex>' "$seed" && fail` stub-phrase blocker + one `[[ $(wc -l < "$seed") -ge <N> ]]` minimum-lines check. `sort | head -1` is required for deterministic selection across filesystems. `|| true` is required to keep `pipefail` shells from misclassifying broken pipe as failure.

**Per-template values**: spring `find examples/initializr-seed/src/main/java -name 'TemplateApplication.java'` + stub `(// TODO|UnsupportedOperationException|NotImplementedException|throw new RuntimeException\("not implemented"\))` + `≥3 lines` ; python `find examples/archetype-fastapi/src -name '*.py' -path '*/handlers/*'` + stub `^[[:space:]]*(pass|raise NotImplementedError\b.*|\.\.\.)[[:space:]]*$` + `≥5 lines` ; typescript `find examples/archetype-next/seed/src/app -name 'page.tsx'` + stub `(return null;|throw new Error\("Not implemented"\))` + `≥5 lines`.

---

## 3. §0 schema 5 strict items

`SETUP.md` of every template MUST contain a `## Phase 0: System Overview` section with the following 5 items, in order. V0e (Section 2) verifies each item. Updates to `SETUP.md` Phase 0 schema MUST be paired with a V0e update in the same PR (Q5 LOCK fail-closed semantics). Phase 14a R6 ASCII canonical: heading dropped the `§` prefix (was `## §0 ...` before R6) so the heading is pure ASCII -- compatible with templates that enforce ASCII-only-source guards.

(a) **Header**: a level-2 heading exactly `## Phase 0: System Overview`. No translation, no decoration, no extra spaces.

(b) **Mermaid block**: an opening fence ` ```mermaid ` (BRE-anchored at start of line) followed by a Mermaid diagram before any other code block. `flowchart` or `graph` direction is up to the template.

(c) **4 core nodes**: the Mermaid diagram MUST reference 4 named nodes: `clone`, `scaffold`, `verify`, `ci`. V0e checks each as a word-boundary token (substring matches like `ci` ⊂ `initializr` MUST NOT pass). The scaffold subgraph (per-template archetype list) is allowed but does not satisfy this item by itself.

(d) **ENV table with `Change blast radius` column**: a Markdown table whose header row contains the literal string `Change blast radius`. The table SHOULD enumerate environment dependencies (one row per variable / file / service) and rate the blast radius of changing each.

(e) **Extension Points — 4 strict English headings**: four level-3 headings, in any order, with EXACT English text: `### Adding a new archetype`, `### Adding a new verify step`, `### Adding a new env dependency`, `### Phase E (DDD/TDD) stack hook`. Translations or punctuation variants do not satisfy V0e (which uses `grep -q` BRE — literal match).

---

---

## 4. Ratchet template

A ratchet is a deliberate, documented raise of a per-template threshold (V0a `validate.sh` / `scaffold.sh` / `SETUP.md` limit). Phase 14a itself does NOT permit any same-PR ratchet — if Phase 14a code triggers V0a, the response is `STOP` and request an R5 (or later) refine round. Subsequent stack Phases (14b, 14c) MAY ratchet under the rules below.

**Trigger conditions**
- ≤10% raise vs current per-template threshold + same-PR + reviewer-approved → permitted.
- >10% raise OR cross-template — separate PR, separate review.

**Standard PR body block** — paste verbatim into the ratchet PR description:
```
### Ratchet — V0a `<file>` `<old>` → `<new>` (+<delta> lines, <+%>%)
- Reason (Reality Lens): why the existing threshold is no longer adequate
- Diff scope: what 14b / 14c addition pushed past the limit
- Re-verify: post-merge `wc -l <file>` matches `<new>` ± 0
- Reviewer: @<handle>
```

**Reality Lens re-verification** — every ratchet PR MUST include a `wc -l` capture in its description showing the actual line count after the new code lands. The capture command + its stdout MUST appear in the PR body before merge.

---

## 5. Phase E entry rubric

Phase E (DDD/TDD 3-template stack — jMolecules / hexagonal / FSD-DDD) entry is gated by the 3-axis rubric below. Phase 14a-bis is the **separate** Phase that authors this rubric in detail; this Section is a normative summary only.

**14a-bis 5-line meaning checklist** (Phase E hook body, one line each; the 14a-bis canonical version preserves the same 5 keywords listed below):
1. Flexibility: small UL/BC edits (2-3 words or additions) allowed; core domain redefinition requires a new project.
2. Universality: rule semantics MUST be expressible in terms common to all 3 stacks (Spring/Python/TS).
3. Convention precedence: per-stack idiom > shared abstraction when they conflict — convention wins, abstractions are opt-in.
4. Contract test specifications: every cross-stack rule has an executable contract test in each template (no English-only enforcement).
5. Opt-in examples / docs / CI only: examples + docs are opt-in; CI gates are opt-in unless a Phase explicitly opts a rule into the V0/V_seed contract.

**3-axis summary**
- **Flexibility**: where templates may diverge (small edits, archetype-specific adapters).
- **Universality**: what must hold across all 3 templates (V0a/V0e/V_seed schema, §0 5 strict items, F1 4 subfacets).
- **Convention precedence**: when stack convention conflicts with shared abstraction, convention wins; the shared abstraction becomes opt-in.

**Cross-drift policy** (Phase 14a R4 CX4-6, refined by 14a-bis Q1=B K3 LOCK): the 5-line meaning checklist above and the 14a-bis canonical version of the same checklist are a single source of truth. Any update to one MUST update the other with the 5 keywords (flexibility / universality / convention precedence / contract test specifications / opt-in examples) preserved in the same PR; the V_drift CI guard (validate.sh) enforces 5-keyword presence + 5-line count + negation-marker absence. If a drift surfaces (e.g., a Phase E PR changes 14a-bis but not this Section 5), the policy is to **reconcile against the spring-template `plan-review-deep.md` Section 5** as canonical and re-issue the 14a-bis update accordingly.

---

## 6. Phase 13c case study + external references

Phase 13c (TypeScript clone+script architecture, merged `c8f4a97` on 2026-04-26 in `llm-setup-templates/typescript-template`) is the canonical worked example of the F1-discipline + scaffold/verify split + 3-tier validation that `V0a/V0e/V_seed` codify. Phase 13c plan-review-deep (Round 1-3, Critical-0 convergence) demonstrated that the Reality Lens catches `examples/.../seed/` directory drift that Contract+Completeness Lenses miss.

**External references** (necessity grounded outside this Phase, per CRITIQUE.md Necessity remedy):

- [Thoughtworks — TDD as a scaffold for a better product](https://www.thoughtworks.com/insights/blog/testing/tdd-as-a-scaffold-for-a-better-product): TDD as mindset/process/tool, not a single tactic.
- [Spec-driven development — Wikipedia](https://en.wikipedia.org/wiki/Spec-driven_development): specification as executable contract; aligns with V0e/V_seed fail-closed semantics.
- [Cookiecutter — advanced hooks](https://cookiecutter.readthedocs.io/en/stable/advanced/hooks.html): fail-closed `pre_gen_project` / `post_gen_project` pattern; precedent for V0a/V_seed exit-1-on-violation.

---

## 7. Critical-0 convergence loop policy

A `plan-review-deep` Round converges when **(a) Critical issues = 0** + verdict ∈ {PROCEED, PROCEED-WITH-CONDITIONS}, OR **(b) Round 5 (max)** is reached and the user explicitly elects escalate option (b) "Critical을 ADR로 박제 후 PROCEED-WITH-CONDITIONS".

Each round must rotate the Lens. Reference: `~/.claude/skills/project-flow/planning.md` §"Mode: plan-review-deep" — full rule including 4 Lens definitions (Contract / Completeness / Reality / Runtime Contract). New-Phase canonical cycle: Contract → Completeness → Reality → Runtime Contract → user-selected (Phase 14a R1-R5 confirmed this cycle, R5 user-selected Reality refine for baseline correction).

---

## 8. Phase 14b / 14c stack policy

After Phase 14a (top-3 mindset principles F1 + F2 + F4 + F12 → landed across 3 templates) merges, the next stacks open in this order. Both must follow Section 4 (Ratchet template) for any same-PR threshold raise.

### Phase 14b (compromise reduction + F7 3-tier expansion)
- F5 / F8 / F10 layered additions per `step4-application-matrix-detailed.md`.
- F7 3-tier validation expansion: `V_seed` → `V_seed-l1 / l2 / l3` per archetype maturity (typescript Tier1=F1.c / Tier2=F1.b / Tier3=F1.a precedent).
- Same-PR ratchet allowance: ≤10% over Phase 14a-locked threshold per template (see Phase 14a PLAN.md per-template threshold rev.6 R5-corrected).

### Phase 14c (deferred F11 + remainder)
- F11 (Hidden Behavior Coverage) — deferred from 14a per Q5 LOCK.
- Final cleanup of Phase 14a compromises (CX-10 spring V_seed `// TODO` broad pattern, R3-09 python `routers/` V_seed addition, etc.).

Phase 14a-bis (3-axis Phase E entry rubric, Section 5) is a **separate Phase**, not a 14b/14c stack item.
