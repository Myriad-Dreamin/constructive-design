#!/usr/bin/env node
import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import * as sea from 'node:sea';
import { fileURLToPath } from 'node:url';
import * as vm from 'node:vm';

const VERSION = '0.1.0';
const DEFAULT_CXX_OUTPUT_DIR = 'include';
const DESIGN_LS_ASSET_KEY = 'design-ls/main.js';
const SKIPPED_DIRECTORIES = new Set([
  '.bloop',
  '.git',
  '.metals',
  '.scala-build',
  'dist',
  'node_modules',
  'target',
]);

type CompileCxxResult = {
  ok?: unknown;
  content?: unknown;
  error?: unknown;
};

type DesignCompiler = {
  compileCxx(text: string): CompileCxxResult;
};

type DesignLanguageServerFactory = new () => DesignCompiler;

type CompilerSandbox = {
  DesignLanguageServer?: DesignLanguageServerFactory;
  console: Console;
  globalThis?: CompilerSandbox;
};

type CliCommand =
  | { kind: 'help' }
  | { kind: 'version' }
  | { kind: 'compile'; inputPath: string; outputDir?: string };

type DesignConfig = {
  cxxOutputDir?: string;
};

type CompileTarget = {
  sourceRoot: string;
  sourceFiles: string[];
  isDirectory: boolean;
};

main().catch((error: unknown) => {
  console.error(errorMessage(error));
  process.exit(1);
});

async function main(): Promise<void> {
  const command = parseArgs(process.argv.slice(2));

  switch (command.kind) {
    case 'help':
      console.log(helpText());
      return;
    case 'version':
      console.log(`dsc ${VERSION}`);
      return;
    case 'compile':
      await compile(command);
      return;
  }
}

function parseArgs(args: string[]): CliCommand {
  const command = args[0];

  if (command === undefined || command === 'help' || command === '--help' || command === '-h') {
    return { kind: 'help' };
  }
  if (command === 'version' || command === '--version' || command === '-v') {
    return { kind: 'version' };
  }
  if (command === 'compile') {
    return parseCompileArgs(args.slice(1));
  }

  throw new Error(`Unknown command: ${command}\n\n${helpText()}`);
}

function parseCompileArgs(args: string[]): CliCommand {
  let inputPath: string | undefined;
  let outputDir: string | undefined;

  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];

    if (arg === undefined) {
      continue;
    }
    if (arg === '--help' || arg === '-h') {
      return { kind: 'help' };
    }
    if (arg === '-o' || arg === '--output') {
      const value = args[index + 1];
      if (value === undefined || value.startsWith('-')) {
        throw new Error(`Option ${arg} requires an output directory.`);
      }
      outputDir = value;
      index += 1;
      continue;
    }
    if (arg.startsWith('--output=')) {
      outputDir = arg.slice('--output='.length);
      if (outputDir.length === 0) {
        throw new Error('Option --output requires an output directory.');
      }
      continue;
    }
    if (arg.startsWith('-')) {
      throw new Error(`Unknown compile option: ${arg}`);
    }
    if (inputPath !== undefined) {
      throw new Error(`Unexpected compile argument: ${arg}`);
    }

    inputPath = arg;
  }

  return {
    kind: 'compile',
    inputPath: inputPath ?? '.',
    ...(outputDir === undefined ? {} : { outputDir }),
  };
}

function helpText(): string {
  return `Usage:
  dsc help
  dsc version
  dsc compile <path> [-o <dir>]

Commands:
  help                 Show this help text.
  version              Show the dsc version.
  compile <path>       Compile a .ds file or every .ds file under a directory to C++ headers.

Options:
  -h, --help           Show this help text.
  -v, --version        Show the dsc version.
  -o, --output <dir>   Write generated headers to this directory. Defaults to design.toml cxx.output_dir, then include.`;
}

async function compile(command: Extract<CliCommand, { kind: 'compile' }>): Promise<void> {
  const target = await resolveCompileTarget(command.inputPath);
  const projectRoot = target.isDirectory ? target.sourceRoot : process.cwd();
  const config = await readDesignConfig(projectRoot);
  const outputDirectory = resolveOutputDirectory(
    command.outputDir === undefined ? projectRoot : process.cwd(),
    command.outputDir ?? config.cxxOutputDir,
  );
  const compiler = await loadCompiler();

  if (target.sourceFiles.length === 0) {
    throw new Error(`No .ds files found under ${target.sourceRoot}.`);
  }

  for (const sourcePath of target.sourceFiles) {
    const source = await fs.readFile(sourcePath, 'utf8');
    const emitted = compiler.compileCxx(source);

    if (emitted.ok !== true) {
      throw new Error(
        `${sourcePath}: ${typeof emitted.error === 'string' ? emitted.error : 'failed to compile Design source'}`,
      );
    }
    if (typeof emitted.content !== 'string') {
      throw new Error(`${sourcePath}: Design compiler returned no C++ output.`);
    }

    const outputPath = cxxOutputPath(sourcePath, target, outputDirectory);
    await fs.mkdir(path.dirname(outputPath), { recursive: true });
    await fs.writeFile(outputPath, emitted.content, 'utf8');
    console.log(`Generated ${displayPath(outputPath)}`);
  }

  if (target.sourceFiles.length > 1) {
    console.log(`Generated ${target.sourceFiles.length} C++ headers.`);
  }
}

