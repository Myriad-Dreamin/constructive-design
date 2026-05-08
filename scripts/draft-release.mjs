import { execFile } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

const changelogPath = 'editors/vscode/CHANGELOG.md';
const extensionPackagePath = 'editors/vscode/package.json';
const rootPackagePath = 'package.json';
const outputPath = 'target/announcement.gen.md';
const cliReleaseTargets = [
  { target: 'linux-x64', platform: 'Linux x64', extension: 'tar.gz' },
  { target: 'macos-x64', platform: 'macOS x64', extension: 'tar.gz' },
  { target: 'windows-x64', platform: 'Windows x64', extension: 'zip' },
];

const stripPrefix = (value, prefix) =>
  value.startsWith(prefix) ? value.slice(prefix.length) : value;

const tagName = process.argv[2];
const versionToRelease = tagName ? stripPrefix(tagName, 'v') : undefined;

const fail = (message) => {
  console.error(message);
  process.exit(1);
};

const readJson = (filePath) => JSON.parse(fs.readFileSync(filePath, 'utf8'));

const run = async (command, args) => {
  const { stdout } = await execFileAsync(command, args, {
    maxBuffer: 1024 * 1024 * 8,
  });
  return stdout.trim();
};

const repository = () => {
  if (process.env.GITHUB_REPOSITORY) {
    return process.env.GITHUB_REPOSITORY;
  }

  const packageJson = readJson('package.json');
  const repositoryUrl =
    typeof packageJson.repository === 'string'
      ? packageJson.repository
      : packageJson.repository?.url;

  if (!repositoryUrl) {
    return 'Myriad-Dreamin/constructive-design';
  }

  const match = repositoryUrl.match(/github\.com[:/](.+?)(?:\.git)?$/);
  return match?.[1] ?? 'Myriad-Dreamin/constructive-design';
};

const releaseAssetUrl = (tag, fileName) =>
  `https://github.com/${repository()}/releases/download/${tag}/${fileName}`;

const generateDownloadTable = (heading, tag, rows) =>
  [
    `## ${heading}`,
    '',
    '| File | Platform |',
    '| ---- | -------- |',
    ...rows.map(
      ({ fileName, platform }) =>
        `| [${fileName}](${releaseAssetUrl(tag, fileName)}) | ${platform} |`,
    ),
  ].join('\n');

const generateCliDownload = (version, tag) =>
  generateDownloadTable(
    `Download CLI ${version}`,
    tag,
    cliReleaseTargets.map(({ target, platform, extension }) => ({
      fileName: `dsc-${version}-${target}.${extension}`,
      platform,
    })),
  );

const generateExtensionDownload = (extensionPackage, tag) => {
  const version = extensionPackage.version;
  const fileName = `${extensionPackage.name}-${version}.vsix`;

  return generateDownloadTable(`Download VS Code Extension ${version}`, tag, [
    { fileName, platform: 'Universal' },
  ]);
};

const main = async () => {
  if (!tagName || !versionToRelease) {
    fail('Please provide the release tag, for example: pnpm draft-release v0.1.0-rc1');
  }

  const rootPackage = readJson(rootPackagePath);
  const extensionPackage = readJson(extensionPackagePath);
  for (const [packagePath, packageJson] of [
    [rootPackagePath, rootPackage],
    [extensionPackagePath, extensionPackage],
  ]) {
    if (packageJson.version !== versionToRelease) {
      fail(
        `Version in ${packagePath} (${packageJson.version}) does not match release tag ${tagName} (${versionToRelease}).`,
      );
    }
  }

  if (!fs.existsSync(changelogPath)) {
    fail(`Missing changelog: ${changelogPath}`);
  }

  const changelog = await run('parse-changelog', [changelogPath, versionToRelease]);
  if (!changelog) {
    fail(`No changelog entry found for ${versionToRelease} in ${changelogPath}`);
  }

  const releaseBody = [
    changelog,
    generateCliDownload(rootPackage.version, tagName),
    generateExtensionDownload(extensionPackage, tagName),
  ].join('\n\n');

  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${releaseBody}\n`);

  if (process.env.GITHUB_OUTPUT) {
    fs.appendFileSync(
      process.env.GITHUB_OUTPUT,
      [`tag=${tagName}`, `version=${versionToRelease}`, `notes=${outputPath}`].join('\n') + '\n',
    );
  }

  console.log(`Generated ${outputPath}`);
};

main().catch((error) => {
  fail(error instanceof Error ? error.message : String(error));
});
