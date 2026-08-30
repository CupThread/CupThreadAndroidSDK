#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync
} from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import readline from "node:readline/promises";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const R2_BUCKET = process.env.R2_BUCKET || "cupthread-sdks";
const CDN_BASE = process.env.CDN_BASE || "https://cdn.cupthread.com";

const ANDROID_CONTENT_TYPES = {
  pom: "application/xml", module: "application/json", aar: "application/vnd.android.package-archive",
  jar: "application/java-archive", asc: "application/pgp-signature",
  sha1: "text/plain", md5: "text/plain", sha512: "text/plain", sha256: "text/plain"
};

function parseArgs(argv) {
  const args = { dryRun: false, skipTests: false, yes: false, version: null, skipUpload: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--version") args.version = argv[++i];
    else if (arg === "--dry-run") args.dryRun = true;
    else if (arg === "--skip-tests") args.skipTests = true;
    else if (arg === "--skip-upload") args.skipUpload = true;
    else if (arg === "--yes") args.yes = true;
    else fail(`Unknown argument: ${arg}`);
  }
  if (!args.version || !/^\d+\.\d+\.\d+$/.test(args.version)) {
    fail("--version must be semver, e.g. --version 0.1.0");
  }
  return args;
}

function fail(message) {
  console.error(`✗ ${message}`);
  process.exit(1);
}

function run(cmd, args, opts = {}) {
  console.log(`  $ ${cmd} ${args.join(" ")}`);
  const result = spawnSync(cmd, args, {
    cwd: opts.cwd ?? ROOT,
    stdio: opts.capture ? ["ignore", "pipe", "inherit"] : "inherit",
    encoding: "utf8"
  });
  if (result.status !== 0) fail(`Command failed (${result.status}): ${cmd} ${args.join(" ")}`);
  return result.stdout;
}

function sha256(file) {
  const hash = createHash("sha256");
  hash.update(readFileSync(file));
  return hash.digest("hex");
}

function humanSize(bytes) {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function uploadTree(dir, prefix) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) uploadTree(full, `${prefix}/${entry.name}`);
    else {
      const ext = entry.name.split(".").pop().toLowerCase();
      run("npx", ["wrangler", "r2", "object", "put", `${R2_BUCKET}/${prefix}/${entry.name}`,
        "--file", full, "--remote", "--content-type", ANDROID_CONTENT_TYPES[ext] ?? "application/octet-stream"]);
    }
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const version = args.version;
  const gradle = path.join(ROOT, "gradlew");
  const staging = path.join(ROOT, "build/release/android-maven");

  if (!args.dryRun && !args.yes) {
    if (!process.stdout.isTTY) fail("Refusing to publish without --yes (or use --dry-run).");
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    const answer = await rl.question(`Release Android SDK v${version} to ${CDN_BASE} + GitHub? [y/N] `);
    rl.close();
    if (!/^y(es)?$/i.test(answer.trim())) fail("Aborted.");
  }

  console.log(`\n🤖 Building CupThread Android SDK v${version}`);

  if (!args.skipTests) {
    console.log("• unit tests");
    run(gradle, [":feedback:testDebugUnitTest", "--console=plain", "-q"], { cwd: ROOT });
  }

  console.log(`• bumping feedback/build.gradle.kts to ${version}`);
  const gradleFile = path.join(ROOT, "feedback/build.gradle.kts");
  const versionLine = /^version = findProperty\("cupthreadVersion"\) \?: ".*"$/m;
  const previous = readFileSync(gradleFile, "utf8");
  if (!versionLine.test(previous)) {
    fail('expected "version = findProperty(\\"cupthreadVersion\\") ?: …" line in feedback/build.gradle.kts');
  }
  if (!args.dryRun) {
    writeFileSync(gradleFile, previous.replace(versionLine, `version = findProperty("cupthreadVersion") ?: "${version}"`));
  }

  console.log("• publishing Maven repo layout to staging");
  rmSync(staging, { recursive: true, force: true });
  run(gradle, [":feedback:publishReleasePublicationToCdnRepository", "--console=plain", "-q",
    `-PcupthreadVersion=${version}`, `-PcupthreadRepoDir=${staging}`], { cwd: ROOT });

  const aarPath = path.join(staging, "dev/cupthread/feedback", version, `feedback-${version}.aar`);
  if (!existsSync(aarPath)) fail(`expected artifact missing: ${aarPath}`);
  const artifact = {
    name: "Maven artifact (AAR · POM · Gradle module metadata)",
    filename: `feedback-${version}.aar`,
    url: `${CDN_BASE}/maven/dev/cupthread/feedback/${version}/feedback-${version}.aar`,
    size: humanSize(statSync(aarPath).size),
    sha256: sha256(aarPath)
  };
  console.log(`  dev.cupthread:feedback:${version}  ${artifact.size}  sha256:${artifact.sha256}`);

  const releaseInfo = {
    sdk: "android",
    version,
    date: new Date().toISOString().slice(0, 10),
    artifact,
    notes: [
      `CupThread Android SDK v${version}`,
      "Jetpack Compose surfaces: roadmap board, What's New, feature requests, feedback composer.",
      "minSdk 26 (Android 8.0+) · Material 3 · Kotlin 2.x.",
      `Repository: ${CDN_BASE}/maven — coordinates dev.cupthread:feedback:${version}.`
    ]
  };

  const infoFile = path.join(ROOT, "build/release/release-info.json");
  mkdirSync(path.dirname(infoFile), { recursive: true });
  writeFileSync(infoFile, JSON.stringify(releaseInfo, null, 2) + "\n");

  if (args.dryRun) {
    console.log(`\n  [dry-run] Android SDK v${version} built successfully.`);
    return;
  }

  if (!args.skipUpload) {
    console.log("• uploading maven/ tree to R2");
    uploadTree(staging, "maven");
  }

  console.log("• committing version bump and creating release tag");
  run("git", ["add", "feedback/build.gradle.kts"]);
  run("git", ["commit", "-m", `release: android SDK v${version}`]);
  const tag = `v${version}`;
  run("git", ["tag", "-a", tag, "-m", `Release ${tag}`]);
  run("git", ["push", "origin", "HEAD", tag]);
  run("gh", ["release", "create", tag,
    "--title", `Android SDK v${version}`,
    "--notes", releaseInfo.notes.map((n) => `- ${n}`).join("\n"),
    aarPath]);

  console.log(`\n✓ Android SDK v${version} successfully released!`);
}

main().catch((err) => fail(err.stack || err.message));
