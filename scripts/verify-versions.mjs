import { readFile } from "node:fs/promises";

const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
const gradle = await readFile(new URL("../android/app/build.gradle", import.meta.url), "utf8");

const versionName = gradle.match(/versionName\s+"([^"]+)"/)?.[1];
const versionCode = Number(gradle.match(/versionCode\s+(\d+)/)?.[1]);

if (!versionName || !Number.isInteger(versionCode) || versionCode < 1) {
  throw new Error("Could not read a valid Android versionName/versionCode");
}

if (packageJson.version !== versionName) {
  throw new Error(
    `Version mismatch: package.json=${packageJson.version}, Android=${versionName}`,
  );
}

console.log(`OpenPanel ${versionName} (Android versionCode ${versionCode})`);
