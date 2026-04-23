# CI / CD — maintainer setup

Everything in this repo's `.github/` is driven by three workflows and one config:

| File | Purpose | Trigger |
|---|---|---|
| `.github/workflows/ci.yml` | Build + verify on every PR and every push to `main`. | `push`, `pull_request`, `workflow_dispatch` |
| `.github/workflows/codeql.yml` | GitHub security + quality analysis. | `push`, `pull_request`, weekly cron |
| `.github/workflows/release.yml` | Release to Maven Central, tag, publish GitHub Release. | `workflow_dispatch` only (manual) |
| `.github/dependabot.yml` | Grouped weekly/monthly dependency PRs. | Dependabot runtime |

The rest of this document lists the one-time setup a maintainer has to do to make the release workflow green.

---

## One-time GitHub configuration

### 1. Repository secrets

Create these under **Settings → Secrets and variables → Actions**:

| Secret name | What it is | Where to get it |
|---|---|---|
| `CENTRAL_USERNAME` | Central Portal *user token* username | [central.sonatype.com](https://central.sonatype.com) → Account → Generate User Token |
| `CENTRAL_TOKEN` | Central Portal *user token* password | same page as above |
| `GPG_PRIVATE_KEY` | ASCII-armored private key used to sign artifacts | `gpg --export-secret-keys --armor <KEY_ID>` — paste the full block including `-----BEGIN PGP PRIVATE KEY BLOCK-----` |
| `GPG_PASSPHRASE` | Passphrase that unlocks the key above | The one you set when generating the key |

`GITHUB_TOKEN` is provisioned by Actions automatically — no setup needed.

### 2. GPG key — publishing to a keyserver

Maven Central verifies signatures against public GPG keyservers. The public half of whichever key you used for `GPG_PRIVATE_KEY` must be reachable at:

```
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

If this is a fresh key, allow ~15 minutes for key propagation before the first release.

### 3. GitHub Environment — `maven-central`

The release workflow runs in an environment called `maven-central`. Create it under **Settings → Environments → New environment** and consider:

- **Required reviewers** — at least one reviewer must approve each release job before it runs. This turns every release into a "sign off to ship" step, matching how a release would happen in a mature project.
- **Deployment branches** — restrict to `main` only. The workflow already guards this at runtime, but a redundant gate at the environment level is cheap and safer.
- **Environment secrets** — optionally re-scope `CENTRAL_*` and `GPG_*` to this environment rather than leaving them at repo level. Tighter access control.

### 4. Maven Central namespace — one-time

`com.github.ifrugal` has to be verified for the publishing account on Central Portal. Since the sibling repo `all-about-persistence` already publishes under this namespace, no action needed — re-use the same verified namespace.

---

## Running a release

Once the above is set up:

1. Go to **Actions → Release to Maven Central → Run workflow**.
2. (Optional) Fill in `release-version` and `development-version` — or leave blank to let `maven-release-plugin` auto-derive.
3. Tick `dry-run: true` for the first run to validate the wiring without publishing.
4. Approve the deployment in the `maven-central` environment when prompted.
5. The workflow will:
   1. Pre-flight `./mvnw clean verify` (no deploy).
   2. `release:prepare` — bumps to release version, commits, tags, bumps to next SNAPSHOT, commits.
   3. `release:perform` — checks out the tag, builds, signs, uploads to Central.
   4. Publishes a GitHub Release with auto-generated notes.

Central will show the release as "validated" within a few minutes. Promotion to the real index is automatic when `central.waitUntil=uploaded` succeeds.

---

## When a release goes wrong

- **`release:prepare` fails** after having committed version bumps: run locally `./mvnw release:rollback`, then investigate.
- **`release:perform` fails** while uploading to Central: the tag exists but no artifacts. You can safely retry by running the workflow again with the same version; Central will reject duplicates with a clear error.
- **Signature verification fails on Central**: your key is probably not propagated to the keyservers yet — wait 15 minutes, retry.
- **`central.waitUntil=uploaded` times out**: Central is slow occasionally; retry the workflow, it's idempotent.

---

## Relationship to `all-about-persistence`

This setup is modeled after `iFrugal/all-about-persistence` (that repo's `release-action.yml`) with the following deliberate improvements:

| | `all-about-persistence` | `notification-service` |
|---|---|---|
| CI on PRs | ❌ not configured | ✅ `ci.yml` + `codeql.yml` |
| Release trigger | Push to `master` (every merge) | `workflow_dispatch` only (manual) |
| Maven cache | ❌ | ✅ via `setup-java`'s `cache: maven` |
| Dry-run input | ❌ | ✅ |
| Pre-flight verify | ❌ | ✅ explicit step before release |
| Environment gating | ❌ | ✅ `maven-central` environment |
| GitHub Release creation | ❌ | ✅ via `action-gh-release` |
| Dependabot | ⚠ stub (empty `package-ecosystem`) | ✅ Maven + GH Actions, grouped |
| CodeQL | ❌ | ✅ with `security-and-quality` query pack |

If any of these improvements prove annoying in practice, trim them. The point is to make routine releases boring and to make every PR's health visible on GitHub.
