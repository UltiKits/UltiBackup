## Summary

<!-- What this pull request changes, and why. English-first, Chinese as a supplement. -->

## Issue closure

<!--
REQUIRED - do not delete this section.

List every issue this pull request closes, one per line, using a closing keyword:

    Closes #1234

Write `None` if it closes nothing, so that an empty section reads as a decision rather than
an oversight.

Why this section exists: `master` is this repository's own default branch (unlike the
framework, which merges feature work into `alpha` first), so a `Closes #1234` written outside
this comment DOES act automatically on merge here -- GitHub only honours closing keywords on a
merge into the default branch, and a pull request into `master` already is one. There is no
`phase-closeout.yml` or equivalent workflow in this repository (`.github/workflows/` holds only
`maven-ci.yml` and `publish.yml`) -- this section's declarations are what closes the issue, not a
follow-up automation step. State `None` explicitly rather than leaving the section blank, so a
reviewer can tell "closes nothing" apart from "forgot to fill this in".

Anything inside an HTML comment, a code fence, or backticks does not count as a real declaration
-- which is why the example above closes nothing. Put real declarations outside this comment.
-->

## Verification

<!-- The commands you ran and what they actually reported. Paste the counts, not "tests pass". -->

## Checklist

<!-- Delete any line that does not apply. -->

- [ ] Targets `master`
- [ ] Line endings preserved per file (`file <path>` before and after; this tree is mixed CRLF/LF)
- [ ] Every comment, javadoc, workflow comment, and this PR's own title and body are English-first with Chinese as a supplement, and nothing was added to `.github/cjk-allowlist.txt`
- [ ] `FEATURES.md` and `UAT-CHECKLIST.md` updated for every feature change in this PR, or N/A with the reason