async function resolveCompileTarget(rawInputPath: string): Promise<CompileTarget> {
  const inputPath = path.resolve(rawInputPath);
  const stats = await fs.stat(inputPath);

  if (stats.isFile()) {
    if (path.extname(inputPath) !== '.ds') {
      throw new Error(`Design compile input must be a .ds file: ${inputPath}`);
    }
    return {
      sourceRoot: path.dirname(inputPath),
      sourceFiles: [inputPath],
      isDirectory: false,
    };
  }

  if (!stats.isDirectory()) {
    throw new Error(`Design compile input must be a .ds file or directory: ${inputPath}`);
  }

  return {
    sourceRoot: inputPath,
    sourceFiles: await collectDesignFiles(inputPath),
    isDirectory: true,
  };
}

async function collectDesignFiles(directory: string): Promise<string[]> {
  const entries = await fs.readdir(directory, { withFileTypes: true });
  const files: string[] = [];

  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (SKIPPED_DIRECTORIES.has(entry.name)) {
        continue;
      }
      files.push(...(await collectDesignFiles(path.join(directory, entry.name))));
    } else if (entry.isFile() && path.extname(entry.name) === '.ds') {
      files.push(path.join(directory, entry.name));
    }
  }

  return files.sort((left, right) => left.localeCompare(right));
}

async function loadCompiler(): Promise<DesignCompiler> {
  const source = await loadCompilerSource();
  const transformedSource = exposeDesignLanguageServer(source);
  const sandbox: CompilerSandbox = {
    console,
  };
  sandbox.globalThis = sandbox;

  vm.runInNewContext(transformedSource, sandbox, {
    filename: DESIGN_LS_ASSET_KEY,
  });

  const factory = sandbox.DesignLanguageServer;
  if (typeof factory !== 'function') {
    throw new Error('Design compiler module did not export DesignLanguageServer.');
  }

  return new factory();
}

async function loadCompilerSource(): Promise<string> {
  if (sea.isSea()) {
    return sea.getAsset(DESIGN_LS_ASSET_KEY, 'utf8');
  }

  const configuredPath = process.env.DESIGN_LS_MODULE_PATH;
  if (configuredPath !== undefined && configuredPath.length > 0) {
    return fs.readFile(filePathFromModulePath(configuredPath), 'utf8');
  }

  const entrypointDirectory = path.dirname(fileURLToPath(import.meta.url));
  const candidates = [
    path.join(entrypointDirectory, 'scala', 'design-ls', 'main.js'),
    path.join(
      entrypointDirectory,
      '..',
      'editors',
      'vscode',
      'dist',
      'scala',
      'design-ls',
      'main.js',
    ),
    path.join(process.cwd(), 'editors', 'vscode', 'dist', 'scala', 'design-ls', 'main.js'),
  ];

  for (const candidate of candidates) {
    try {
      return await fs.readFile(candidate, 'utf8');
    } catch (error: unknown) {
      if (isNodeError(error) && error.code === 'ENOENT') {
        continue;
      }
      throw error;
    }
  }

  throw new Error(
    `Design compiler module is missing. Run pnpm build:cli first, or set DESIGN_LS_MODULE_PATH.`,
  );
}

function exposeDesignLanguageServer(source: string): string {
  const exportMatch = /export\s*\{\s*([A-Za-z_$][\w$]*)\s+as\s+DesignLanguageServer\s*\};/u.exec(
    source,
  );
  if (exportMatch === null || exportMatch.index === undefined) {
    throw new Error('Design compiler module has an unsupported export format.');
  }

  const exportName = exportMatch[1] ?? '';
  return [
    source.slice(0, exportMatch.index),
    `globalThis.DesignLanguageServer = ${exportName};`,
    source.slice(exportMatch.index + exportMatch[0].length),
  ].join('');
}

