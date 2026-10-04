# Local Git provenance

This workspace originally contained an empty `.git` directory mounted read-only
by the desktop environment. It could not be initialized or replaced. To preserve
real local commit objects without inventing history, the implementation baseline
uses `.git-local` as `GIT_DIR` with this workspace as its configured work tree.

Use it exactly like a normal local repository by supplying the Git directory:

```bash
git --git-dir=.git-local status
git --git-dir=.git-local log --oneline --decorate
git --git-dir=.git-local show --stat HEAD
```

No remote is configured and no push is performed. The initial commit date is the
actual local verification date; it is not evidence of past employment, team
tenure, production deployment, or historical customer usage.

If the read-only `.git` mount is later removed, convert the workspace back to the
usual layout while preserving every object and commit:

```bash
mv .git-local .git
git status
```

Resolve the mounted empty `.git` directory first; do not delete or replace a
non-empty repository.
