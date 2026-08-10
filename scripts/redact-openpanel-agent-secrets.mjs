#!/usr/bin/env node

import { constants } from "node:fs";
import {
  access,
  chmod,
  lstat,
  mkdir,
  readFile,
  readdir,
  rename,
  unlink,
  writeFile,
} from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const REDACTION = "[REDACTED_OPENPANEL_SIGNING_PASSWORD]";
const CANDIDATE_PATTERN =
  /(?:^|[^A-Za-z0-9+/])([A-Za-z0-9+/]{43}=)(?![A-Za-z0-9+/=])/g;
const COMMAND_MARKER = "openssl rand -base64 32";
const VARIABLE_MARKERS = ["NEW_PASS", "NEW_KEYSTORE_PASS"];
const SIGNING_CONTEXT_MARKERS = [
  "keytool",
  "keystore",
  "openpanel-upload",
  "openpanel-production-v2",
  "OPENPANEL_KEYSTORE_PASS",
  ...VARIABLE_MARKERS,
];
const MAX_SIGNING_RECORD_DISTANCE = 16;
const TEXT_EXTENSIONS = new Set([".jsonl", ".log", ".md", ".txt"]);

const scriptPath = fileURLToPath(import.meta.url);
const projectRoot = path.resolve(path.dirname(scriptPath), "..");
const privateAgentState = path.join(projectRoot, "private", "agent-state");
const sharedHistoryFiles = [
  "/Volumes/Files/Projects/Infrastructure & Server/ServerPlus/Files/agent-state/claude/history.jsonl",
  "/Volumes/Files/Projects/Infrastructure & Server/ServerPlus/Files/agent-state/claude-archive-20260718/history.jsonl",
  "/Volumes/Files/Projects/Infrastructure & Server/ServerPlus/Files/Chat Storage/codex/process_manager/chat_processes.json",
  "/Volumes/Files/Projects/Infrastructure & Server/ServerPlus/Files/Chat Storage/codex/sessions/2026/07/21/rollout-2026-07-21T15-31-20-019f8660-3822-7b71-8414-77ae554defb2.jsonl",
];

function usage() {
  console.log(
    "Usage: node scripts/redact-openpanel-agent-secrets.mjs --dry-run|--apply|--verify",
  );
}

function walkStrings(value, output) {
  if (typeof value === "string") {
    output.push(value);
    return;
  }

  if (Array.isArray(value)) {
    for (const item of value) walkStrings(item, output);
    return;
  }

  if (value && typeof value === "object") {
    for (const item of Object.values(value)) walkStrings(item, output);
  }
}

function parseRecords(filePath, text) {
  if (path.extname(filePath) === ".json") {
    let value;
    try {
      value = JSON.parse(text);
    } catch {
      throw new Error(`Invalid JSON at ${filePath}`);
    }

    const strings = [];
    walkStrings(value, strings);
    return [{ index: 0, strings }];
  }

  if (path.extname(filePath) !== ".jsonl") {
    return [{ index: 0, strings: [text] }];
  }

  const records = [];
  const lines = text.split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (!line.trim()) continue;

    let value;
    try {
      value = JSON.parse(line);
    } catch {
      throw new Error(`Invalid JSONL at ${filePath}:${index + 1}`);
    }

    const strings = [];
    walkStrings(value, strings);
    records.push({ index, strings });
  }

  return records;
}

function hasCommandMarker(strings) {
  const hasCommand = strings.some((value) => value.includes(COMMAND_MARKER));
  const hasVariable = strings.some((value) =>
    VARIABLE_MARKERS.some((marker) => value.includes(marker)),
  );
  return hasCommand && hasVariable;
}

function extractCandidatesFromMarkedRecords(records) {
  const hasMarker = records.some((record) => hasCommandMarker(record.strings));
  const candidates = new Set();

  for (const record of records) {
    if (!hasMarker) continue;

    for (const value of record.strings) {
      CANDIDATE_PATTERN.lastIndex = 0;
      for (const match of value.matchAll(CANDIDATE_PATTERN)) {
        candidates.add(match[1]);
      }
    }
  }

  return candidates;
}

async function collectTextFiles(directory) {
  const files = [];

  async function visit(currentPath) {
    const entries = await readdir(currentPath, { withFileTypes: true });
    for (const entry of entries) {
      const entryPath = path.join(currentPath, entry.name);
      if (entry.isDirectory()) {
        await visit(entryPath);
      } else if (entry.isFile() && TEXT_EXTENSIONS.has(path.extname(entry.name))) {
        files.push(entryPath);
      }
    }
  }

  await visit(directory);
  return files;
}

async function existingFiles(paths) {
  const found = [];
  for (const filePath of paths) {
    try {
      await access(filePath, constants.R_OK | constants.W_OK);
      found.push(filePath);
    } catch {
      throw new Error(`Required history file is unavailable: ${filePath}`);
    }
  }
  return found;
}

async function loadTargets() {
  const projectFiles = await collectTextFiles(privateAgentState);
  const sharedFiles = await existingFiles(sharedHistoryFiles);
  const files = [...projectFiles, ...sharedFiles].sort();
  const loaded = [];

  for (const filePath of files) {
    const text = await readFile(filePath, "utf8");
    loaded.push({
      filePath,
      records: parseRecords(filePath, text),
      text,
    });
  }

  return loaded;
}

function discoverCandidates(loaded) {
  const candidates = new Set();
  for (const item of loaded) {
    for (const candidate of extractCandidatesFromMarkedRecords(item.records)) {
      candidates.add(candidate);
    }
  }
  return candidates;
}