async function readDesignConfig(projectRoot: string): Promise<DesignConfig> {
  const configPath = path.join(projectRoot, 'design.toml');

  try {
    const content = await fs.readFile(configPath, 'utf8');
    return parseDesignToml(content, configPath);
  } catch (error: unknown) {
    if (isNodeError(error) && error.code === 'ENOENT') {
      return {};
    }
    throw error;
  }
}

function parseDesignToml(content: string, configPath: string): DesignConfig {
  let section = '';
  let cxxOutputDir: string | undefined;
  const lines = content.split(/\r?\n/u);

  lines.forEach((rawLine, index) => {
    const lineNumber = index + 1;
    const line = stripTomlComment(rawLine).trim();
    if (line.length === 0) {
      return;
    }

    const tableMatch = /^\[([A-Za-z0-9_.-]+)\]$/u.exec(line);
    if (tableMatch !== null) {
      section = tableMatch[1] ?? '';
      return;
    }

    const keyValueMatch = /^([A-Za-z0-9_.-]+)\s*=\s*(.+)$/u.exec(line);
    if (keyValueMatch === null) {
      throw new Error(`${configPath}:${lineNumber}: expected a TOML key/value pair.`);
    }

    const rawKey = keyValueMatch[1] ?? '';
    const rawValue = keyValueMatch[2] ?? '';
    const keyParts = rawKey.split('.');
    const key = keyParts.at(-1) ?? '';
    const keySection = [
      ...(section.length === 0 ? [] : section.split('.')),
      ...keyParts.slice(0, -1),
    ].join('.');

    if (keySection === 'cxx' && isCxxOutputDirectoryKey(key)) {
      cxxOutputDir = parseTomlString(rawValue, configPath, lineNumber);
    }
  });

  return cxxOutputDir === undefined ? {} : { cxxOutputDir };
}

function stripTomlComment(line: string): string {
  let inBasicString = false;
  let inLiteralString = false;
  let escaping = false;

  for (let index = 0; index < line.length; index += 1) {
    const char = line[index];

    if (inBasicString) {
      if (escaping) {
        escaping = false;
      } else if (char === '\\') {
        escaping = true;
      } else if (char === '"') {
        inBasicString = false;
      }
      continue;
    }

    if (inLiteralString) {
      if (char === "'") {
        inLiteralString = false;
      }
      continue;
    }

    if (char === '#') {
      return line.slice(0, index);
    }
    if (char === '"') {
      inBasicString = true;
    } else if (char === "'") {
      inLiteralString = true;
    }
  }

  return line;
}

function parseTomlString(rawValue: string, configPath: string, lineNumber: number): string {
  const value = rawValue.trim();

  if (value.startsWith('"')) {
    if (!value.endsWith('"')) {
      throw new Error(`${configPath}:${lineNumber}: expected a complete quoted string.`);
    }
    const parsed = JSON.parse(value) as unknown;
    if (typeof parsed === 'string') {
      return parsed;
    }
  } else if (value.startsWith("'")) {
    if (value.length >= 2 && value.endsWith("'")) {
      return value.slice(1, -1);
    }
  } else if (/^[^\s]+$/u.test(value)) {
    return value;
  }

  throw new Error(`${configPath}:${lineNumber}: expected a string value.`);
}

function isCxxOutputDirectoryKey(key: string): boolean {
  return key === 'output_dir' || key === 'outputDir' || key === 'out_dir' || key === 'outDir';
}

function resolveOutputDirectory(projectRoot: string, outputDir: string | undefined): string {
  const rawOutputDir = outputDir ?? DEFAULT_CXX_OUTPUT_DIR;
  if (rawOutputDir.trim().length === 0) {
    throw new Error('Design C++ output directory cannot be empty.');
  }

  return path.isAbsolute(rawOutputDir)
    ? path.normalize(rawOutputDir)
    : path.resolve(projectRoot, rawOutputDir);
}

function cxxOutputPath(sourcePath: string, target: CompileTarget, outputDirectory: string): string {
  const sourceRelativePath = target.isDirectory
    ? path.relative(target.sourceRoot, sourcePath)
    : path.basename(sourcePath);
  const parsedPath = path.parse(sourceRelativePath);

  return path.join(outputDirectory, parsedPath.dir, `${parsedPath.name}.h`);
}

function filePathFromModulePath(modulePath: string): string {
  if (modulePath.startsWith('file:')) {
    return fileURLToPath(modulePath);
  }

  return path.resolve(modulePath);
}

function displayPath(filePath: string): string {
  const relative = path.relative(process.cwd(), filePath);
  return relative.length === 0 || relative.startsWith('..') || path.isAbsolute(relative)
    ? filePath
    : relative;
}

function isNodeError(error: unknown): error is NodeJS.ErrnoException {
  return error instanceof Error && 'code' in error;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
