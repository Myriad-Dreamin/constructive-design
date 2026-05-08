import { chmod, mkdir, writeFile } from 'node:fs/promises';
import * as path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const MINIMUM_BUILD_SEA_VERSION = [25, 5, 0];
const rootDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const distDirectory = path.join(rootDirectory, 'dist');
const executablePath = path.join(distDirectory, process.platform === 'win32' ? 'dsc.exe' : 'dsc');
const configPath = path.join(distDirectory, 'sea-config.json');
const designLanguageServerPath = path.join(
  rootDirectory,
  'editors',
  'vscode',
  'dist',
  'scala',
  'design-ls',
  'main.js',
);

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});

async function main() {
  if (!hasMinimumNodeVersion(process.versions.node, MINIMUM_BUILD_SEA_VERSION)) {
    throw new Error(
      `Node ${process.versions.node} cannot build SEA executables with --build-sea. Use Node >= ${MINIMUM_BUILD_SEA_VERSION.join(
        '.',
      )}.`,
    );
  }

  await mkdir(distDirectory, { recursive: true });
  await writeFile(
    configPath,
    `${JSON.stringify(
      {
        main: path.join(distDirectory, 'index.js'),
        output: executablePath,
        mainFormat: 'module',
        disableExperimentalSEAWarning: true,
        useCodeCache: false,
        assets: {
          'design-ls/main.js': designLanguageServerPath,
        },
      },
      null,
      2,
    )}\n`,
    'utf8',
  );

  const result = spawnSync(process.execPath, ['--build-sea', configPath], {
    cwd: rootDirectory,
    stdio: 'inherit',
  });

  if (result.error !== undefined) {
    throw result.error;
  }
  if (result.status !== 0) {
    throw new Error(`node --build-sea exited with status ${result.status ?? 'unknown'}.`);
  }

  if (process.platform !== 'win32') {
    await chmod(executablePath, 0o755);
  }

  console.log(`Generated ${path.relative(rootDirectory, executablePath)}`);
}

function hasMinimumNodeVersion(rawVersion, minimum) {
  const current = rawVersion.split('.').map((part) => Number.parseInt(part, 10));

  for (let index = 0; index < minimum.length; index += 1) {
    const currentPart = current[index] ?? 0;
    const minimumPart = minimum[index] ?? 0;
    if (currentPart > minimumPart) {
      return true;
    }
    if (currentPart < minimumPart) {
      return false;
    }
  }

  return true;
}
