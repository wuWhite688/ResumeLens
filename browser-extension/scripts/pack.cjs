const path = require("node:path");
const { spawnSync } = require("node:child_process");

const script = path.join(__dirname, "package.ps1");
const command = process.platform === "win32" ? "powershell.exe" : "pwsh";
const args = ["-NoLogo", "-NoProfile"];
if (process.platform === "win32") args.push("-ExecutionPolicy", "Bypass");
args.push("-File", script);

const result = spawnSync(command, args, { stdio: "inherit" });
if (result.error) {
  console.error(`Unable to start ${command}: ${result.error.message}`);
  process.exit(1);
}
process.exit(result.status ?? 1);