function selectSigningCandidates(loaded, candidates) {
  const selected = new Set();
  for (const candidate of candidates) {
    for (const item of loaded) {
      const candidateIndexes = item.records
        .filter((record) =>
          record.strings.some((value) => value.includes(candidate)),
        )
        .map((record) => record.index);
      const signingIndexes = item.records
        .filter((record) =>
          record.strings.some((value) =>
            SIGNING_CONTEXT_MARKERS.some((marker) => value.includes(marker)),
          ),
        )
        .map((record) => record.index);
      const isSigningAdjacent = candidateIndexes.some((candidateIndex) =>
        signingIndexes.some(
          (signingIndex) =>
            Math.abs(candidateIndex - signingIndex) <= MAX_SIGNING_RECORD_DISTANCE,
        ),
      );
      if (isSigningAdjacent) selected.add(candidate);
    }
  }
  return selected;
}

function countOccurrences(text, value) {
  if (!value) return 0;
  return text.split(value).length - 1;
}

function summarizeOccurrences(loaded, candidate) {
  let fileCount = 0;
  let occurrenceCount = 0;
  for (const item of loaded) {
    const count = countOccurrences(item.text, candidate);
    if (count > 0) fileCount += 1;
    occurrenceCount += count;
  }
  return { fileCount, occurrenceCount };
}

function describeCandidateLocations(loaded, candidates) {
  const candidateList = [...candidates];
  const lines = [];
  for (const item of loaded) {
    const counts = candidateList.map((candidate) =>
      countOccurrences(item.text, candidate),
    );
    if (counts.every((count) => count === 0)) continue;

    const label = item.filePath.startsWith(projectRoot)
      ? path.relative(projectRoot, item.filePath)
      : item.filePath;
    const markerIndexes = item.records
      .filter((record) => hasCommandMarker(record.strings))
      .map((record) => record.index);
    const signingIndexes = item.records
      .filter((record) =>
        record.strings.some((value) =>
          SIGNING_CONTEXT_MARKERS.some((marker) => value.includes(marker)),
        ),
      )
      .map((record) => record.index);
    const metadata = candidateList.map((candidate, index) => {
      const recordIndexes = item.records
        .filter((record) =>
          record.strings.some((value) => value.includes(candidate)),
        )
        .map((record) => record.index);
      const distances = recordIndexes.flatMap((recordIndex) =>
        markerIndexes.map((markerIndex) => Math.abs(recordIndex - markerIndex)),
      );
      const distance = distances.length > 0 ? Math.min(...distances) : "none";
      const signingDistances = recordIndexes.flatMap((recordIndex) =>
        signingIndexes.map((signingIndex) => Math.abs(recordIndex - signingIndex)),
      );
      const signingDistance =
        signingDistances.length > 0 ? Math.min(...signingDistances) : "none";
      return `candidate-${index + 1}-records=${recordIndexes.join("/") || "none"},command-distance=${distance},signing-distance=${signingDistance}`;
    });
    lines.push(
      `${label}: ${counts.map((count, index) => `candidate-${index + 1}=${count}`).join(", ")}; ${metadata.join("; ")}`,
    );
  }
  return lines;
}

async function atomicRewrite(filePath, text) {
  const stat = await lstat(filePath);
  const temporaryPath = `${filePath}.openpanel-redact-${process.pid}.tmp`;

  try {
    await writeFile(temporaryPath, text, { mode: stat.mode });
    await chmod(temporaryPath, stat.mode);
    await rename(temporaryPath, filePath);
  } finally {
    try {
      await unlink(temporaryPath);
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
  }
}

async function applyRedaction(loaded, candidate) {
  let changedFiles = 0;
  let replacements = 0;

  for (const item of loaded) {
    const count = countOccurrences(item.text, candidate);
    if (count === 0) continue;

    const redactedText = item.text.split(candidate).join(REDACTION);
    parseRecords(item.filePath, redactedText);
    await atomicRewrite(item.filePath, redactedText);
    changedFiles += 1;
    replacements += count;
  }

  return { changedFiles, replacements };
}

async function verify() {
  const loaded = await loadTargets();
  const candidates = selectSigningCandidates(
    loaded,
    discoverCandidates(loaded),
  );
  const redactionCount = loaded.reduce(
    (total, item) => total + countOccurrences(item.text, REDACTION),
    0,
  );

  if (candidates.size > 0) {
    throw new Error(
      `Verification failed: ${candidates.size} unredacted signing credential candidate(s) remain.`,
    );
  }
  if (redactionCount === 0) {
    throw new Error("Verification failed: no redaction markers were found.");
  }

  console.log(
    `Verification passed: ${redactionCount} redaction marker(s); JSONL remains valid.`,
  );
}

async function main() {
  const mode = process.argv[2];
  if (!["--dry-run", "--apply", "--verify"].includes(mode)) {
    usage();
    process.exitCode = 2;
    return;
  }

  await mkdir(privateAgentState, { recursive: true, mode: 0o700 });

  if (mode === "--verify") {
    await verify();
    return;
  }

  const loaded = await loadTargets();
  const candidates = selectSigningCandidates(
    loaded,
    discoverCandidates(loaded),
  );
  if (candidates.size !== 1) {
    for (const line of describeCandidateLocations(loaded, candidates)) {
      console.error(line);
    }
    throw new Error(
      `Expected exactly one signing credential candidate, found ${candidates.size}; no files changed.`,
    );
  }

  const [candidate] = candidates;
  const summary = summarizeOccurrences(loaded, candidate);
  if (mode === "--dry-run") {
    console.log(
      `Dry run passed: 1 credential candidate, ${summary.occurrenceCount} occurrence(s) in ${summary.fileCount} file(s).`,
    );
    return;
  }

  const result = await applyRedaction(loaded, candidate);
  console.log(
    `Redaction applied: ${result.replacements} replacement(s) in ${result.changedFiles} file(s).`,
  );
  await verify();
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
